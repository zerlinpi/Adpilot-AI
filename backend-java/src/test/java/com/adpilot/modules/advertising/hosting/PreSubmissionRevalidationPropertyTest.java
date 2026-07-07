package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.operation.OperationRecordService;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.TransitionEvent;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based test for {@link PreSubmissionRevalidatorImpl#revalidate}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 14: Stale or expired decisions are never submitted
 *
 * <p><b>Validates: Requirements 3.7, 33.2, 33.3, 33.4</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>An Operation whose decision has expired (past {@code decision_expires_at}) is never submitted —
 *       it is transitioned to superseded with reason DECISION_EXPIRED.</li>
 *   <li>An Operation whose pre-submission DQ gate re-check fails (stale/incomplete data) is never
 *       submitted — it is transitioned to superseded with the DQ rejection reason.</li>
 *   <li>An Operation whose pre-submission boundary re-resolution fails (proposed value outside
 *       current boundaries) is never submitted — it is transitioned to superseded with BOUNDARY_VIOLATION.</li>
 *   <li>Valid, non-expired decisions with passing DQ gates and valid boundaries CAN proceed to
 *       submission (revalidation returns valid=true).</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 14: Stale or expired decisions are never submitted")
class PreSubmissionRevalidationPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiDecisionEntity.class);
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ── Helper: build the revalidator with controlled collaborators ──────────────

    /**
     * Builds a {@link PreSubmissionRevalidatorImpl} with mocked collaborators.
     *
     * @param operation       the Operation entity to return from OperationRecordService.findById
     * @param decision        the linked AiDecisionEntity (or null if none)
     * @param dqResult        the DataQualityResult to return from the gate (null = throw exception)
     * @param throwOnDq       if true, the DataQualityGate throws a RuntimeException
     * @param decisionTtlHours the TTL configuration
     * @return a configured PreSubmissionRevalidatorImpl
     */
    @SuppressWarnings("unchecked")
    private PreSubmissionRevalidatorImpl buildRevalidator(
            OperationEntity operation,
            AiDecisionEntity decision,
            DataQualityResult dqResult,
            boolean throwOnDq,
            long decisionTtlHours) {
        return buildRevalidator(operation, decision, dqResult, throwOnDq, decisionTtlHours, null);
    }

    /**
     * Builds a {@link PreSubmissionRevalidatorImpl} with mocked collaborators and optional
     * custom boundary for testing boundary violations.
     */
    @SuppressWarnings("unchecked")
    private PreSubmissionRevalidatorImpl buildRevalidator(
            OperationEntity operation,
            AiDecisionEntity decision,
            DataQualityResult dqResult,
            boolean throwOnDq,
            long decisionTtlHours,
            SafetyBoundary customBoundary) {

        OperationRecordService operationRecordService = mock(OperationRecordService.class);
        OperationService operationService = mock(OperationService.class);
        DataQualityGate dataQualityGate = mock(DataQualityGate.class);
        AiDecisionMapper aiDecisionMapper = mock(AiDecisionMapper.class);

        // Configure OperationRecordService
        if (operation != null) {
            when(operationRecordService.findById(operation.getId()))
                    .thenReturn(Optional.of(operation));
        }

        // Configure AiDecisionMapper
        if (decision != null) {
            when(aiDecisionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(decision);
        } else {
            when(aiDecisionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(null);
        }

        // Configure DataQualityGate
        if (throwOnDq) {
            when(dataQualityGate.check(any(UUID.class), any(UUID.class)))
                    .thenThrow(new RuntimeException("Simulated DQ gate error"));
        } else if (dqResult != null) {
            when(dataQualityGate.check(any(UUID.class), any(UUID.class)))
                    .thenReturn(dqResult);
        }

        // Configure OperationService.transition to return a simple result
        when(operationService.transition(any(UUID.class), any(TransitionEvent.class)))
                .thenReturn(OperationResult.builder()
                        .operationId(operation != null ? operation.getId() : UUID.randomUUID())
                        .storeId(operation != null ? operation.getStoreId() : UUID.randomUUID())
                        .build());

        if (customBoundary != null) {
            // Return a subclass that overrides resolveCurrentBoundary to inject test boundaries
            return new PreSubmissionRevalidatorImpl(
                    operationRecordService,
                    operationService,
                    dataQualityGate,
                    aiDecisionMapper,
                    OBJECT_MAPPER,
                    decisionTtlHours) {
                @Override
                SafetyBoundary resolveCurrentBoundary() {
                    return customBoundary;
                }
            };
        }

        return new PreSubmissionRevalidatorImpl(
                operationRecordService,
                operationService,
                dataQualityGate,
                aiDecisionMapper,
                OBJECT_MAPPER,
                decisionTtlHours);
    }

    /**
     * Build a standard AI-sourced Operation with campaign entity type.
     */
    private OperationEntity buildAiOperation(UUID operationId, UUID storeId, UUID campaignId,
                                              String field, String afterValue) {
        return OperationEntity.builder()
                .id(operationId)
                .storeId(storeId)
                .operationSource("ai_hosting")
                .operationScope("platform_mutation")
                .entityType("campaign")
                .entityId(campaignId)
                .field(field)
                .afterValue(afterValue)
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("test-key-" + operationId)
                .attemptId(UUID.randomUUID())
                .syncState("pending")
                .build();
    }

    /**
     * Build an AiDecisionEntity linked to a given Operation with specified expiry.
     */
    private AiDecisionEntity buildDecision(UUID operationId, UUID storeId, UUID campaignId,
                                            LocalDateTime expiresAt) {
        return AiDecisionEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .campaignId(campaignId)
                .engine("v2_budget")
                .decisionType("budget_adjustment")
                .executionMode("auto_execute")
                .riskScore(BigDecimal.valueOf(0.15))
                .routingOutcome("pending")
                .promotedOperationId(operationId)
                .decisionSnapshot("{\"version\":1}")
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();
    }

    // ── Property 1: Expired decision → never submitted, superseded ──────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 14: Stale or expired decisions are never submitted
     *
     * <p><b>Validates: Requirements 33.1, 33.3</b></p>
     *
     * <p>When an Operation's linked AI decision has an {@code expires_at} that has passed,
     * the revalidation MUST fail with reason DECISION_EXPIRED and the Operation transitions
     * to superseded. The decision is never submitted to the platform.</p>
     */
    @Property(tries = 150)
    void expiredDecisionIsNeverSubmitted(
            @ForAll @IntRange(min = 1, max = 72) int hoursExpiredAgo,
            @ForAll @IntRange(min = 1, max = 12) int ttlHours) {

        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        OperationEntity operation = buildAiOperation(operationId, storeId, campaignId,
                "daily_budget", "50.00");

        // The decision expired `hoursExpiredAgo` hours in the past
        LocalDateTime expiresAt = LocalDateTime.now().minusHours(hoursExpiredAgo);
        AiDecisionEntity decision = buildDecision(operationId, storeId, campaignId, expiresAt);

        PreSubmissionRevalidatorImpl revalidator = buildRevalidator(
                operation, decision, DataQualityResult.pass(), false, ttlHours);

        PreSubmissionRevalidator.RevalidationResult result = revalidator.revalidate(operationId);

        assertThat(result.valid())
                .as("Expired decision (expired %d hours ago) must not be valid for submission",
                        hoursExpiredAgo)
                .isFalse();
        assertThat(result.reason())
                .as("Expired decision rejection must have reason DECISION_EXPIRED")
                .isEqualTo("DECISION_EXPIRED");
    }

    // ── Property 2: DQ gate failure → never submitted ───────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 14: Stale or expired decisions are never submitted
     *
     * <p><b>Validates: Requirements 3.7, 33.2</b></p>
     *
     * <p>When the pre-submission DQ gate re-check fails (DATA_STALE or DATA_INCOMPLETE),
     * the Operation must not be submitted. It is transitioned to superseded.</p>
     */
    @Property(tries = 150)
    void dqGateFailureIsNeverSubmitted(
            @ForAll("dqFailureReasons") String dqReason,
            @ForAll @IntRange(min = 1, max = 12) int ttlHours) {

        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        OperationEntity operation = buildAiOperation(operationId, storeId, campaignId,
                "daily_budget", "50.00");

        // Decision is NOT expired (still valid)
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(2);
        AiDecisionEntity decision = buildDecision(operationId, storeId, campaignId, expiresAt);

        // DQ gate returns a failure result
        DataQualityResult dqResult = new DataQualityResult(false, dqReason);

        PreSubmissionRevalidatorImpl revalidator = buildRevalidator(
                operation, decision, dqResult, false, ttlHours);

        PreSubmissionRevalidator.RevalidationResult result = revalidator.revalidate(operationId);

        assertThat(result.valid())
                .as("DQ gate failure (%s) must prevent submission", dqReason)
                .isFalse();
        assertThat(result.reason())
                .as("DQ failure reason must be passed through")
                .isEqualTo(dqReason);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 14: Stale or expired decisions are never submitted
     *
     * <p><b>Validates: Requirements 3.7, 33.2, 33.4</b></p>
     *
     * <p>When the DQ gate throws a runtime exception (e.g., database unreachable after outage),
     * the revalidator operates fail-closed: the Operation is NOT submitted. This guarantees
     * that after connectivity is restored, accumulated decisions are not blindly submitted (Req 33.4).</p>
     */
    @Property(tries = 100)
    void dqGateExceptionFailsClosed(
            @ForAll @IntRange(min = 1, max = 12) int ttlHours) {

        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        OperationEntity operation = buildAiOperation(operationId, storeId, campaignId,
                "daily_budget", "50.00");

        // Decision is NOT expired
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(2);
        AiDecisionEntity decision = buildDecision(operationId, storeId, campaignId, expiresAt);

        // DQ gate throws an exception (simulating DB error / unreachable service)
        PreSubmissionRevalidatorImpl revalidator = buildRevalidator(
                operation, decision, null, true, ttlHours);

        PreSubmissionRevalidator.RevalidationResult result = revalidator.revalidate(operationId);

        assertThat(result.valid())
                .as("DQ gate exception must fail-closed (never pass)")
                .isFalse();
        assertThat(result.reason())
                .as("DQ gate exception must report an error reason")
                .isNotNull();
    }

    // ── Property 3: Boundary violation → never submitted ────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 14: Stale or expired decisions are never submitted
     *
     * <p><b>Validates: Requirements 33.2</b></p>
     *
     * <p>When the pre-submission boundary re-resolution finds the proposed value outside
     * the current safety boundaries, the Operation must not be submitted. It is transitioned
     * to superseded with a BOUNDARY_VIOLATION reason.</p>
     */
    @Property(tries = 150)
    void boundaryViolationIsNeverSubmitted(
            @ForAll("outOfBoundsBidValues") BigDecimal outOfBoundsValue,
            @ForAll @IntRange(min = 1, max = 12) int ttlHours) {

        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        // The after_value is outside the boundary limits
        OperationEntity operation = buildAiOperation(operationId, storeId, campaignId,
                "bid", outOfBoundsValue.toPlainString());

        // Decision is NOT expired
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(2);
        AiDecisionEntity decision = buildDecision(operationId, storeId, campaignId, expiresAt);

        // Build a boundary with explicit min/max bid: MIN_BID=1.00, MAX_BID=50.00
        SafetyBoundary restrictiveBoundary = SafetyBoundaryResolver.resolve(
                SafetyBoundaryLimits.builder()
                        .minBid(BigDecimal.valueOf(1.00))
                        .maxBid(BigDecimal.valueOf(50.00))
                        .build(),
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty());

        // DQ gate passes, boundary is violated
        PreSubmissionRevalidatorImpl revalidator = buildRevalidator(
                operation, decision, DataQualityResult.pass(), false, ttlHours, restrictiveBoundary);

        PreSubmissionRevalidator.RevalidationResult result = revalidator.revalidate(operationId);

        assertThat(result.valid())
                .as("Out-of-bounds bid value %s (boundary MIN=1.00 MAX=50.00) must be rejected",
                        outOfBoundsValue)
                .isFalse();
        assertThat(result.reason())
                .as("Boundary rejection reason must start with BOUNDARY_VIOLATION")
                .startsWith("BOUNDARY_VIOLATION");
    }

    // ── Property 4: Valid, non-expired, passing DQ → CAN proceed ────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 14: Stale or expired decisions are never submitted
     *
     * <p><b>Validates: Requirements 3.7, 33.2, 33.3, 33.4</b></p>
     *
     * <p>When an Operation's decision is not expired, the DQ gate passes, and the proposed
     * value is within safety boundaries, the revalidation succeeds and the Operation CAN
     * be submitted to the platform.</p>
     */
    @Property(tries = 150)
    void validNonExpiredDecisionCanProceed(
            @ForAll("withinBoundsBidValues") BigDecimal withinBoundsValue,
            @ForAll @IntRange(min = 1, max = 48) int hoursUntilExpiry,
            @ForAll @IntRange(min = 1, max = 12) int ttlHours) {

        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        OperationEntity operation = buildAiOperation(operationId, storeId, campaignId,
                "bid", withinBoundsValue.toPlainString());

        // Decision is still valid (expires in the future)
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(hoursUntilExpiry);
        AiDecisionEntity decision = buildDecision(operationId, storeId, campaignId, expiresAt);

        // DQ gate passes
        PreSubmissionRevalidatorImpl revalidator = buildRevalidator(
                operation, decision, DataQualityResult.pass(), false, ttlHours);

        PreSubmissionRevalidator.RevalidationResult result = revalidator.revalidate(operationId);

        assertThat(result.valid())
                .as("Valid decision (expires in %d hours, bid=%s within bounds) must pass revalidation",
                        hoursUntilExpiry, withinBoundsValue)
                .isTrue();
        assertThat(result.reason())
                .as("Passing revalidation must have null reason")
                .isNull();
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 14: Stale or expired decisions are never submitted
     *
     * <p><b>Validates: Requirements 33.3, 33.4</b></p>
     *
     * <p>Non-numeric fields (state changes, keyword text) bypass boundary checks entirely
     * and can proceed if the decision is valid and DQ passes.</p>
     */
    @Property(tries = 100)
    void nonNumericFieldsBypassBoundaryCheck(
            @ForAll("nonNumericAfterValues") String afterValue,
            @ForAll @IntRange(min = 1, max = 48) int hoursUntilExpiry,
            @ForAll @IntRange(min = 1, max = 12) int ttlHours) {

        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        OperationEntity operation = buildAiOperation(operationId, storeId, campaignId,
                "state", afterValue);

        // Decision is still valid
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(hoursUntilExpiry);
        AiDecisionEntity decision = buildDecision(operationId, storeId, campaignId, expiresAt);

        // DQ gate passes
        PreSubmissionRevalidatorImpl revalidator = buildRevalidator(
                operation, decision, DataQualityResult.pass(), false, ttlHours);

        PreSubmissionRevalidator.RevalidationResult result = revalidator.revalidate(operationId);

        assertThat(result.valid())
                .as("Non-numeric field change (state=%s) must pass revalidation when decision is valid and DQ passes",
                        afterValue)
                .isTrue();
    }

    // ── Property 5: Non-AI operations always pass ───────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 14: Stale or expired decisions are never submitted
     *
     * <p><b>Validates: Requirements 33.2, 33.3</b></p>
     *
     * <p>Operations with a non-AI source (manual, recommendation, etc.) bypass revalidation
     * entirely and always pass. Only AI-sourced operations are subject to expiry/DQ/boundary checks.</p>
     */
    @Property(tries = 100)
    void nonAiOperationsAlwaysPass(
            @ForAll("nonAiSources") String operationSource,
            @ForAll @IntRange(min = 1, max = 12) int ttlHours) {

        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        OperationEntity operation = OperationEntity.builder()
                .id(operationId)
                .storeId(storeId)
                .operationSource(operationSource)
                .operationScope("platform_mutation")
                .entityType("campaign")
                .entityId(campaignId)
                .field("bid")
                .afterValue("999.99") // Would violate boundaries if checked
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("test-key-" + operationId)
                .attemptId(UUID.randomUUID())
                .syncState("pending")
                .build();

        // Even with an expired decision and failed DQ gate, non-AI operations pass
        LocalDateTime expiresAt = LocalDateTime.now().minusHours(10);
        AiDecisionEntity decision = buildDecision(operationId, storeId, campaignId, expiresAt);

        PreSubmissionRevalidatorImpl revalidator = buildRevalidator(
                operation, decision, DataQualityResult.stale(), false, ttlHours);

        PreSubmissionRevalidator.RevalidationResult result = revalidator.revalidate(operationId);

        assertThat(result.valid())
                .as("Non-AI operation (source=%s) must always pass revalidation", operationSource)
                .isTrue();
    }

    // ── Generators ───────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<String> dqFailureReasons() {
        return Arbitraries.of(DataQualityResult.DATA_STALE, DataQualityResult.DATA_INCOMPLETE);
    }

    @Provide
    Arbitrary<BigDecimal> outOfBoundsBidValues() {
        // Values that violate the test boundary limits:
        // MIN_BID = 1.00, MAX_BID = 50.00
        return Arbitraries.oneOf(
                // Below minimum bid (< 1.00)
                Arbitraries.bigDecimals()
                        .between(BigDecimal.valueOf(-10.0), BigDecimal.valueOf(0.99))
                        .ofScale(2),
                // Above maximum bid (> 50.00)
                Arbitraries.bigDecimals()
                        .between(BigDecimal.valueOf(50.01), BigDecimal.valueOf(9999.99))
                        .ofScale(2)
        );
    }

    @Provide
    Arbitrary<BigDecimal> withinBoundsBidValues() {
        // Values within the test boundary (MIN_BID=1.00, MAX_BID=50.00)
        // For the "valid non-expired" test, the boundary resolution returns empty (no limits),
        // so any value passes. We use values that would also pass restrictive boundaries.
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(1.00), BigDecimal.valueOf(50.00))
                .ofScale(2);
    }

    @Provide
    Arbitrary<String> nonNumericAfterValues() {
        return Arbitraries.of(
                "\"ENABLED\"",
                "\"PAUSED\"",
                "\"exact_keyword_text\"",
                "\"negative_keyword_text\""
        );
    }

    @Provide
    Arbitrary<String> nonAiSources() {
        return Arbitraries.of("manual", "recommendation", "one_click_optimize", "creation");
    }
}
