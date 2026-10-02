package com.harshal.seats.web;

import com.harshal.seats.auth.AuthFilter;
import com.harshal.seats.auth.Principal;
import com.harshal.seats.service.CancelOutcome;
import com.harshal.seats.service.ReservationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cancel is ownership-checked inside the same atomic update as the lookup (see
 * ReservationService.cancel), so "only the owner may cancel" cannot be bypassed by a race.
 * A reservation belonging to someone else, or that does not exist, both return 404: the API
 * never reveals that a reservation id it does not own exists.
 */
@RestController
public class ReservationLookupController {

    private final ReservationService reservations;

    public ReservationLookupController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<Object> cancel(@RequestAttribute(AuthFilter.ATTR) Principal principal,
                                         @PathVariable String id) {
        UUID reservationId;
        try {
            reservationId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(404, "reservation_not_found", "reservation not found");
        }

        CancelOutcome outcome = reservations.cancel(reservationId, principal.userId());
        if (outcome.isSuccess()) {
            return ResponseEntity.ok(Map.of("reservation_id", id, "status", "cancelled"));
        }
        return ResponseEntity.status(outcome.httpStatus())
                .body(Map.of("error", outcome.errorCode(), "message", outcome.message()));
    }
}
