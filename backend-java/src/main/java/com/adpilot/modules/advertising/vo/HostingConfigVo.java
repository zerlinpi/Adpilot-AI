package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Response view for hosting configuration GET/PUT endpoints (Req 21.1, 21.5, 12.1, 12.2).
 *
 * <p>Returns the persisted configuration for a particular scope (store, goal, or campaign).
 * When no configuration row exists for the scope, the value fields are {@code null}
 * (the frontend then falls back to inherited / system defaults).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostingConfigVo {

    /** Scope type this configuration applies to: store / goal / campaign. */
    @JsonProperty("scope")
    private String scope;

    /** Identifier within the scope (store id, goal id, or campaign id). */
    @JsonProperty("scope_id")
    private String scopeId;

    /** Owning store id. */
    @JsonProperty("store_id")
    private String storeId;

    @JsonProperty("active_phase")
    private String activePhase;

    @JsonProperty("default_personality")
    private String defaultPersonality;

    @JsonProperty("execution_mode")
    private String executionMode;

    @JsonProperty("auto_execute_threshold")
    private BigDecimal autoExecuteThreshold;

    @JsonProperty("emergency_auto_action_enabled")
    private Boolean emergencyAutoActionEnabled;

    @JsonProperty("shadow_mode")
    private Boolean shadowMode;

    @JsonProperty("notification_preferences")
    private Map<String, Object> notificationPreferences;

    @JsonProperty("boundary_overrides")
    private Map<String, BigDecimal> boundaryOverrides;
}
