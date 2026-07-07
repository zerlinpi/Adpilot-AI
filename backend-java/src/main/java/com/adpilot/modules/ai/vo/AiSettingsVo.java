package com.adpilot.modules.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class AiSettingsVo {
    private String id;
    private String provider;
    private String baseUrl;
    /** Masked, e.g. "sk-****abcd"; never the raw key. */
    private String apiKeyMasked;
    private boolean apiKeyConfigured;
    private String model;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Boolean enabled;
    private Map<String, String> extraHeaders;
}
