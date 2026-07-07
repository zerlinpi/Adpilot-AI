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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link WriteBackServiceImpl#apply(String)} reconciled onto the generic
 * Operation_Write_Back path (tasks 11.1 / 11.2).
 *
 * <p>Feature: advertising-workspace-rework, Property 25: Recommendations are never marked effective
 * without an effective Operation.
 *
 * <p>Validates: Requirements 9.5.
 *
 * <p>Property 25 (transcribed from the design's Correctness Properties section): <em>For any
 * Recommendation, one-click-optimize action, or hosting adjustment, it is marked effective only if a
 * corresponding Operation_Record has reached the {@code effective} Sync_State.</em>
 *
 * <p>The apply path is driven against mocked collaborators ({@link OperationService},
 * {@link OperationWriteBack}, {@link RecommendationMapper}, {@link OperationMapper}) so the
 * recommendation rides the same create-then-submit delegation the production code uses, but the
 * Operation's resolved {@link SyncState} is controlled by the generator. The generator ranges over
 * the <em>entire</em> {@code SyncState} space, routing each value the way the delegation does:</p>
 *
 * <ul>
 *   <li>{@code local-only} and {@code awaiting_approval} are resolved at <b>creation</b>
 *       ({@code createOperation} returns them and {@code applyOperation} is never called);</li>
 *   <li>every other state is resolved at <b>submission</b> ({@code createOperation} returns
 *       {@code pending}, then {@code applyOperation} returns the generated state).</li>
 * </ul>
 *
 * <p>For each generated state the property asserts the Requirement 9.5 invariant from two angles:</p>
 *
 * <ol>
 *   <li><b>Observable effective signal</b> — {@code WriteBackResultVo.applied} is {@code true} (and
 *       the status reads {@code applied}) <em>if and only if</em> the corresponding Operation reached
 *       the {@code effective} Sync_State. No non-effective state is ever reported as applied.</li>
 *   <li><b>Persisted recommendation status</b> — the Recommendation is NEVER written to the
 *       {@code effective} status unless its Operation is {@code effective}; in every non-effective
 *       state the apply path leaves it {@code applying} / {@code local-only} / {@code failed}, never
 *       {@code effective} (the promotion to {@code effective} is deferred to the platform
 *       callback / status poller).</li>
 * </ol>
 */
@Label("Feature: advertising-workspace-rework, Property 25: Recommendations are never marked effective without an effective Operation")
class RecommendationEffectiveOnlyWithEffectiveOperationPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    private static final String STATUS_EFFECTIVE = "effective";

    /** The three writable targets a Recommendation can resolve to. */
    enum Target { KEYWORD, TARGET, CAMPAIGN }

    /** A supported Recommendation_Type, the target it acts on, and the Operation's resolved state. */
    record ApplyScenario(String type, Target target, String currentValue,
                         String recommendedValue, SyncState resolvedState) {}

    /**
     * Feature: advertising-workspace-rework, Property 25: Recommendations are never marked effective
     * without an effective Operation.
     *
     * <p>Validates: Requirements 9.5.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 25: a Recommendation is reported/marked effective iff its Operation reached the effective Sync_State")
    void recommendationEffectiveOnlyWhenOperationEffective(@ForAll("applyScenarios") ApplyScenario s) {
        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationService operationService = mock(OperationService.class);
        OperationWriteBack operationWriteBack = mock(OperationWriteBack.class);

        RecommendationEntity recommendation = buildRecommendation(s);
        UUID recId = recommendation.getId();
        UUID opId = UUID.randomUUID();

        when(recommendationMapper.selectById(any())).thenReturn(recommendation);

        // The delegation routes local-only / awaiting_approval at creation; everything else is
        // resolved by the submission. createOperation therefore returns the generated state directly
        // for the creation-resolved cases and `pending` (so applyOperation runs) otherwise.
        boolean creationResolved =
                s.resolvedState() == SyncState.LOCAL_ONLY || s.resolvedState() == SyncState.AWAITING_APPROVAL;
        SyncState createdState = creationResolved ? s.resolvedState() : SyncState.PENDING;

        when(operationService.createOperation(any())).thenReturn(result(opId, createdState));
        when(operationWriteBack.applyOperation(eq(opId))).thenReturn(result(opId, s.resolvedState()));
        when(operationMapper.selectById(eq(opId)))
                .thenReturn(operationEntity(opId, recommendation.getStoreId(), s.resolvedState()));

        WriteBackServiceImpl service = new WriteBackServiceImpl(
                recommendationMapper, operationMapper, operationService, operationWriteBack);

        WriteBackResultVo result = service.apply(recId.toString());

        boolean operationEffective = s.resolvedState() == SyncState.EFFECTIVE;

        // Routing: applyOperation is invoked exactly for submission-resolved states and never for the
        // creation-resolved (local-only / awaiting_approval) states.
        if (creationResolved) {
            verify(operationWriteBack, never()).applyOperation(any());
        } else {
            verify(operationWriteBack, times(1)).applyOperation(eq(opId));
        }

        // (1) Observable effective signal: applied (status "applied") iff the Operation is effective.
        assertThat(result.isApplied())
                .as("the change is reported applied iff its Operation reached effective (state=%s)",
                        s.resolvedState())
                .isEqualTo(operationEffective);
        assertThat(STATUS_EFFECTIVE.equals(mapStatusToEffectiveFlag(result.getStatus())))
                .as("result status reads 'applied' iff the Operation reached effective (state=%s)",
                        s.resolvedState())
                .isEqualTo(operationEffective);

        // (2) Persisted recommendation status is NEVER 'effective' unless the Operation is effective
        //     (Req 9.5). The synchronous apply path never promotes to effective itself: a
        //     non-effective state leaves it applying / local-only / failed.
        if (STATUS_EFFECTIVE.equals(recommendation.getStatus())) {
            assertThat(operationEffective)
                    .as("recommendation may only be 'effective' when its Operation is effective (state=%s)",
                            s.resolvedState())
                    .isTrue();
        }
        if (!operationEffective) {
            assertThat(recommendation.getStatus())
                    .as("a non-effective Operation must never leave the recommendation 'effective' (state=%s)",
                            s.resolvedState())
                    .isNotEqualTo(STATUS_EFFECTIVE);
        }
    }

    /** {@code "applied"} maps to the effective signal; anything else is non-effective. */
    private static String mapStatusToEffectiveFlag(String status) {
        return "applied".equals(status) ? STATUS_EFFECTIVE : status;
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

    private static OperationEntity operationEntity(UUID opId, UUID storeId, SyncState state) {
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
                .statusReason(state == SyncState.FAILED ? "platform rejected" : null)
                .build();
    }

    private static RecommendationEntity buildRecommendation(ApplyScenario s) {
        RecommendationEntity.RecommendationEntityBuilder b = RecommendationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .type(s.type())
                .title("rec")
                .currentValue(s.currentValue())
                .recommendedValue(s.recommendedValue())
                .status("pending");
        switch (s.target()) {
            case KEYWORD -> b.keywordId(UUID.randomUUID());
            case TARGET -> b.targetId(UUID.randomUUID());
            case CAMPAIGN -> b.campaignId(UUID.randomUUID());
        }
        return b.build();
    }

    // --- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<ApplyScenario> applyScenarios() {
        Arbitrary<String> types = Arbitraries.of(
                "decrease_bid", "increase_bid", "add_negative", "add_exact",
                "increase_budget", "pause_target");
        Arbitrary<Target> targets = Arbitraries.of(Target.class);
        Arbitrary<String> currentValues = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> recommendedValues = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        // Range over the ENTIRE Sync_State space so the effective-guard is proven total.
        Arbitrary<SyncState> states = Arbitraries.of(SyncState.class);
        return Combinators.combine(types, targets, currentValues, recommendedValues, states)
                .as(ApplyScenario::new);
    }
}
