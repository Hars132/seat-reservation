package com.harshal.seats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class MetricsTest extends ApiTestBase {

    @Autowired MeterRegistry registry;

    private double confirmed() {
        return registry.counter("reservations_confirmed_total").count();
    }

    private double declined(String reason) {
        return registry.counter("reservations_declined_total", "reason", reason).count();
    }

    private double gauge(String showId) {
        Gauge g = registry.find("seats_available").tag("show", showId).gauge();
        assertNotNull(g, "gauge for show " + showId + " should be registered");
        return g.value();
    }

    private ResponseEntity<Map> reserve(String show, String token, List<String> seats, String key) {
        return post("/shows/" + show + "/reserve", token, Map.of("seats", seats, "idempotency_key", key));
    }

    @Test
    void metricsEndpoint_isPublic_andPrometheusFormatted() {
        ResponseEntity<String> r = rest.getForEntity("/metrics", String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("reservations_confirmed_total"));
    }

    @Test
    void confirmedCounter_incrementsOnNewReservation_notOnReplay() {
        String show = createShow(labels(3), 100);
        double before = confirmed();

        assertEquals(201, reserve(show, token("alice"), List.of("A1"), "k1").getStatusCode().value());
        assertEquals(before + 1, confirmed());

        reserve(show, token("alice"), List.of("A1"), "k1"); // replay
        assertEquals(before + 1, confirmed(), "a replay must not double-count as confirmed");
    }

    @Test
    void declinedCounter_seatTaken() {
        String show = createShow(labels(3), 100);
        reserve(show, token("u1"), List.of("A1"), "k1");
        double before = declined("seat_taken");

        assertEquals(409, reserve(show, token("u2"), List.of("A1"), "k2").getStatusCode().value());
        assertEquals(before + 1, declined("seat_taken"));
    }

    @Test
    void declinedCounter_perUserLimit() {
        String show = createShow(labels(10), 100);
        double before = declined("per_user_limit");

        assertEquals(409, reserve(show, token("alice"), labels(5), "k1").getStatusCode().value());
        assertEquals(before + 1, declined("per_user_limit"));
    }

    @Test
    void declinedCounter_idempotentReplay() {
        String show = createShow(labels(3), 100);
        reserve(show, token("alice"), List.of("A1"), "k1");
        double before = declined("idempotent_replay");

        reserve(show, token("alice"), List.of("A1"), "k1");
        assertEquals(before + 1, declined("idempotent_replay"));
    }

    @Test
    void seatsAvailableGauge_tracksReserveAndCancel() {
        String show = createShow(labels(5), 100);
        assertEquals(5.0, gauge(show));

        ResponseEntity<Map> r = reserve(show, token("alice"), List.of("A1"), "k1");
        assertEquals(4.0, gauge(show));

        post("/reservations/" + r.getBody().get("reservation_id") + "/cancel", token("alice"), Map.of());
        assertEquals(5.0, gauge(show));
    }
}
