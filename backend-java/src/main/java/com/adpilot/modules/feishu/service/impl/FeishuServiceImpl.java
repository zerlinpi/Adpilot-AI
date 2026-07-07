package com.adpilot.modules.feishu.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.resilience.CircuitBreaker;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.feishu.client.FeishuApiClient;
import com.adpilot.modules.feishu.dto.FeishuChatBindingDto;
import com.adpilot.modules.feishu.dto.FeishuIntegrationDto;
import com.adpilot.modules.feishu.dto.FeishuNotificationRuleDto;
import com.adpilot.modules.feishu.dto.FeishuWebhookConnectDto;
import com.adpilot.modules.feishu.entity.FeishuActionRequestEntity;
import com.adpilot.modules.feishu.entity.FeishuChatBindingEntity;
import com.adpilot.modules.feishu.entity.FeishuIntegrationEntity;
import com.adpilot.modules.feishu.entity.FeishuMessageLogEntity;
import com.adpilot.modules.feishu.entity.FeishuNotificationRuleEntity;
import com.adpilot.modules.feishu.mapper.FeishuActionRequestMapper;
import com.adpilot.modules.feishu.mapper.FeishuChatBindingMapper;
import com.adpilot.modules.feishu.mapper.FeishuIntegrationMapper;
import com.adpilot.modules.feishu.mapper.FeishuMessageLogMapper;
import com.adpilot.modules.feishu.mapper.FeishuNotificationRuleMapper;
import com.adpilot.modules.feishu.service.FeishuService;
import com.adpilot.modules.feishu.support.FeishuWebhookValidator;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.feishu.vo.FeishuChatBindingVo;
import com.adpilot.modules.feishu.vo.FeishuIntegrationVo;
import com.adpilot.modules.feishu.vo.FeishuNotificationRuleVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuServiceImpl implements FeishuService {

    private final FeishuIntegrationMapper feishuIntegrationMapper;
    private final FeishuMessageLogMapper feishuMessageLogMapper;
    private final FeishuChatBindingMapper feishuChatBindingMapper;
    private final FeishuNotificationRuleMapper feishuNotificationRuleMapper;
    private final FeishuActionRequestMapper feishuActionRequestMapper;
    private final FeishuApiClient feishuApiClient;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;
    private final StoreMapper storeMapper;
    private final DataScopeService dataScopeService;
    private final FeishuWebhookValidator feishuWebhookValidator;
    private final CircuitBreaker circuitBreaker;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PageResponse<FeishuIntegrationVo> listIntegrations(int page, int pageSize) {
        // Data isolation: only show integrations belonging to the current user's org.
        UUID currentOrgId = resolveCurrentOrgId();
        if (currentOrgId == null) {
            return PageResponse.of(List.of(), 0L, page, pageSize);
        }

        Page<FeishuIntegrationEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<FeishuIntegrationEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("org_id", currentOrgId.toString());
        // Store_Group_Scope isolation (Req 7.6): restrict store-bound integrations to the
        // account's data scope. Super-admin / all-company scopes add no restriction; scoped
        // accounts only see integrations whose store falls within their Store_Group_Scope.
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, ScopeTarget.store("store_id"), user);
        }
        wrapper.orderByDesc("created_at");

        Page<FeishuIntegrationEntity> result = feishuIntegrationMapper.selectPage(pageParam, wrapper);
        List<FeishuIntegrationVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    /**
     * Enforce the account's Store_Group_Scope on a single store-bound integration
     * (Req 7.6). Only integrations tied to a store are scoped; org-wide integrations
     * (null {@code store_id}) are governed by the org check alone since they are not
     * "a store's" integration. A {@code null} user (system/background context) is not
     * scoped here. {@code write} selects the write guard, otherwise the read guard;
     * both reach the identical allow/deny outcome in {@link DataScopeService}.
     */
    private void assertIntegrationInScope(FeishuIntegrationEntity entity, boolean write) {
        if (entity == null || entity.getStoreId() == null) {
            return;
        }
        CurrentUser user = scopeUser();
        if (user == null) {
            return;
        }
        if (write) {
            dataScopeService.assertCanWrite(entity, user);
        } else {
            dataScopeService.assertCanRead(entity, user);
        }
    }

    @Override
    @Transactional
    public FeishuIntegrationVo connect(FeishuIntegrationDto dto, String userId) {
        // Resolve orgId: prefer DTO, fall back to security context — never trust front-end blindly.
        UUID orgId = parseOptionalUuid(dto.getOrgId());
        if (orgId == null) {
            orgId = resolveCurrentOrgId();
        }
        if (orgId == null) {
            throw new BusinessException("ORG_REQUIRED", "无法确定组织 ID，请重新登录");
        }
        UUID storeId = parseOptionalUuid(dto.getStoreId());

        // Prefer the plain-text appSecret field; fall back to the legacy appSecretEncrypted field.
        String plainSecret = dto.getAppSecret() != null && !dto.getAppSecret().isBlank()
                ? dto.getAppSecret()
                : dto.getAppSecretEncrypted();

        String connectionType = dto.getConnectionType() != null && !dto.getConnectionType().isBlank()
                ? dto.getConnectionType() : "app";

        // App type REQUIRES both appId and appSecret.
        if ("app".equals(connectionType)) {
            if (dto.getAppId() == null || dto.getAppId().isBlank()
                    || plainSecret == null || plainSecret.isBlank()) {
                throw new BusinessException("FEISHU_CREDENTIALS_REQUIRED",
                        "App 类型必须提供 App ID 和 App Secret");
            }
            // Validate credentials by requesting a tenant_access_token BEFORE persisting (bypass cache).
            try {
                feishuApiClient.tenantAccessTokenNoCache(dto.getAppId(), plainSecret);
            } catch (RuntimeException e) {
                throw new BusinessException("FEISHU_AUTH_FAILED",
                        "飞书应用凭证验证失败: " + e.getMessage());
            }
        }

        FeishuIntegrationEntity entity = FeishuIntegrationEntity.builder()
                .orgId(orgId)
                .storeId(storeId)
                .ownerAccountId(parseOwnerAccountId(userId))
                .provider(dto.getProvider() != null ? dto.getProvider() : "feishu")
                .connectionType(connectionType)
                .appId(dto.getAppId())
                .appSecretEncrypted(plainSecret != null ? cryptoUtil.encrypt(plainSecret) : null)
                .verificationTokenEncrypted(dto.getVerificationTokenEncrypted())
                .encryptKeyEncrypted(dto.getEncryptKeyEncrypted())
                .botOpenId(dto.getBotOpenId())
                .defaultChatId(dto.getDefaultChatId())
                .status("active")
                .build();

        feishuIntegrationMapper.insert(entity);
        log.info("Feishu integration created: id={}, appId={}, orgId={}", entity.getId(), entity.getAppId(), orgId);
        auditIntegration("FEISHU_CONNECT", entity);
        return toVo(entity);
    }

    /**
     * Write a forensic AUDIT-TRAIL entry for a Feishu integration lifecycle event.
     * Auditing is additive and best-effort: any failure here is logged and swallowed
     * so it can never break the connect operation or alter its transaction/return
     * value. Details carry only non-secret metadata (connectionType, storeId) — never
     * the app secret, verification token, encrypt key, or webhook URL/secret.
     */
    private void auditIntegration(String action, FeishuIntegrationEntity entity) {
        try {
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            if (entity != null) {
                if (entity.getConnectionType() != null) details.put("connectionType", entity.getConnectionType());
                if (entity.getStoreId() != null) details.put("storeId", entity.getStoreId().toString());
            }
            UUID actorId = auditActorId();
            UUID orgId = entity != null ? entity.getOrgId() : resolveCurrentOrgId();
            UUID entityId = entity != null ? entity.getId() : null;
            auditLogService.createLog(actorId, orgId, action, "feishu_integration", entityId, details);
        } catch (Exception ex) {
            log.warn("Failed to write audit log action={} integrationId={}: {}", action,
                    entity != null ? entity.getId() : null, ex.getMessage());
        }
    }

    /** Current authenticated user's id for audit attribution, or {@code null}. */
    private UUID auditActorId() {
        try {
            String id = SecurityUtils.getCurrentUserIdOrNull();
            return id != null && !id.isBlank() ? UUID.fromString(id.trim()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse the binding user's id into the {@code owner_account_id} written on the
     * integration at connect time (Req 7.1). Returns {@code null} when there is no
     * authenticated user or the id is not a valid UUID, so a missing/invalid actor
     * never aborts the bind.
     */
    private UUID parseOwnerAccountId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(userId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Resolve the current user's org UUID from the security context. */
    private UUID resolveCurrentOrgId() {
        try {
            String orgIdStr = SecurityUtils.getCurrentOrgId();
            return orgIdStr != null && !orgIdStr.isBlank() ? UUID.fromString(orgIdStr) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private UUID parseRequiredUuid(String value, String field) {
        // For orgId, fall back to the authenticated user's org so the UI does not
        // have to ask the user for an org UUID.
        String effective = value;
        if ((effective == null || effective.isBlank()) && "orgId".equals(field)) {
            try {
                effective = SecurityUtils.getCurrentOrgId();
            } catch (Exception ignore) {
                effective = null;
            }
        }
        if (effective == null || effective.isBlank()) {
            throw new BusinessException("INVALID_" + field.toUpperCase(), field + " is required");
        }
        try {
            return UUID.fromString(effective.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_" + field.toUpperCase(),
                    field + " must be a valid UUID: " + effective);
        }
    }

    private UUID parseOptionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_STORE_ID", "storeId must be a valid UUID: " + value);
        }
    }

    @Override
    @Transactional
    public FeishuIntegrationVo connectWebhook(FeishuWebhookConnectDto dto, String userId) {
        if (dto.getWebhookUrl() == null || dto.getWebhookUrl().isBlank()) {
            throw new BusinessException("INVALID_WEBHOOK_URL", "Webhook URL is required");
        }
        // SSRF guard: only accept https URLs on the allow-listed Feishu/Lark hosts,
        // and never internal/loopback/metadata targets (validated at the point of
        // acceptance so a malicious URL is never persisted).
        feishuWebhookValidator.validate(dto.getWebhookUrl());
        FeishuIntegrationEntity entity = FeishuIntegrationEntity.builder()
                .orgId(parseRequiredUuid(dto.getOrgId(), "orgId"))
                .storeId(parseOptionalUuid(dto.getStoreId()))
                .provider("feishu")
                .connectionType("webhook")
                .botOpenId(null)
                .webhookUrlEncrypted(cryptoUtil.encrypt(dto.getWebhookUrl()))
                .webhookSecretEncrypted(
                        dto.getWebhookSecret() != null && !dto.getWebhookSecret().isBlank()
                                ? cryptoUtil.encrypt(dto.getWebhookSecret())
                                : null)
                .status("active")
                .build();

        feishuIntegrationMapper.insert(entity);
        log.info("Feishu webhook integration created: id={}, store={}", entity.getId(), entity.getStoreId());
        auditIntegration("FEISHU_WEBHOOK_CONNECT", entity);
        return toVo(entity);
    }

    @Override
    @Transactional
    public FeishuIntegrationVo updateIntegration(String id, FeishuIntegrationDto dto, String userId) {
        FeishuIntegrationEntity entity = feishuIntegrationMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("FEISHU_INTEGRATION_NOT_FOUND", "Feishu integration not found: " + id);
        }
        // Cross-org access check
        UUID currentOrgId = resolveCurrentOrgId();
        if (currentOrgId == null || !entity.getOrgId().equals(currentOrgId)) {
            throw new BusinessException(403, "FORBIDDEN", "无权操作其他组织的飞书集成");
        }
        // Store_Group_Scope isolation: a store-bound integration may only be managed by
        // accounts whose data scope covers its store (Req 7.6).
        assertIntegrationInScope(entity, true);

        if (dto.getOrgId() != null) {
            entity.setOrgId(UUID.fromString(dto.getOrgId()));
        }
        if (dto.getStoreId() != null) {
            entity.setStoreId(UUID.fromString(dto.getStoreId()));
        }
        if (dto.getProvider() != null) {
            entity.setProvider(dto.getProvider());
        }
        if (dto.getAppId() != null) {
            entity.setAppId(dto.getAppId());
        }
        // Prefer plain-text appSecret; fall back to legacy appSecretEncrypted
        String secret = dto.getAppSecret() != null && !dto.getAppSecret().isBlank()
                ? dto.getAppSecret() : dto.getAppSecretEncrypted();
        if (secret != null && !secret.isBlank()) {
            entity.setAppSecretEncrypted(cryptoUtil.encrypt(secret));
        }
        if (dto.getConnectionType() != null && !dto.getConnectionType().isBlank()) {
            entity.setConnectionType(dto.getConnectionType());
        }
        if (dto.getVerificationTokenEncrypted() != null) {
            entity.setVerificationTokenEncrypted(dto.getVerificationTokenEncrypted());
        }
        if (dto.getEncryptKeyEncrypted() != null) {
            entity.setEncryptKeyEncrypted(dto.getEncryptKeyEncrypted());
        }
        if (dto.getBotOpenId() != null) {
            entity.setBotOpenId(dto.getBotOpenId());
        }
        if (dto.getDefaultChatId() != null) {
            entity.setDefaultChatId(dto.getDefaultChatId());
        }

        // Re-validate credentials if appId or appSecret changed
        boolean appIdChanged = dto.getAppId() != null && !dto.getAppId().isBlank();
        boolean secretChanged = secret != null && !secret.isBlank();
        if (appIdChanged || secretChanged) {
            String effectiveAppId = entity.getAppId();
            String effectiveSecret = secretChanged ? secret : cryptoUtil.decrypt(entity.getAppSecretEncrypted());
            if (effectiveAppId != null && !effectiveAppId.isBlank()
                    && effectiveSecret != null && !effectiveSecret.isBlank()) {
                try {
                    feishuApiClient.tenantAccessTokenNoCache(effectiveAppId, effectiveSecret);
                } catch (RuntimeException e) {
                    throw new BusinessException("FEISHU_AUTH_FAILED",
                            "飞书应用凭证验证失败: " + e.getMessage());
                }
            }
        }

        entity.setUpdatedAt(LocalDateTime.now());

        feishuIntegrationMapper.updateById(entity);
        log.info("Feishu integration updated: id={}", id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public void sendTestMessage(String id, String chatId) {
        FeishuIntegrationEntity entity = feishuIntegrationMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("FEISHU_INTEGRATION_NOT_FOUND", "Feishu integration not found: " + id);
        }
        // Cross-org access check
        UUID currentOrgId = resolveCurrentOrgId();
        if (currentOrgId == null || !entity.getOrgId().equals(currentOrgId)) {
            throw new BusinessException(403, "FORBIDDEN", "无权操作其他组织的飞书集成");
        }
        // Store_Group_Scope isolation: only manage a store-bound integration when the
        // account's data scope covers its store (Req 7.6).
        assertIntegrationInScope(entity, true);

        String content = feishuApiClient.buildTextContent("AdPilot AI 测试消息 ✅ 连接正常");
        boolean ok;
        if ("webhook".equalsIgnoreCase(entity.getConnectionType())) {
            ok = dispatch(entity, null, "text", content, "test");
        } else {
            String targetChatId = chatId != null ? chatId : entity.getDefaultChatId();
            if (targetChatId == null) {
                throw new BusinessException("NO_CHAT_ID", "No chat ID specified and no default chat ID configured");
            }
            ok = dispatch(entity, targetChatId, "text", content, "test");
        }
        if (!ok) {
            throw new BusinessException("FEISHU_TEST_FAILED", "Failed to send test message to Feishu");
        }
        log.info("Test message sent: integrationId={}", id);
    }

    @Override
    @Transactional
    public UUID requestConfirmation(UUID storeId, String actionType, String title, String body,
                                    Map<String, Object> actionData) {
        FeishuIntegrationEntity integration = resolveTarget(storeId);
        if (integration == null) {
            return null;
        }
        String actionDataJson;
        try {
            actionDataJson = actionData != null ? objectMapper.writeValueAsString(actionData) : "{}";
        } catch (Exception e) {
            actionDataJson = "{}";
        }
        // When the confirm is linked to an AdPilot approval request, record the
        // linkage so the card-callback can transition the real approval.
        UUID approvalId = null;
        if (actionData != null && actionData.get("approvalRequestId") != null) {
            try {
                approvalId = UUID.fromString(actionData.get("approvalRequestId").toString());
            } catch (IllegalArgumentException ignore) {
                approvalId = null;
            }
        }
        FeishuActionRequestEntity request = FeishuActionRequestEntity.builder()
                .feishuIntegrationId(integration.getId())
                .chatId(integration.getDefaultChatId())
                .actionType(actionType != null ? actionType : "confirm")
                .actionData(actionDataJson)
                .relatedEntityType(approvalId != null ? "approval_request" : null)
                .relatedEntityId(approvalId)
                .approvalRequestId(approvalId)
                .status("pending")
                .build();
        feishuActionRequestMapper.insert(request);

        String card = feishuApiClient.buildConfirmCardContent(
                title != null ? title : "请确认操作",
                body != null ? body : "",
                request.getId().toString());
        dispatch(integration, integration.getDefaultChatId(), "interactive", card,
                "confirm:" + (actionType != null ? actionType : "confirm"));
        return request.getId();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean pushAlert(UUID storeId, String title, String message) {
        FeishuIntegrationEntity integration = resolveTarget(storeId);
        if (integration == null) {
            // No Feishu push configured for this store (Req 10.1.6 WHERE-guard) -> no-op.
            return false;
        }
        String text = String.format("[%s] %s", nullSafe(title), nullSafe(message));
        String content = feishuApiClient.buildTextContent(text);
        boolean ok = dispatch(integration, integration.getDefaultChatId(), "text", content, "alert");
        if (!ok) {
            throw new BusinessException("FEISHU_PUSH_FAILED", "Failed to push alert to Feishu");
        }
        return true;
    }

    /**
     * Resolve a push target for a store, strictly scoped by store and account
     * (Req 7.2, 7.3). Only an integration whose {@code store_id} equals the given
     * store AND whose {@code owner_account_id} equals the store's owning account
     * (the account that created the store) is eligible — there is no fall-back to
     * org-wide / null-store integrations and no cross-account reuse. A valid target
     * is either a webhook connection (fixed destination, no chat id needed) or an
     * app connection with a default chat id. Returns {@code null} when the store is
     * unknown or no matching integration exists, in which case nothing is sent.
     */
    private FeishuIntegrationEntity resolveTarget(UUID storeId) {
        if (storeId == null) {
            return null;
        }
        StoreEntity store = storeMapper.selectById(storeId);
        if (store == null) {
            // Cannot determine the store's owning account -> never send (Req 7.3).
            return null;
        }
        UUID ownerAccountId = store.getCreatedBy();

        LambdaQueryWrapper<FeishuIntegrationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FeishuIntegrationEntity::getStatus, "active")
                .eq(FeishuIntegrationEntity::getStoreId, storeId)
                .and(w -> w.eq(FeishuIntegrationEntity::getConnectionType, "webhook")
                        .or()
                        .isNotNull(FeishuIntegrationEntity::getDefaultChatId))
                .last("LIMIT 20");
        // Bind the account dimension explicitly so the SQL predicate itself carries
        // the isolation: match the store owner's id, or null-owner for legacy rows.
        if (ownerAccountId != null) {
            wrapper.eq(FeishuIntegrationEntity::getOwnerAccountId, ownerAccountId);
        } else {
            wrapper.isNull(FeishuIntegrationEntity::getOwnerAccountId);
        }
        if (store.getOrgId() != null) {
            wrapper.eq(FeishuIntegrationEntity::getOrgId, store.getOrgId());
        }

        List<FeishuIntegrationEntity> candidates = feishuIntegrationMapper.selectList(wrapper);
        // Defence in depth: re-assert the (store_id, owner_account_id) invariants on
        // the returned rows before using any of them (Req 7.3).
        return candidates.stream()
                .filter(e -> isEligibleForStore(e, storeId, ownerAccountId))
                .findFirst()
                .orElse(null);
    }

    /**
     * True when an integration may be used to notify the given store: it must be
     * active, its {@code store_id} must equal the store, and its
     * {@code owner_account_id} must equal the store's owning account (Req 7.2, 7.3).
     */
    private boolean isEligibleForStore(FeishuIntegrationEntity integration, UUID storeId, UUID ownerAccountId) {
        return integration != null
                && "active".equalsIgnoreCase(integration.getStatus())
                && storeId != null
                && storeId.equals(integration.getStoreId())
                && java.util.Objects.equals(ownerAccountId, integration.getOwnerAccountId());
    }

    /**
     * Actually deliver a message to Feishu, routing by connection type, and record
     * a feishu_message_logs row. Returns true on success, false on failure (never
     * throws), so callers decide whether to surface the error.
     */
    private boolean dispatch(FeishuIntegrationEntity integration, String chatId,
                             String msgType, String contentJson, String relatedType) {
        FeishuMessageLogEntity logEntry = FeishuMessageLogEntity.builder()
                .feishuIntegrationId(integration.getId())
                .chatId(chatId)
                .messageType(msgType)
                .direction("outbound")
                .relatedEntityType(relatedType)
                .content(contentJson)
                .status("sent")
                .build();
        // H3: when Feishu delivery for this integration is known-down, treat this send as a
        // (temporary) delivery failure via the EXISTING failure path — record a failed message
        // log and return false — rather than hitting the API every dispatch. Callers already
        // handle a false return (e.g. pushAlert surfaces FEISHU_PUSH_FAILED, notification paths
        // skip gracefully). No new exception type is introduced.
        String breakerKey = feishuBreakerKey(integration);
        if (!circuitBreaker.allow(breakerKey)) {
            log.debug("Feishu dispatch skipped (circuit OPEN) for {}: integrationId={}, type={}",
                    breakerKey, integration.getId(), integration.getConnectionType());
            logEntry.setStatus("failed");
            logEntry.setErrorMessage("Circuit open: Feishu delivery temporarily suppressed");
            try {
                feishuMessageLogMapper.insert(logEntry);
            } catch (Exception ignore) {
                // logging failure must not mask the suppressed-delivery outcome
            }
            return false;
        }
        try {
            if ("webhook".equalsIgnoreCase(integration.getConnectionType())) {
                String url = cryptoUtil.decrypt(integration.getWebhookUrlEncrypted());
                // Defence in depth: re-validate the stored URL before sending so a
                // row persisted before this guard (or otherwise tampered) can never
                // drive an SSRF request to an internal/metadata target.
                feishuWebhookValidator.validate(url);
                String secret = integration.getWebhookSecretEncrypted() != null
                        ? cryptoUtil.decrypt(integration.getWebhookSecretEncrypted()) : null;
                feishuApiClient.sendWebhook(url, secret, msgType, contentJson);
            } else {
                String secret = cryptoUtil.decrypt(integration.getAppSecretEncrypted());
                String token = feishuApiClient.tenantAccessToken(integration.getAppId(), secret);
                feishuApiClient.sendAppMessage(token, integration.getAppId(), chatId, msgType, contentJson);
            }
            feishuMessageLogMapper.insert(logEntry);
            integration.setLastConnectedAt(LocalDateTime.now());
            feishuIntegrationMapper.updateById(integration);
            circuitBreaker.recordSuccess(breakerKey);
            return true;
        } catch (Exception e) {
            log.warn("Feishu dispatch failed: integrationId={}, type={}, reason={}",
                    integration.getId(), integration.getConnectionType(), e.getMessage());
            circuitBreaker.recordFailure(breakerKey);
            logEntry.setStatus("failed");
            logEntry.setErrorMessage(e.getMessage());
            try {
                feishuMessageLogMapper.insert(logEntry);
            } catch (Exception ignore) {
                // logging failure must not mask the original error
            }
            return false;
        }
    }

    /**
     * Circuit-breaker key for a Feishu integration's delivery (H3). Keyed by dependency +
     * integration id so a single down integration opens the breaker only for itself, never for a
     * healthy integration's messages.
     */
    private static String feishuBreakerKey(FeishuIntegrationEntity integration) {
        return "feishu:" + integration.getId();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean pushAiNotification(UUID storeId, String title, String message) {
        if (storeId == null) {
            // No store -> no destination resolvable (Req 7.4). Skip gracefully with a
            // readable reason; never throw so sibling stores' notifications continue.
            log.info("AI notification skipped: no storeId provided, no Feishu destination resolvable");
            return false;
        }

        // Resolve the store's owning account so the chat-binding path can be scoped
        // strictly by (store_id, owner_account_id) — no cross-account reuse (Req 7.3).
        StoreEntity store = storeMapper.selectById(storeId);
        if (store == null) {
            // Unknown store -> cannot verify account ownership -> never send.
            log.info("AI notification skipped: store {} not found, no Feishu destination resolvable", storeId);
            return false;
        }
        UUID ownerAccountId = store.getCreatedBy();

        // Prefer the store's active chat bindings (feishu_chat_bindings).
        LambdaQueryWrapper<FeishuChatBindingEntity> bindingWrapper = new LambdaQueryWrapper<>();
        bindingWrapper.eq(FeishuChatBindingEntity::getStoreId, storeId)
                .eq(FeishuChatBindingEntity::getStatus, "active")
                .isNotNull(FeishuChatBindingEntity::getChatId);
        List<FeishuChatBindingEntity> bindings = feishuChatBindingMapper.selectList(bindingWrapper);

        String text = String.format("[AI通知] %s\n%s", nullSafe(title), nullSafe(message));
        String content = feishuApiClient.buildTextContent(text);

        int dispatched = 0;
        for (FeishuChatBindingEntity binding : bindings) {
            FeishuIntegrationEntity integration =
                    feishuIntegrationMapper.selectById(binding.getFeishuIntegrationId());
            // Only dispatch through an integration that belongs to this store AND
            // this store's owning account — never cross-store/cross-account (Req 7.3).
            if (!isEligibleForStore(integration, storeId, ownerAccountId)) {
                continue;
            }
            if (dispatch(integration, binding.getChatId(), "text", content, "ai_notification")) {
                dispatched++;
            }
        }

        if (dispatched > 0) {
            log.info("AI notification pushed to {} Feishu chat(s) for store {}", dispatched, storeId);
            return true;
        }

        // No bound chats — fall back to the store's own bound integration default
        // chat / webhook (still scoped by store + account in resolveTarget).
        boolean delivered = pushAlertQuiet(storeId, title, message);
        if (!delivered) {
            // No destination bound for this store/account (Req 7.4): skip gracefully with
            // a readable reason; the false return lets per-store callers continue without
            // interrupting other stores' notifications.
            log.info("AI notification skipped for store {}: no Feishu integration bound for this "
                    + "store/account; other stores' notifications are unaffected", storeId);
        }
        return delivered;
    }

    /** Like {@link #pushAlert} but never throws (used as the AI-notification fallback). */
    private boolean pushAlertQuiet(UUID storeId, String title, String message) {
        FeishuIntegrationEntity integration = resolveTarget(storeId);
        if (integration == null) {
            return false;
        }
        String text = String.format("[%s] %s", nullSafe(title), nullSafe(message));
        return dispatch(integration, integration.getDefaultChatId(), "text",
                feishuApiClient.buildTextContent(text), "ai_notification");
    }

    @Override
    public List<FeishuChatBindingVo> listChatBindings(String integrationId) {
        UUID integrationUuid = parseIntegrationId(integrationId);
        requireIntegrationWithOrgCheck(integrationUuid);

        LambdaQueryWrapper<FeishuChatBindingEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FeishuChatBindingEntity::getFeishuIntegrationId, integrationUuid)
                .orderByDesc(FeishuChatBindingEntity::getCreatedAt);

        return feishuChatBindingMapper.selectList(wrapper).stream()
                .map(this::toChatBindingVo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public FeishuChatBindingVo createChatBinding(String integrationId, FeishuChatBindingDto dto) {
        UUID integrationUuid = parseIntegrationId(integrationId);
        requireIntegrationWithOrgCheck(integrationUuid);

        FeishuChatBindingEntity entity = FeishuChatBindingEntity.builder()
                .feishuIntegrationId(integrationUuid)
                .storeId(dto.getStoreId() != null ? UUID.fromString(dto.getStoreId()) : null)
                .chatId(dto.getChatId())
                .chatType(dto.getChatType() != null ? dto.getChatType() : "group")
                .chatName(dto.getChatName())
                .notifyOnApproval(dto.getNotifyOnApproval() != null ? dto.getNotifyOnApproval() : true)
                .notifyOnExecution(dto.getNotifyOnExecution() != null ? dto.getNotifyOnExecution() : true)
                .notifyOnRollback(dto.getNotifyOnRollback() != null ? dto.getNotifyOnRollback() : true)
                .notifyOnRiskAlert(dto.getNotifyOnRiskAlert() != null ? dto.getNotifyOnRiskAlert() : true)
                .status("active")
                .build();

        feishuChatBindingMapper.insert(entity);
        log.info("Feishu chat binding created: id={}, integrationId={}, chatId={}",
                entity.getId(), integrationId, entity.getChatId());
        return toChatBindingVo(entity);
    }

    @Override
    public List<FeishuNotificationRuleVo> listNotificationRules(String integrationId) {
        UUID integrationUuid = parseIntegrationId(integrationId);
        requireIntegrationWithOrgCheck(integrationUuid);

        LambdaQueryWrapper<FeishuNotificationRuleEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FeishuNotificationRuleEntity::getFeishuIntegrationId, integrationUuid)
                .orderByDesc(FeishuNotificationRuleEntity::getCreatedAt);

        return feishuNotificationRuleMapper.selectList(wrapper).stream()
                .map(this::toNotificationRuleVo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public FeishuNotificationRuleVo createNotificationRule(String integrationId, FeishuNotificationRuleDto dto) {
        UUID integrationUuid = parseIntegrationId(integrationId);
        requireIntegrationWithOrgCheck(integrationUuid);

        FeishuNotificationRuleEntity entity = FeishuNotificationRuleEntity.builder()
                .feishuIntegrationId(integrationUuid)
                .storeId(dto.getStoreId() != null ? UUID.fromString(dto.getStoreId()) : null)
                .chatId(dto.getChatId())
                .name(dto.getName())
                .eventType(dto.getEventType())
                .conditionJson(dto.getConditionJson())
                .enabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                .status("active")
                .build();

        feishuNotificationRuleMapper.insert(entity);
        log.info("Feishu notification rule created: id={}, integrationId={}, eventType={}",
                entity.getId(), integrationId, entity.getEventType());
        return toNotificationRuleVo(entity);
    }

    private UUID parseIntegrationId(String integrationId) {
        try {
            return UUID.fromString(integrationId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_INTEGRATION_ID", "Invalid Feishu integration id: " + integrationId);
        }
    }

    private void requireIntegration(UUID integrationUuid) {
        if (feishuIntegrationMapper.selectById(integrationUuid) == null) {
            throw new BusinessException("FEISHU_INTEGRATION_NOT_FOUND",
                    "Feishu integration not found: " + integrationUuid);
        }
    }

    /** Load integration and verify it belongs to the current user's org. */
    private FeishuIntegrationEntity requireIntegrationWithOrgCheck(UUID integrationUuid) {
        FeishuIntegrationEntity entity = feishuIntegrationMapper.selectById(integrationUuid);
        if (entity == null) {
            throw new BusinessException("FEISHU_INTEGRATION_NOT_FOUND",
                    "Feishu integration not found: " + integrationUuid);
        }
        UUID currentOrgId = resolveCurrentOrgId();
        if (currentOrgId == null || !entity.getOrgId().equals(currentOrgId)) {
            throw new BusinessException(403, "FORBIDDEN", "无权操作其他组织的飞书集成");
        }
        // Store_Group_Scope isolation: a store-bound integration's sub-resources
        // (chat bindings / notification rules) may only be viewed or managed by
        // accounts whose data scope covers its store (Req 7.6).
        assertIntegrationInScope(entity, true);
        return entity;
    }

    private FeishuChatBindingVo toChatBindingVo(FeishuChatBindingEntity entity) {
        return FeishuChatBindingVo.builder()
                .id(entity.getId().toString())
                .feishuIntegrationId(entity.getFeishuIntegrationId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .chatId(entity.getChatId())
                .chatType(entity.getChatType())
                .chatName(entity.getChatName())
                .notifyOnApproval(entity.getNotifyOnApproval())
                .notifyOnExecution(entity.getNotifyOnExecution())
                .notifyOnRollback(entity.getNotifyOnRollback())
                .notifyOnRiskAlert(entity.getNotifyOnRiskAlert())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private FeishuNotificationRuleVo toNotificationRuleVo(FeishuNotificationRuleEntity entity) {
        return FeishuNotificationRuleVo.builder()
                .id(entity.getId().toString())
                .feishuIntegrationId(entity.getFeishuIntegrationId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .chatId(entity.getChatId())
                .name(entity.getName())
                .eventType(entity.getEventType())
                .conditionJson(entity.getConditionJson())
                .enabled(entity.getEnabled())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private FeishuIntegrationVo toVo(FeishuIntegrationEntity entity) {
        return FeishuIntegrationVo.builder()
                .id(entity.getId().toString())
                .orgId(entity.getOrgId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .provider(entity.getProvider())
                .appId(entity.getAppId())
                .connectionType(entity.getConnectionType())
                .botOpenId(entity.getBotOpenId())
                .defaultChatId(entity.getDefaultChatId())
                .status(entity.getStatus())
                .lastConnectedAt(entity.getLastConnectedAt() != null ? entity.getLastConnectedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
