package com.harshal.seats.web;

import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness: the process is up. Never touches the DB, so a DB outage does not get the pod killed.
 * Readiness: the DB answers a trivial query within 2s. Fails CLOSED (503) on any error.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final JdbcTemplate jdbc;

    public HealthController(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(2); // seconds; a hung DB must not hang the probe
    }

    @GetMapping("/healthz")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP", "db", "UP"));
        } catch (Exception e) {
            log.warn("readiness check failed: {}", e.toString());
            return ResponseEntity.status(503).body(Map.of("status", "DOWN", "db", "DOWN"));
        }
    }
}
