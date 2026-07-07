package com.adpilot.modules.feishu.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.feishu.dto.FeishuChatBindingDto;
import com.adpilot.modules.feishu.dto.FeishuIntegrationDto;
import com.adpilot.modules.feishu.dto.FeishuNotificationRuleDto;
import com.adpilot.modules.feishu.dto.FeishuWebhookConnectDto;
import com.adpilot.modules.feishu.vo.FeishuChatBindingVo;
import com.adpilot.modules.feishu.vo.FeishuIntegrationVo;
import com.adpilot.modules.feishu.vo.FeishuNotificationRuleVo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface FeishuService {

    /**
     * List Feishu integrations with pagination.
     */
    PageResponse<FeishuIntegrationVo> listIntegrations(int page, int pageSize);

    /**
     * Connect / create a new Feishu integration.
     */
    FeishuIntegrationVo connect(FeishuIntegrationDto dto, String userId);

    /**
     * Quick-connect a Feishu custom-bot webhook integration. Creates a
     * {@code feishu_integrations} row with {@code connection_type = "webhook"},
     * encrypting the webhook URL and (optional) signing secret at rest.
     */
    FeishuIntegrationVo connectWebhook(FeishuWebhookConnectDto dto, String userId);

    /**
     * Update an existing Feishu integration.
     */
    FeishuIntegrationVo updateIntegration(String id, FeishuIntegrationDto dto, String userId);

    /**
     * Send a test message through a Feishu integration.
     */
    void sendTestMessage(String id, String chatId);

    /**
     * Push an alert notification to the Feishu destination configured for the given
     * store (Req 10.1.6). Used by the alert engine.
     *
     * @return {@code true} if a configured destination was found and the message was
     *         dispatched; {@code false} if no Feishu push is configured for the store
     *         (in which case nothing is sent and no error is raised)
     * @throws com.adpilot.common.exception.BusinessException if a destination is
     *         configured but the push fails, so callers can record the failure
     *         (Req 10.1.9)
     */
    boolean pushAlert(java.util.UUID storeId, String title, String message);

    /**
     * Push an AI Notification message to the store's bound Feishu chat(s)
     * (item 19). Resolves the store's active {@code feishu_chat_bindings} first
     * and falls back to an active integration's default chat. Reuses the same
     * Feishu sending path as {@link #pushAlert}; if no Feishu chat is bound for
     * the store this is a graceful no-op.
     *
     * @return {@code true} if at least one message was dispatched; {@code false}
     *         when no Feishu destination is configured for the store
     */
    boolean pushAiNotification(java.util.UUID storeId, String title, String message);

    /**
     * List the chat bindings for a Feishu integration (Req 10.2).
     */
    List<FeishuChatBindingVo> listChatBindings(String integrationId);

    /**
     * Create a chat binding for a Feishu integration and return the saved binding (Req 10.2).
     */
    FeishuChatBindingVo createChatBinding(String integrationId, FeishuChatBindingDto dto);

    /**
     * List the notification rules for a Feishu integration (Req 10.4).
     */
    List<FeishuNotificationRuleVo> listNotificationRules(String integrationId);

    /**
     * Create a notification rule for a Feishu integration and return the saved rule (Req 10.4).
     */
    FeishuNotificationRuleVo createNotificationRule(String integrationId, FeishuNotificationRuleDto dto);

    /**
     * Request an interactive confirmation in the store's bound Feishu chat.
     * Resolves the store's chat (same resolution as {@link #pushAiNotification}),
     * inserts a pending {@code feishu_action_requests} row, and sends an
     * interactive confirm card carrying approve/reject buttons. Returns the id of
     * the created action request, or {@code null} when no Feishu destination is
     * configured for the store.
     *
     * @param storeId    the store whose chat receives the confirm card
     * @param actionType a short action-type identifier recorded on the request
     * @param title      card header title
     * @param body       card markdown body
     * @param actionData arbitrary structured payload stored on the request
     * @return the created action request id, or {@code null} if not dispatched
     */
    UUID requestConfirmation(UUID storeId, String actionType, String title, String body,
                             Map<String, Object> actionData);
}
