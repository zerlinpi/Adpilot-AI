package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * View model for org-level AI hosting canary rollout controls.
 */
@Data
@Builder
public class CanaryRolloutVo {

    @JsonProperty("org_id")
    private String orgId;

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("store_ids")
    private Set<String> storeIds;
}
