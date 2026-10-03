package com.harshal.seats.web;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /metrics is a convenience alias for Actuator's own Prometheus endpoint at /actuator/prometheus
 * (already public, see AuthFilter and application.yml: management.endpoints.web.exposure).
 * A redirect, rather than a hand-written scrape() call, avoids coupling this code to whichever
 * concrete Micrometer registry class a given Boot/Micrometer version happens to ship.
 * curl needs -L to follow it; TestRestTemplate and browsers follow 302s automatically.
 */
@RestController
public class MetricsController {

    @GetMapping("/metrics")
    public ResponseEntity<Void> metrics() {
        return ResponseEntity.status(302).location(URI.create("/actuator/prometheus")).build();
    }
}
