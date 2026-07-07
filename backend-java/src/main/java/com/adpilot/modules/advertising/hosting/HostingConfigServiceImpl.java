package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.dto.HostingConfigRequest;
import com.adpilot.modules.advertising.entity.SafetyBoundaryEntity;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.support.BoundaryValidationResult;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLevel;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.advertising.vo.HostingConfigVo;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link HostingConfigService} persisting per-scope hosting configuration to the
 * {@code hosting_configs} table as a JSON payload, with backend validation of personality
 * and execution-mode enums, the {@code auto_execute_threshold} range, and the only-tighten
 * boundary rule (Req 12.6, 21.3, 21.4).
 *
 * <p>Validates: Requirements 21.1, 21.2, 21.3, 21.4, 21.5, 21.6, 12.1, 12.2, 12.3, 12.4,
 * 12.5, 12.6.</p>
 */
@Slf4j
@Service
public class HostingConfigServiceImpl implements HostingConfigService {

    // Canonical snake_case JSON keys (Req 21.1) — kept compatible with the lightweight
    // readers in ShadowModeServiceImpl ("shadow_mode") and PhaseConfigurationServiceImpl
    // ("active_phase").
    static final String KEY_ACTIVE_PHASE = "active_phase";
    static final String KEY_DEFAULT_PERSONALITY = "default_personality";
    static final String KEY_EXECUTION_MODE = "execution_mode";
    static final String KEY_AUTO_EXECUTE_THRESHOLD = "auto_execute_threshold";
    static final String KEY_EMERGENCY_AUTO_ACTION = "emergency_auto_action_enabled";
    static final String KEY_SHADOW_MODE = "shadow_mode";
    static final String KEY_NOTIFICATION_PREFERENCES = "notification_preferences";
    static final String KEY_BOUNDARY_OVERRIDES = "boundary_overrides";

    static final BigDecimal ZERO = BigDecimal.ZERO;
    static final BigDecimal ONE = BigDecimal.ONE;

    private final HostingConfigMapper hostingConfigMapper;
    private final StoreMapper storeMapper;
    private final SafetyBoundaryMapper safetyBoundaryMapper;
    private final SafetyBoundaryValidator boundaryValidator;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public HostingConfigServiceImpl(HostingConfigMapper hostingConfigMapper,
                                    StoreMapper storeMapper,
                                    SafetyBoundaryMapper safetyBoundaryMapper,
                                    SafetyBoundaryValidator boundaryValidator,
                                    AuditLogService auditLogService,
                                    ObjectMapper objectMapper) {
        this.hostingConfigMapper = hostingConfigMapper;
        this.storeMapper = storeMapper;
        this.safetyBoundaryMapper = safetyBoundaryMapper;
        this.boundaryValidator = boundaryValidator;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Override
    public HostingConfigVo getConfig(UUID storeId, String scope, UUID scopeId) {
        requireNonNull(storeId, "Store ID must not be null");
        String normalizedScope = normalizeScope(scope);
        requireNonNull(scopeId, "Scope ID must not be null");

        HostingConfigEntity entity = findConfig(storeId, normalizedScope, scopeId);
        Map<String, Object> config = parseConfig(entity == null ? null : entity.getConfig());
        return toVo(storeId, normalizedScope, scopeId, config);
    }

    @Override
    @Transactional
    public HostingConfigVo saveStoreConfig(UUID storeId, HostingConfigRequest request, UUID actorId) {
        requireNonNull(storeId, "Store ID must not be null");
        if (request == null) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", "Request body must not be null");
        }

        // Field-level validation (Req 12.6, 21.3).
        validateEnums(request);
        validateThreshold(request.getAutoExecuteThreshold());

        // Boundary overrides: only-tighten + cross-field (Req 21.4, 12.6).
        Map<SafetyBoundaryLimit, BigDecimal> parsedOverrides = parseBoundaryOverrides(request.getBoundaryOverrides());
        validateBoundaryOverrides(storeId, parsedOverrides);

        // Load existing config, overlay non-null fields, persist.
        HostingConfigEntity entity = findConfig(storeId, "store", storeId);
        Map<String, Object> before = parseConfig(entity == null ? null : entity.getConfig());
        Map<String, Object> after = applyOverlay(before, request);
        String json = writeConfig(after);

        if (entity != null) {
            entity.setConfig(json);
            entity.setUpdatedBy(actorId);
            hostingConfigMapper.updateById(entity);
        } else {
            entity = HostingConfigEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .scope("store")
                    .scopeId(storeId)
                    .config(json)
                    .createdBy(actorId)
                    .updatedBy(actorId)
                    .build();
            hostingConfigMapper.insert(entity);
        }

        recordAudit(storeId, actorId, before, after);

        log.info("Hosting config saved for store={} by actor={}", storeId, actorId);
        return toVo(storeId, "store", storeId, after);
    }

