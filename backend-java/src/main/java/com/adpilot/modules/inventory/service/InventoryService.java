package com.adpilot.modules.inventory.service;

import com.adpilot.modules.inventory.vo.InventoryHealthVo;
import com.adpilot.modules.inventory.vo.InventoryItemVo;
import com.adpilot.modules.inventory.vo.ReplenishmentPlanVo;
import com.adpilot.modules.inventory.dto.ReplenishmentPlanUpdateRequest;

import java.util.List;

public interface InventoryService {

    InventoryHealthVo getInventoryHealth(String storeId);

    List<InventoryItemVo> getInventorySnapshots(String storeId);

    List<ReplenishmentPlanVo> getReplenishmentPlans(String storeId, String status);

    ReplenishmentPlanVo generateReplenishmentPlan(String storeId, String userId);

    /**
     * Generate replenishment plan rows for every at-risk product in the store
     * (one row per product with a high/medium stockout forecast that does not
     * already have an active plan). Returns the list of plans created.
     */
    List<ReplenishmentPlanVo> generateReplenishmentPlans(String storeId, String userId);

    ReplenishmentPlanVo approvePlan(String id, int approvedQty, String userId);

    ReplenishmentPlanVo updatePlan(String id, ReplenishmentPlanUpdateRequest request, String userId);

    ReplenishmentPlanVo cancelPlan(String id, String userId);
}
