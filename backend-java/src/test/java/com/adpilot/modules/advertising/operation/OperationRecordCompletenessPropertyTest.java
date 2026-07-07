package com.adpilot.modules.advertising.operation;

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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the completeness and state-field coverage of every persisted
 * {@code Operation_Record}, enforced by the pure {@link OperationRecordAssembler}.
 *
 * <p>Feature: advertising-workspace-rework, Property 22: Operation_Record completeness and
 * statusReason coverage.
 *
 * <p>Validates: Requirements 8.1, 3.8, 22.10, 49.9, 49.15.
 *
 * <p>For any recorded Operation, the Operation_Record contains all required audit fields (source,
 * scope, key model, before/after, reversible, affected count, acting user, resulting state); a
 * {@code statusReason} is present whenever the state is {@code failed}, {@code cancelled},
 * {@code expired}, {@code cancel_requested}, or {@code reconciliation_required}; a
 * {@code platform_mutation} carries a {@code sync_state} and a null {@code executionStatus}, and a
 * {@code local_configuration} carries an {@code executionStatus} in {@code {applied, failed,
 * cancelled}} and a null {@code sync_state}. An {@code ai_hosting} Operation additionally records its
 * AI-decision audit fields (the Personality_Rule_Version and the structured decision).
 *
 * <p>The assembler is a pure mapping/validation component, so the property exercises it directly
 * without a database: created/updated timestamps are assigned by the database on insert and are
 * therefore out of scope for this unit. The created/updated timestamp columns are covered by the
 * persistence layer.
 */
@Tag("pbt")
class OperationRecordCompletenessPropertyTest {

    /** Real, side-effect-free collaborators: a plain ObjectMapper-backed JSON codec. */
    private final OperationRecordAssembler assembler =
            new OperationRecordAssembler(new OperationJsonCodec(new ObjectMapper()));

    /**
     * Feature: advertising-workspace-rework, Property 22: Operation_Record completeness and
     * statusReason coverage.
     *
     * <p>Validates: Requirements 8.1, 3.8, 22.10, 49.9, 49.15.
     */
    @Property(tries = 200)
    @Label("Property 22: Operation_Record completeness and statusReason coverage")
    void operationRecordIsCompleteAndStateCoverageHolds(
            @ForAll("validCommands") OperationRecordCommand command) {

        OperationEntity entity = assembler.toEntity(command);

        // --- All required audit fields are present (Req 8.1, key model + acting user) ---
        assertThat(entity.getStoreId()).isNotNull();
        assertThat(entity.getOperationSource()).isNotBlank();
        assertThat(entity.getOperationScope()).isNotBlank();
        assertThat(entity.getEntityType()).isNotBlank();
        assertThat(entity.getEntityId()).isNotNull();
        assertThat(entity.getLogicalOperationId()).isNotNull();
        assertThat(entity.getLogicalIdempotencyKey()).isNotBlank();
        assertThat(entity.getAttemptId()).isNotNull();
        assertThat(entity.getActingUserId()).isNotNull();

        // --- before/after, reversible, affected count are always populated ---
        assertThat(entity.getBeforeValue()).isNotNull();
        assertThat(entity.getAfterValue()).isNotNull();
        assertThat(entity.getReversible()).isNotNull();
        assertThat(entity.getAffectedCount()).isNotNull().isGreaterThanOrEqualTo(1);
        assertThat(entity.getAttemptNumber()).isNotNull().isGreaterThanOrEqualTo(1);

        // The persisted classification round-trips to the same domain enums (machine-value totality).
        OperationScope scope = OperationMachineValues.toOperationScope(entity.getOperationScope());
        OperationSource source = OperationMachineValues.toOperationSource(entity.getOperationSource());

        // --- executionStatus vs sync_state separation and resulting-state presence (Req 3.8) ---
        if (scope == OperationScope.PLATFORM_MUTATION) {
            // platform_mutation: resulting state is a sync_state; executionStatus is null.
            assertThat(entity.getSyncState()).isNotBlank();
            assertThat(entity.getExecutionStatus()).isNull();

            SyncState syncState = OperationMachineValues.toSyncState(entity.getSyncState());
            // statusReason coverage for the failure/in-flight-unsettled terminal states (Req 8.1).
            if (OperationRecordAssembler.SYNC_STATES_REQUIRING_REASON.contains(syncState)) {
                assertThat(entity.getStatusReason()).isNotBlank();
            }
        } else {
            // local_configuration: resulting state is an executionStatus in {applied,failed,cancelled};
            // sync_state is null.
            assertThat(entity.getSyncState()).isNull();
            assertThat(entity.getExecutionStatus())
                    .isIn("applied", "failed", "cancelled");

            ExecutionStatus executionStatus =
                    OperationMachineValues.toExecutionStatus(entity.getExecutionStatus());
            if (OperationRecordAssembler.EXEC_STATUSES_REQUIRING_REASON.contains(executionStatus)) {
                assertThat(entity.getStatusReason()).isNotBlank();
            }
        }

        // --- AI decision audit fields are recorded for an ai_hosting Operation (Req 49.9, 22.10) ---
        if (source == OperationSource.AI_HOSTING) {
            assertThat(entity.getPersonalityRuleVersion()).isNotBlank();
            assertThat(entity.getAiDecision()).isNotNull();
        }
    }

