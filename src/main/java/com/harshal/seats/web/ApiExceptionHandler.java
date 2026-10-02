package com.harshal.seats.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Uniform error body: {"error": "<code>", "message": "<text>"}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "malformed_request", "message", "request body is missing or not valid JSON"));
    }

    /** DB unreachable, pool exhausted, lock/statement timeout: fail closed with a retryable 503. */
    @ExceptionHandler({TransientDataAccessException.class,
                       DataAccessResourceFailureException.class,
                       CannotCreateTransactionException.class})
    ResponseEntity<Map<String, Object>> infrastructure(Exception e) {
        log.error("infrastructure failure: {}", e.toString());
        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(Map.of("error", "temporarily_unavailable", "message", "dependency unavailable, retry shortly"));
    }
}
