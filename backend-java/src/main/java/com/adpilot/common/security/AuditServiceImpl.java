package com.adpilot.common.security;

import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.audit.entity.AuditLogEntity;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link AuditService} writing authorization decisions to the existing
 * {@code audit_logs} table via {@link AuditLogMapper}.
 *
 * <p>The user identity is resolved from the current security context and the
 * requested operation is recorded as the audit action detail. Both permit and
 * deny decisions are persisted (Requirement 2.1.5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    /** Audit action recorded for a permitted authorization decision. */
    static final String ACTION_PERMIT = "AUTHZ_PERMIT";
    /** Audit action recorded for a denied authorization decision. */
    static final String ACTION_DENY = "AUTHZ_DENY";
    /** Entity type tagging an authorization-decision audit entry. */
    static final String ENTITY_TYPE = "AUTHORIZATION";

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void recordAuthorizationDecision(String operation, boolean permitted) {
        UUID userId = parseUuid(SecurityUtils.getCurrentUserIdOrNull());
        UUID orgId = parseUuid(currentOrgIdOrNull());

        AuditLogEntity entity = AuditLogEntity.builder()
                .userId(userId)
                .orgId(orgId)
                .action(permitted ? ACTION_PERMIT : ACTION_DENY)
                .entityType(ENTITY_TYPE)
                .newData(buildDetail(operation, permitted))
                .ipAddress(currentRequestIp())
                .userAgent(currentRequestUserAgent())
                .source("app")
                .build();

        auditLogMapper.insert(entity);
        log.debug("Authorization decision audited: operation={}, permitted={}, userId={}",
                operation, permitted, userId);
    }

    private String buildDetail(String operation, boolean permitted) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", operation);
        detail.put("decision", permitted ? "permit" : "deny");
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize authorization audit detail: {}", e.getMessage());
            return "{\"operation\":\"" + operation + "\",\"decision\":\""
                    + (permitted ? "permit" : "deny") + "\"}";
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String currentOrgIdOrNull() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentOrgId() : null;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static String currentRequestIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String currentRequestUserAgent() {
        HttpServletRequest request = currentRequest();
        return request != null ? request.getHeader("User-Agent") : null;
    }
}
