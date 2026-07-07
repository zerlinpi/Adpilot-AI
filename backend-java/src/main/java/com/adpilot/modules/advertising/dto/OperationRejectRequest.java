package com.adpilot.modules.advertising.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/advertising/hosting/operations/{id}/reject} (Req 24.3).
 *
 * <p>Carries the operator-provided rejection reason, which is recorded on the linked
 * {@code approval_requests} record and the cancelled Operation's audit trail, and surfaced
 * to the decision originator (Req 24.3).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationRejectRequest {

    /** The operator-provided reason the awaiting-approval Operation is being rejected. */
    @NotBlank(message = "reason must not be blank")
    @Size(max = 1000, message = "reason must be at most 1000 characters")
    @JsonProperty("reason")
    private String reason;
}