    // --- generators --------------------------------------------------------

    /**
     * Generates only well-formed commands spanning the entire valid input space: both
     * operationScopes, every Operation_Source, every Sync_State / executionStatus, with a
     * {@code statusReason} supplied exactly where the resulting state requires one and the AI-decision
     * audit fields supplied for an {@code ai_hosting} Operation. Every generated command is one the
     * assembler accepts, so the property asserts what a successfully recorded Operation looks like.
     */
    @Provide
    Arbitrary<OperationRecordCommand> validCommands() {
        Arbitrary<OperationScope> scope = Arbitraries.of(OperationScope.values());
        Arbitrary<OperationSource> source = Arbitraries.of(OperationSource.values());
        Arbitrary<SyncState> syncState = Arbitraries.of(SyncState.values());
        Arbitrary<ExecutionStatus> executionStatus = Arbitraries.of(ExecutionStatus.values());
        Arbitrary<String> entityType = nonBlankStrings();
        Arbitrary<String> logicalKey = nonBlankStrings();
        Arbitrary<String> reason = nonBlankStrings();
        // {attemptNumber, affectedCount, reversibleFlag} folded into one arbitrary to stay within
        // the eight-component combine bound.
        Arbitrary<int[]> numbers = Combinators.combine(
                        Arbitraries.integers().between(1, 10),
                        Arbitraries.integers().between(1, 100),
                        Arbitraries.integers().between(0, 1))
                .as((attempt, affected, flag) -> new int[]{attempt, affected, flag});

        return Combinators.combine(scope, source, syncState, executionStatus,
                        entityType, logicalKey, reason, numbers)
                .as(this::buildCommand);
    }

    private OperationRecordCommand buildCommand(
            OperationScope scope,
            OperationSource source,
            SyncState syncState,
            ExecutionStatus executionStatus,
            String entityType,
            String logicalKey,
            String reason,
            int[] numbers) {

        OperationRecordCommand.OperationRecordCommandBuilder builder = OperationRecordCommand.builder()
                .storeId(UUID.randomUUID())
                .operationSource(source)
                .operationScope(scope)
                .entityType(entityType)
                .entityId(UUID.randomUUID())
                .field("bid")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey(logicalKey)
                .attemptId(UUID.randomUUID())
                .attemptNumber(numbers[0])
                .beforeValue(new BigDecimal("1.00"))
                .afterValue(new BigDecimal("2.00"))
                .reversible(numbers[2] == 1)
                .affectedCount(numbers[1])
                .actingUserId(UUID.randomUUID());

        if (scope == OperationScope.PLATFORM_MUTATION) {
            builder.syncState(syncState);
            // Supply a reason exactly when the state requires one; leave it absent otherwise so the
            // "absent when not required" path is also exercised.
            builder.statusReason(
                    OperationRecordAssembler.SYNC_STATES_REQUIRING_REASON.contains(syncState)
                            ? reason : null);
        } else {
            builder.executionStatus(executionStatus);
            builder.statusReason(
                    OperationRecordAssembler.EXEC_STATUSES_REQUIRING_REASON.contains(executionStatus)
                            ? reason : null);
        }

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

        return builder.build();
    }

    /** Non-blank strings within the column bounds used for entity_type / idempotency / reason. */
    private Arbitrary<String> nonBlankStrings() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(40)
                .filter(s -> !s.isBlank());
    }
}
