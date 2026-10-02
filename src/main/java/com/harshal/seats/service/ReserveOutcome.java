package com.harshal.seats.service;

/** A reserve call ends in exactly one of these. Declines are domain outcomes (4xx), not errors. */
public sealed interface ReserveOutcome {

    record Created(ReservationView reservation) implements ReserveOutcome {}

    record Declined(int httpStatus, String reason, String message) implements ReserveOutcome {}
}
