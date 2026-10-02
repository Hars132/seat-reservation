package com.harshal.seats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.harshal.seats.auth.JwtService;
import com.harshal.seats.auth.Role;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@SuppressWarnings({"rawtypes", "unchecked"})
class AuthTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Value("${app.admin.secret}") String adminSecret;

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private ResponseEntity<Map> mint(Map<String, String> body) {
        return rest.postForEntity("/auth/token", body, Map.class);
    }

    private String mintUserToken(String userId) {
        return (String) mint(Map.of("user_id", userId)).getBody().get("token");
    }

    @Test
    void me_withoutToken_is401() {
        assertEquals(401, get("/auth/me", null).getStatusCode().value());
    }

    @Test
    void mintedUserToken_identifiesThatUser_withRoleUser() {
        ResponseEntity<Map> me = get("/auth/me", mintUserToken("alice"));
        assertEquals(200, me.getStatusCode().value());
        assertEquals("alice", me.getBody().get("user_id"));
        assertEquals("USER", me.getBody().get("role"));
    }

    @Test
    void wrongAdminSecret_is403() {
        assertEquals(403, mint(Map.of("user_id", "mallory", "admin_secret", "nope")).getStatusCode().value());
    }

    @Test
    void correctAdminSecret_givesAdminRole() {
        String token = (String) mint(Map.of("user_id", "root", "admin_secret", adminSecret)).getBody().get("token");
        assertEquals("ADMIN", get("/auth/me", token).getBody().get("role"));
    }

    @Test
    void invalidUserId_is400() {
        assertEquals(400, mint(Map.of("user_id", "bad id!")).getStatusCode().value());
        assertEquals(400, mint(Map.of("user_id", "")).getStatusCode().value());
    }

    @Test
    void tokenSignedWithDifferentKey_is401() {
        String forged = new JwtService("another-secret-another-secret-another-secret-1234", 60)
                .issue("alice", Role.ADMIN);
        assertEquals(401, get("/auth/me", forged).getStatusCode().value());
    }

    @Test
    void expiredToken_is401() {
        String expired = jwt.issue("alice", Role.USER, Duration.ofSeconds(-60));
        assertEquals(401, get("/auth/me", expired).getStatusCode().value());
    }

    @Test
    void algNoneToken_is401() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString("{\"sub\":\"alice\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(401, get("/auth/me", header + "." + payload + ".").getStatusCode().value());
    }

    @Test
    void garbageToken_is401() {
        assertEquals(401, get("/auth/me", "not-a-jwt").getStatusCode().value());
    }

    @Test
    void healthEndpoints_arePublic() {
        assertEquals(200, get("/healthz", null).getStatusCode().value());
        assertEquals(200, get("/readyz", null).getStatusCode().value());
    }

    @Test
    void requestId_isGenerated_honoured_andSanitised() {
        String generated = get("/healthz", null).getHeaders().getFirst("X-Request-ID");
        assertNotNull(generated);

        HttpHeaders h = new HttpHeaders();
        h.set("X-Request-ID", "my-trace-123");
        ResponseEntity<Map> echoed = rest.exchange("/healthz", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertEquals("my-trace-123", echoed.getHeaders().getFirst("X-Request-ID"));

        h.set("X-Request-ID", "bad id with spaces!");
        ResponseEntity<Map> replaced = rest.exchange("/healthz", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertNotEquals("bad id with spaces!", replaced.getHeaders().getFirst("X-Request-ID"));
    }
}
