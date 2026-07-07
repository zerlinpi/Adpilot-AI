package com.adpilot.modules.writeback.service.impl;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.RecommendationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.RecommendationMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
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
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the delegation contract of {@link WriteBackServiceImpl#apply(String)} after
 * its reconciliation onto the generic Operation_Write_Back path (tasks 11.1 / 11.2).
 *
 * <p>Feature: advertising-workspace-rework, Property 25: Recommendations are never marked effective
 * without an effective Operation.
 *
 * <p>Validates: Requirements 9.5, 2.3, 9.1.
 *
 * <h2>What this test now asserts (reconciled from the retired auditing path)</h2>
 *
 * <p>This file replaces the former {@code WriteBackAuditingPropertyTest}, which asserted the OLD
 * recommendation-only path's self-managed audit entries (it constructed the {@code WriteBackServiceImpl}
 * with a connector list and verified {@code AuditLogService.createLog(...)} with the removed
 * {@code ACTION_APPLIED} / {@code AUDIT_ENTITY_TYPE} constants). Tasks 11.1 / 11.2 moved auditing into
 * the Operation core: applying a Recommendation now builds a {@code recommendation}-sourced
 * {@code platform_mutation} {@link CreateOperationCommand} and routes it through
 * {@link OperationService#createOperation} (which persists the audit-complete Operation_Record) and
 * then {@link OperationWriteBack#applyOperation} for submission.</p>
 *
 * <p>The "the change details and source are fully recorded" idea is preserved against the delegation
 * contract: the property captures the {@code CreateOperationCommand} the delegation builds and proves
 * it carries the recommendation's change details and the immutable {@code recommendation} source — the
 * data that the Operation_Record (the new audit record) is assembled from — and that a write-capable
 * apply submits exactly once through {@code applyOperation}.</p>
 */
@Label("Feature: advertising-workspace-rework, Property 25: apply delegates a recommendation-sourced platform_mutation Operation")
class WriteBackDelegationPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    /** The three writable targets a Recommendation can resolve to, and the entity type each implies. */
    enum Target {
        KEYWORD("keyword"), TARGET("target"), CAMPAIGN("campaign");
        final String entityType;
        Target(String entityType) { this.entityType = entityType; }
    }

    record Scenario(String type, Target target, String currentValue, String recommendedValue) {}

    // Feature: advertising-workspace-rework, Property 25
    // apply() builds a recommendation-sourced platform_mutation Operation carrying the change details
    // and submits it through the generic write path exactly once.
    @Property(tries = MIN_ITERATIONS)
    @Label("apply builds a recommendation-sourced platform_mutation command and submits it once")
    void applyDelegatesRecommendationSourcedPlatformMutation(@ForAll("scenarios") Scenario s) {
        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationService operationService = mock(OperationService.class);
        OperationWriteBack operationWriteBack = mock(OperationWriteBack.class);

        RecommendationEntity recommendation = buildRecommendation(s);
        UUID recId = recommendation.getId();
        UUID opId = UUID.randomUUID();

        when(recommendationMapper.selectById(any())).thenReturn(recommendation);
        // Write-capable: created pending so the delegation proceeds to submission.
        when(operationService.createOperation(any())).thenReturn(result(opId, SyncState.PENDING));
        when(operationWriteBack.applyOperation(eq(opId))).thenReturn(result(opId, SyncState.SUBMITTED));
        when(operationMapper.selectById(eq(opId)))
                .thenReturn(operationEntity(opId, recommendation.getStoreId()));

        WriteBackServiceImpl service = new WriteBackServiceImpl(
                recommendationMapper, operationMapper, operationService, operationWriteBack);

        WriteBackResultVo result = service.apply(recId.toString());

        // The delegation builds exactly one recommendation-sourced platform_mutation command.
        ArgumentCaptor<CreateOperationCommand> cmdCaptor =
                ArgumentCaptor.forClass(CreateOperationCommand.class);
        verify(operationService, times(1)).createOperation(cmdCaptor.capture());
        CreateOperationCommand cmd = cmdCaptor.getValue();

        assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.RECOMMENDATION);
        assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
        assertThat(cmd.getStoreId()).isEqualTo(recommendation.getStoreId());
        assertThat(cmd.getEntityType()).isEqualTo(s.target().entityType);
        // The change details flow into the Operation_Record (the new audit record).
        assertThat(cmd.getField()).isEqualTo(s.type());
        assertThat(cmd.getBeforeValue()).isEqualTo(s.currentValue());
        assertThat(cmd.getAfterValue()).isEqualTo(s.recommendedValue());
        // Repeated applies of the SAME recommendation coalesce into one logical Operation.
        assertThat(cmd.getLogicalIdempotencyKey()).isEqualTo("recommendation:" + recId);

        // A write-capable (pending) apply submits exactly once through the generic write path.
        verify(operationWriteBack, times(1)).applyOperation(eq(opId));

        // An in-flight submission is reported as submitted, never as applied.
        assertThat(result.isApplied()).isFalse();
        assertThat(result.getStatus()).isEqualTo("submitted");
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

    private static OperationEntity operationEntity(UUID opId, UUID storeId) {
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
                .syncState(OperationMachineValues.toValue(SyncState.SUBMITTED))
                .platformReference("amzn-ref-" + opId)
                .build();
    }

    private static RecommendationEntity buildRecommendation(Scenario s) {
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
    Arbitrary<Scenario> scenarios() {
        Arbitrary<String> types = Arbitraries.of(
                "decrease_bid", "increase_bid", "add_negative", "add_exact",
                "increase_budget", "pause_target");
        Arbitrary<Target> targets = Arbitraries.of(Target.class);
        Arbitrary<String> currentValues = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> recommendedValues = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        return Combinators.combine(types, targets, currentValues, recommendedValues).as(Scenario::new);
    }
}
