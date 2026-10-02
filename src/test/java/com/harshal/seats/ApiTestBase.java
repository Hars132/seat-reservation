package com.harshal.seats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Shared plumbing for API-level tests: real Postgres, real HTTP, real tokens. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@SuppressWarnings({"rawtypes", "unchecked"})
abstract class ApiTestBase {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;
    @Value("${app.admin.secret}") String adminSecret;

    protected String userToken(String userId) {
        return (String) rest.postForEntity("/auth/token", Map.of("user_id", userId), Map.class)
                .getBody().get("token");
    }

    protected String adminToken() {
        return (String) rest.postForEntity("/auth/token",
                Map.of("user_id", "admin", "admin_secret", adminSecret), Map.class).getBody().get("token");
    }

    protected ResponseEntity<Map> post(String path, String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    protected ResponseEntity<Map> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    protected static List<String> labels(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) out.add("A" + i);
        return out;
    }

    /** Creates a show as admin and returns its id. */
    protected String createShow(List<String> seats, long pricePaise) {
        ResponseEntity<Map> r = post("/shows", adminToken(),
                Map.of("name", "test-show", "seats", seats, "price_paise", pricePaise));
        if (r.getStatusCode().value() != 201) {
            throw new IllegalStateException("show creation failed: " + r.getStatusCode() + " " + r.getBody());
        }
        return (String) r.getBody().get("id");
    }
}
