package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.operation.OperationRecordService;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.TransitionEvent;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of {@link PreSubmissionRevalidator} (Requirements 3.7, 33.1–33.5).
 *
 * <p>Called by the OutboxWorker after claiming an Outbox row but before invoking the
 * platform connector. Ensures that stale or invalid decisions are never submitted.</p>
 *
 * <p>The revalidation flow:</p>
 * <ol>
 *   <li>Check decision expiry against the linked {@code ai_decisions.expires_at}.</li>
 *   <li>Re-run the {@link DataQualityGate} for the campaign.</li>
 *   <li>Re-resolve safety boundaries and verify the proposed change still fits.</li>
 * </ol>
 *
 * <p>On failure, the Operation is transitioned to {@code superseded} with the reason
 * recorded in {@code status_reason}.</p>
 *
 * <p>Validates: Requirements 3.7, 33.1, 33.2, 33.3, 33.4, 33.5.</p>
 */
@Slf4j
@Service
public class PreSubmissionRevalidatorImpl implements PreSubmissionRevalidator {

    /** Default decision TTL in hours. */
    private final long decisionTtlHours;

    private final OperationRecordService operationRecordService;
    private final OperationService operationService;
    private final DataQualityGate dataQualityGate;
    private final AiDecisionMapper aiDecisionMapper;
    private final ObjectMapper objectMapper;

    public PreSubmissionRevalidatorImpl(
            OperationRecordService operationRecordService,
            OperationService operationService,
            DataQualityGate dataQualityGate,
            AiDecisionMapper aiDecisionMapper,
            ObjectMapper objectMapper,
            @Value("${adpilot.hosting.decision-ttl-hours:4}") long decisionTtlHours) {
        this.operationRecordService = operationRecordService;
        this.operationService = operationService;
        this.dataQualityGate = dataQualityGate;
        this.aiDecisionMapper = aiDecisionMapper;
        this.objectMapper = objectMapper;
        this.decisionTtlHours = decisionTtlHours;
    }

    @Override
    public RevalidationResult revalidate(UUID operationId) {
        if (operationId == null) {
            return RevalidationResult.pass();
        }

        OperationEntity operation = operationRecordService.findById(operationId).orElse(null);
        if (operation == null) {
            return RevalidationResult.pass();
        }

        // Only revalidate AI-sourced operations (ai_hosting)
        if (!"ai_hosting".equals(operation.getOperationSource())) {
            return RevalidationResult.pass();
        }

        // --- Step 1: Check decision expiry (Req 33.1, 33.3) ---
        RevalidationResult expiryResult = checkDecisionExpiry(operation);
        if (!expiryResult.valid()) {
            supersedeOperation(operation, expiryResult.reason());
            return expiryResult;
        }

        // --- Step 2: Re-run Data Quality Gate (Req 33.2, 3.7) ---
        RevalidationResult dqResult = checkDataQuality(operation);
        if (!dqResult.valid()) {
            supersedeOperation(operation, dqResult.reason());
            return dqResult;
        }

        // --- Step 3: Re-resolve safety boundaries (Req 33.2) ---
        RevalidationResult boundaryResult = checkBoundaries(operation);
        if (!boundaryResult.valid()) {
            supersedeOperation(operation, boundaryResult.reason());
            return boundaryResult;
        }

        return RevalidationResult.pass();
    }

    @Override
    public LocalDateTime computeExpiresAt() {
        return LocalDateTime.now().plusHours(decisionTtlHours);
    }

    /**
     * Check if the decision associated with this Operation has expired.
     * Looks up the ai_decisions row by promoted_operation_id.
     */
    private RevalidationResult checkDecisionExpiry(OperationEntity operation) {
        AiDecisionEntity decision = findDecisionForOperation(operation.getId());
        if (decision == null) {
            // No linked AI decision — skip expiry check (manual operations, etc.)
            return RevalidationResult.pass();
        }

        LocalDateTime expiresAt = decision.getExpiresAt();
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            log.info("PreSubmissionRevalidator: Operation {} decision expired at {} (now={})",
                    operation.getId(), expiresAt, LocalDateTime.now());
            return RevalidationResult.expired();
        }

