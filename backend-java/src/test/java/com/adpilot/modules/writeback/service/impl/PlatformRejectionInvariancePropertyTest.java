package com.adpilot.modules.writeback.service.impl;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.RecommendationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.RecommendationMapper;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationWriteBack;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.writeback.vo.WriteBackResultVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the rejection invariance of {@link WriteBackServiceImpl#apply(String)}
 * after its reconciliation onto the generic Operation_Write_Back delegation (tasks 11.1 / 11.2).
 *
 * <p>Feature: advertising-workspace-rework, Property 25: Recommendations are never marked effective
 * without an effective Operation.
 *
 * <p>Validates: Requirements 9.5, 2.3.
 *
 * <h2>What this test now asserts (reconciled from the retired recommendation-only path)</h2>
 *
 * <p>This test previously exercised the OLD recommendation-only {@code apply} path that submitted to
 * the connector itself, mutated the recommendation through {@code RecommendationService}, and wrote
 * its own audit entries (constants {@code ACTION_APPLIED} / {@code ACTION_REJECTED} /
 * {@code AUDIT_ENTITY_TYPE}). Tasks 11.1 / 11.2 replaced that path: {@code apply} now delegates to
 * {@link OperationService#createOperation} + {@link OperationWriteBack#applyOperation}, the platform
 * submission and its audit are owned by the Operation core, and the recommendation is updated only to
 * reflect the resulting {@link SyncState}.</p>
 *
 * <p>The rejection-invariance idea is preserved against the delegation contract: when the resulting
 * Operation is a <b>rejection</b> ({@code failed}) the recommendation is left internally unchanged in
 * the sense that mattered — it is NEVER marked effective/applied, its {@code appliedAt} /
 * {@code appliedBy} stay {@code null}, and the platform's reason is surfaced; the recommendation is
 * instead set to the actionable {@code failed} status so it can be retried (Req 10.4). Conversely,
 * when the Operation is {@code effective} the change is reported applied. A blank/invalid
 * recommendation id is rejected before any Operation is created.</p>
 */
@Label("Feature: advertising-workspace-rework, Property 25: rejection leaves the recommendation un-applied and records the reason")
class PlatformRejectionInvariancePropertyTest {

    private static final int MIN_ITERATIONS = 200;

    /** The non-effective resolved states a submission/creation can land in. */
    private static final SyncState[] NON_EFFECTIVE = {
            SyncState.FAILED, SyncState.LOCAL_ONLY, SyncState.SUBMITTED,
            SyncState.AMAZON_PROCESSING, SyncState.PENDING, SyncState.AWAITING_APPROVAL
    };

    record Scenario(String type, String currentValue, String recommendedValue,
                    SyncState resolvedState, String reason) {}

    // Feature: advertising-workspace-rework, Property 25
    // A rejection (failed) or any non-effective outcome never marks the recommendation
    // effective/applied; appliedAt/appliedBy are left untouched and the reason is surfaced.
    @Property(tries = MIN_ITERATIONS)
    @Label("a non-effective Operation never applies the recommendation and leaves applied-fields untouched")
    void nonEffectiveOutcomeLeavesRecommendationUnapplied(@ForAll("rejectionScenarios") Scenario s) {
        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationService operationService = mock(OperationService.class);
        OperationWriteBack operationWriteBack = mock(OperationWriteBack.class);

        RecommendationEntity recommendation = buildRecommendation(s.type(), s.currentValue(), s.recommendedValue());
        UUID recId = recommendation.getId();
        UUID opId = UUID.randomUUID();

        when(recommendationMapper.selectById(any())).thenReturn(recommendation);

        boolean creationResolved =
                s.resolvedState() == SyncState.LOCAL_ONLY || s.resolvedState() == SyncState.AWAITING_APPROVAL;
        SyncState createdState = creationResolved ? s.resolvedState() : SyncState.PENDING;
        when(operationService.createOperation(any())).thenReturn(result(opId, createdState));
        when(operationWriteBack.applyOperation(eq(opId))).thenReturn(result(opId, s.resolvedState()));
        when(operationMapper.selectById(eq(opId)))
                .thenReturn(operationEntity(opId, recommendation.getStoreId(), s.resolvedState(), s.reason()));

        WriteBackServiceImpl service = new WriteBackServiceImpl(
                recommendationMapper, operationMapper, operationService, operationWriteBack);

        WriteBackResultVo result = service.apply(recId.toString());

        // The internal record is never marked applied/effective for a non-effective Operation.
        assertThat(result.isApplied()).isFalse();
        assertThat(result.getStatus()).isNotEqualTo("applied");
        assertThat(recommendation.getStatus()).isNotEqualTo("effective");
        assertThat(recommendation.getAppliedAt()).isNull();
        assertThat(recommendation.getAppliedBy()).isNull();

        // A platform rejection is surfaced as a rejection carrying the recorded reason and keeps the
        // recommendation actionable for retry (Req 10.4).
        if (s.resolvedState() == SyncState.FAILED) {
            assertThat(result.getStatus()).isEqualTo("rejected");
            assertThat(result.getMessage()).isEqualTo(s.reason());
            assertThat(recommendation.getStatus()).isEqualTo("failed");
        } else if (s.resolvedState() == SyncState.LOCAL_ONLY) {
            assertThat(result.getStatus()).isEqualTo("local-only");
            assertThat(recommendation.getStatus()).isEqualTo("local-only");
        }
    }

    // Companion: when the Operation reaches effective the change is reported applied.
    @Property(tries = MIN_ITERATIONS)
    @Label("an effective Operation reports the change as applied")
    void effectiveOutcomeReportsApplied(@ForAll("acceptedScenarios") Scenario s) {
        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationService operationService = mock(OperationService.class);
        OperationWriteBack operationWriteBack = mock(OperationWriteBack.class);

        RecommendationEntity recommendation = buildRecommendation(s.type(), s.currentValue(), s.recommendedValue());
        UUID recId = recommendation.getId();
        UUID opId = UUID.randomUUID();

        when(recommendationMapper.selectById(any())).thenReturn(recommendation);
        when(operationService.createOperation(any())).thenReturn(result(opId, SyncState.PENDING));
        when(operationWriteBack.applyOperation(eq(opId))).thenReturn(result(opId, SyncState.EFFECTIVE));
        when(operationMapper.selectById(eq(opId)))
                .thenReturn(operationEntity(opId, recommendation.getStoreId(), SyncState.EFFECTIVE, null));

        WriteBackServiceImpl service = new WriteBackServiceImpl(
                recommendationMapper, operationMapper, operationService, operationWriteBack);

        WriteBackResultVo result = service.apply(recId.toString());

        assertThat(result.isApplied()).isTrue();
        assertThat(result.getStatus()).isEqualTo("applied");
        assertThat(result.getPlatformReference()).isNotNull();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static OperationResult result(UUID opId, SyncState state) {
        return OperationResult.builder()
                .operationId(opId)
                .logicalOperationId(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .syncState(state)
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .coalesced(false)
                .build();
    }

    private static OperationEntity operationEntity(UUID opId, UUID storeId, SyncState state, String reason) {
        return OperationEntity.builder()
                .id(opId)
                .storeId(storeId)
                .operationSource("recommendation")
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("recommendation:" + opId)
                .attemptId(UUID.randomUUID())
                .syncState(OperationMachineValues.toValue(state))
                .platformReference(state == SyncState.EFFECTIVE ? "amzn-ref-" + opId : null)
                .statusReason(state == SyncState.FAILED ? reason : null)
                .build();
    }

    private static RecommendationEntity buildRecommendation(String type, String current, String recommended) {
        return RecommendationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .keywordId(UUID.randomUUID())
                .type(type)
                .title("rec")
                .currentValue(current)
                .recommendedValue(recommended)
                .status("pending")
                .build();
    }

    // --- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<Scenario> rejectionScenarios() {
        return Combinators.combine(
                        supportedTypes(),
                        nonBlank(),
                        nonBlank(),
                        Arbitraries.of(NON_EFFECTIVE),
                        nonBlank())
                .as(Scenario::new);
    }

    @Provide
    Arbitrary<Scenario> acceptedScenarios() {
        return Combinators.combine(
                        supportedTypes(),
                        nonBlank(),
                        nonBlank(),
                        Arbitraries.just(SyncState.EFFECTIVE),
                        nonBlank())
                .as(Scenario::new);
    }

    private Arbitrary<String> supportedTypes() {
        return Arbitraries.of("decrease_bid", "increase_bid", "add_negative", "add_exact",
                "increase_budget", "pause_target");
    }

    /** Non-blank alphanumeric strings, safe to compare verbatim as reasons/values. */
    private Arbitrary<String> nonBlank() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(24);
    }
}
