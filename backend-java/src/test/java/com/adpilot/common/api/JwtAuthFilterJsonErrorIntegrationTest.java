package com.adpilot.common.api;

import com.adpilot.common.config.SecurityConfig;
import com.adpilot.common.security.JwtAuthFilter;
import com.adpilot.common.security.JwtUtil;
import com.adpilot.common.security.SessionControlService;
import com.adpilot.common.security.UserContextService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Servlet-level integration test for the JSON-on-filter-error path (Requirement 1.3).
 *
 * <p>Runs through the real {@link SecurityConfig} filter chain (the same chain used
 * in production) so that an exception thrown inside {@link JwtAuthFilter} — which
 * executes before the request reaches the DispatcherServlet and is therefore beyond
 * the reach of {@code GlobalExceptionHandler} — is serialized by the filter itself
 * into an HTTP 500 JSON envelope rather than Spring Boot's Whitelabel HTML error
 * page, even when the client sends an {@code Accept: text/html} header.
 *
 * Validates: Requirements 1.3
 */
@WebMvcTest(controllers = ApiNotFoundController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class JwtAuthFilterJsonErrorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // Collaborators required to construct the JwtAuthFilter bean inside the slice.
    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserContextService userContextService;

    @MockBean
    private SessionControlService sessionControlService;

    @Test
    @DisplayName("Exception thrown inside JwtAuthFilter returns a JSON 500, not HTML")
    void filterThrows_returnsJson500() throws Exception {
        // validateToken is invoked outside the filter's inner try/catch, so a thrown
        // exception propagates to the filter's outer catch, which serializes a JSON
        // error envelope instead of letting the request fall through to the
        // Whitelabel HTML page.
        when(jwtUtil.validateToken(anyString()))
                .thenThrow(new RuntimeException("simulated filter failure"));

        mockMvc.perform(get("/api/anything")
                        .header("Authorization", "Bearer test-token")
                        .accept(MediaType.TEXT_HTML, MediaType.ALL))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("500"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }
}
