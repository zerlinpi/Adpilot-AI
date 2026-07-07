package com.adpilot.modules.ai.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
public class AiSettingsRequest {
    private String provider;
    private String baseUrl;
    /**
     * API key. When null/blank the existing stored key is preserved (so the UI
     * can submit without re-typing the secret); a sentinel of "" clears it.
     */
    private String apiKey;
    private String model;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Boolean enabled;
    private Map<String, String> extraHeaders;
}
