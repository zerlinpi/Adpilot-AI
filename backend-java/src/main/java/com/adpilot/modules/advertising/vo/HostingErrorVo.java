package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured error payload for hosting endpoints (Req 26.1, 26.5).
 *
 * <p>Carried as the {@code data} of the standard {@link com.adpilot.common.api.ApiResponse}
 * envelope so every hosting error response surfaces a structured {@code HOSTING_*} code, a
 * human-readable message, and a {@code request_id} for tracing (Req 26.5).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostingErrorVo {

    /** Structured error code, prefixed {@code HOSTING_} (Req 26.1). */
    @JsonProperty("code")
    private String code;

    /** Human-readable error message. */
    @JsonProperty("message")
    private String message;

    /** Correlation id present on every hosting error response (Req 26.5). */
    @JsonProperty("request_id")
    private String requestId;
}
