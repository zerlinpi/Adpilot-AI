package com.adpilot.modules.automation.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RiskEvaluationVo {

    private String id;
    private String storeId;
    private String actionType;
    private String entityType;
    private String entityId;
    private String riskLevel;
    private String reasons;
    private Boolean blocked;
    private Boolean requiresApproval;
    private String createdAt;
}
