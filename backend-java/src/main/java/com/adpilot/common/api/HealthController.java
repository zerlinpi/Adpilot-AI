package com.adpilot.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational health / readiness endpoint for load-balancer and Kubernetes
 * liveness/readiness probes.
 *
 * <p>{@code /api/health} is whitelisted in both {@link com.adpilot.common.config.SecurityConfig}
 * and {@link com.adpilot.common.security.JwtAuthFilter}, so probes reach it without a JWT.
 * This concrete mapping is more specific than the {@code /api/**} catch-all in
 * {@link ApiNotFoundController}, so the advertised path is now functional instead of
 * falling through to a JSON 404.
 *
 * <p>The endpoint is fast and side-effect-free: it runs a lightweight {@code SELECT 1}
 * against MySQL and pings Redis. Both probes are wrapped so a dependency failure yields a
 * {@code DOWN} component (and HTTP 503 when a <em>critical</em> dependency is down) rather
 * than a 500 stack trace. MySQL is treated as the liveness essential — when it is
 * unreachable the service reports {@code DOWN}/503. Redis reachability is reported but not
 * fatal, because the application degrades gracefully to in-memory fallbacks when Redis is
 * unavailable.
 *
 * <p>The response is written directly (bypassing {@link GlobalResponseWrapper}) so the body
 * is a self-contained {@code {status, db, redis, time}} document and the HTTP status code
 * deterministically reflects health — never a {@code success:true} envelope paired with a
 * 503. No stack traces or internal details are leaked.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/health")
    public void health(HttpServletResponse response) throws IOException {
        boolean dbUp = probeDb();
        boolean redisUp = probeRedis();

        // Liveness/readiness essential: the database must be reachable. Redis is
        // reported but non-fatal — the app falls back to in-memory paths when Redis
        // is down — so it never alone drives the probe to 503.
        boolean up = dbUp;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "UP" : "DOWN");
        body.put("db", dbUp ? "UP" : "DOWN");
        body.put("redis", redisUp ? "UP" : "DOWN");
        body.put("time", LocalDateTime.now().format(TIME_FORMAT));

        response.setStatus(up ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /** Lightweight MySQL reachability probe. Never throws — failure maps to DOWN. */
    private boolean probeDb() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception ex) {
            log.warn("Health: DB probe failed: {}", ex.getMessage());
            return false;
        }
    }

    /** Redis connection ping probe. Never throws — failure/absence maps to DOWN. */
    private boolean probeRedis() {
        try {
            var factory = redisTemplate.getConnectionFactory();
            if (factory == null) {
                return false;
            }
            factory.getConnection().ping();
            return true;
        } catch (Exception ex) {
            log.warn("Health: Redis probe failed: {}", ex.getMessage());
            return false;
        }
    }
}
