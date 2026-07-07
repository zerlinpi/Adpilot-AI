package com.adpilot.modules.advertising.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Request body for {@code PUT /api/advertising/hosting/config/{storeId}} (Req 21.2, 12.2).
 *
 * <p>All fields are optional; only the non-null fields supplied are overlaid onto the
 * persisted configuration. The JSON contract uses the snake_case keys named directly in
 * Requirement 21.1 so the persisted {@code hosting_configs.config} payload stays compatible
 * with the lightweight readers in {@code ShadowModeServiceImpl} and
 * {@code PhaseConfigurationServiceImpl}.</p>
 *
 * <p>Backend validation (Req 12.6, 21.3, 21.4) is performed by
 * {@code HostingConfigService} rather than by bean-validation annotations, because the
 * only-tighten boundary rule needs the resolved higher-level boundary.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostingConfigRequest {

    /** Active hosting phase: V1 / V2 / V3 (Req 21.1). */
    @JsonProperty("active_phase")
    private String activePhase;

    /** Default AI personality machine value: conservative / balanced / aggressive (Req 12.6). */
    @JsonProperty("default_personality")
    private String defaultPersonality;

    /** Execution mode enum value: observe_only / recommend_only / approval_required / auto_execute. */
    @JsonProperty("execution_mode")
    private String executionMode;

    /** Risk threshold above which auto-execute is gated; must be within [0.0, 1.0] (Req 21.3). */
    @JsonProperty("auto_execute_threshold")
    private BigDecimal autoExecuteThreshold;

    /** Whether the emergency internal auto-action (kill switch) is enabled. */
    @JsonProperty("emergency_auto_action_enabled")
    private Boolean emergencyAutoActionEnabled;

    /** Whether the store is in shadow mode (decisions logged, no Operations). */
    @JsonProperty("shadow_mode")
    private Boolean shadowMode;

    /** Free-form notification preferences map persisted verbatim. */
    @JsonProperty("notification_preferences")
    private Map<String, Object> notificationPreferences;

    /**
     * Safety-boundary overrides keyed by {@code SafetyBoundaryLimit} name
     * (e.g. {@code MAX_BID}); each value subject to the only-tighten constraint (Req 21.4).
     */
    @JsonProperty("boundary_overrides")
    private Map<String, BigDecimal> boundaryOverrides;
}
