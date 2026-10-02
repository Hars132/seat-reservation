package com.harshal.seats.web;

import static net.logstash.logback.argument.StructuredArguments.kv;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation id: honours a safe client-supplied X-Request-ID, otherwise generates one.
 * Puts it in the MDC (so every log line carries request_id), echoes it on the response,
 * and writes one structured access-log line per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader(HEADER);
        if (id == null || !SAFE.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put("request_id", id);
        res.setHeader(HEADER, id);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String path = req.getRequestURI();
            boolean quiet = path.equals("/healthz") || path.equals("/readyz") || path.startsWith("/actuator/");
            if (quiet) {
                log.debug("request", kv("method", req.getMethod()), kv("path", path),
                        kv("status", res.getStatus()), kv("latency_ms", ms));
            } else {
                log.info("request", kv("method", req.getMethod()), kv("path", path),
                        kv("status", res.getStatus()), kv("latency_ms", ms));
            }
            MDC.clear();
        }
    }
}
