package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RecentAuditLogVo {

    private String id;
    private String action;
    private String entityType;
    private String userName;
    private String createdAt;
}
