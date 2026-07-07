package com.adpilot.common.security;

import com.adpilot.common.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that requests to a protected route without valid authentication are
 * rejected with HTTP 401, as configured by {@link SecurityConfig} and
 * {@link JwtAuthFilter}.
 *
 * <p>Requirement 2.1.3: IF a request is received without valid authentication for
 * an operation that requires authentication, THEN THE System SHALL reject the
 * request with an HTTP 401 status.</p>
 */
@WebMvcTest(controllers = ProtectedRouteAuthTest.ProtectedTestController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class ProtectedRouteAuthTest {

    @Autowired
    private MockMvc mockMvc;

    // Collaborators of JwtAuthFilter — not exercised on the no-token path, but
    // required to construct the filter bean inside the security slice.
    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserContextService userContextService;

    @MockBean
    private SessionControlService sessionControlService;

    @Test
    void protectedRoute_withoutAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/protected/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("401"));
    }

    @Test
    void protectedRoute_withInvalidBearerToken_returns401() throws Exception {
        // jwtUtil.validateToken(...) returns false by default for the mock, so the
        // filter never establishes authentication and the request is rejected.
        mockMvc.perform(get("/api/protected/ping")
                        .header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("401"));
    }

    /**
     * Minimal protected endpoint used only by this slice test. It is not in the
     * public allow-list of {@link SecurityConfig}, so it requires authentication.
     */
    @RestController
    static class ProtectedTestController {

        @GetMapping("/api/protected/ping")
        String ping() {
            return "pong";
        }
    }
}
