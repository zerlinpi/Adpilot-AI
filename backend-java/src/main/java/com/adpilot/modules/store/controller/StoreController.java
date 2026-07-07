package com.adpilot.modules.store.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.store.dto.StoreDto;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.service.StoreService;
import com.adpilot.modules.store.vo.StoreVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    /**
     * GET /api/stores - List stores with pagination.
     */
    @GetMapping("/stores")
    @RequirePermission("store:view")
    public ApiResponse<PageResponse<StoreVo>> listStores(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<StoreVo> result = storeService.listStores(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/stores/{id} - Get store by ID.
     */
    @GetMapping("/stores/{id}")
    @RequirePermission("store:view")
    public ApiResponse<StoreVo> getStore(@PathVariable String id) {
        StoreVo store = storeService.getStoreById(id);
        return ApiResponse.ok(store);
    }

    /**
     * POST /api/stores - Create a new store.
     */
    @PostMapping("/stores")
    @RequirePermission("store:manage")
    public ApiResponse<StoreVo> createStore(@Valid @RequestBody StoreDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        StoreVo store = storeService.createStore(dto, userId);
        return ApiResponse.ok(store);
    }

    /**
     * PUT /api/stores/{id} - Update an existing store.
     */
    @PutMapping("/stores/{id}")
    @RequirePermission("store:manage")
    public ApiResponse<StoreVo> updateStore(@PathVariable String id, @Valid @RequestBody StoreDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        StoreVo store = storeService.updateStore(id, dto, userId);
        return ApiResponse.ok(store);
    }

    /**
     * DELETE /api/stores/{id} - Delete a store.
     */
    @DeleteMapping("/stores/{id}")
    @RequirePermission("store:manage")
    public ApiResponse<Void> deleteStore(@PathVariable String id) {
        storeService.deleteStore(id);
        return ApiResponse.ok(null);
    }

    /**
     * GET /api/marketplaces - List all marketplaces.
     */
    @GetMapping("/marketplaces")
    @RequirePermission("store:view")
    public ApiResponse<List<MarketplaceEntity>> listMarketplaces() {
        List<MarketplaceEntity> marketplaces = storeService.listMarketplaces();
        return ApiResponse.ok(marketplaces);
    }

    /**
     * GET /api/users/{userId}/stores - List store IDs assigned to a user.
     */
    @GetMapping("/users/{userId}/stores")
    @RequirePermission("user:view")
    public ApiResponse<List<String>> getUserStores(@PathVariable String userId) {
        return ApiResponse.ok(storeService.getAssignedStoreIds(userId));
    }

    /**
     * PUT /api/users/{userId}/stores - Assign a set of stores to a user (admin action).
     */
    @PutMapping("/users/{userId}/stores")
    @RequirePermission("user:manage")
    public ApiResponse<Void> assignUserStores(
            @PathVariable String userId,
            @RequestBody java.util.Map<String, List<String>> body) {
        storeService.assignStores(userId, body != null ? body.get("storeIds") : null);
        return ApiResponse.ok(null);
    }
}