    @Override
    @Transactional
    public HostingConfigVo saveCampaignConfig(UUID storeId, UUID campaignId, HostingConfigRequest request, UUID actorId) {
        requireNonNull(storeId, "Store ID must not be null");
        requireNonNull(campaignId, "Campaign ID must not be null");
        if (request == null) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", "Request body must not be null");
        }

        // Field-level validation (Req 12.6, 21.3) — identical to the store-scope path.
        validateEnums(request);
        validateThreshold(request.getAutoExecuteThreshold());

        // Boundary overrides: only-tighten + cross-field against the store-and-above
        // resolved boundary (Req 21.4, 12.6). Empty overrides short-circuit.
        Map<SafetyBoundaryLimit, BigDecimal> parsedOverrides = parseBoundaryOverrides(request.getBoundaryOverrides());
        validateBoundaryOverrides(storeId, parsedOverrides);

        // Load existing campaign-scope config, overlay non-null fields, persist.
        HostingConfigEntity entity = findConfig(storeId, "campaign", campaignId);
        Map<String, Object> before = parseConfig(entity == null ? null : entity.getConfig());
        Map<String, Object> after = applyOverlay(before, request);
        String json = writeConfig(after);

        if (entity != null) {
            entity.setConfig(json);
            entity.setUpdatedBy(actorId);
            hostingConfigMapper.updateById(entity);
        } else {
            entity = HostingConfigEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .scope("campaign")
                    .scopeId(campaignId)
                    .config(json)
                    .createdBy(actorId)
                    .updatedBy(actorId)
                    .build();
            hostingConfigMapper.insert(entity);
        }

        recordAudit(storeId, actorId, before, after);

        log.info("Hosting config saved for store={} campaign={} by actor={}", storeId, campaignId, actorId);
        return toVo(storeId, "campaign", campaignId, after);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Validation
    // ────────────────────────────────────────────────────────────────────────────

    /** Validate personality, execution-mode, and active-phase enums (Req 12.6, 21.3). */
    void validateEnums(HostingConfigRequest request) {
        String personality = request.getDefaultPersonality();
        if (isPresent(personality) && AiPersonality.parse(personality).isEmpty()) {
            throw new BusinessException(400, "HOSTING_INVALID_PERSONALITY",
                    "Invalid default_personality '" + personality
                            + "'. Must be one of: conservative, balanced, aggressive.");
        }

        String executionMode = request.getExecutionMode();
        if (isPresent(executionMode) && ExecutionMode.parse(executionMode) == null) {
            throw new BusinessException(400, "HOSTING_INVALID_EXECUTION_MODE",
                    "Invalid execution_mode '" + executionMode
                            + "'. Must be one of: observe_only, recommend_only, approval_required, auto_execute.");
        }

        String activePhase = request.getActivePhase();
        if (isPresent(activePhase) && !isValidPhase(activePhase)) {
            throw new BusinessException(400, "HOSTING_INVALID_PHASE",
                    "Invalid active_phase '" + activePhase + "'. Must be one of: V1, V2, V3.");
        }
    }

    /** Validate the auto_execute_threshold lies within [0.0, 1.0] (Req 21.3). */
    void validateThreshold(BigDecimal threshold) {
        if (threshold == null) {
            return;
        }
        if (threshold.compareTo(ZERO) < 0 || threshold.compareTo(ONE) > 0) {
            throw new BusinessException(400, "HOSTING_INVALID_THRESHOLD",
                    "auto_execute_threshold must be within [0.0, 1.0] but was " + threshold.toPlainString());
        }
    }

