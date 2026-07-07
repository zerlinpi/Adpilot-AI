package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlatformConnectionVo {

    private String id;
    private String storeId;
    private String platform;
    /** Alias of {@code platform} expected by the frontend. */
    private String platformId;
    private String connectionName;
    private String status;
    private String lastSyncAt;
    /** Alias of {@code lastSyncAt} expected by the frontend. */
    private String lastSyncTime;
    private String createdAt;
    private String updatedAt;

    /** Whether credentials are stored for this connection. */
    private Boolean hasConfig;
    /** Masked credential values (secrets shown as ****1234) for display in the form. */
    private java.util.Map<String, String> configMasked;
    /** Human-readable result message from connect/test actions. */
    private String message;
}
