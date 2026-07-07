package com.adpilot.modules.advertising.operation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.operation.alert.WriteBackAlerting;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the approval-gate routing of the
 * {@link OperationServiceImpl#createOperation} pipeline against the seeded
 * {@code personality_policies} system defaults (task 13.2 / 13.5).
 *
 * <p>Feature: advertising-workspace-rework, Property 52: Approval gate triggers below the maximum.
 *
 * <p>Validates: Requirements 22.8, 49.5, 49.19, 4.1.
 *
 * <p>Property 52 (transcribed from the design's Correctness Properties section): <em>For any change
 * whose absolute change ratio is greater than or equal to the applicable approval threshold
 * ({@code approvalBidChangeRatio} / {@code approvalBudgetChangeRatio}), the Operation transitions to
 * {@code awaiting_approval}; and for every personality each approval ratio is strictly less than its
 * corresponding maximum ratio, so approval can trigger before the maximum is reached.</em>
 *
 * <p>The {@link PersonalityPolicy} fixtures below are the exact Requirement 49.5 system-default rows
 * seeded into {@code personality_policies} (see {@code db/schema.sql}). Each iteration:
 *
 * <ol>
 *   <li>asserts the data invariant (clause two) — for the chosen personality and adjustment kind the
 *       applicable approval ratio is <em>strictly less than</em> its corresponding maximum ratio, so
 *       the interval {@code [approvalRatio, maxRatio)} is non-empty and approval can fire before the
 *       maximum is reached (Req 49.5);</li>
 *   <li>computes the absolute change ratio from a generated before/after pair exactly as the AI
 *       hosting optimizer does ({@code |after - before| / before}, scale 6, HALF_UP), evaluates the
 *       approval predicate ({@code ratio >= approvalRatio}), and drives a write-capable
 *       {@code platform_mutation} through {@link OperationServiceImpl#createOperation};</li>
 *   <li>asserts the routing (clause one) — the resolved Sync_State is exactly
 *       {@code awaiting_approval} when the ratio meets/exceeds the approval threshold and exactly
 *       {@code pending} when it is below, and that a held ({@code awaiting_approval}) Operation writes
 *       NO Outbox entry while a {@code pending} one does (Req 4.1, 22.8);</li>
 *   <li>whenever the ratio lands in {@code [approvalRatio, maxRatio)} it additionally asserts the
 *       Operation is held even though the change is still strictly below the maximum — the literal
 *       meaning of "approval gate triggers below the maximum" (Req 49.19).</li>
 * </ol>
 *
 * <p>The {@link SecurityContextHolder} is left empty so the acting user resolves to the reserved
 * system actor and per-user data-scope validation is skipped, keeping the focus on the approval gate.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 52: Approval gate triggers below the maximum")
class ApprovalGateTriggersPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** Production-faithful base value for the change-ratio math (any positive value works). */
    private static final BigDecimal BASE_VALUE = new BigDecimal("100.000000");

    /**
     * A Requirement 49.5 seeded system-default Personality_Policy row, reduced to the numeric fields
     * the approval gate consults. These mirror {@code db/schema.sql} verbatim.
     */
    private record PersonalityPolicy(String personality,
                                     BigDecimal approvalBidChangeRatio,
                                     BigDecimal approvalBudgetChangeRatio,
                                     BigDecimal maxBidIncreaseRatio,
                                     BigDecimal maxBidDecreaseRatio,
                                     BigDecimal maxDailyBudgetIncreaseRatio) {}

    private static final PersonalityPolicy CONSERVATIVE = new PersonalityPolicy(
            "conservative",
            ratio("0.030000"), ratio("0.030000"),
            ratio("0.050000"), ratio("0.100000"), ratio("0.050000"));

    private static final PersonalityPolicy BALANCED = new PersonalityPolicy(
            "balanced",
            ratio("0.070000"), ratio("0.100000"),
            ratio("0.100000"), ratio("0.150000"), ratio("0.150000"));

    private static final PersonalityPolicy AGGRESSIVE = new PersonalityPolicy(
            "aggressive",
            ratio("0.150000"), ratio("0.200000"),
            ratio("0.200000"), ratio("0.250000"), ratio("0.300000"));

    /** The adjustment kind decides which approval threshold + maximum ratio apply (Req 22.8/49.5). */
    private enum Kind { BID_INCREASE, BID_DECREASE, BUDGET_INCREASE }

    /** A single generated approval-gate scenario. */
    private record Scenario(PersonalityPolicy policy, Kind kind, int ratioThousandths) {}

    /** A constructed service paired with the Outbox mock the assertions inspect. */
    private record Harness(OperationServiceImpl service, OperationOutboxMapper outboxMapper) {}

    /**
     * Feature: advertising-workspace-rework, Property 52: Approval gate triggers below the maximum.
     *
     * <p>Validates: Requirements 22.8, 49.5, 49.19, 4.1.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 52: change ratio >= approval threshold (and < maximum) holds the Operation for approval")
    void approvalGateTriggersAtThresholdBelowMaximum(@ForAll("scenarios") Scenario scenario) {
        SecurityContextHolder.clearContext();

        PersonalityPolicy policy = scenario.policy();
        Kind kind = scenario.kind();

        BigDecimal approvalRatio = approvalRatio(policy, kind);
        BigDecimal maxRatio = maxRatio(policy, kind);

        // --- Clause two (Req 49.5): for every personality each approval ratio is STRICTLY LESS than
        //     its corresponding maximum ratio, so approval can trigger before the maximum is reached.
        assertThat(approvalRatio)
                .as("[%s/%s] approval ratio must be strictly below its maximum ratio so the gate "
                        + "can fire before the maximum is reached", policy.personality(), kind)
                .isLessThan(maxRatio);

        // Build a before/after pair whose absolute change ratio is exactly ratioThousandths/1000,
        // then derive the applied magnitude EXACTLY as AiHostingOptimizer does.
        BigDecimal changeRatio = BigDecimal.valueOf(scenario.ratioThousandths(), 3);
        BigDecimal before = BASE_VALUE;
        BigDecimal after = kind == Kind.BID_DECREASE
                ? before.subtract(before.multiply(changeRatio))
                : before.add(before.multiply(changeRatio));
        BigDecimal appliedMagnitude = after.subtract(before).abs()
                .divide(before, 6, RoundingMode.HALF_UP);

        // The approval predicate evaluated by the optimizer / approval-threshold evaluation step.
        boolean approvalRequired = appliedMagnitude.compareTo(approvalRatio) >= 0;

        // --- Drive the real createOperation routing for a write-capable platform_mutation. --------
        Harness harness = newService(/* writeCapable = */ true);

        OperationResult result = harness.service().createOperation(CreateOperationCommand.builder()
                .storeId(UUID.randomUUID())
                .operationSource(OperationSource.AI_HOSTING)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .field(kind == Kind.BUDGET_INCREASE ? "budget" : "bid")
                .beforeValue(before)
                .afterValue(after)
                .approvalRequired(approvalRequired)
                .personalityRuleVersion("v1")
                .logicalIdempotencyKey(UUID.randomUUID().toString())
                .build());

        // --- Clause one (Req 4.1, 22.8): the gate routes exactly by the approval predicate. --------
        if (approvalRequired) {
            assertThat(result.getSyncState())
                    .as("[%s/%s] ratio %s >= approval %s must hold the Operation for approval",
                            policy.personality(), kind, appliedMagnitude, approvalRatio)
                    .isEqualTo(SyncState.AWAITING_APPROVAL);
            // A held Operation is NOT submitted: no Outbox entry is written (Req 22.8).
            verify(harness.outboxMapper(), never()).insert(any());
        } else {
            assertThat(result.getSyncState())
                    .as("[%s/%s] ratio %s < approval %s must proceed directly to pending",
                            policy.personality(), kind, appliedMagnitude, approvalRatio)
                    .isEqualTo(SyncState.PENDING);
            // A pending, write-capable platform_mutation enqueues exactly one Outbox entry.
            verify(harness.outboxMapper(), times(1)).insert(any());
        }

        // --- "Triggers BELOW the maximum" (Req 49.19): a ratio in [approvalRatio, maxRatio) is still
        //     held for approval even though it has not reached the maximum permitted ratio. ----------
        if (appliedMagnitude.compareTo(approvalRatio) >= 0
                && appliedMagnitude.compareTo(maxRatio) < 0) {
            assertThat(result.getSyncState())
                    .as("[%s/%s] ratio %s is below the maximum %s yet must already require approval",
                            policy.personality(), kind, appliedMagnitude, maxRatio)
                    .isEqualTo(SyncState.AWAITING_APPROVAL);
        }
    }

    // --- service wiring (mirrors OperationServiceImplTest / Property 2) ---------------------------

    /**
     * Build an {@link OperationServiceImpl} wired with mocked collaborators (mirroring
     * {@code OperationServiceImplTest}). {@code record()} echoes a persisted entity reflecting the
     * resolved Sync_State so {@link OperationResult#from} surfaces it.
     */
    private Harness newService(boolean writeCapable) {
        PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
        DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);
        InFlightConflictLock inFlightConflictLock = Mockito.mock(InFlightConflictLock.class);
        IdempotencyService idempotencyService = Mockito.mock(IdempotencyService.class);
        EntityVersionGuard entityVersionGuard = Mockito.mock(EntityVersionGuard.class);
        WriteCapabilityService writeCapabilityService = Mockito.mock(WriteCapabilityService.class);
        OperationRecordService operationRecordService = Mockito.mock(OperationRecordService.class);
        OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
        OperationStateMachine stateMachine = new OperationStateMachine();
        ConfirmedValueWriter confirmedValueWriter = Mockito.mock(ConfirmedValueWriter.class);
        OperationPendingChangeMapper pendingChangeMapper = Mockito.mock(OperationPendingChangeMapper.class);
        OperationOutboxMapper outboxMapper = Mockito.mock(OperationOutboxMapper.class);
        PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

        when(writeCapabilityService.isWriteCapable(any())).thenReturn(writeCapable);
        when(inFlightConflictLock.findInFlightOperation(any(), any(), any())).thenReturn(Optional.empty());
        when(idempotencyService.findLogicalOperation(any(), any())).thenReturn(Optional.empty());
        when(operationRecordService.record(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
        // A write-capable Store resolves to a connected platform connection when the pending,
        // submission-ready branch writes the Outbox row (Req 6.1) — irrelevant to a held Operation.
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connectedConnection()));

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                new OperationJsonCodec(new ObjectMapper()),
                Mockito.mock(WriteBackAlerting.class));
        return new Harness(service, outboxMapper);
    }

    private static PlatformConnectionEntity connectedConnection() {
        return PlatformConnectionEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .platform("amazon")
                .status("connected")
                .build();
    }

    private static OperationEntity persist(OperationRecordCommand c) {
        return OperationEntity.builder()
                .storeId(c.getStoreId())
                .operationSource(OperationMachineValues.toValue(c.getOperationSource()))
                .operationScope(OperationMachineValues.toValue(c.getOperationScope()))
                .entityType(c.getEntityType())
                .entityId(c.getEntityId())
                .field(c.getField())
                .logicalOperationId(c.getLogicalOperationId())
                .logicalIdempotencyKey(c.getLogicalIdempotencyKey())
                .attemptId(c.getAttemptId())
                .syncState(OperationMachineValues.toValue(c.getSyncState()))
                .executionStatus(OperationMachineValues.toValue(c.getExecutionStatus()))
                .build();
    }

    // --- threshold/maximum selection -------------------------------------------------------------

    private static BigDecimal approvalRatio(PersonalityPolicy p, Kind kind) {
        return kind == Kind.BUDGET_INCREASE ? p.approvalBudgetChangeRatio() : p.approvalBidChangeRatio();
    }

    private static BigDecimal maxRatio(PersonalityPolicy p, Kind kind) {
        return switch (kind) {
            case BID_INCREASE -> p.maxBidIncreaseRatio();
            case BID_DECREASE -> p.maxBidDecreaseRatio();
            case BUDGET_INCREASE -> p.maxDailyBudgetIncreaseRatio();
        };
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Generates a (seeded personality, adjustment kind, change ratio) scenario. The change ratio in
     * thousandths spans {@code 0.000 .. 0.400}, which straddles every personality's approval ratio
     * and maximum ratio, so the below-approval, {@code [approval, max)}, and at/above-maximum regions
     * are all exercised.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<PersonalityPolicy> policies = Arbitraries.of(CONSERVATIVE, BALANCED, AGGRESSIVE);
        Arbitrary<Kind> kinds = Arbitraries.of(Kind.values());
        Arbitrary<Integer> ratios = Arbitraries.integers().between(0, 400);
        return Combinators.combine(policies, kinds, ratios).as(Scenario::new);
    }

    private static BigDecimal ratio(String value) {
        return new BigDecimal(value);
    }
}
