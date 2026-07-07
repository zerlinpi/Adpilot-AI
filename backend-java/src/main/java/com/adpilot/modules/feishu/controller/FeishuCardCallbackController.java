package com.adpilot.modules.feishu.controller;

import com.adpilot.common.security.AuditService;
import com.adpilot.modules.approval.service.ApprovalService;
import com.adpilot.modules.feishu.entity.FeishuActionRequestEntity;
import com.adpilot.modules.feishu.entity.FeishuUserBindingEntity;
import com.adpilot.modules.feishu.mapper.FeishuActionRequestMapper;
import com.adpilot.modules.feishu.mapper.FeishuUserBindingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Receives Feishu interactive-card button callbacks ({@code card.action.trigger}).
 *
 * <p>Public endpoint (permitted in SecurityConfig). When an operator taps the
 * 批准 / 驳回 button on a confirm card, Feishu posts the button's {@code value}
 * here. We record the decision on the {@code feishu_action_requests} row and
 * write an audit log, then return a card toast.</p>
 *
 * <p>NOTE: this records the human decision on the {@code feishu_action_requests}
 * row and writes an audit log. When the request is linked to an AdPilot approval
 * ({@code approval_request_id} is set), it also transitions that approval via
 * {@link com.adpilot.modules.approval.service.ApprovalService} (approve / reject),
 * resolving the acting user from {@code feishu_user_bindings} (open id → user id).
 * If no binding exists, the approval transition is skipped while the decision and
 * audit are still recorded, so we never act as an unknown user. Approval failures
 * are recorded on the row but never break the 200 callback response.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/integrations/feishu")
@RequiredArgsConstructor
public class FeishuCardCallbackController {

    private final FeishuActionRequestMapper feishuActionRequestMapper;
    private final FeishuUserBindingMapper feishuUserBindingMapper;
    private final ApprovalService approvalService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @PostMapping("/card-callback")
    public Map<String, Object> cardCallback(@RequestBody(required = false) String rawBody) {
        try {
            JsonNode root = parse(rawBody);

            // URL verification handshake (card callback URL setup).
            if (root.hasNonNull("challenge") || "url_verification".equals(text(root, "type"))) {
                return Map.of("challenge", text(root, "challenge"));
            }

            // The action value can live at action.value (legacy) or
            // event.action.value (event-style card.action.trigger).
            JsonNode action = root.path("action");
            if (action.isMissingNode() || action.isNull()) {
                action = root.path("event").path("action");
            }
            JsonNode value = action.path("value");
            String actionRequestId = value.path("action_request_id").asText(null);
            String decision = value.path("decision").asText(null);

            String operator = firstNonBlank(
                    text(root.path("event").path("operator"), "open_id"),
                    text(root, "open_id"),
                    text(root, "user_id"));

            if (actionRequestId != null && decision != null) {
                recordDecision(actionRequestId, decision, operator);
                String label = "approve".equals(decision) ? "批准" : "驳回";
                return toast("success", "已记录: " + label);
            }
        } catch (Exception e) {
            log.warn("Feishu card callback handling failed: {}", e.getMessage());
        }
        return toast("info", "已收到");
    }

    private void recordDecision(String actionRequestId, String decision, String operator) {
        UUID id;
        try {
            id = UUID.fromString(actionRequestId);
        } catch (IllegalArgumentException e) {
            return;
        }
        FeishuActionRequestEntity request = feishuActionRequestMapper.selectById(id);
        if (request == null) {
            log.warn("Feishu card callback for unknown action request {}", actionRequestId);
            return;
        }
        String status = "approve".equals(decision) ? "approved" : "rejected";
        request.setStatus(status);
        request.setFeishuUserId(operator);
        request.setUpdatedAt(LocalDateTime.now());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decision", decision);
        result.put("operator", operator);
        result.put("decidedAt", LocalDateTime.now().toString());
        try {
            request.setResult(objectMapper.writeValueAsString(result));
        } catch (Exception ignore) {
            // keep prior result
        }
        feishuActionRequestMapper.updateById(request);

        // Audit the decision (permit-style record keyed by the action request).
        auditService.recordAuthorizationDecision(
                "FEISHU_CONFIRM_" + decision.toUpperCase() + ":" + actionRequestId,
                "approve".equals(decision));
        log.info("Feishu confirm decision recorded: request={}, decision={}, operator={}",
                actionRequestId, decision, operator);

        // Execute the underlying business action: when the confirm is linked to an
        // AdPilot approval request, transition the real approval. Best-effort —
        // failures are recorded but never break the 200 callback response.
        if (request.getApprovalRequestId() != null) {
            executeLinkedApproval(request, decision, operator);
        }
    }

    /**
     * Transition the linked approval request based on the Feishu decision. The
     * acting AdPilot user is resolved from {@code feishu_user_bindings} (open id ->
     * user id). When no binding exists, the approval transition is skipped (the
     * decision + audit are still recorded) so we never act as an unknown user.
     */
    private void executeLinkedApproval(FeishuActionRequestEntity request, String decision, String operator) {
        String approvalId = request.getApprovalRequestId().toString();
        UUID actingUserId = resolveUser(request.getFeishuIntegrationId(), operator);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (actingUserId == null) {
                result.put("approvalLinked", true);
                result.put("approvalApplied", false);
                result.put("reason", "no_feishu_user_binding");
                request.setResult(safeJson(result));
                feishuActionRequestMapper.updateById(request);
                log.warn("Feishu confirm: operator {} not bound to an AdPilot user; "
                        + "approval {} not transitioned", operator, approvalId);
                return;
            }
            if ("approve".equals(decision)) {
                approvalService.approveRequest(approvalId, actingUserId.toString());
            } else {
                approvalService.rejectRequest(approvalId, actingUserId.toString(), "Rejected via Feishu");
            }
            result.put("approvalLinked", true);
            result.put("approvalApplied", true);
            request.setResult(safeJson(result));
            feishuActionRequestMapper.updateById(request);
            log.info("Feishu confirm applied to approval {} ({}) by user {}", approvalId, decision, actingUserId);
        } catch (Exception e) {
            result.put("approvalLinked", true);
            result.put("approvalApplied", false);
            result.put("error", e.getMessage());
            request.setResult(safeJson(result));
            try {
                feishuActionRequestMapper.updateById(request);
            } catch (Exception ignore) {
                // ignore secondary failure
            }
            log.warn("Feishu confirm failed to transition approval {}: {}", approvalId, e.getMessage());
        }
    }

    /** Resolve an AdPilot user id from a Feishu operator open id within an integration. */
    private UUID resolveUser(UUID integrationId, String operatorOpenId) {
        if (operatorOpenId == null || operatorOpenId.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<FeishuUserBindingEntity> w = new LambdaQueryWrapper<>();
        w.eq(FeishuUserBindingEntity::getFeishuIntegrationId, integrationId)
                .and(q -> q.eq(FeishuUserBindingEntity::getFeishuOpenId, operatorOpenId)
                        .or()
                        .eq(FeishuUserBindingEntity::getFeishuUserId, operatorOpenId))
                .eq(FeishuUserBindingEntity::getStatus, "active")
                .last("LIMIT 1");
        FeishuUserBindingEntity binding = feishuUserBindingMapper.selectOne(w);
        return binding != null ? binding.getUserId() : null;
    }

    private String safeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> toast(String type, String content) {
        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("type", type);
        toast.put("content", content);
        return Map.of("toast", toast);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
