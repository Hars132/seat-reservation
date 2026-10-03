package com.harshal.seats.service;

import com.harshal.seats.web.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One reserve = one database transaction. The order of steps is the design:
 *
 *   1. idempotency key  (unique row; duplicates wait here, then replay the stored result)
 *   2. SAVEPOINT        (everything below can be undone while the key row survives)
 *   3. per-user quota   (conditional upsert; serialises one user's parallel requests)
 *   4. seat claim       (conditional update; sorted row locks; all-or-nothing)
 *   5. reservation row, key row marked confirmed, COMMIT
 *
 * A domain decline rolls back to the savepoint, marks the key row 'declined', and commits, so the
 * key is remembered. An infrastructure failure rolls back everything, key row included, so the
 * client can safely retry. Lock order is always key -> quota -> seats (sorted), so it cannot cycle.
 */
@Service
public class ReservationService {

    private record ShowInfo(long pricePaise, int perUserLimit) {}
    private record IdemRow(String requestHash, String outcome, UUID reservationId, String declineReason) {}
    private record ReservationRow(String userId, UUID showId, String status, int seatCount) {}

    /** Returns 1 if this request now owns the key, 0 if the key already exists (committed by someone else). */
    private static final String IDEM_INSERT = """
        INSERT INTO idempotency(user_id, idem_key, request_hash) VALUES (?, ?, ?)
        ON CONFLICT (user_id, idem_key) DO NOTHING
        """;

    /**
     * Atomic per-user limit. Insert the first time; on conflict add to the counter ONLY IF the result
     * stays within the limit. The row lock taken by ON CONFLICT DO UPDATE serialises this user's
     * concurrent requests, and the WHERE is re-checked against the latest committed row after the
     * wait. No row returned = over the limit. (The insert path skips the WHERE, so the caller checks
     * seats <= limit before calling.)
     */
    private static final String QUOTA_SQL = """
        INSERT INTO user_show_quota(show_id, user_id, held) VALUES (CAST(? AS uuid), ?, ?)
        ON CONFLICT (show_id, user_id) DO UPDATE
           SET held = user_show_quota.held + EXCLUDED.held
         WHERE user_show_quota.held + EXCLUDED.held <= ?
        RETURNING held
        """;

    /**
     * THE ATOMIC DECISION. Lock the requested seats that are currently available, in ascending label
     * order (global lock order = no deadlock), then flip exactly those rows with an UPDATE that is
     * itself guarded on status = 'available'. RETURNING gives the seats this transaction won; fewer
     * than requested means the caller undoes everything (all-or-nothing). A transaction waiting on a
     * locked row re-checks status on the latest row version once the holder finishes.
     */
    private static final String CLAIM_SQL = """
        WITH claimable AS (
            SELECT label FROM seats
            WHERE show_id = CAST(? AS uuid)
              AND label = ANY(CAST(? AS text[]))
              AND status = 'available'
            ORDER BY label
            FOR UPDATE
        )
        UPDATE seats s
           SET status = 'confirmed', reservation_id = CAST(? AS uuid), user_id = ?
          FROM claimable c
         WHERE s.show_id = CAST(? AS uuid)
           AND s.label = c.label
           AND s.status = 'available'
        RETURNING s.label
        """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final Semaphore gate;
    private final long queueTimeoutMs;
    private final MeterRegistry metrics;
    private final Counter confirmedCounter;
    // Price and per-user limit never change after creation, so caching them is safe.
    // Only positive lookups are cached; an unknown show is always re-checked.
    private final ConcurrentHashMap<UUID, ShowInfo> showCache = new ConcurrentHashMap<>();

    public ReservationService(JdbcTemplate jdbc,
                              TransactionTemplate txTemplate,
                              MeterRegistry metrics,
                              @Value("${app.reserve.max-concurrency:16}") int maxConcurrency,
                              @Value("${app.reserve.queue-timeout-ms:20000}") long queueTimeoutMs) {
        this.jdbc = jdbc;
        this.txTemplate = txTemplate;
        this.metrics = metrics;
        this.gate = new Semaphore(maxConcurrency, true);
        this.queueTimeoutMs = queueTimeoutMs;
        this.confirmedCounter = Counter.builder("reservations_confirmed_total")
                .description("Reservations that were newly confirmed (excludes idempotent replays)")
                .register(metrics);
    }

    /**
     * Waits in memory (not on a DB connection) when the system is saturated. Metrics are recorded
     * AFTER the transaction returns (TransactionTemplate has already committed by then), so a
     * confirmed/declined count always matches what was actually persisted.
     */
    public ReserveOutcome reserve(UUID showId, String userId, String idempotencyKey, List<String> seats) {
        String hash = requestHash(showId, seats);
        acquire();
        ReserveOutcome outcome;
        try {
            outcome = txTemplate.execute(status -> doReserve(status, showId, userId, idempotencyKey, hash, seats));
        } finally {
            gate.release();
        }
        record(outcome);
        return outcome;
    }

    /**
     * reservations_declined_total{reason}: seat_taken, per_user_limit, unknown_seat,
     * idempotency_conflict, idempotent_replay. A replay (whether of a confirmed or a declined
     * original) is counted ONLY as idempotent_replay, per the brief's named reasons - it does not
     * also double-count toward "confirmed" or the original decline reason.
     */
    private void record(ReserveOutcome outcome) {
        switch (outcome) {
            case ReserveOutcome.Created c -> {
                if (c.replay()) declinedCounter("idempotent_replay").increment();
                else confirmedCounter.increment();
            }
            case ReserveOutcome.Declined d -> {
                declinedCounter(d.replay() ? "idempotent_replay" : d.reason()).increment();
            }
        }
    }

    private Counter declinedCounter(String reason) {
        return metrics.counter("reservations_declined_total", "reason", reason);
    }

    private ReserveOutcome doReserve(TransactionStatus status, UUID showId, String userId,
                                     String key, String hash, List<String> seats) {
        ShowInfo show = showInfo(showId);

        // 1. Idempotency. A concurrent duplicate blocks inside this INSERT until the first
        //    transaction commits, then gets 0 rows and replays the stored result.
        int inserted = jdbc.update(IDEM_INSERT, ps -> {
            ps.setString(1, userId);
            ps.setString(2, key);
            ps.setString(3, hash);
        });
        if (inserted == 0) {
            return replay(userId, key, hash);
        }

        // 2. From here on a domain decline can be undone without losing the key row.
        Object savepoint = status.createSavepoint();
        int n = seats.size();
        String[] labels = seats.toArray(new String[0]);

        // 3. Per-user limit.
        if (n > show.perUserLimit()) {
            return decline(status, savepoint, userId, key, "per_user_limit");
        }
        List<Integer> quota = jdbc.query(QUOTA_SQL, ps -> {
            ps.setObject(1, showId);
            ps.setString(2, userId);
            ps.setInt(3, n);
            ps.setInt(4, show.perUserLimit());
        }, (rs, i) -> rs.getInt(1));
        if (quota.isEmpty()) {
            return decline(status, savepoint, userId, key, "per_user_limit");
        }

        // 4. Atomic seat claim.
        UUID reservationId = UUID.randomUUID();
        List<String> claimed = jdbc.query(CLAIM_SQL, ps -> {
            ps.setObject(1, showId);
            ps.setArray(2, ps.getConnection().createArrayOf("text", labels));
            ps.setObject(3, reservationId);
            ps.setString(4, userId);
            ps.setObject(5, showId);
        }, (rs, i) -> rs.getString(1));
        if (claimed.size() != n) {
            // Undoes the quota increment and any seats this transaction did win.
            return decline(status, savepoint, userId, key, diagnose(showId, labels));
        }

        // 5. Record the reservation and finalise the key.
        long amount = Math.multiplyExact(show.pricePaise(), (long) n);
        jdbc.update("INSERT INTO reservations(id, show_id, user_id, seats, amount_paise, status) "
                  + "VALUES (?, ?, ?, ?, ?, 'confirmed')", ps -> {
            ps.setObject(1, reservationId);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setArray(4, ps.getConnection().createArrayOf("text", labels));
            ps.setLong(5, amount);
        });
        jdbc.update("UPDATE idempotency SET outcome = 'confirmed', reservation_id = ? "
                  + "WHERE user_id = ? AND idem_key = ?", reservationId, userId, key);

        return new ReserveOutcome.Created(new ReservationView(
                reservationId.toString(), showId.toString(), userId, List.copyOf(seats), amount, "confirmed"), false);
    }

    /** Undo this attempt's writes, but keep the key row and mark it declined so the decline is remembered. */
    private ReserveOutcome decline(TransactionStatus status, Object savepoint,
                                   String userId, String key, String reason) {
        status.rollbackToSavepoint(savepoint);
        jdbc.update("UPDATE idempotency SET outcome = 'declined', decline_reason = ? "
                  + "WHERE user_id = ? AND idem_key = ?", reason, userId, key);
        return new ReserveOutcome.Declined(statusFor(reason), reason, messageFor(reason), false);
    }

    /** The key already exists: same request replays the stored result, a different request is a conflict. */
    private ReserveOutcome replay(String userId, String key, String hash) {
        List<IdemRow> rows = jdbc.query(
            "SELECT request_hash, outcome, reservation_id, decline_reason FROM idempotency "
          + "WHERE user_id = ? AND idem_key = ?",
            (rs, i) -> new IdemRow(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)),
            userId, key);
        if (rows.isEmpty()) {
            throw new ApiException(503, "overloaded", "request is still being processed, retry shortly");
        }
        IdemRow row = rows.get(0);
        if (!row.requestHash().equals(hash)) {
            return new ReserveOutcome.Declined(409, "idempotency_conflict", messageFor("idempotency_conflict"), false);
        }
        return switch (row.outcome()) {
            case "confirmed" -> new ReserveOutcome.Created(loadReservation(row.reservationId()), true);
            case "declined" -> new ReserveOutcome.Declined(
                    statusFor(row.declineReason()), row.declineReason(), messageFor(row.declineReason()), true);
            default -> throw new ApiException(503, "overloaded", "request is still being processed, retry shortly");
        };
    }

    private ReservationView loadReservation(UUID id) {
        return jdbc.query(
            "SELECT id, show_id, user_id, seats, amount_paise, status FROM reservations WHERE id = ?",
            (rs, i) -> new ReservationView(rs.getString(1), rs.getString(2), rs.getString(3),
                    List.of((String[]) rs.getArray(4).getArray()), rs.getLong(5), rs.getString(6)),
            id).get(0);
    }

    /** Failure path only: tell "no such seat" apart from "someone has it". Seat labels are immutable. */
    private String diagnose(UUID showId, String[] labels) {
        int existing = jdbc.query(
            "SELECT count(*) FROM seats WHERE show_id = ? AND label = ANY(CAST(? AS text[]))",
            ps -> {
                ps.setObject(1, showId);
                ps.setArray(2, ps.getConnection().createArrayOf("text", labels));
            }, (rs, i) -> rs.getInt(1)).get(0);
        return existing < labels.length ? "unknown_seat" : "seat_taken";
    }

    private void acquire() {
        try {
            if (!gate.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new ApiException(503, "overloaded", "server is busy, retry shortly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "overloaded", "request interrupted, retry shortly");
        }
    }

    private ShowInfo showInfo(UUID showId) {
        ShowInfo cached = showCache.get(showId);
        if (cached != null) {
            return cached;
        }
        List<ShowInfo> rows = jdbc.query("SELECT price_paise, per_user_limit FROM shows WHERE id = ?",
                (rs, i) -> new ShowInfo(rs.getLong(1), rs.getInt(2)), showId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "show_not_found", "show not found");
        }
        showCache.put(showId, rows.get(0));
        return rows.get(0);
    }

    /**
     * Cancel: ownership and current status are checked IN the UPDATE's WHERE clause, so the
     * decision is atomic. Matching by reservation_id (not by seat label) means a seat already
     * re-confirmed to someone else under a NEW reservation_id can never be touched by a stale
     * cancel of the old one.
     */
    private static final String RELEASE_SEATS_SQL = """
        UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL
        WHERE reservation_id = ?
        """;

    public CancelOutcome cancel(UUID reservationId, String userId) {
        return txTemplate.execute(status -> {
            // Row lock here serializes a double-cancel race: the second caller waits, then sees
            // status already 'cancelled' and declines cleanly instead of double-releasing seats.
            List<ReservationRow> rows = jdbc.query(
                "SELECT user_id, show_id, status, cardinality(seats) AS n "
              + "FROM reservations WHERE id = ? FOR UPDATE",
                (rs, i) -> new ReservationRow(rs.getString(1), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getInt(4)),
                reservationId);
            if (rows.isEmpty()) {
                return new CancelOutcome(404, "reservation_not_found", "reservation not found");
            }
            ReservationRow row = rows.get(0);
            if (!row.userId().equals(userId)) {
                // Do not reveal whether the id exists to someone who does not own it.
                return new CancelOutcome(404, "reservation_not_found", "reservation not found");
            }
            if ("cancelled".equals(row.status())) {
                return new CancelOutcome(409, "already_cancelled", "this reservation was already cancelled");
            }

            jdbc.update("UPDATE reservations SET status = 'cancelled', cancelled_at = now() WHERE id = ?",
                    reservationId);
            jdbc.update(RELEASE_SEATS_SQL, reservationId);
            jdbc.update("UPDATE user_show_quota SET held = held - ? WHERE show_id = ? AND user_id = ?",
                    row.seatCount(), row.showId(), userId);
            return new CancelOutcome(200, null, null);
        });
    }

    /** Same show and same seat set (in any order) = same request. Labels cannot contain ','. */
    static String requestHash(UUID showId, List<String> seats) {
        String canonical = showId + "|" + String.join(",", seats.stream().sorted().toList());
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int statusFor(String reason) {
        return "unknown_seat".equals(reason) ? 404 : 409;
    }

    private static String messageFor(String reason) {
        return switch (reason) {
            case "seat_taken" -> "one or more requested seats are already taken";
            case "per_user_limit" -> "this reservation would exceed the per-user seat limit for the show";
            case "unknown_seat" -> "one or more seats do not exist in this show";
            case "idempotency_conflict" -> "this idempotency key was already used with a different request";
            default -> "request declined";
        };
    }
}
