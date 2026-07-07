package com.adpilot.modules.feishu.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.feishu.dto.FeishuChatBindingDto;
import com.adpilot.modules.feishu.dto.FeishuIntegrationDto;
import com.adpilot.modules.feishu.dto.FeishuNotificationRuleDto;
import com.adpilot.modules.feishu.dto.FeishuWebhookConnectDto;
import com.adpilot.modules.feishu.service.FeishuService;
import com.adpilot.modules.feishu.vo.FeishuChatBindingVo;
import com.adpilot.modules.feishu.vo.FeishuIntegrationVo;
import com.adpilot.modules.feishu.vo.FeishuNotificationRuleVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/integrations/feishu")
@RequiredArgsConstructor
public class FeishuController {

    private final FeishuService feishuService;

    /**
     * GET /api/integrations/feishu - List Feishu integrations with pagination.
     */
    @GetMapping
    public ApiResponse<PageResponse<FeishuIntegrationVo>> listIntegrations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<FeishuIntegrationVo> result = feishuService.listIntegrations(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/integrations/feishu/connect-webhook - Quick-connect a custom-bot
     * webhook (one-way push; no app credentials required).
     */
    @PostMapping("/connect-webhook")
    @RequirePermission("feishu:manage")
    public ApiResponse<FeishuIntegrationVo> connectWebhook(@Valid @RequestBody FeishuWebhookConnectDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated()
                ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        return ApiResponse.ok(feishuService.connectWebhook(dto, userId));
    }

    /**
     * POST /api/integrations/feishu/{id}/test-confirm - Send a sample interactive
     * confirm card to exercise the approve/reject + card-callback flow end to end.
     * The {@code storeId} of the integration's resolution is taken from the body
     * (optional); defaults to the integration's store.
     */
    @PostMapping("/{id}/test-confirm")
    @RequirePermission("feishu:manage")
    public ApiResponse<Map<String, Object>> testConfirm(
            @PathVariable String id,
            @RequestParam(required = false) String storeId) {
        UUID storeUuid = storeId != null && !storeId.isBlank() ? UUID.fromString(storeId) : null;
        UUID requestId = feishuService.requestConfirmation(
                storeUuid,
                "test_confirm",
                "AdPilot 确认测试",
                "这是一条来自 AdPilot 的确认卡片测试。请点击下方按钮进行确认或驳回。",
                Map.of("source", "test-confirm", "integrationId", id));
        return ApiResponse.ok(Map.of(
                "dispatched", requestId != null,
                "actionRequestId", requestId != null ? requestId.toString() : ""));
    }

    /**
     * POST /api/integrations/feishu/connect - Connect / create a new Feishu integration.
     */
    @PostMapping("/connect")
    @RequirePermission("feishu:manage")
    public ApiResponse<FeishuIntegrationVo> connect(@Valid @RequestBody FeishuIntegrationDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        FeishuIntegrationVo integration = feishuService.connect(dto, userId);
        return ApiResponse.ok(integration);
    }

    /**
     * PUT /api/integrations/feishu/{id} - Update an existing Feishu integration.
     */
    @PutMapping("/{id}")
    @RequirePermission("feishu:manage")
    public ApiResponse<FeishuIntegrationVo> updateIntegration(
            @PathVariable String id,
            @Valid @RequestBody FeishuIntegrationDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        FeishuIntegrationVo integration = feishuService.updateIntegration(id, dto, userId);
        return ApiResponse.ok(integration);
    }

    /**
     * POST /api/integrations/feishu/{id}/test-message - Send a test message.
     */
    @PostMapping("/{id}/test-message")
    @RequirePermission("feishu:manage")
    public ApiResponse<Void> sendTestMessage(
            @PathVariable String id,
            @RequestParam(required = false) String chatId) {
        feishuService.sendTestMessage(id, chatId);
        return ApiResponse.ok(null);
    }

    /**
     * GET /api/integrations/feishu/{id}/chat-bindings - List chat bindings (Req 10.2).
     */
    @GetMapping("/{id}/chat-bindings")
    @RequirePermission("feishu:manage")
    public ApiResponse<List<FeishuChatBindingVo>> listChatBindings(@PathVariable String id) {
        return ApiResponse.ok(feishuService.listChatBindings(id));
    }

    /**
     * POST /api/integrations/feishu/{id}/chat-bindings - Bind a Feishu group chat (Req 10.2).
     */
    @PostMapping("/{id}/chat-bindings")
    @RequirePermission("feishu:manage")
    public ApiResponse<FeishuChatBindingVo> createChatBinding(
            @PathVariable String id,
            @Valid @RequestBody FeishuChatBindingDto dto) {
        return ApiResponse.ok(feishuService.createChatBinding(id, dto));
    }

    /**
     * GET /api/integrations/feishu/{id}/notification-rules - List notification rules (Req 10.4).
     */
    @GetMapping("/{id}/notification-rules")
    @RequirePermission("feishu:manage")
    public ApiResponse<List<FeishuNotificationRuleVo>> listNotificationRules(@PathVariable String id) {
        return ApiResponse.ok(feishuService.listNotificationRules(id));
    }

    /**
     * POST /api/integrations/feishu/{id}/notification-rules - Save a notification rule (Req 10.4).
     */
    @PostMapping("/{id}/notification-rules")
    @RequirePermission("feishu:manage")
    public ApiResponse<FeishuNotificationRuleVo> createNotificationRule(
            @PathVariable String id,
            @Valid @RequestBody FeishuNotificationRuleDto dto) {
        return ApiResponse.ok(feishuService.createNotificationRule(id, dto));
    }
}
