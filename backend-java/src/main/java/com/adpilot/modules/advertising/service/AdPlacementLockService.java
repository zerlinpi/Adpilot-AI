package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.dto.AdPlacementLockCreateRequest;
import com.adpilot.modules.advertising.vo.AdPlacementLockStrategyVo;
import com.adpilot.modules.advertising.vo.AdPlacementLockTaskVo;
import com.adpilot.modules.advertising.vo.PlacementLockAmsVo;

import java.util.List;

/**
 * Ad Placement Lock management (Req 26): list/create placement-lock strategies,
 * list enforcement tasks, and surface stubbed AMS real-time data, all scoped to
 * the active store.
 */
public interface AdPlacementLockService {

    /** List placement-lock strategies for a store (Req 26.1). */
    List<AdPlacementLockStrategyVo> listStrategies(String storeId);

    /**
     * Create a placement-lock strategy (Req 26.2). Validates {@code bidMin <= bidMax}
     * and rejects with a {@code BusinessException} when the range is invalid (Req 26.4).
     */
    AdPlacementLockStrategyVo createStrategy(AdPlacementLockCreateRequest request, String userId);

    /** List per-keyword enforcement tasks for a store's strategies (Req 26.2). */
    List<AdPlacementLockTaskVo> listTasks(String storeId);

    /** Surface AMS real-time data, stubbed from stored enforcement state (Req 26.1). */
    List<PlacementLockAmsVo> listAmsData(String storeId);
}