    /**
     * Parse the raw boundary-override map (keyed by {@link SafetyBoundaryLimit} name) into a
     * typed map, rejecting unknown limit names (Req 21.4).
     */
    Map<SafetyBoundaryLimit, BigDecimal> parseBoundaryOverrides(Map<String, BigDecimal> raw) {
        Map<SafetyBoundaryLimit, BigDecimal> parsed = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return parsed;
        }
        for (Map.Entry<String, BigDecimal> entry : raw.entrySet()) {
            String key = entry.getKey();
            BigDecimal value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            SafetyBoundaryLimit limit;
            try {
                limit = SafetyBoundaryLimit.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(400, "HOSTING_INVALID_BOUNDARY",
                        "Unknown boundary limit '" + key + "' in boundary_overrides.");
            }
            parsed.put(limit, value);
        }
        return parsed;
    }

    /**
     * Validate proposed store-level boundary overrides against the resolved higher-level
     * boundary (organization + system) using the only-tighten rule and cross-field
     * constraints (Req 21.4, 12.6).
     */
    void validateBoundaryOverrides(UUID storeId, Map<SafetyBoundaryLimit, BigDecimal> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }

        SafetyBoundaryLimits.Builder proposedBuilder = SafetyBoundaryLimits.builder();
        overrides.forEach(proposedBuilder::limit);
        SafetyBoundaryLimits proposed = proposedBuilder.build();

        SafetyBoundaryLimits orgLimits = loadOrgLimits(storeId);
        SafetyBoundaryLimits systemLimits = loadSystemLimits();

        // Resolved boundary from levels strictly above the store level.
        SafetyBoundary higherLevelBoundary =
                SafetyBoundaryResolver.resolve(null, null, null, orgLimits, systemLimits);

        BoundaryValidationResult onlyTighten =
                boundaryValidator.validateOnlyTighten(proposed, SafetyBoundaryLevel.STORE_POLICY, higherLevelBoundary);

        // Effective limits = proposed (store) folded with org + system, for cross-field checks.
        SafetyBoundary effective =
                SafetyBoundaryResolver.resolve(null, null, proposed, orgLimits, systemLimits);
        BoundaryValidationResult crossField =
                boundaryValidator.validateCrossFieldConstraints(toLimits(effective));

        if (onlyTighten.valid() && crossField.valid()) {
            return;
        }

        List<BoundaryValidationResult.ConstraintViolation> violations = new ArrayList<>();
        violations.addAll(onlyTighten.violations());
        violations.addAll(crossField.violations());
        String detail = violations.isEmpty()
                ? "Boundary override violates configured constraints."
                : violations.get(0).message();
        throw new BusinessException(400, "HOSTING_BOUNDARY_VIOLATION", detail);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Persistence helpers
    // ────────────────────────────────────────────────────────────────────────────

    private HostingConfigEntity findConfig(UUID storeId, String scope, UUID scopeId) {
        LambdaQueryWrapper<HostingConfigEntity> query = new LambdaQueryWrapper<>();
        query.eq(HostingConfigEntity::getStoreId, storeId)
                .eq(HostingConfigEntity::getScope, scope)
                .eq(HostingConfigEntity::getScopeId, scopeId);
        return hostingConfigMapper.selectOne(query);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception ex) {
            log.warn("Failed to parse hosting config JSON; treating as empty. error={}", ex.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String writeConfig(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception ex) {
            throw new BusinessException(500, "HOSTING_CONFIG_SERIALIZE_FAILED",
                    "Failed to serialize hosting configuration: " + ex.getMessage());
        }
    }

    /** Overlay only the non-null fields of the request onto a copy of the existing config. */
    Map<String, Object> applyOverlay(Map<String, Object> existing, HostingConfigRequest request) {
        Map<String, Object> result = new LinkedHashMap<>(existing);
        if (isPresent(request.getActivePhase())) {
            result.put(KEY_ACTIVE_PHASE, request.getActivePhase().trim().toUpperCase(Locale.ROOT));
        }
        if (isPresent(request.getDefaultPersonality())) {
            result.put(KEY_DEFAULT_PERSONALITY, request.getDefaultPersonality().trim().toLowerCase(Locale.ROOT));
        }
        if (isPresent(request.getExecutionMode())) {
            result.put(KEY_EXECUTION_MODE, request.getExecutionMode().trim().toLowerCase(Locale.ROOT));
        }
        if (request.getAutoExecuteThreshold() != null) {
            result.put(KEY_AUTO_EXECUTE_THRESHOLD, request.getAutoExecuteThreshold());
        }
        if (request.getEmergencyAutoActionEnabled() != null) {
            result.put(KEY_EMERGENCY_AUTO_ACTION, request.getEmergencyAutoActionEnabled());
        }
        if (request.getShadowMode() != null) {
            result.put(KEY_SHADOW_MODE, request.getShadowMode());
        }
        if (request.getNotificationPreferences() != null) {
            result.put(KEY_NOTIFICATION_PREFERENCES, request.getNotificationPreferences());
        }
        if (request.getBoundaryOverrides() != null) {
            result.put(KEY_BOUNDARY_OVERRIDES, request.getBoundaryOverrides());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    HostingConfigVo toVo(UUID storeId, String scope, UUID scopeId, Map<String, Object> config) {
        Map<String, Object> notificationPreferences = null;
        Object np = config.get(KEY_NOTIFICATION_PREFERENCES);
        if (np instanceof Map) {
            notificationPreferences = (Map<String, Object>) np;
        }

        Map<String, BigDecimal> boundaryOverrides = null;
        Object bo = config.get(KEY_BOUNDARY_OVERRIDES);
        if (bo instanceof Map) {
            boundaryOverrides = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) bo).entrySet()) {
                boundaryOverrides.put(String.valueOf(e.getKey()), toBigDecimal(e.getValue()));
            }
        }

        return HostingConfigVo.builder()
                .scope(scope)
                .scopeId(scopeId.toString())
                .storeId(storeId.toString())
                .activePhase(asString(config.get(KEY_ACTIVE_PHASE)))
                .defaultPersonality(asString(config.get(KEY_DEFAULT_PERSONALITY)))
                .executionMode(asString(config.get(KEY_EXECUTION_MODE)))
                .autoExecuteThreshold(toBigDecimal(config.get(KEY_AUTO_EXECUTE_THRESHOLD)))
                .emergencyAutoActionEnabled(toBoolean(config.get(KEY_EMERGENCY_AUTO_ACTION)))
                .shadowMode(toBoolean(config.get(KEY_SHADOW_MODE)))
                .notificationPreferences(notificationPreferences)
                .boundaryOverrides(boundaryOverrides)
                .build();
    }

    private void recordAudit(UUID storeId, UUID actorId, Map<String, Object> before, Map<String, Object> after) {
        try {
            UUID orgId = resolveOrgId(storeId);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("scope", "store");
            details.put("scope_id", storeId.toString());
            details.put("before", before);
            details.put("after", after);
            auditLogService.createLog(actorId, orgId, "local_configuration",
                    "hosting_config", storeId, details);
        } catch (Exception ex) {
            // Auditing must never break the configuration write; log and continue.
            log.warn("Failed to record hosting config audit for store={}: {}", storeId, ex.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Boundary loading
    // ────────────────────────────────────────────────────────────────────────────

    private SafetyBoundaryLimits loadOrgLimits(UUID storeId) {
        UUID orgId = resolveOrgId(storeId);
        if (orgId == null) {
            return SafetyBoundaryLimits.empty();
        }
        return toLimits(safetyBoundaryMapper.findByScopeAndScopeId("organization", orgId));
    }

    private SafetyBoundaryLimits loadSystemLimits() {
        return toLimits(safetyBoundaryMapper.findSystemBoundaries());
    }

    private UUID resolveOrgId(UUID storeId) {
        StoreEntity store = storeMapper.selectById(storeId);
        return store == null ? null : store.getOrgId();
    }

    /** Convert persisted boundary rows into a partial {@link SafetyBoundaryLimits} set. */
    static SafetyBoundaryLimits toLimits(List<SafetyBoundaryEntity> rows) {
        SafetyBoundaryLimits.Builder builder = SafetyBoundaryLimits.builder();
        if (rows != null) {
            for (SafetyBoundaryEntity row : rows) {
                if (row == null || row.getLimitType() == null) {
                    continue;
                }
                SafetyBoundaryLimit limit;
                try {
                    limit = SafetyBoundaryLimit.valueOf(row.getLimitType().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    continue; // Skip unknown limit types defensively.
                }
                BigDecimal value = row.getEffectiveValue();
                if (value != null) {
                    builder.limit(limit, value);
                }
            }
        }
        return builder.build();
    }

    /** Convert a resolved {@link SafetyBoundary} back into a {@link SafetyBoundaryLimits} set. */
    static SafetyBoundaryLimits toLimits(SafetyBoundary boundary) {
        SafetyBoundaryLimits.Builder builder = SafetyBoundaryLimits.builder();
        if (boundary != null) {
            for (Map.Entry<SafetyBoundaryLimit, BigDecimal> e : boundary.values().entrySet()) {
                builder.limit(e.getKey(), e.getValue());
            }
        }
        return builder.build();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Small helpers
    // ────────────────────────────────────────────────────────────────────────────

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isValidPhase(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("V1") || normalized.equals("V2") || normalized.equals("V3");
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "store";
        }
        String normalized = scope.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("store") && !normalized.equals("goal") && !normalized.equals("campaign")) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER",
                    "Invalid scope '" + scope + "'. Must be one of: store, goal, campaign.");
        }
        return normalized;
    }

    private static void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", message);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
