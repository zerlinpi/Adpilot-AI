package com.adpilot.modules.rollback.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.rollback.service.RollbackService;
import com.adpilot.modules.rollback.vo.RollbackPlanVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/rollback-plans")
@RequiredArgsConstructor
public class RollbackController {

    private final RollbackService rollbackService;

    /**
     * GET /api/rollback-plans - List rollback plans.
     */
    @GetMapping
    public ApiResponse<PageResponse<RollbackPlanVo>> listRollbackPlans(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<RollbackPlanVo> result = rollbackService.listRollbackPlans(storeId, status, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/rollback-plans/{id} - Get rollback plan by ID.
     */
    @GetMapping("/{id}")
    public ApiResponse<RollbackPlanVo> getRollbackPlan(@PathVariable String id) {
        RollbackPlanVo plan = rollbackService.getRollbackPlan(id);
        return ApiResponse.ok(plan);
    }

    /**
     * POST /api/rollback-plans/{id}/execute - Execute a rollback plan.
     */
    @PostMapping("/{id}/execute")
    @RequirePermission("advertising:execute")
    public ApiResponse<RollbackPlanVo> executeRollback(@PathVariable String id) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        RollbackPlanVo plan = rollbackService.executeRollback(id, userId);
        return ApiResponse.ok(plan);
    }
}
