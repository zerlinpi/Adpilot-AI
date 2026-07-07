package com.adpilot.modules.automation.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.advertising.service.AiHostingOptimizer;
import com.adpilot.modules.automation.dto.AutomationPolicyDto;
import com.adpilot.modules.automation.dto.RiskEvaluateDto;
import com.adpilot.modules.automation.service.AutomationService;
import com.adpilot.modules.automation.vo.AutomationExecutionVo;
import com.adpilot.modules.automation.vo.AutomationPolicyVo;
import com.adpilot.modules.automation.vo.RiskEvaluationVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/automation")
@RequiredArgsConstructor
public class AutomationController {

    private final AutomationService automationService;
    private final AiHostingOptimizer aiHostingOptimizer;

    /**
     * GET /api/automation/policies - Get automation policy for a store.
     */
    @GetMapping("/policies")
    @RequirePermission("automation:view")
    public ApiResponse<AutomationPolicyVo> getAutomationPolicy(
            @RequestParam(required = false) String storeId) {
        AutomationPolicyVo policy = automationService.getAutomationPolicy(storeId);
        return ApiResponse.ok(policy);
    }

    /**
     * POST /api/automation/policies - Create a new automation policy.
     */
    @PostMapping("/policies")
    @RequirePermission("automation:manage")
    public ApiResponse<AutomationPolicyVo> createPolicy(@Valid @RequestBody AutomationPolicyDto dto) {
        AutomationPolicyVo policy = automationService.createOrUpdatePolicy(dto);
        return ApiResponse.ok(policy);
    }

    /**
     * PUT /api/automation/policies/{id} - Update an existing automation policy.
     */
    @PutMapping("/policies/{id}")
    @RequirePermission("automation:manage")
    public ApiResponse<AutomationPolicyVo> updatePolicy(
            @PathVariable String id,
            @Valid @RequestBody AutomationPolicyDto dto) {
        // The service handles create-or-update logic based on org/store
        AutomationPolicyVo policy = automationService.createOrUpdatePolicy(dto);
        return ApiResponse.ok(policy);
    }

    /**
     * POST /api/automation/evaluate - Evaluate the risk of a proposed action.
     */
    @PostMapping("/evaluate")
    @RequirePermission("automation:view")
    public ApiResponse<RiskEvaluationVo> evaluateAction(@Valid @RequestBody RiskEvaluateDto dto) {
        RiskEvaluationVo evaluation = automationService.evaluateAction(dto);
        return ApiResponse.ok(evaluation);
    }

    /**
     * POST /api/automation/hosting/run - Run one AI hosting optimization tick on
     * demand (Req 21.2). The scheduled loop fires every few minutes; this lets an
     * operator trigger it immediately to observe its effect. It loads hosted
     * campaigns, moves keyword bids toward Target_ACoS within policy bounds, and
     * writes the resulting bid_changes / automation_executions rows. The returned
     * summary reports how many campaigns were processed and how many bids changed
     * (0/0 means there is no hosted-campaign + recent performance_daily data to
     * act on — the optimizer can only act when such data exists).
     */
    @PostMapping("/hosting/run")
    @RequirePermission("automation:manage")
    public ApiResponse<AiHostingOptimizer.OptimizationSummary> runHostingOptimizer() {
        AiHostingOptimizer.OptimizationSummary summary = aiHostingOptimizer.runOnce();
        log.info("AI hosting optimizer manual run: {} campaigns, {} bids changed, {} failed",
                summary.getCampaignsProcessed(), summary.getBidsChanged(), summary.getCampaignsFailed());
        return ApiResponse.ok(summary);
    }

    /**
     * GET /api/automation/executions - List automation executions.
     */
    @GetMapping("/executions")
    @RequirePermission("automation:view")
    public ApiResponse<PageResponse<AutomationExecutionVo>> listExecutions(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<AutomationExecutionVo> result = automationService.listExecutions(storeId, status, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/automation/executions/{id} - Get execution by ID.
     */
    @GetMapping("/executions/{id}")
    @RequirePermission("automation:view")
    public ApiResponse<AutomationExecutionVo> getExecution(@PathVariable String id) {
        AutomationExecutionVo execution = automationService.getExecution(id);
        return ApiResponse.ok(execution);
    }

    /**
     * POST /api/automation/executions/{id}/rollback - Rollback an execution.
     */
    @PostMapping("/executions/{id}/rollback")
    @RequirePermission("automation:manage")
    public ApiResponse<AutomationExecutionVo> rollbackExecution(@PathVariable String id) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        AutomationExecutionVo execution = automationService.rollbackExecution(id, userId);
        return ApiResponse.ok(execution);
    }
}
