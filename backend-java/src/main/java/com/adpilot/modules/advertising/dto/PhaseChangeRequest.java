package com.adpilot.modules.advertising.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for the admin phase-change endpoint
 * {@code POST /api/advertising/hosting/phase} (Req 17.5).
 *
 * <p>Requests an adjacent-only hosting phase transition (V1↔V2↔V3) for a store. The
 * transition validity and downgrade cleanup are enforced by the
 * {@link com.adpilot.modules.advertising.hosting.PhaseConfigurationService}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhaseChangeRequest {

    /** The store whose active hosting phase is being changed. */
    @NotBlank(message = "store_id must not be blank")
    @JsonProperty("store_id")
    private String storeId;

    /** The target hosting phase: {@code V1}, {@code V2}, or {@code V3} (case-insensitive). */
    @NotBlank(message = "phase must not be blank")
    @JsonProperty("phase")
    private String phase;
}
