package com.harshal.seats.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies the Bearer token and exposes the caller as request attribute "principal".
 * Identity comes from here and nowhere else. Public: health, metrics, token minting.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR = "principal";

    private final JwtService jwt;

    public AuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return p.equals("/healthz") || p.equals("/readyz") || p.equals("/metrics")
                || p.startsWith("/actuator/") || p.equals("/auth/token");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(res, "missing bearer token");
            return;
        }
        Principal principal;
        try {
            principal = jwt.parse(header.substring(7).trim());
        } catch (JwtException | IllegalArgumentException e) {
            unauthorized(res, "invalid or expired token");
            return;
        }
        req.setAttribute(ATTR, principal);
        MDC.put("user_id", principal.userId());
        chain.doFilter(req, res);
    }

    private static void unauthorized(HttpServletResponse res, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", "Bearer");
        res.setContentType("application/json");
        res.getOutputStream().write(
            ("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
