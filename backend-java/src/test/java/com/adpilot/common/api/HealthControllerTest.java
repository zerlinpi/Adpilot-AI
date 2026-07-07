package com.adpilot.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Servlet-level test for the operational health endpoint {@link HealthController}
 * (optimization B1).
 *
 * <p>Drives a standalone {@code DispatcherServlet} wired with the controller so the
 * actual {@code /api/health} request mapping and the direct servlet response write are
 * exercised, while the MySQL ({@link JdbcTemplate}) and Redis ({@link RedisTemplate})
 * probes are mocked. Confirms:
 * <ul>
 *   <li>an unauthenticated GET returns 200 with {@code status=UP} under normal wiring;</li>
 *   <li>a failing DB probe drives the endpoint to 503 with the {@code DOWN} component map
 *       (no stack trace leak);</li>
 *   <li>a failing Redis probe alone is reported but non-fatal (still 200/UP).</li>
 * </ul>
 */
class HealthControllerTest {

    private JdbcTemplate jdbcTemplate;
    private RedisTemplate<String, Object> redisTemplate;
    private RedisConnectionFactory redisConnectionFactory;
    private RedisConnection redisConnection;
    private MockMvc mockMvc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        redisTemplate = mock(RedisTemplate.class);
        redisConnectionFactory = mock(RedisConnectionFactory.class);
        redisConnection = mock(RedisConnection.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(redisConnectionFactory);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);

        HealthController controller =
                new HealthController(jdbcTemplate, redisTemplate, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("unauthenticated GET /api/health returns 200 UP when DB and Redis are reachable")
    void healthUpWhenDependenciesReachable() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(redisConnection.ping()).thenReturn("PONG");

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.db").value("UP"))
                .andExpect(jsonPath("$.redis").value("UP"))
                .andExpect(jsonPath("$.time").exists());
    }

    @Test
    @DisplayName("GET /api/health returns 503 DOWN with the component map when the DB probe fails")
    void healthDownWhenDbUnreachable() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        when(redisConnection.ping()).thenReturn("PONG");

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.db").value("DOWN"))
                .andExpect(jsonPath("$.redis").value("UP"))
                // No stack trace / internal detail leaks into the body.
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/health stays 200 UP when only Redis is down (non-fatal)")
    void healthUpWhenOnlyRedisDown() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(redisConnection.ping()).thenThrow(new RuntimeException("redis down"));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.db").value("UP"))
                .andExpect(jsonPath("$.redis").value("DOWN"));
    }
}
