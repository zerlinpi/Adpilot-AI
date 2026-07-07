package com.adpilot.common.api;

import com.adpilot.common.enums.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Servlet-level integration test for the JSON-on-404 error path (Requirement 1.5).
 *
 * <p>Drives a DispatcherServlet wired with {@link ApiNotFoundController} as the
 * {@code /api/**} catch-all so that any undefined {@code /api} route resolves to
 * the controller and returns a structured JSON 404 body
 * {@code {success:false,error:{code:"NOT_FOUND",message}}} instead of Spring
 * Boot's Whitelabel HTML error page — even when the client sends an
 * {@code Accept: text/html} header (the exact condition that produces the HTML
 * leak reported as {@code Unexpected token '<'} on the frontend).
 *
 * Validates: Requirements 1.5
 */
class ApiNotFoundControllerIntegrationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ApiNotFoundController()).build();
    }

    @Test
    @DisplayName("GET on an undefined /api route returns a JSON 404, not HTML")
    void undefinedApiGet_returnsJson404() throws Exception {
        mockMvc.perform(get("/api/this-route-does-not-exist")
                        .accept(MediaType.TEXT_HTML, MediaType.ALL))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ResultCode.NOT_FOUND.name()))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    @Test
    @DisplayName("POST on a deeper undefined /api route also returns a JSON 404, not HTML")
    void undefinedApiPost_returnsJson404() throws Exception {
        mockMvc.perform(post("/api/nope/deeper/missing")
                        .accept(MediaType.TEXT_HTML, MediaType.ALL))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ResultCode.NOT_FOUND.name()))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }
}
