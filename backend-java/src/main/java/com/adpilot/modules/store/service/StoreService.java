package com.adpilot.modules.store.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.store.dto.StoreDto;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.vo.StoreVo;

import java.util.List;

public interface StoreService {

    /**
     * List stores with pagination.
     */
    PageResponse<StoreVo> listStores(int page, int pageSize);

    /**
     * Get store by ID.
     */
    StoreVo getStoreById(String id);

    /**
     * Create a new store.
     */
    StoreVo createStore(StoreDto dto, String userId);

    /**
     * Update an existing store.
     */
    StoreVo updateStore(String id, StoreDto dto, String userId);

    /**
     * Delete a store by ID.
     */
    void deleteStore(String id);

    /**
     * List all marketplaces.
     */
    List<MarketplaceEntity> listMarketplaces();

    /**
     * List the store IDs assigned to (or owned by) a user.
     */
    List<String> getAssignedStoreIds(String userId);

    /**
     * Replace the set of stores assigned to a user (admin action).
     */
    void assignStores(String userId, List<String> storeIds);
}
