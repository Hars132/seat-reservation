package com.harshal.seats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class CancelTest extends ApiTestBase {

    private ResponseEntity<Map> reserve(String show, String token, List<String> seats, String key) {
        return post("/shows/" + show + "/reserve", token, Map.of("seats", seats, "idempotency_key", key));
    }

    private ResponseEntity<Map> cancel(String reservationId, String token) {
        return post("/reservations/" + reservationId + "/cancel", token, Map.of());
    }

    private String seatStatus(String show, String label) {
        return jdbc.queryForObject(
            "SELECT status FROM seats WHERE show_id = ?::uuid AND label = ?", String.class, show, label);
    }

    private int held(String show, String user) {
        return jdbc.queryForObject(
            "SELECT coalesce(sum(held), 0) FROM user_show_quota WHERE show_id = ?::uuid AND user_id = ?",
            Integer.class, show, user);
    }

    private String reserveOne(String show, String token, String seat, String key) {
        return (String) reserve(show, token, List.of(seat), key).getBody().get("reservation_id");
    }

    @Test
    void owner_cancels_seatBecomesAvailable_quotaReleased() {
        String show = createShow(labels(5), 100);
        String t = token("alice");
        String resId = reserveOne(show, t, "A1", "k1");
        assertEquals(1, held(show, "alice"));

        ResponseEntity<Map> r = cancel(resId, t);
        assertEquals(200, r.getStatusCode().value());
        assertEquals("cancelled", r.getBody().get("status"));
        assertEquals("available", seatStatus(show, "A1"));
        assertEquals(0, held(show, "alice"));
        assertEquals("cancelled", jdbc.queryForObject(
            "SELECT status FROM reservations WHERE id = ?::uuid", String.class, resId));
    }

    @Test
    void anotherUser_cancelling_is404_seatUntouched() {
        String show = createShow(labels(5), 100);
        String resId = reserveOne(show, token("alice"), "A1", "k1");

        ResponseEntity<Map> r = cancel(resId, token("mallory"));
        assertEquals(404, r.getStatusCode().value());
        assertEquals("confirmed", seatStatus(show, "A1"));
        assertEquals(1, held(show, "alice"));
    }

    @Test
    void spoofedUserIdInBody_isIgnored_cancelStillScopedToTokenUser() {
        String show = createShow(labels(5), 100);
        String resId = reserveOne(show, token("alice"), "A1", "k1");

        ResponseEntity<Map> r = post("/reservations/" + resId + "/cancel", token("mallory"),
                Map.of("user_id", "alice"));
        assertEquals(404, r.getStatusCode().value());
        assertEquals("confirmed", seatStatus(show, "A1"));
    }

    @Test
    void cancelTwice_secondIsCleanDecline_quotaDecrementedOnce() {
        String show = createShow(labels(5), 100);
        String t = token("alice");
        String resId = reserveOne(show, t, "A1", "k1");

        assertEquals(200, cancel(resId, t).getStatusCode().value());
        ResponseEntity<Map> second = cancel(resId, t);
        assertEquals(409, second.getStatusCode().value());
        assertEquals("already_cancelled", second.getBody().get("error"));
        assertEquals(0, held(show, "alice"), "quota must only be released once");
    }

    @Test
    void concurrentDoubleCancel_exactlyOneSucceeds_noOverRelease() throws Exception {
        String show = createShow(labels(5), 100);
        String t = token("alice");
        String resId = reserveOne(show, t, "A1", "k1");

        List<Callable<Integer>> tasks = List.of(
            () -> cancel(resId, t).getStatusCode().value(),
            () -> cancel(resId, t).getStatusCode().value());
        List<Integer> results = runConcurrently(2, tasks);

        assertEquals(1, results.stream().filter(c -> c == 200).count());
        assertEquals(1, results.stream().filter(c -> c == 409).count());
        assertEquals(0, results.stream().filter(c -> c >= 500).count());
        assertEquals(0, held(show, "alice"));
        assertEquals("available", seatStatus(show, "A1"));
    }

    @Test
    void cancelUnknownReservation_is404() {
        assertEquals(404, cancel(UUID.randomUUID().toString(), token("alice")).getStatusCode().value());
        assertEquals(404, cancel("not-a-uuid", token("alice")).getStatusCode().value());
    }

    @Test
    void cancelWithoutToken_is401() {
        String show = createShow(labels(5), 100);
        String resId = reserveOne(show, token("alice"), "A1", "k1");
        assertEquals(401, cancel(resId, null).getStatusCode().value());
    }

    @Test
    void releasedSeat_isCleanlyRebookable() {
        String show = createShow(labels(5), 100);
        String t1 = token("alice");
        String resId = reserveOne(show, t1, "A1", "k1");
        assertEquals(200, cancel(resId, t1).getStatusCode().value());

        ResponseEntity<Map> r = reserve(show, token("bob"), List.of("A1"), "k2");
        assertEquals(201, r.getStatusCode().value());
        assertEquals("confirmed", seatStatus(show, "A1"));
        Map state = get("/shows/" + show + "?include_seats=false", t1).getBody();
        assertEquals(1, ((Number) state.get("confirmed")).intValue());
    }

    @Test
    void cancelDoesNotResurrectASeatReconfirmedToSomeoneElse() {
        String show = createShow(labels(5), 100);
        String t1 = token("alice");
        String staleReservation = reserveOne(show, t1, "A1", "k1");
        assertEquals(200, cancel(staleReservation, t1).getStatusCode().value());

        String newReservation = reserveOne(show, token("bob"), "A1", "k2");
        assertTrue(!newReservation.equals(staleReservation));

        // A retried/late cancel of the OLD reservation must not touch bob's seat.
        ResponseEntity<Map> stale = cancel(staleReservation, t1);
        assertEquals(409, stale.getStatusCode().value());   // already cancelled
        assertEquals("confirmed", seatStatus(show, "A1"));
        assertEquals("bob", jdbc.queryForObject(
            "SELECT user_id FROM seats WHERE show_id = ?::uuid AND label = 'A1'", String.class, show));
    }

    @Test
    void afterCancel_userCanBookUpToTheLimitAgain() {
        String show = createShow(labels(10), 100);
        String t = token("alice");
        String firstReservation = reserveOne(show, t, "A1", "k1");
        for (int i = 2; i <= 4; i++) {
            reserveOne(show, t, "A" + i, "k" + i);
        }
        assertEquals(4, held(show, "alice"));

        assertEquals(200, cancel(firstReservation, t).getStatusCode().value());
        assertEquals(3, held(show, "alice"));

        ResponseEntity<Map> r = reserve(show, t, List.of("A5"), "k5");
        assertEquals(201, r.getStatusCode().value());
        assertEquals(4, held(show, "alice"));
    }

    @Test
    void multiSeatReservation_cancelReleasesAllSeats() {
        String show = createShow(labels(5), 100);
        String t = token("alice");
        ResponseEntity<Map> r = reserve(show, t, List.of("A1", "A2", "A3"), "k1");
        String resId = (String) r.getBody().get("reservation_id");
        assertEquals(3, held(show, "alice"));

        assertEquals(200, cancel(resId, t).getStatusCode().value());
        for (String seat : List.of("A1", "A2", "A3")) {
            assertEquals("available", seatStatus(show, seat));
        }
        assertEquals(0, held(show, "alice"));
    }
}
