package com.adpilot.modules.audit.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuditLogVo {

    private String id;
    private String userId;
    private String orgId;
    private String action;
    /** Human-readable label for {@link #action} (Requirement 11.1). Always non-empty. */
    private String actionLabel;
    private String entityType;
    /** Human-readable label for {@link #entityType} (Requirement 11.2). Always non-empty. */
    private String entityTypeLabel;
    private String entityId;
    private String oldData;
    private String newData;
    private String ipAddress;
    private String source;
    private String createdAt;
}
