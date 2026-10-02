package com.harshal.seats;

import com.harshal.seats.auth.JwtService;
import com.harshal.seats.auth.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared plumbing for API-level tests: real Postgres, real HTTP, real tokens. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
abstract class ApiTestBase {

    // ONE Postgres container for the whole test JVM, started once and never stopped between test
    // classes (Ryuk removes it at JVM exit). Spring caches the application context across test
    // classes that share this base; a per-class @Container would be stopped and restarted on a new
    // port while the cached context still pointed at the old one, giving 503s on every later class.
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        pg.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JwtService jwt;
    @Value("${app.admin.secret}") String adminSecret;

    /** Fast user token (signed directly, no HTTP), for tests that need hundreds of users. */
    protected String token(String userId) {
        return jwt.issue(userId, Role.USER);
    }

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

    /** Runs all tasks on `threads` threads, released together by a start gate. Returns results in task order. */
    protected static <T> List<T> runConcurrently(int threads, List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return t.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(180, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
