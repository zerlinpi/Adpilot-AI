package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.advertising.dto.AiNotificationConfigRequest;
import com.adpilot.modules.advertising.service.AiNotificationService;
import com.adpilot.modules.advertising.vo.AiNotificationConfigVo;
import com.adpilot.modules.advertising.vo.AiNotificationOverviewVo;
import com.adpilot.modules.advertising.vo.AiNotificationVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * AI Notifications work-item endpoints (Req 23). Surfaces the four notification
 * categories with their pending/closed lists, the apply/confirm/reject actions
 * that close an item, and the per-store notification configuration.
 *
 * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
 * error envelope with a 4xx/5xx status.
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-notifications")
@RequiredArgsConstructor
public class AiNotificationController {

    private final AiNotificationService aiNotificationService;

    /**
     * GET /api/ai-notifications - The four categories with pending/closed counts
     * and pending/closed item lists for the active store (Req 23.1, 23.2, 23.6).
     */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<AiNotificationOverviewVo> getOverview(@RequestParam(required = false) String storeId) {
        return ApiResponse.ok(aiNotificationService.getOverview(storeId));
    }

    /**
     * POST /api/ai-notifications/{id}/apply - Apply a one-click optimization on a
     * pending item, closing it and returning the result (Req 23.3).
     */
    @PostMapping("/{id}/apply")
    @RequirePermission("advertising:execute")
    public ApiResponse<AiNotificationVo> apply(@PathVariable String id) {
        return ApiResponse.ok(aiNotificationService.apply(id));
    }

    /**
     * POST /api/ai-notifications/{id}/confirm - Confirm an AI target correction,
     * applying the proposed change and closing the item (Req 23.4).
     */
    @PostMapping("/{id}/confirm")
    @RequirePermission("advertising:execute")
    public ApiResponse<AiNotificationVo> confirm(@PathVariable String id) {
        return ApiResponse.ok(aiNotificationService.confirm(id));
    }

    /**
     * POST /api/ai-notifications/{id}/reject - Reject an AI target correction,
     * discarding the proposed change and closing the item (Req 23.4).
     */
    @PostMapping("/{id}/reject")
    @RequirePermission("advertising:manage")
    public ApiResponse<AiNotificationVo> reject(@PathVariable String id) {
        return ApiResponse.ok(aiNotificationService.reject(id));
    }

    /**
     * POST /api/ai-notifications/{id}/push-feishu - Push the notification to the
     * store's bound Feishu chat(s) via the existing Feishu integration (item 19).
     */
    @PostMapping("/{id}/push-feishu")
    @RequirePermission("advertising:manage")
    public ApiResponse<AiNotificationVo> pushToFeishu(@PathVariable String id) {
        return ApiResponse.ok(aiNotificationService.pushToFeishu(id));
    }

    /**
     * GET /api/ai-notifications/config - Per-store configuration controlling
     * which core-ops items are raised (Req 23.5).
     */
    @GetMapping("/config")
    @RequirePermission("advertising:view")
    public ApiResponse<AiNotificationConfigVo> getConfig(@RequestParam(required = false) String storeId) {
        return ApiResponse.ok(aiNotificationService.getConfig(storeId));
    }

    /**
     * PUT /api/ai-notifications/config - Upsert the per-store notification
     * configuration (Req 23.5).
     */
    @PutMapping("/config")
    @RequirePermission("advertising:manage")
    public ApiResponse<AiNotificationConfigVo> updateConfig(@Valid @RequestBody AiNotificationConfigRequest request) {
        return ApiResponse.ok(aiNotificationService.updateConfig(request));
    }
}
