package com.harshal.seats.service;

import com.harshal.seats.web.ApiException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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

@Service
public class ReservationService {

    private record ShowInfo(long pricePaise, int perUserLimit) {}

    /**
     * THE ATOMIC DECISION. One statement locks and claims in a single step:
     *
     *  1. claimable: pick the requested seats that are CURRENTLY available and lock them
     *     (FOR UPDATE), in ascending label order. Every transaction locks in the same global
     *     order, so two multi-seat requests can never wait on each other in a cycle: no deadlock.
     *     A transaction blocked on a locked row wakes up when the holder commits or rolls back and
     *     RE-CHECKS status = 'available' on the latest row version (READ COMMITTED). If the holder
     *     confirmed it, the row drops out. If the holder rolled back, we get it.
     *  2. UPDATE ... WHERE status = 'available': the write is itself guarded on current state, so
     *     even in theory it cannot flip a seat that is not available. There is no read-then-write.
     *  3. RETURNING gives exactly the seats this transaction won. If that is fewer than requested,
     *     the caller rolls back, which releases any seats it did win: all-or-nothing.
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
    private final TransactionTemplate tx;
    private final Semaphore gate;
    private final long queueTimeoutMs;
    // Price and per-user limit never change after creation, so caching them is safe.
    // Only positive lookups are cached; an unknown show is always re-checked.
    private final ConcurrentHashMap<UUID, ShowInfo> showCache = new ConcurrentHashMap<>();

    public ReservationService(JdbcTemplate jdbc,
                              TransactionTemplate tx,
                              @Value("${app.reserve.max-concurrency:16}") int maxConcurrency,
                              @Value("${app.reserve.queue-timeout-ms:20000}") long queueTimeoutMs) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gate = new Semaphore(maxConcurrency, true);
        this.queueTimeoutMs = queueTimeoutMs;
    }

    /** Waits in memory (not on a DB connection) when the system is saturated. */
    public ReserveOutcome reserve(UUID showId, String userId, List<String> seats) {
        acquire();
        try {
            return tx.execute(status -> doReserve(status, showId, userId, seats));
        } finally {
            gate.release();
        }
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

    private ReserveOutcome doReserve(TransactionStatus status, UUID showId, String userId, List<String> seats) {
        ShowInfo show = showInfo(showId);
        UUID reservationId = UUID.randomUUID();
        String[] labels = seats.toArray(new String[0]);

        List<String> claimed = jdbc.query(CLAIM_SQL, ps -> {
            ps.setObject(1, showId);
            ps.setArray(2, ps.getConnection().createArrayOf("text", labels));
            ps.setObject(3, reservationId);
            ps.setString(4, userId);
            ps.setObject(5, showId);
        }, (rs, i) -> rs.getString(1));

        if (claimed.size() != labels.length) {
            status.setRollbackOnly(); // releases whatever this transaction did win
            return decline(showId, labels);
        }

        long amount = Math.multiplyExact(show.pricePaise(), (long) labels.length);
        jdbc.update("INSERT INTO reservations(id, show_id, user_id, seats, amount_paise, status) "
                  + "VALUES (?, ?, ?, ?, ?, 'confirmed')", ps -> {
            ps.setObject(1, reservationId);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setArray(4, ps.getConnection().createArrayOf("text", labels));
            ps.setLong(5, amount);
        });
        return new ReserveOutcome.Created(new ReservationView(
                reservationId.toString(), showId.toString(), userId, List.copyOf(seats), amount, "confirmed"));
    }

    /** Runs only on the failure path: tell "no such seat" apart from "someone has it". */
    private ReserveOutcome decline(UUID showId, String[] labels) {
        int existing = jdbc.query(
            "SELECT count(*) FROM seats WHERE show_id = ? AND label = ANY(CAST(? AS text[]))",
            ps -> bind(ps, showId, labels), (rs, i) -> rs.getInt(1)).get(0);
        if (existing < labels.length) {
            return new ReserveOutcome.Declined(404, "unknown_seat", "one or more seats do not exist in this show");
        }
        return new ReserveOutcome.Declined(409, "seat_taken", "one or more requested seats are already taken");
    }

    private static void bind(PreparedStatement ps, UUID showId, String[] labels) throws SQLException {
        ps.setObject(1, showId);
        ps.setArray(2, ps.getConnection().createArrayOf("text", labels));
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
}
