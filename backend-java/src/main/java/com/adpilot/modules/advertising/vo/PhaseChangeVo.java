package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response view for the admin phase-change endpoint
 * {@code POST /api/advertising/hosting/phase} (Req 17.5).
 *
 * <p>Reports the store's resulting active hosting phase after an adjacent-only transition,
 * along with the phase it transitioned from.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhaseChangeVo {

    /** The store whose hosting phase was changed. */
    @JsonProperty("store_id")
    private String storeId;

    /** The phase the store was in before the transition. */
    @JsonProperty("previous_phase")
    private String previousPhase;

    /** The store's active hosting phase after the transition. */
    @JsonProperty("active_phase")
    private String activePhase;
}
