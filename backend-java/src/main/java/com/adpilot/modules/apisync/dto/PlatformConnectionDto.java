package com.adpilot.modules.apisync.dto;

import lombok.Data;

import java.util.Map;

@Data
public class PlatformConnectionDto {

    /** Optional: store this connection binds to. Defaults to the default store when absent. */
    private String storeId;

    private String platform;

    private String connectionName;

    /** Structured credential fields (e.g. clientId, accessToken). Encrypted at rest. */
    private Map<String, String> config;

    /** Legacy raw blob support (kept for backward compatibility). */
    private String configEncrypted;
}
