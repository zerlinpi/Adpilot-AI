package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the immutability of the two audit facts {@code origin} (on a Campaign)
 * and {@code Operation_Source} (on an Operation).
 *
 * <p>Feature: advertising-workspace-rework, Property 28: origin and Operation_Source are immutable.
 *
 * <p>Validates: Requirements 12.6.
 *
 * <p>For any Campaign and any Operation, the Campaign's {@code origin} and the Operation's
 * {@code Operation_Source} are never rewritten across any state transition, including when the
 * Campaign syncs to the platform (its creation Operation reaches {@code effective} and an
 * {@code amazon_campaign_id} is assigned).
 *
 * <p>The test drives a real {@link OperationEntity} — built exactly the way production builds it via
 * the pure {@link OperationRecordAssembler} — through an arbitrary-length random walk of the
 * <strong>real</strong> {@link OperationStateMachine}, which is the single authority for every
 * {@code sync_state} change. At each legal transition the only field the service is permitted to
 * mutate is the lifecycle field ({@code sync_state}); the state machine exposes no mechanism that
 * could rewrite {@code operation_source} or {@code operation_scope}. After every step — and
 * specifically when the lifecycle reaches {@code effective} (the platform-sync point, where the
 * Campaign additionally gains its {@code amazon_campaign_id}) — the test asserts that the immutable
 * audit facts still hold their creation-time values.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 28: origin and Operation_Source are immutable")
class OriginAndSourceImmutabilityPropertyTest {

    /** Real, side-effect-free production collaborators. */
    private final OperationRecordAssembler assembler =
            new OperationRecordAssembler(new OperationJsonCodec(new ObjectMapper()));
    private final OperationStateMachine stateMachine = new OperationStateMachine();

    /**
     * Feature: advertising-workspace-rework, Property 28: origin and Operation_Source are immutable.
     *
     * <p>Validates: Requirements 12.6.
     */
    @Property(tries = 200)
    @Label("Property 28: origin and Operation_Source survive every state transition, including platform sync")
    void originAndSourceAreNeverRewrittenAcrossTransitions(
            @ForAll("sources") OperationSource source,
            @ForAll("origins") String origin,
            @ForAll @Size(min = 0, max = 30) List<Integer> eventPicks) {

        // --- Arrange: a Campaign and its creation Operation, built the production way. ---
        final String originalSource = OperationMachineValues.toValue(source);
        final String originalScope = OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION);

        OperationEntity operation = buildCreationOperation(source);
        assertThat(operation.getOperationSource()).isEqualTo(originalSource);
        assertThat(operation.getOperationScope()).isEqualTo(originalScope);

        CampaignEntity campaign = CampaignEntity.builder()
                .id(UUID.randomUUID())
                .storeId(operation.getStoreId())
                .name("camp")
                .origin(origin)
                .status("enabled")
                .build();
        assertThat(campaign.getOrigin()).isEqualTo(origin);

        // --- Act + Assert: walk the real state machine through arbitrary legal transitions. ---
        // The Operation begins in PENDING (a created platform_mutation awaiting submission).
        SyncState current = SyncState.PENDING;
        operation.setSyncState(OperationMachineValues.toValue(current));

        for (Integer pick : eventPicks) {
            List<TransitionEvent> legal = legalEventsFrom(current);
            if (legal.isEmpty()) {
                // Reached a terminal state — no further transitions are possible.
                break;
            }
            TransitionEvent event = legal.get(Math.floorMod(pick, legal.size()));
            SyncState next = stateMachine.transition(current, event);

            // The ONLY mutation a transition performs on the record is to its lifecycle field.
            operation.setSyncState(OperationMachineValues.toValue(next));
            current = next;

            // When the Operation becomes effective, the Campaign "syncs": it is assigned its
            // Amazon-side id (Req 12.7) and its confirmed value is updated — but NOT its origin.
            if (current == SyncState.EFFECTIVE && campaign.getAmazonCampaignId() == null) {
                campaign.setAmazonCampaignId("AMZ-" + UUID.randomUUID());
                campaign.setStatus("enabled"); // a confirmed-value write that must not touch origin
            }

            // Invariant after every transition: the audit facts are unchanged.
            assertThat(operation.getOperationSource())
                    .as("operation_source must never be rewritten (state=%s)", operation.getSyncState())
                    .isEqualTo(originalSource);
            assertThat(operation.getOperationScope())
                    .as("operation_scope must never be rewritten (state=%s)", operation.getSyncState())
                    .isEqualTo(originalScope);
            assertThat(campaign.getOrigin())
                    .as("campaign.origin must never be rewritten (state=%s)", operation.getSyncState())
                    .isEqualTo(origin);
        }

        // Final invariant, including after a full sync to effective.
        assertThat(operation.getOperationSource()).isEqualTo(originalSource);
        assertThat(operation.getOperationScope()).isEqualTo(originalScope);
        assertThat(campaign.getOrigin()).isEqualTo(origin);
        // The persisted source still round-trips to the exact source the Operation was created with.
        assertThat(OperationMachineValues.toOperationSource(operation.getOperationSource()))
                .isEqualTo(source);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * The legal events out of {@code from}, derived from the real {@link OperationStateMachine}: an
     * event is legal iff {@link OperationStateMachine#transition} accepts it. Terminal states yield
     * an empty list.
     */
    private List<TransitionEvent> legalEventsFrom(SyncState from) {
        List<TransitionEvent> legal = new ArrayList<>();
        for (TransitionEvent event : TransitionEvent.values()) {
            try {
                stateMachine.transition(from, event);
                legal.add(event);
            } catch (IllegalStateException notLegal) {
                // event is not permitted from this state — skip it.
            }
        }
        return legal;
    }

    /** Builds a {@code pending} {@code platform_mutation} creation Operation via the production assembler. */
    private OperationEntity buildCreationOperation(OperationSource source) {
        OperationRecordCommand.OperationRecordCommandBuilder builder = OperationRecordCommand.builder()
                .storeId(UUID.randomUUID())
                .operationSource(source)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .field("status")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("idem-" + UUID.randomUUID())
                .attemptId(UUID.randomUUID())
                .attemptNumber(1)
                .beforeValue("enabled")
                .afterValue("paused")
                .reversible(true)
                .affectedCount(1)
                .actingUserId(UUID.randomUUID())
                .syncState(SyncState.PENDING);

        if (source == OperationSource.AI_HOSTING) {
            builder.personalityRuleVersion("v1.0");
            builder.aiDecision(AiDecision.builder()
                    .triggerMetric("acos")
                    .triggerValue(new BigDecimal("0.42"))
                    .resolvedPersonality("balanced")
                    .personalityAllowedMagnitude(new BigDecimal("0.10"))
                    .appliedMagnitude(new BigDecimal("0.05"))
                    .decisionReason("acos above target")
                    .approvalRequired(Boolean.FALSE)
                    .build());
        }
        return assembler.toEntity(builder.build());
    }

    // --- generators --------------------------------------------------------

    /** Every Operation_Source — the immutable fact may be any of the five sources. */
    @Provide
    Arbitrary<OperationSource> sources() {
        return Arbitraries.of(OperationSource.values());
    }

    /** A Campaign's origin is exactly one of the two immutable values (Req 12.6). */
    @Provide
    Arbitrary<String> origins() {
        return Arbitraries.of("local", "amazon_import");
    }
}
