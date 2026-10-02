package com.harshal.seats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class ReserveTest extends ApiTestBase {

    private ResponseEntity<Map> reserve(String showId, String token, List<String> seats, String key) {
        return post("/shows/" + showId + "/reserve", token, Map.of("seats", seats, "idempotency_key", key));
    }

    private int num(Map m, String key) {
        return ((Number) m.get(key)).intValue();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private long countStatus(List<Integer> codes, int code) {
        return codes.stream().filter(c -> c == code).count();
    }

    // ---- basics ----

    @Test
    void reserveOneSeat_returns201_andSeatIsConfirmed() {
        String show = createShow(labels(5), 25000);
        ResponseEntity<Map> r = reserve(show, token("alice"), List.of("A1"), "k1");
        assertEquals(201, r.getStatusCode().value());
        Map b = r.getBody();
        assertNotNull(b.get("reservation_id"));
        assertEquals(show, b.get("show_id"));
        assertEquals("alice", b.get("user_id"));
        assertEquals(List.of("A1"), b.get("seats"));
        assertEquals(25000, num(b, "amount_paise"));
        assertEquals("confirmed", b.get("status"));

        Map seat = jdbc.queryForMap(
            "SELECT status, user_id, reservation_id::text AS rid FROM seats WHERE show_id = ?::uuid AND label = 'A1'", show);
        assertEquals("confirmed", seat.get("status"));
        assertEquals("alice", seat.get("user_id"));
        assertEquals(b.get("reservation_id"), seat.get("rid"));

        Map state = get("/shows/" + show + "?include_seats=false", token("alice")).getBody();
        assertEquals(4, num(state, "available"));
        assertEquals(1, num(state, "confirmed"));
    }

    @Test
    void reserveThreeSeats_amountIsThreeTimesPrice() {
        String show = createShow(labels(5), 25000);
        ResponseEntity<Map> r = reserve(show, token("alice"), List.of("A1", "A2", "A3"), "k1");
        assertEquals(201, r.getStatusCode().value());
        assertEquals(75000, num(r.getBody(), "amount_paise"));
    }

    @Test
    void seatAlreadyTaken_is409() {
        String show = createShow(labels(3), 100);
        assertEquals(201, reserve(show, token("u1"), List.of("A1"), "k1").getStatusCode().value());
        ResponseEntity<Map> r = reserve(show, token("u2"), List.of("A1"), "k2");
        assertEquals(409, r.getStatusCode().value());
        assertEquals("seat_taken", r.getBody().get("error"));
    }

    @Test
    void multiSeat_isAllOrNothing() {
        String show = createShow(labels(3), 100);
        assertEquals(201, reserve(show, token("u1"), List.of("A1"), "k1").getStatusCode().value());

        ResponseEntity<Map> r = reserve(show, token("u2"), List.of("A1", "A2"), "k2");
        assertEquals(409, r.getStatusCode().value());

        assertEquals("available", jdbc.queryForObject(
            "SELECT status FROM seats WHERE show_id = ?::uuid AND label = 'A2'", String.class, show));
        assertEquals(1, count("SELECT count(*) FROM reservations WHERE show_id = ?::uuid", show));
    }

    @Test
    void unknownSeat_is404_andNothingIsClaimed() {
        String show = createShow(labels(3), 100);
        ResponseEntity<Map> r = reserve(show, token("u1"), List.of("A1", "ZZ9"), "k1");
        assertEquals(404, r.getStatusCode().value());
        assertEquals("unknown_seat", r.getBody().get("error"));
        assertEquals("available", jdbc.queryForObject(
            "SELECT status FROM seats WHERE show_id = ?::uuid AND label = 'A1'", String.class, show));
    }

    @Test
    void unknownShow_is404() {
        assertEquals(404, reserve(UUID.randomUUID().toString(), token("u1"), List.of("A1"), "k1")
                .getStatusCode().value());
    }

    // ---- validation ----

    @Test
    void missingIdempotencyKey_is400() {
        String show = createShow(labels(3), 100);
        assertEquals(400, post("/shows/" + show + "/reserve", token("u1"),
                Map.of("seats", List.of("A1"))).getStatusCode().value());
    }

    @Test
    void emptySeats_is400() {
        String show = createShow(labels(3), 100);
        assertEquals(400, reserve(show, token("u1"), List.of(), "k1").getStatusCode().value());
    }

    @Test
    void duplicateSeatsInRequest_is400() {
        String show = createShow(labels(3), 100);
        assertEquals(400, reserve(show, token("u1"), List.of("A1", "A1"), "k1").getStatusCode().value());
    }

    @Test
    void badLabel_is400() {
        String show = createShow(labels(3), 100);
        assertEquals(400, reserve(show, token("u1"), List.of("bad label!"), "k1").getStatusCode().value());
    }

    @Test
    void tooManySeatsInOneRequest_is400() {
        String show = createShow(labels(200), 100);
        assertEquals(400, reserve(show, token("u1"), labels(101), "k1").getStatusCode().value());
    }

    @Test
    void idempotencyKeyInHeader_isAccepted() {
        String show = createShow(labels(3), 100);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token("u1"));
        h.set("Idempotency-Key", "hdr-key-1");
        ResponseEntity<Map> r = rest.exchange("/shows/" + show + "/reserve", HttpMethod.POST,
                new HttpEntity<>(Map.of("seats", List.of("A1")), h), Map.class);
        assertEquals(201, r.getStatusCode().value());
    }

    // ---- identity ----

    @Test
    void noToken_is401() {
        String show = createShow(labels(3), 100);
        assertEquals(401, reserve(show, null, List.of("A1"), "k1").getStatusCode().value());
    }

    @Test
    void spoofedUserIdInBody_isIgnored() {
        String show = createShow(labels(3), 100);
        ResponseEntity<Map> r = post("/shows/" + show + "/reserve", token("mallory"),
                Map.of("seats", List.of("A1"), "idempotency_key", "k1", "user_id", "someone-else"));
        assertEquals(201, r.getStatusCode().value());
        assertEquals("mallory", r.getBody().get("user_id"));
        assertEquals("mallory", jdbc.queryForObject(
            "SELECT user_id FROM seats WHERE show_id = ?::uuid AND label = 'A1'", String.class, show));
    }

    // ---- concurrency: the headline tests ----

    @Test
    void hotSeat_500Users_exactlyOneWinner() throws Exception {
        String show = createShow(labels(20), 25000);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String user = "user" + i;
            String t = token(user);
            tasks.add(() -> reserve(show, t, List.of("A12"), "k-" + user).getStatusCode().value());
        }
        List<Integer> codes = runConcurrently(100, tasks);

        assertEquals(1, countStatus(codes, 201), "exactly one winner");
        assertEquals(499, countStatus(codes, 409), "everyone else gets a clean decline");
        assertEquals(0, codes.stream().filter(c -> c >= 500).count(), "zero 5xx");
        assertEquals(1, count("SELECT count(*) FROM seats WHERE show_id = ?::uuid AND status = 'confirmed'", show));
        assertEquals(1, count("SELECT count(*) FROM reservations WHERE show_id = ?::uuid", show));
    }

    @Test
    void invariantHoldsDuringAndAfterBurst() throws Exception {
        int seatCount = 50, users = 300;
        String show = createShow(labels(seatCount), 100);
        String viewer = token("viewer");

        AtomicBoolean done = new AtomicBoolean(false);
        AtomicInteger polls = new AtomicInteger();
        List<String> violations = new CopyOnWriteArrayList<>();
        Thread poller = new Thread(() -> {
            while (!done.get()) {
                Map s = get("/shows/" + show + "?include_seats=false", viewer).getBody();
                polls.incrementAndGet();
                if (num(s, "available") + num(s, "held") + num(s, "confirmed") != num(s, "total_seats")) {
                    violations.add(s.toString());
                }
            }
        });
        poller.start();

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            String user = "u" + i;
            String t = token(user);
            String seat = "A" + ((i % seatCount) + 1);   // 6 users fight over each seat
            tasks.add(() -> reserve(show, t, List.of(seat), "k-" + user).getStatusCode().value());
        }
        List<Integer> codes = runConcurrently(100, tasks);
        done.set(true);
        poller.join();

        assertTrue(polls.get() > 0, "poller ran during the burst");
        assertTrue(violations.isEmpty(), "invariant violated: " + violations);
        assertEquals(seatCount, countStatus(codes, 201));
        assertEquals(users - seatCount, countStatus(codes, 409));
        assertEquals(0, codes.stream().filter(c -> c >= 500).count());

        Map end = get("/shows/" + show + "?include_seats=false", viewer).getBody();
        assertEquals(seatCount, num(end, "confirmed"));
        assertEquals(0, num(end, "available"));
    }

    @Test
    void overlappingMultiSeatRequests_noDeadlock_exactlyOneWinnerPerRound() throws Exception {
        record Req(int round, String user, List<String> seats) {}
        int rounds = 60;
        String show = createShow(labels(rounds * 3), 100);

        List<Req> reqs = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            String x = "A" + (3 * r + 1), y = "A" + (3 * r + 2), z = "A" + (3 * r + 3);
            // Every pair overlaps another, and some are the same pair in opposite order.
            List<List<String>> pairs = List.of(
                List.of(x, y), List.of(y, z), List.of(z, x), List.of(y, x), List.of(z, y), List.of(x, z));
            for (int p = 0; p < pairs.size(); p++) {
                reqs.add(new Req(r, "u" + r + "_" + p, pairs.get(p)));
            }
        }
        Collections.shuffle(reqs, new Random(42));

        List<Callable<int[]>> tasks = new ArrayList<>();
        for (Req q : reqs) {
            String t = token(q.user());
            tasks.add(() -> new int[] {q.round(),
                    reserve(show, t, q.seats(), "k-" + q.user()).getStatusCode().value()});
        }
        List<int[]> results = runConcurrently(64, tasks);

        int[] winnersPerRound = new int[rounds];
        for (int[] res : results) {
            assertTrue(res[1] == 201 || res[1] == 409, "unexpected status " + res[1]);
            if (res[1] == 201) winnersPerRound[res[0]]++;
        }
        for (int r = 0; r < rounds; r++) {
            assertEquals(1, winnersPerRound[r], "round " + r + " must have exactly one winner");
        }
        assertEquals(rounds * 2, count(
            "SELECT count(*) FROM seats WHERE show_id = ?::uuid AND status = 'confirmed'", show));
        assertEquals(rounds, count("SELECT count(*) FROM reservations WHERE show_id = ?::uuid", show));
        // Every confirmed seat belongs to a reservation that actually lists it.
        assertEquals(rounds * 2, count(
            "SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id "
          + "WHERE s.show_id = ?::uuid AND s.status = 'confirmed' AND s.label = ANY(r.seats)", show));
    }
}
