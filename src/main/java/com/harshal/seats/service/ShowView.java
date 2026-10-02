package com.harshal.seats.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Counts are exposed both flat and nested ("counts") so either shape is easy to consume.
 * "seats" is omitted when include_seats=false.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShowView(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        int available,
        int held,
        int confirmed,
        Counts counts,
        List<SeatView> seats) {

    public record Counts(int available, int held, int confirmed) {}

    public static ShowView of(String id, String name, long pricePaise, int perUserLimit, int totalSeats,
                              int available, int held, int confirmed, List<SeatView> seats) {
        return new ShowView(id, name, pricePaise, perUserLimit, totalSeats, available, held, confirmed,
                new Counts(available, held, confirmed), seats);
    }
}
