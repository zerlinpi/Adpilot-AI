package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.advertising.dto.RankMonitorCreateRequest;
import com.adpilot.modules.advertising.service.RankMonitorService;
import com.adpilot.modules.advertising.vo.RankMonitorQuotaVo;
import com.adpilot.modules.advertising.vo.RankMonitorTaskVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Rank Monitoring endpoints (Req 28). Add quota-checked rank-monitor tasks,
 * list a store's monitored keywords with their organic + ad ranks, and report
 * the consumed/total monitoring quota.
 *
 * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
 * error envelope with a 4xx/5xx status — a quota-exhausted add surfaces as a
 * {@code RANK_QUOTA_EXHAUSTED} business error (Req 28.4).
 */
@Slf4j
@RestController
@RequestMapping("/api/rank-monitor")
@RequiredArgsConstructor
public class RankMonitorController {

    private final RankMonitorService rankMonitorService;

    /**
     * GET /api/rank-monitor/tasks - List rank-monitor tasks for the active store
     * with each keyword's latest organic and ad rank (Req 28.2).
     */
    @GetMapping("/tasks")
    @RequirePermission("keyword:view")
    public ApiResponse<List<RankMonitorTaskVo>> listTasks(@RequestParam(required = false) String storeId) {
        return ApiResponse.ok(rankMonitorService.listTasks(storeId));
    }

    /**
     * GET /api/rank-monitor/quota - Report the consumed/total monitoring quota
     * for the active store (Req 28.3).
     */
    @GetMapping("/quota")
    @RequirePermission("keyword:view")
    public ApiResponse<RankMonitorQuotaVo> getQuota(@RequestParam(required = false) String storeId) {
        return ApiResponse.ok(rankMonitorService.getQuota(storeId));
    }

    /**
     * POST /api/rank-monitor/tasks - Add a rank-monitor task for a keyword,
     * rejecting the request when the store's monitoring quota is exhausted
     * (Req 28.1, 28.4).
     */
    @PostMapping("/tasks")
    @RequirePermission("keyword:manage")
    public ApiResponse<RankMonitorTaskVo> createTask(@Valid @RequestBody RankMonitorCreateRequest request) {
        RankMonitorTaskVo task = rankMonitorService.createTask(request);
        log.info("Rank monitor task created: {}", task.getId());
        return ApiResponse.ok(task);
    }
}
