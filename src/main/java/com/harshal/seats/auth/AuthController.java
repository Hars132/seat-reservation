package com.harshal.seats.auth;

import com.harshal.seats.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exercise-only identity provider: anyone can mint a USER token for any user id, so a test
 * harness can create thousands of distinct buyers. ADMIN requires the configured admin secret.
 * Everything else in the API treats the token, not the body, as the source of truth for identity.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    public record TokenRequest(String userId, String adminSecret) {}
    public record TokenResponse(String token, String userId, String role, long expiresInSeconds) {}

    private final JwtService jwt;
    private final byte[] adminSecretBytes;

    public AuthController(JwtService jwt, @Value("${app.admin.secret}") String adminSecret) {
        this.jwt = jwt;
        this.adminSecretBytes = adminSecret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/token")
    public TokenResponse token(@RequestBody TokenRequest req) {
        if (req.userId() == null || !USER_ID.matcher(req.userId()).matches()) {
            throw new ApiException(400, "invalid_user_id", "user_id must match [A-Za-z0-9._@-]{1,64}");
        }
        Role role = Role.USER;
        if (req.adminSecret() != null) {
            boolean ok = MessageDigest.isEqual(adminSecretBytes, req.adminSecret().getBytes(StandardCharsets.UTF_8));
            if (!ok) {
                throw new ApiException(403, "forbidden", "invalid admin_secret");
            }
            role = Role.ADMIN;
        }
        return new TokenResponse(jwt.issue(req.userId(), role), req.userId(), role.name(), jwt.ttlSeconds());
    }

    /** Echo of the identity the server derived from the token. Handy for debugging and tests. */
    @GetMapping("/me")
    public Map<String, String> me(@RequestAttribute(AuthFilter.ATTR) Principal p) {
        return Map.of("user_id", p.userId(), "role", p.role().name());
    }
}
