package com.harshal.seats.service;

import java.util.List;

public record ReservationView(
        String reservationId,
        String showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status) {}
