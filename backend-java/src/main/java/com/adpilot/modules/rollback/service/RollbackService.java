package com.adpilot.modules.rollback.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.rollback.vo.RollbackPlanVo;

import java.util.List;

public interface RollbackService {

    /**
     * List rollback plans with optional filters.
     */
    PageResponse<RollbackPlanVo> listRollbackPlans(String storeId, String status, int page, int pageSize);

    /**
     * Get a single rollback plan by ID.
     */
    RollbackPlanVo getRollbackPlan(String id);

    /**
     * Execute a rollback plan.
     */
    RollbackPlanVo executeRollback(String id, String userId);

    /**
     * Expire old rollback plans that have passed their expiration time.
     */
    void expireOldPlans();
}
