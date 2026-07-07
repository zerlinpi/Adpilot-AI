package com.adpilot.modules.rollback.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RollbackPlanVo {

    private String id;
    private String automationExecutionId;
    private String entityType;
    private String entityId;
    private String rollbackData;
    private String status;
    private String expiresAt;
    private String createdAt;
    private String updatedAt;
}
