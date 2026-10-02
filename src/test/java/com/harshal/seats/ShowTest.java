package com.harshal.seats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class ShowTest extends ApiTestBase {

    private Map<String, Object> body(Object seats, Object price) {
        return Map.of("name", "friday-night", "seats", seats, "price_paise", price);
    }

    private int num(Map m, String key) {
        return ((Number) m.get(key)).intValue();
    }

    // ---- create ----

    @Test
    void admin_createsShow_withEverySeatAvailable() {
        ResponseEntity<Map> r = post("/shows", adminToken(), body(List.of("A1", "A2", "A3"), 25000));
        assertEquals(201, r.getStatusCode().value());
        Map s = r.getBody();
        assertNotNull(s.get("id"));
        assertEquals(25000, num(s, "price_paise"));
        assertEquals(4, num(s, "per_user_limit"));       // default
        assertEquals(3, num(s, "total_seats"));
        assertEquals(3, num(s, "available"));
        assertEquals(0, num(s, "held"));
        assertEquals(0, num(s, "confirmed"));
        List<Map> seats = (List<Map>) s.get("seats");
        assertEquals(3, seats.size());
        assertTrue(seats.stream().allMatch(x -> "available".equals(x.get("status"))));
    }

    @Test
    void customPerUserLimit_isStored() {
        ResponseEntity<Map> r = post("/shows", adminToken(),
                Map.of("name", "x", "seats", List.of("A1"), "price_paise", 100, "per_user_limit", 2));
        assertEquals(201, r.getStatusCode().value());
        assertEquals(2, num(r.getBody(), "per_user_limit"));
    }

    @Test
    void seatOrder_isPreserved() {
        ResponseEntity<Map> r = post("/shows", adminToken(), body(List.of("A10", "A2", "A1"), 100));
        List<Map> seats = (List<Map>) r.getBody().get("seats");
        assertEquals(List.of("A10", "A2", "A1"), seats.stream().map(x -> x.get("label")).toList());
        String id = (String) r.getBody().get("id");
        List<Map> fetched = (List<Map>) get("/shows/" + id, userToken("u")).getBody().get("seats");
        assertEquals(List.of("A10", "A2", "A1"), fetched.stream().map(x -> x.get("label")).toList());
    }

    @Test
    void nonAdmin_is403() {
        assertEquals(403, post("/shows", userToken("alice"), body(List.of("A1"), 100)).getStatusCode().value());
    }

    @Test
    void noToken_is401() {
        assertEquals(401, post("/shows", null, body(List.of("A1"), 100)).getStatusCode().value());
    }

    @Test
    void duplicateLabels_is400() {
        assertEquals(400, post("/shows", adminToken(), body(List.of("A1", "A2", "A1"), 100)).getStatusCode().value());
    }

    @Test
    void emptyOrMissingSeats_is400() {
        assertEquals(400, post("/shows", adminToken(), body(List.of(), 100)).getStatusCode().value());
        assertEquals(400, post("/shows", adminToken(), Map.of("name", "x", "price_paise", 100)).getStatusCode().value());
    }

    @Test
    void invalidLabel_is400() {
        assertEquals(400, post("/shows", adminToken(), body(List.of("bad label!"), 100)).getStatusCode().value());
    }

    @Test
    void badPrice_is400() {
        String t = adminToken();
        assertEquals(400, post("/shows", t, body(List.of("A1"), 0)).getStatusCode().value());
        assertEquals(400, post("/shows", t, body(List.of("A1"), -5)).getStatusCode().value());
        assertEquals(400, post("/shows", t, body(List.of("A1"), 250.5)).getStatusCode().value()); // float, never truncated
        assertEquals(400, post("/shows", t, Map.of("name", "x", "seats", List.of("A1"))).getStatusCode().value());
    }

    @Test
    void badPerUserLimit_is400() {
        assertEquals(400, post("/shows", adminToken(),
                Map.of("name", "x", "seats", List.of("A1"), "price_paise", 100, "per_user_limit", 0))
                .getStatusCode().value());
    }

    @Test
    void missingName_is400() {
        assertEquals(400, post("/shows", adminToken(),
                Map.of("seats", List.of("A1"), "price_paise", 100)).getStatusCode().value());
    }

    @Test
    void malformedJson_is400() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(adminToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = rest.exchange("/shows", HttpMethod.POST, new HttpEntity<>("{not json", h), Map.class);
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void largeShow_50kSeats_isCreated() {
        ResponseEntity<Map> r = post("/shows", adminToken(), body(labels(50_000), 25000));
        assertEquals(201, r.getStatusCode().value());
        assertEquals(50_000, num(r.getBody(), "total_seats"));
        String id = (String) r.getBody().get("id");
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM seats WHERE show_id = ?::uuid", Integer.class, id);
        assertEquals(50_000, rows);
    }

    // ---- get ----

    @Test
    void get_returnsCounts_andInvariantHolds() {
        String id = createShow(labels(10), 25000);
        ResponseEntity<Map> r = get("/shows/" + id, userToken("bob"));   // any authenticated user
        assertEquals(200, r.getStatusCode().value());
        Map s = r.getBody();
        assertEquals(num(s, "total_seats"), num(s, "available") + num(s, "held") + num(s, "confirmed"));
        Map counts = (Map) s.get("counts");
        assertEquals(10, num(counts, "available"));
        assertEquals(10, ((List) s.get("seats")).size());
    }

    @Test
    void get_withIncludeSeatsFalse_omitsSeatList_butKeepsCounts() {
        String id = createShow(labels(5), 100);
        Map s = get("/shows/" + id + "?include_seats=false", userToken("bob")).getBody();
        assertFalse(s.containsKey("seats"));
        assertEquals(5, num(s, "available"));
        assertEquals(5, num(s, "total_seats"));
    }

    @Test
    void get_reflectsSeatStateInDb() {
        String id = createShow(labels(4), 100);
        // Simulate a confirmed seat directly (the reserve flow comes later).
        UUID res = UUID.randomUUID();
        jdbc.update("INSERT INTO reservations(id, show_id, user_id, seats, amount_paise, status) "
                  + "VALUES (?, ?::uuid, 'u1', ARRAY['A1'], 100, 'confirmed')", res, id);
        jdbc.update("UPDATE seats SET status='confirmed', reservation_id=?, user_id='u1' "
                  + "WHERE show_id=?::uuid AND label='A1'", res, id);
        for (String path : List.of("/shows/" + id, "/shows/" + id + "?include_seats=false")) {
            Map s = get(path, userToken("bob")).getBody();
            assertEquals(3, num(s, "available"));
            assertEquals(1, num(s, "confirmed"));
            assertEquals(4, num(s, "available") + num(s, "held") + num(s, "confirmed"));
        }
    }

    @Test
    void get_unknownShow_is404() {
        assertEquals(404, get("/shows/" + UUID.randomUUID(), userToken("bob")).getStatusCode().value());
        assertEquals(404, get("/shows/not-a-uuid", userToken("bob")).getStatusCode().value());
    }

    @Test
    void get_withoutToken_is401() {
        assertEquals(401, get("/shows/" + UUID.randomUUID(), null).getStatusCode().value());
    }
}
