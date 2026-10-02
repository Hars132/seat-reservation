package com.harshal.seats.web;

import com.harshal.seats.auth.AuthFilter;
import com.harshal.seats.auth.Principal;
import com.harshal.seats.service.ReservationService;
import com.harshal.seats.service.ReserveOutcome;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    public record ReserveRequest(List<String> seats, String idempotencyKey) {}

    private static final int MAX_SEATS_PER_REQUEST = 100;
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,128}");

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /**
     * The caller is the token's user. Any user_id in the body is never read.
     * Multi-seat policy: ALL-OR-NOTHING. Either every requested seat is yours or none is.
     * Idempotency key: "Idempotency-Key" header, or "idempotency_key" in the body.
     */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<Object> reserve(@RequestAttribute(AuthFilter.ATTR) Principal principal,
                                          @PathVariable String id,
                                          @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
                                          @RequestBody ReserveRequest req) {
        java.util.UUID showId = ShowController.parseId(id);

        String key = resolveKey(headerKey, req.idempotencyKey());
        List<String> seats = validateSeats(req.seats());

        // NOTE (step 6): `key` is validated here and enforced for exactly-once in the next step.
        ReserveOutcome outcome = reservations.reserve(showId, principal.userId(), seats);

        if (outcome instanceof ReserveOutcome.Created c) {
            return ResponseEntity.status(201).body(c.reservation());
        }
        ReserveOutcome.Declined d = (ReserveOutcome.Declined) outcome;
        return ResponseEntity.status(d.httpStatus())
                .body(Map.of("error", d.reason(), "message", d.message()));
    }

    private static String resolveKey(String headerKey, String bodyKey) {
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
            throw bad("Idempotency-Key header and idempotency_key body field disagree");
        }
        String key = headerKey != null ? headerKey : bodyKey;
        if (key == null || !KEY.matcher(key).matches()) {
            throw bad("an idempotency key (header Idempotency-Key or body idempotency_key, 1-128 printable chars) is required");
        }
        return key;
    }

    private static List<String> validateSeats(List<String> seats) {
        if (seats == null || seats.isEmpty()) {
            throw bad("seats must be a non-empty list");
        }
        if (seats.size() > MAX_SEATS_PER_REQUEST) {
            throw bad("at most " + MAX_SEATS_PER_REQUEST + " seats per request");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < seats.size(); i++) {
            String s = seats.get(i);
            if (s == null || !ShowController.LABEL.matcher(s).matches()) {
                throw bad("invalid seat label at index " + i);
            }
            if (!seen.add(s)) {
                throw bad("duplicate seat label at index " + i);
            }
        }
        return seats;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, "invalid_request", message);
    }
}
