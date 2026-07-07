package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.mapper.PersonalityPolicyMapper;
import com.adpilot.modules.advertising.service.PersonalityPolicyScopeService;
import com.adpilot.modules.audit.entity.AuditLogEntity;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scoped personality policy resolution and lifecycle management (Requirement 38).
 *
 * <p>Enforces:
 * <ul>
 *   <li>At most one {@code status='active'} per {@code (scope, scope_id, personality)} (Req 38.2)</li>
 *   <li>Most-specific scope resolution: campaign → goal → store → organization → system (Req 38.3)</li>
 *   <li>Audit trail for all activation/deactivation changes (Req 38.6)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalityPolicyScopeServiceImpl implements PersonalityPolicyScopeService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";

    /** Audit action for personality policy changes. */
    private static final String AUDIT_ACTION_ACTIVATE = "PERSONALITY_POLICY_ACTIVATE";
    private static final String AUDIT_ACTION_DEACTIVATE = "PERSONALITY_POLICY_DEACTIVATE";
    private static final String AUDIT_ENTITY_TYPE = "PERSONALITY_POLICY";

    /** Canonical AI_Personality machine values. */
    private static final Set<String> CANONICAL_PERSONALITIES =
            Set.of("conservative", "balanced", "aggressive");

    private static final String DEFAULT_PERSONALITY = "balanced";

    /**
     * Scope precedence order from most-specific to least-specific.
     */
    private static final List<String> SCOPE_PRECEDENCE = List.of(
            SCOPE_CAMPAIGN, SCOPE_GOAL, SCOPE_STORE, SCOPE_ORGANIZATION, SCOPE_SYSTEM
    );

    private final PersonalityPolicyMapper personalityPolicyMapper;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    public PersonalityPolicyEntity resolvePolicy(String personality, UUID campaignId,
                                                 UUID goalId, UUID storeId, UUID orgId) {
        String resolved = normalize(personality);

        // Walk scopes from most-specific to least-specific (Req 38.3)
        UUID[] scopeIds = { campaignId, goalId, storeId, orgId, null };

        for (int i = 0; i < SCOPE_PRECEDENCE.size(); i++) {
            String scope = SCOPE_PRECEDENCE.get(i);
            UUID scopeId = scopeIds[i];

            // Skip null scope ids for non-system scopes
            if (scopeId == null && !SCOPE_SYSTEM.equals(scope)) {
                continue;
            }

            PersonalityPolicyEntity policy = findActivePolicy(scope, scopeId, resolved);
            if (policy != null) {
                return policy;
            }
        }

        // If a non-default personality has no row at all, fall back to the balanced system policy.
        if (!DEFAULT_PERSONALITY.equals(resolved)) {
            log.warn("No active personality_policies row for personality={}, "
                    + "falling back to system {}", resolved, DEFAULT_PERSONALITY);
            PersonalityPolicyEntity fallback = findActivePolicy(SCOPE_SYSTEM, null, DEFAULT_PERSONALITY);
            if (fallback != null) {
                return fallback;
            }
        }

        throw new BusinessException(
                "PERSONALITY_POLICY_NOT_FOUND",
                "No active Personality_Policy configured for personality '" + resolved
                        + "' at any scope level and no system '" + DEFAULT_PERSONALITY
                        + "' fallback exists");
    }

    @Override
    @Transactional
    public void activatePolicy(UUID policyId) {
        PersonalityPolicyEntity policy = personalityPolicyMapper.selectById(policyId);
        if (policy == null) {
            throw new BusinessException("PERSONALITY_POLICY_NOT_FOUND",
                    "Personality policy not found: " + policyId);
        }

        String previousStatus = policy.getStatus();

        // Deactivate any currently active version for the same (scope, scope_id, personality)
        PersonalityPolicyEntity currentActive = findActivePolicy(
                policy.getScope(), policy.getScopeId(), policy.getPersonality());

        if (currentActive != null && !currentActive.getId().equals(policyId)) {
            // Deactivate the current active one
            deactivateInternal(currentActive, "Superseded by policy " + policyId);
        }

        // Activate the requested policy
        LambdaUpdateWrapper<PersonalityPolicyEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PersonalityPolicyEntity::getId, policyId)
                .set(PersonalityPolicyEntity::getStatus, STATUS_ACTIVE)
                .set(PersonalityPolicyEntity::getEffectiveFrom, LocalDateTime.now());
        personalityPolicyMapper.update(null, updateWrapper);

        // Audit the activation (Req 38.6)
        auditPolicyChange(policy, previousStatus, STATUS_ACTIVE, AUDIT_ACTION_ACTIVATE);

        log.info("Activated personality policy: id={}, scope={}, scopeId={}, personality={}, ruleVersion={}",
                policyId, policy.getScope(), policy.getScopeId(),
                policy.getPersonality(), policy.getRuleVersion());
    }

    @Override
    @Transactional
    public void deactivatePolicy(UUID policyId) {
        PersonalityPolicyEntity policy = personalityPolicyMapper.selectById(policyId);
        if (policy == null) {
            throw new BusinessException("PERSONALITY_POLICY_NOT_FOUND",
                    "Personality policy not found: " + policyId);
        }

        deactivateInternal(policy, "Manual deactivation");
    }

    // -------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------

    /**
     * Finds the single active policy for the given scope tuple.
     */
    private PersonalityPolicyEntity findActivePolicy(String scope, UUID scopeId, String personality) {
        LambdaQueryWrapper<PersonalityPolicyEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PersonalityPolicyEntity::getScope, scope)
                .eq(PersonalityPolicyEntity::getPersonality, personality)
                .eq(PersonalityPolicyEntity::getStatus, STATUS_ACTIVE);

        if (scopeId != null) {
            wrapper.eq(PersonalityPolicyEntity::getScopeId, scopeId);
        } else {
            wrapper.isNull(PersonalityPolicyEntity::getScopeId);
        }

        wrapper.last("LIMIT 1");
        return personalityPolicyMapper.selectOne(wrapper);
    }

    /**
     * Deactivates a policy and records the audit trail.
     */
    private void deactivateInternal(PersonalityPolicyEntity policy, String reason) {
        String previousStatus = policy.getStatus();

        LambdaUpdateWrapper<PersonalityPolicyEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PersonalityPolicyEntity::getId, policy.getId())
                .set(PersonalityPolicyEntity::getStatus, STATUS_INACTIVE)
                .set(PersonalityPolicyEntity::getEffectiveTo, LocalDateTime.now());
        personalityPolicyMapper.update(null, updateWrapper);

        // Audit the deactivation (Req 38.6)
        auditPolicyChange(policy, previousStatus, STATUS_INACTIVE, AUDIT_ACTION_DEACTIVATE);

        log.info("Deactivated personality policy: id={}, scope={}, scopeId={}, personality={}, "
                        + "ruleVersion={}, reason={}",
                policy.getId(), policy.getScope(), policy.getScopeId(),
                policy.getPersonality(), policy.getRuleVersion(), reason);
    }

    /**
     * Records before/after state when a policy is activated/deactivated (Req 38.6).
     */
    private void auditPolicyChange(PersonalityPolicyEntity policy, String previousStatus,
                                   String newStatus, String action) {
        UUID userId = parseUuid(SecurityUtils.getCurrentUserIdOrNull());
        UUID orgId = parseUuid(currentOrgIdOrNull());

        Map<String, Object> oldData = buildAuditData(policy, previousStatus);
        Map<String, Object> newData = buildAuditData(policy, newStatus);

        AuditLogEntity auditEntry = AuditLogEntity.builder()
                .userId(userId)
                .orgId(orgId)
                .action(action)
                .entityType(AUDIT_ENTITY_TYPE)
                .entityId(policy.getId())
                .oldData(toJson(oldData))
                .newData(toJson(newData))
                .source("app")
                .build();

        auditLogMapper.insert(auditEntry);
    }

    private Map<String, Object> buildAuditData(PersonalityPolicyEntity policy, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", policy.getScope());
        data.put("scope_id", policy.getScopeId() != null ? policy.getScopeId().toString() : null);
        data.put("personality", policy.getPersonality());
        data.put("rule_version", policy.getRuleVersion());
        data.put("status", status);
        return data;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit data: {}", e.getMessage());
            return "{}";
        }
    }

    /** Normalizes the personality to a canonical machine value, defaulting to balanced. */
    private String normalize(String personality) {
        if (personality == null || personality.isBlank()) {
            return DEFAULT_PERSONALITY;
        }
        String trimmed = personality.trim().toLowerCase(Locale.ROOT);
        return CANONICAL_PERSONALITIES.contains(trimmed) ? trimmed : DEFAULT_PERSONALITY;
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
}
