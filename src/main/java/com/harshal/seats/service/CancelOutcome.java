package com.harshal.seats.service;

/** errorCode/message are null on success (httpStatus 200). */
public record CancelOutcome(int httpStatus, String errorCode, String message) {
    public boolean isSuccess() { return httpStatus == 200; }
}