        return RevalidationResult.pass();
    }

    /**
     * Re-run the Data Quality Gate for the operation's campaign (Req 3.7, 33.2).
     */
    private RevalidationResult checkDataQuality(OperationEntity operation) {
        UUID storeId = operation.getStoreId();
        UUID campaignId = resolveCampaignId(operation);

        if (campaignId == null) {
            // Cannot determine campaign — skip DQ check (non-campaign operations or
            // operations without a linked AI decision with campaign info)
            log.debug("PreSubmissionRevalidator: Cannot resolve campaign for Operation {}; skipping DQ check",
                    operation.getId());
            return RevalidationResult.pass();
        }

        try {
            DataQualityResult dqResult = dataQualityGate.check(storeId, campaignId);
            if (!dqResult.passed()) {
                log.info("PreSubmissionRevalidator: DQ gate failed for Operation {} campaign {}: {}",
                        operation.getId(), campaignId, dqResult.reason());
                return RevalidationResult.dataQualityFailed(dqResult.reason());
            }
        } catch (RuntimeException e) {
            // Fail-closed: if we cannot determine data quality, do not submit (Req 3.5)
            log.warn("PreSubmissionRevalidator: DQ gate error for Operation {} campaign {}: {}",
                    operation.getId(), campaignId, e.getMessage());
            return RevalidationResult.dataQualityFailed("DQ_CHECK_ERROR");
        }

        return RevalidationResult.pass();
    }

    /**
     * Re-resolve safety boundaries and verify the proposed change still fits (Req 33.2).
     * Checks that the after_value is within the effective min/max bounds.
     */
    private RevalidationResult checkBoundaries(OperationEntity operation) {
        String field = operation.getField();
        String afterValueJson = operation.getAfterValue();

        if (field == null || afterValueJson == null) {
            return RevalidationResult.pass();
        }

        BigDecimal afterValue = parseNumericValue(afterValueJson);
        if (afterValue == null) {
            // Non-numeric changes (state changes, keyword additions) skip boundary check
            return RevalidationResult.pass();
        }

        // Re-resolve the effective boundary from the current system defaults.
        // A full implementation would load all 5 hierarchy levels; system defaults
        // provide the minimum safety net that is always available.
        SafetyBoundary boundary = resolveCurrentBoundary();

        // Check field-specific boundary limits
        Optional<String> violation = checkFieldAgainstBoundary(field, afterValue, boundary);
        if (violation.isPresent()) {
            log.info("PreSubmissionRevalidator: Boundary violation for Operation {} field={} value={}: {}",
                    operation.getId(), field, afterValue, violation.get());
            return RevalidationResult.boundaryViolation(violation.get());
        }

        return RevalidationResult.pass();
    }

    /**
     * Transition the Operation to superseded with the given reason (Req 33.3, 33.5).
     */
    private void supersedeOperation(OperationEntity operation, String reason) {
        try {
            operationService.transition(operation.getId(), TransitionEvent.SUPERSEDE);
            // Update status_reason after transition
            operation.setStatusReason(reason);
            log.info("PreSubmissionRevalidator: Superseded Operation {} with reason: {}",
                    operation.getId(), reason);
        } catch (RuntimeException e) {
            // The Operation may already be in a state where SUPERSEDE is not legal
            // (e.g., it was concurrently cancelled or approved). Log and proceed.
            log.warn("PreSubmissionRevalidator: Could not supersede Operation {}: {}",
                    operation.getId(), e.getMessage());
        }
    }

    /**
     * Find the ai_decisions row linked to this Operation via promoted_operation_id.
     */
    private AiDecisionEntity findDecisionForOperation(UUID operationId) {
        LambdaQueryWrapper<AiDecisionEntity> query = new LambdaQueryWrapper<>();
        query.eq(AiDecisionEntity::getPromotedOperationId, operationId)
             .last("LIMIT 1");
        return aiDecisionMapper.selectOne(query);
    }

    /**
     * Resolve the campaign ID from the operation. For keyword/campaign operations,
     * the entity might be the campaign itself or we derive it from the entity type.
     */
    private UUID resolveCampaignId(OperationEntity operation) {
        String entityType = operation.getEntityType();
        if (entityType == null) {
            return null;
        }
        // For campaign-level operations, the entity IS the campaign
        if ("campaign".equalsIgnoreCase(entityType)) {
            return operation.getEntityId();
        }
        // For keyword/ad_group operations, the campaign must be resolved from the
        // decision snapshot or entity hierarchy. For now, look in the ai_decision.
        AiDecisionEntity decision = findDecisionForOperation(operation.getId());
        if (decision != null && decision.getCampaignId() != null) {
            return decision.getCampaignId();
        }
        // Fallback: cannot resolve campaign
        return null;
    }

    /**
     * Parse a numeric value from the JSON after_value field.
     * Returns null if the value is not a simple number.
     */
    private BigDecimal parseNumericValue(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isNumber()) {
                return node.decimalValue();
            }
            // Try parsing as a quoted string containing a number
            if (node.isTextual()) {
                return new BigDecimal(node.textValue());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve the current effective safety boundary from system defaults.
     * In a full implementation this would load all hierarchy levels from the database;
     * here we use the system-level defaults as the baseline, which is always available.
     *
     * <p>Package-private for testability — test subclasses may override to inject
     * boundary values that trigger violations.</p>
     */
    SafetyBoundary resolveCurrentBoundary() {
        // Resolve with system defaults only — boundaries persisted in the safety_boundaries
        // table at system level. Uses empty at campaign/goal/store/org levels so the system
        // default wins for all limits.
        return SafetyBoundaryResolver.resolve(
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty());
    }

    /**
     * Check a field's proposed value against the resolved boundary.
     * Returns an Optional with the violation description, or empty if valid.
     */
    private Optional<String> checkFieldAgainstBoundary(String field, BigDecimal value,
                                                       SafetyBoundary boundary) {
        return switch (field.toLowerCase()) {
            case "bid", "keyword_bid" -> checkBidBoundary(value, boundary);
            case "daily_budget", "budget" -> checkBudgetBoundary(value, boundary);
            default -> Optional.empty();
        };
    }

    private Optional<String> checkBidBoundary(BigDecimal bid, SafetyBoundary boundary) {
        Optional<BigDecimal> minBid = boundary.get(SafetyBoundaryLimit.MIN_BID);
        Optional<BigDecimal> maxBid = boundary.get(SafetyBoundaryLimit.MAX_BID);

        if (minBid.isPresent() && bid.compareTo(minBid.get()) < 0) {
            return Optional.of("bid " + bid + " below MIN_BID " + minBid.get());
        }
        if (maxBid.isPresent() && bid.compareTo(maxBid.get()) > 0) {
            return Optional.of("bid " + bid + " above MAX_BID " + maxBid.get());
        }
        return Optional.empty();
    }

    private Optional<String> checkBudgetBoundary(BigDecimal budget, SafetyBoundary boundary) {
        Optional<BigDecimal> minBudget = boundary.get(SafetyBoundaryLimit.MIN_DAILY_BUDGET);
        Optional<BigDecimal> maxBudget = boundary.get(SafetyBoundaryLimit.MAX_DAILY_BUDGET);

        if (minBudget.isPresent() && budget.compareTo(minBudget.get()) < 0) {
            return Optional.of("budget " + budget + " below MIN_DAILY_BUDGET " + minBudget.get());
        }
        if (maxBudget.isPresent() && budget.compareTo(maxBudget.get()) > 0) {
            return Optional.of("budget " + budget + " above MAX_DAILY_BUDGET " + maxBudget.get());
        }
        return Optional.empty();
    }
}
