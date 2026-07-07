package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.AdPlacementLockCreateRequest;
import com.adpilot.modules.advertising.service.AdPlacementLockService;
import com.adpilot.modules.advertising.vo.AdPlacementLockStrategyVo;
import com.adpilot.modules.advertising.vo.AdPlacementLockTaskVo;
import com.adpilot.modules.advertising.vo.PlacementLockAmsVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Ad Placement Lock endpoints (Req 26). Strategies hold an SP campaign in a
 * targeted Amazon ad placement by clamping linked keyword bids into a configured
 * range; the scheduled {@code PlacementLockEvaluator} performs the enforcement.
 *
 * <p>Three surfaces back the page tabs: 策略管理 (strategy management),
 * 任务管理 (task management), and AMS实时数据 (AMS real-time data, stubbed from
 * stored enforcement state).
 *
 * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
 * error envelope with a 4xx/5xx status; an invalid bid range raises a
 * {@code BusinessException} from the service layer (Req 26.4).
 */
@Slf4j
@RestController
@RequestMapping("/api/placement-locks")
@RequiredArgsConstructor
public class AdPlacementLockController {

    private final AdPlacementLockService adPlacementLockService;

    /** GET /api/placement-locks - List placement-lock strategies (Req 26.1). */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<List<AdPlacementLockStrategyVo>> listStrategies(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(adPlacementLockService.listStrategies(storeId));
    }

    /** POST /api/placement-locks - Create a placement-lock strategy (Req 26.2, 26.4). */
    @PostMapping
    @RequirePermission("advertising:manage")
    public ApiResponse<AdPlacementLockStrategyVo> createStrategy(
            @Valid @RequestBody AdPlacementLockCreateRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        AdPlacementLockStrategyVo strategy = adPlacementLockService.createStrategy(request, userId);
        log.info("Ad placement lock strategy created: {}", strategy.getId());
        return ApiResponse.ok(strategy);
    }

    /** GET /api/placement-locks/tasks - List per-keyword enforcement tasks (Req 26.2). */
    @GetMapping("/tasks")
    @RequirePermission("advertising:view")
    public ApiResponse<List<AdPlacementLockTaskVo>> listTasks(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(adPlacementLockService.listTasks(storeId));
    }

    /** GET /api/placement-locks/ams - AMS real-time data, stubbed from stored data (Req 26.1). */
    @GetMapping("/ams")
    @RequirePermission("advertising:view")
    public ApiResponse<List<PlacementLockAmsVo>> listAmsData(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(adPlacementLockService.listAmsData(storeId));
    }
}
