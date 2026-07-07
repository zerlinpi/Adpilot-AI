package com.adpilot.modules.automation.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.automation.dto.AutomationPolicyDto;
import com.adpilot.modules.automation.dto.RiskEvaluateDto;
import com.adpilot.modules.automation.vo.AutomationExecutionVo;
import com.adpilot.modules.automation.vo.AutomationPolicyVo;
import com.adpilot.modules.automation.vo.RiskEvaluationVo;

public interface AutomationService {

    /**
     * Get the automation policy for a store.
     */
    AutomationPolicyVo getAutomationPolicy(String storeId);

    /**
     * Create or update an automation policy.
     */
    AutomationPolicyVo createOrUpdatePolicy(AutomationPolicyDto dto);

    /**
     * Evaluate the risk of a proposed action.
     */
    RiskEvaluationVo evaluateAction(RiskEvaluateDto dto);

    /**
     * List automation executions with optional filters and pagination.
     */
    PageResponse<AutomationExecutionVo> listExecutions(String storeId, String status, int page, int pageSize);

    /**
     * Get a single automation execution by ID.
     */
    AutomationExecutionVo getExecution(String id);

    /**
     * Rollback an automation execution.
     */
    AutomationExecutionVo rollbackExecution(String id, String userId);
}
