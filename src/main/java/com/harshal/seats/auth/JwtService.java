package com.harshal.seats.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Issues and verifies HS256 tokens. Unsigned (alg=none) or wrongly signed tokens never parse. */
@Service
public class JwtService {

    private final SecretKey key;
    private final JwtParser parser;
    private final Duration ttl;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-seconds:43200}") long ttlSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.parser = Jwts.parser().verifyWith(key).build();
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public long ttlSeconds() { return ttl.toSeconds(); }

    public String issue(String userId, Role role) {
        return issue(userId, role, ttl);
    }

    public String issue(String userId, Role role, Duration lifetime) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** @throws JwtException or IllegalArgumentException if the token is invalid in any way. */
    public Principal parse(String token) {
        Claims c = parser.parseSignedClaims(token).getPayload();
        String sub = c.getSubject();
        String role = c.get("role", String.class);
        if (sub == null || sub.isBlank() || role == null) {
            throw new JwtException("missing claims");
        }
        return new Principal(sub, Role.valueOf(role));
    }
}
