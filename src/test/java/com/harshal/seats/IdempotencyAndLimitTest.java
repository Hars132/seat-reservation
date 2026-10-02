package com.harshal.seats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class IdempotencyAndLimitTest extends ApiTestBase {

    private ResponseEntity<Map> reserve(String show, String token, List<String> seats, String key) {
        return post("/shows/" + show + "/reserve", token, Map.of("seats", seats, "idempotency_key", key));
    }

    private String replayHeader(ResponseEntity<Map> r) {
        return r.getHeaders().getFirst("Idempotent-Replay");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int held(String show, String user) {
        return count("SELECT coalesce(sum(held), 0) FROM user_show_quota WHERE show_id = ?::uuid AND user_id = ?",
                show, user);
    }

    private String createShowWithLimit(List<String> seats, int limit) {
        ResponseEntity<Map> r = post("/shows", adminToken(),
                Map.of("name", "limited", "seats", seats, "price_paise", 100, "per_user_limit", limit));
        assertEquals(201, r.getStatusCode().value());
        return (String) r.getBody().get("id");
    }

    // ================= idempotency =================

    @Test
    void sameKeySameBody_sequential_replaysOriginal_andMovesNothing() {
        String show = createShow(labels(5), 25000);
        String t = token("alice");
        ResponseEntity<Map> first = reserve(show, t, List.of("A1"), "key-1");
        ResponseEntity<Map> second = reserve(show, t, List.of("A1"), "key-1");

        assertEquals(201, first.getStatusCode().value());
        assertNull(replayHeader(first));
        assertEquals(201, second.getStatusCode().value());
        assertEquals("true", replayHeader(second));
        assertEquals(first.getBody().get("reservation_id"), second.getBody().get("reservation_id"));

        assertEquals(1, count("SELECT count(*) FROM reservations WHERE show_id = ?::uuid", show));
        assertEquals(1, count("SELECT count(*) FROM seats WHERE show_id = ?::uuid AND status = 'confirmed'", show));
        assertEquals(1, held(show, "alice"), "a replay must not consume quota");
    }

    @Test
    void sameKey_50Concurrent_createsExactlyOneReservation() throws Exception {
        String show = createShow(labels(5), 100);
        String t = token("alice");
        List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> reserve(show, t, List.of("A1"), "same-key"));
        }
        List<ResponseEntity<Map>> results = runConcurrently(50, tasks);

        assertTrue(results.stream().allMatch(r -> r.getStatusCode().value() == 201));
        Set<Object> ids = results.stream().map(r -> r.getBody().get("reservation_id")).collect(Collectors.toSet());
        assertEquals(1, ids.size(), "everyone sees the same reservation");
        assertEquals(1, results.stream().filter(r -> replayHeader(r) == null).count(), "exactly one original");
        assertEquals(49, results.stream().filter(r -> "true".equals(replayHeader(r))).count());
        assertEquals(1, count("SELECT count(*) FROM reservations WHERE show_id = ?::uuid", show));
        assertEquals(1, held(show, "alice"));
    }

    @Test
    void sameKeyDifferentSeats_is409_andClaimsNothing() {
        String show = createShow(labels(5), 100);
        String t = token("alice");
        assertEquals(201, reserve(show, t, List.of("A1"), "key-1").getStatusCode().value());

        ResponseEntity<Map> r = reserve(show, t, List.of("A2"), "key-1");
        assertEquals(409, r.getStatusCode().value());
        assertEquals("idempotency_conflict", r.getBody().get("error"));
        assertEquals("available", jdbc.queryForObject(
            "SELECT status FROM seats WHERE show_id = ?::uuid AND label = 'A2'", String.class, show));
        assertEquals(1, held(show, "alice"));
    }

    @Test
    void sameKeySameSeatsInDifferentOrder_isTheSameRequest() {
        String show = createShow(labels(5), 100);
        String t = token("alice");
        ResponseEntity<Map> first = reserve(show, t, List.of("A1", "A2"), "key-1");
        ResponseEntity<Map> second = reserve(show, t, List.of("A2", "A1"), "key-1");
        assertEquals(201, second.getStatusCode().value());
        assertEquals("true", replayHeader(second));
        assertEquals(first.getBody().get("reservation_id"), second.getBody().get("reservation_id"));
    }

    @Test
    void sameKeyUsedByTwoUsers_isIndependent() {
        String show = createShow(labels(5), 100);
        ResponseEntity<Map> a = reserve(show, token("alice"), List.of("A1"), "shared-key");
        ResponseEntity<Map> b = reserve(show, token("bob"), List.of("A2"), "shared-key");
        assertEquals(201, a.getStatusCode().value());
        assertEquals(201, b.getStatusCode().value());
        assertNotEquals(a.getBody().get("reservation_id"), b.getBody().get("reservation_id"));
    }

    @Test
    void declinedKey_isRemembered_soRetriesAreStableAndDifferentSeatsConflict() {
        String show = createShow(labels(5), 100);
        assertEquals(201, reserve(show, token("u1"), List.of("A1"), "k1").getStatusCode().value());

        String u2 = token("u2");
        ResponseEntity<Map> declined = reserve(show, u2, List.of("A1"), "kd");
        assertEquals(409, declined.getStatusCode().value());
        assertEquals("seat_taken", declined.getBody().get("error"));
        assertNull(replayHeader(declined));

        // Free the seat behind the API's back, then retry the SAME key: the stored decline is replayed.
        jdbc.update("UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL "
                  + "WHERE show_id = ?::uuid AND label = 'A1'", show);
        ResponseEntity<Map> again = reserve(show, u2, List.of("A1"), "kd");
        assertEquals(409, again.getStatusCode().value());
        assertEquals("seat_taken", again.getBody().get("error"));
        assertEquals("true", replayHeader(again));

        // Same key, different seats: conflict, even though the first attempt was declined.
        ResponseEntity<Map> conflict = reserve(show, u2, List.of("A2"), "kd");
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("idempotency_conflict", conflict.getBody().get("error"));

        // A fresh key is a fresh attempt.
        assertEquals(201, reserve(show, u2, List.of("A1"), "kd-new").getStatusCode().value());
    }

    @Test
    void retryStorm_everyRequestSentTwice_oneOutcomePerKey_noDoubleSell() throws Exception {
        int seatCount = 50, users = 300;
        String show = createShow(labels(seatCount), 100);

        List<Callable<String[]>> tasks = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            String user = "u" + i;
            String t = token(user);
            String seat = "A" + ((i % seatCount) + 1);
            for (int attempt = 0; attempt < 2; attempt++) {          // the retry
                tasks.add(() -> {
                    ResponseEntity<Map> r = reserve(show, t, List.of(seat), "key-" + user);
                    return new String[] {user, String.valueOf(r.getStatusCode().value()),
                            Objects.toString(r.getBody().get("reservation_id"))};
                });
            }
        }
        List<String[]> results = runConcurrently(100, tasks);

        assertEquals(0, results.stream().filter(a -> Integer.parseInt(a[1]) >= 500).count(), "zero 5xx");
        Map<String, List<String[]>> byUser = results.stream().collect(Collectors.groupingBy(a -> a[0]));
        for (List<String[]> pair : byUser.values()) {
            assertEquals(2, pair.size());
            assertEquals(pair.get(0)[1], pair.get(1)[1], "both attempts with one key get the same status");
            assertEquals(pair.get(0)[2], pair.get(1)[2], "and the same reservation");
        }
        Set<String> winners = results.stream().filter(a -> a[1].equals("201")).map(a -> a[2]).collect(Collectors.toSet());
        assertEquals(seatCount, winners.size());
        assertEquals(seatCount, count("SELECT count(*) FROM reservations WHERE show_id = ?::uuid", show));
        assertEquals(seatCount, count("SELECT count(*) FROM seats WHERE show_id = ?::uuid AND status = 'confirmed'", show));
    }

    // ================= per-user limit =================

    @Test
    void fifthSeat_overLimit_is409() {
        String show = createShow(labels(10), 100);
        String t = token("alice");
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, reserve(show, t, List.of("A" + i), "k" + i).getStatusCode().value());
        }
        ResponseEntity<Map> r = reserve(show, t, List.of("A5"), "k5");
        assertEquals(409, r.getStatusCode().value());
        assertEquals("per_user_limit", r.getBody().get("error"));
        assertEquals("available", jdbc.queryForObject(
            "SELECT status FROM seats WHERE show_id = ?::uuid AND label = 'A5'", String.class, show));
        assertEquals(4, held(show, "alice"));
    }

    @Test
    void singleRequestForMoreThanTheLimit_is409_andClaimsNothing() {
        String show = createShow(labels(10), 100);
        ResponseEntity<Map> r = reserve(show, token("alice"), labels(5), "k1");
        assertEquals(409, r.getStatusCode().value());
        assertEquals("per_user_limit", r.getBody().get("error"));
        assertEquals(0, count("SELECT count(*) FROM seats WHERE show_id = ?::uuid AND status = 'confirmed'", show));
    }

    @Test
    void tenParallelReserves_sameUser_endsWithAtMostFourSeats() throws Exception {
        String show = createShow(labels(10), 100);
        String t = token("alice");
        List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String seat = "A" + i;
            tasks.add(() -> reserve(show, t, List.of(seat), "k-" + seat));
        }
        List<ResponseEntity<Map>> results = runConcurrently(10, tasks);

        assertEquals(4, results.stream().filter(r -> r.getStatusCode().value() == 201).count());
        List<ResponseEntity<Map>> declined = results.stream().filter(r -> r.getStatusCode().value() != 201).toList();
        assertEquals(6, declined.size());
        assertTrue(declined.stream().allMatch(r -> r.getStatusCode().value() == 409
                && "per_user_limit".equals(r.getBody().get("error"))), "clean declines only, no 5xx");
        assertEquals(4, count("SELECT count(*) FROM seats WHERE show_id = ?::uuid AND user_id = 'alice' "
                            + "AND status = 'confirmed'", show));
        assertEquals(4, held(show, "alice"));
    }

    @Test
    void limitIsPerShow() {
        String show1 = createShow(labels(10), 100);
        String show2 = createShow(labels(10), 100);
        String t = token("alice");
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, reserve(show1, t, List.of("A" + i), "s1-" + i).getStatusCode().value());
        }
        assertEquals(201, reserve(show2, t, List.of("A1"), "s2-1").getStatusCode().value());
    }

    @Test
    void twoUsersAtTheLimit_doNotInterfere() {
        String show = createShow(labels(10), 100);
        assertEquals(201, reserve(show, token("u1"), List.of("A1", "A2", "A3", "A4"), "k1").getStatusCode().value());
        assertEquals(201, reserve(show, token("u2"), List.of("A5", "A6", "A7", "A8"), "k2").getStatusCode().value());
    }

    @Test
    void customPerUserLimit_isHonoured() {
        String show = createShowWithLimit(labels(5), 2);
        String t = token("alice");
        assertEquals(201, reserve(show, t, List.of("A1"), "k1").getStatusCode().value());
        assertEquals(201, reserve(show, t, List.of("A2"), "k2").getStatusCode().value());
        ResponseEntity<Map> r = reserve(show, t, List.of("A3"), "k3");
        assertEquals(409, r.getStatusCode().value());
        assertEquals("per_user_limit", r.getBody().get("error"));
    }

    @Test
    void limitIsCheckedBeforeSeatAvailability() {
        String show = createShow(labels(10), 100);
        String alice = token("alice");
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, reserve(show, alice, List.of("A" + i), "k" + i).getStatusCode().value());
        }
        assertEquals(201, reserve(show, token("bob"), List.of("A5"), "kb").getStatusCode().value());

        ResponseEntity<Map> r = reserve(show, alice, List.of("A5"), "k5");   // over limit AND taken
        assertEquals("per_user_limit", r.getBody().get("error"));
    }

    @Test
    void declinedAttempt_doesNotConsumeQuota() {
        String show = createShow(labels(10), 100);
        assertEquals(201, reserve(show, token("bob"), List.of("A1"), "kb").getStatusCode().value());

        String alice = token("alice");
        ResponseEntity<Map> r = reserve(show, alice, List.of("A1", "A2"), "k1");   // A1 taken
        assertEquals("seat_taken", r.getBody().get("error"));
        assertEquals(0, held(show, "alice"), "the rolled-back attempt must leave the quota untouched");

        assertEquals(201, reserve(show, alice, List.of("A2", "A3", "A4", "A5"), "k2").getStatusCode().value());
        assertEquals(4, held(show, "alice"));
    }
}
