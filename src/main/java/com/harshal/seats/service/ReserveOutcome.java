package com.harshal.seats.service;

/**
 * A reserve call ends in exactly one of these. Declines are domain outcomes (4xx), not errors.
 * replay = true means this response was served from the stored result of an earlier request
 * with the same idempotency key (nothing new was done).
 */
public sealed interface ReserveOutcome {

    record Created(ReservationView reservation, boolean replay) implements ReserveOutcome {}

    record Declined(int httpStatus, String reason, String message, boolean replay) implements ReserveOutcome {}
}
