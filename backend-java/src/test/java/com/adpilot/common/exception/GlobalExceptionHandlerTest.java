package com.adpilot.common.exception;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.enums.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler} (optimization M1).
 *
 * <p>Covers two concerns:
 * <ul>
 *   <li>{@link BusinessException} responses carry the intentional user-facing
 *       status/code/message unchanged (401/404/409 pass-through).</li>
 *   <li>The generic 500 handler never leaks internal cause detail (class names,
 *       DB messages) to clients in production; the detail is only appended
 *       outside prod to aid diagnosis.</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handlerFor(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return new GlobalExceptionHandler(env);
    }

    // --- BusinessException pass-through -------------------------------------

    @Test
    void businessException_401_isReturnedUnchanged() {
        GlobalExceptionHandler handler = handlerFor("dev");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusinessException(
                new BusinessException(401, "INVALID_CREDENTIALS", "Invalid email or password"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().isSuccess()).isFalse();
        assertThat(resp.getBody().getError().getCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(resp.getBody().getError().getMessage()).isEqualTo("Invalid email or password");
    }

    @Test
    void businessException_404_isReturnedUnchanged() {
        GlobalExceptionHandler handler = handlerFor("dev");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusinessException(
                new BusinessException(404, "ROLE_NOT_FOUND", "Role not found: 123"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().getError().getCode()).isEqualTo("ROLE_NOT_FOUND");
    }

    @Test
    void businessException_409_isReturnedUnchanged() {
        GlobalExceptionHandler handler = handlerFor("dev");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusinessException(
                new BusinessException(409, "ROLE_CODE_EXISTS", "Role code already exists: admin"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().getError().getCode()).isEqualTo("ROLE_CODE_EXISTS");
    }

    // --- Generic 500 info-disclosure ----------------------------------------

    @Test
    void genericException_inProd_returnsGenericMessageWithoutCauseDetail() {
        GlobalExceptionHandler handler = handlerFor("prod");

        Exception boom = new IllegalStateException(
                "SQLSyntaxError: Table 'adpilot.secret_table' doesn't exist");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleGenericException(boom);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getError().getCode())
                .isEqualTo(String.valueOf(ResultCode.INTERNAL_ERROR.getCode()));
        String message = resp.getBody().getError().getMessage();
        // The client message must NOT contain any internal cause detail.
        assertThat(message).doesNotContain("SQLSyntaxError");
        assertThat(message).doesNotContain("secret_table");
        assertThat(message).doesNotContain("IllegalStateException");
    }

    @Test
    void genericException_inDev_appendsCauseDetailForDiagnosis() {
        GlobalExceptionHandler handler = handlerFor("dev");

        Exception boom = new RuntimeException("wrapper",
                new IllegalStateException("boom-root-cause"));

        ResponseEntity<ApiResponse<Void>> resp = handler.handleGenericException(boom);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String message = resp.getBody().getError().getMessage();
        // Non-prod surfaces the most-specific cause to aid local diagnosis.
        assertThat(message).contains("boom-root-cause");
        assertThat(message).contains("IllegalStateException");
    }
}
