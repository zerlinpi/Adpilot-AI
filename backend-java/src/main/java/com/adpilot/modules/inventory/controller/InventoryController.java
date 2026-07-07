package com.adpilot.modules.inventory.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.inventory.dto.ReplenishmentPlanUpdateRequest;
import com.adpilot.modules.inventory.service.InventoryService;
import com.adpilot.modules.inventory.vo.InventoryHealthVo;
import com.adpilot.modules.inventory.vo.InventoryItemVo;
import com.adpilot.modules.inventory.vo.ReplenishmentPlanVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/api/inventory/health")
    @RequirePermission("warehouse:view")
    public ApiResponse<InventoryHealthVo> getHealth(@RequestParam String storeId) {
        return ApiResponse.ok(inventoryService.getInventoryHealth(storeId));
    }

    @GetMapping("/api/inventory/snapshots")
    @RequirePermission("warehouse:view")
    public ApiResponse<List<InventoryItemVo>> getSnapshots(@RequestParam String storeId) {
        return ApiResponse.ok(inventoryService.getInventorySnapshots(storeId));
    }

    @GetMapping("/api/replenishment/plans")
    @RequirePermission("warehouse:view")
    public ApiResponse<List<ReplenishmentPlanVo>> getPlans(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(inventoryService.getReplenishmentPlans(storeId, status));
    }

    @PostMapping("/api/replenishment/plans")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ReplenishmentPlanVo> generatePlan(@RequestParam String storeId) {
        return ApiResponse.ok(inventoryService.generateReplenishmentPlan(
                storeId, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PostMapping("/api/replenishment/generate")
    @RequirePermission("warehouse:manage")
    public ApiResponse<List<ReplenishmentPlanVo>> generatePlanFromBody(
            @RequestParam(required = false) String storeId,
            @RequestBody(required = false) Map<String, String> body) {
        String resolvedStoreId = storeId != null ? storeId : (body != null ? body.get("storeId") : null);
        if (resolvedStoreId == null || resolvedStoreId.isBlank()) {
            throw new BusinessException("STORE_REQUIRED", "Store ID is required");
        }
        return ApiResponse.ok(inventoryService.generateReplenishmentPlans(
                resolvedStoreId, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PostMapping("/api/replenishment/plans/{id}/reject")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ReplenishmentPlanVo> rejectPlan(@PathVariable String id) {
        return ApiResponse.ok(inventoryService.cancelPlan(id, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PatchMapping("/api/replenishment/plans/{id}")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ReplenishmentPlanVo> updatePlan(
            @PathVariable String id,
            @RequestBody ReplenishmentPlanUpdateRequest request) {
        return ApiResponse.ok(inventoryService.updatePlan(
                id, request, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PostMapping("/api/replenishment/plans/{id}/approve")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ReplenishmentPlanVo> approvePlan(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int approvedQty) {
        return ApiResponse.ok(inventoryService.approvePlan(
                id, approvedQty, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PostMapping("/api/replenishment/plans/{id}/cancel")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ReplenishmentPlanVo> cancelPlan(@PathVariable String id) {
        return ApiResponse.ok(inventoryService.cancelPlan(id, SecurityUtils.getCurrentUserIdOrNull()));
    }
}
