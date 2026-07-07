package com.adpilot.modules.advertising.operation;

import java.util.UUID;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.operation.alert.WriteBackAlerting;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the confirmed-value-on-effective invariant enforced by
 * {@link OperationServiceImpl#transition(UUID, TransitionEvent)} (task 7.1 lifecycle, task 7.9
 * property).
 *
 * <p>Feature: advertising-workspace-rework, Property 5: Confirmed value changes only on effective.
 *
 * <p>Validates: Requirements 3.4, 3.5, 4.4, 4.7, 4.11, 6.4, 7.4, 7.5, 12.1.
 *
 * <p>Property 5 (transcribed from the design's Correctness Properties section): <em>For any
 * Operation that reaches any Sync_State other than {@code effective}, the affected entity's
 * Amazon-confirmed value is left unchanged; the confirmed value is updated to the Operation's after
 * value exactly when the Operation becomes {@code effective}.</em>
 *
 * <p>The transition is driven through the real {@link OperationStateMachine} — the sole authority
 * for legal transitions (Req 4.1) — against mocked collaborators (mirroring
 * {@code OperationServiceImplTest} and the sibling {@link CancellationRoutingPropertyTest}). The
 * generated {@link TransitionCase}s span EVERY legal {@code (from, event)} edge of the state machine
 * (independently transcribed from Requirement 4.1 below, NOT shared with the production table), each
 * paired with the Sync_State that edge resolves to. Across all of them the property asserts the
 * single biconditional invariant:
 *
 * <ol>
 *   <li>the resulting Sync_State equals the independently expected target (so the transition really
 *       ran);</li>
 *   <li>{@link ConfirmedValueWriter#applyConfirmedValue} — the one mutation allowed to change a
 *       writable field's Amazon-confirmed value — is invoked EXACTLY once when (and only when) the
 *       resulting state is {@code effective}, and is NEVER invoked for any other resulting state.</li>
 * </ol>
 *
 * <p>The mocked {@link ConfirmedValueWriter#confirmedValueChangedSince} returns its default
 * {@code false}, so the Requirement 5.8 reconciliation guard does not fire and a
 * {@code PLATFORM_EFFECTIVE} edge genuinely lands in {@code effective}; that keeps this property
 * focused on the effective-vs-not biconditional (the 5.8 divergence path is covered by Property 17).
 *
 * <p>The {@link SecurityContextHolder} is left empty so per-user data-scope validation is skipped,
 * keeping the focus on the transition invariant.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 5: Confirmed value changes only on effective")
class ConfirmedValueOnEffectivePropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /**
     * One legal Sync_State transition edge: applying {@code event} to {@code from} resolves to
     * {@code expectedTo}, together with the freely-varied object dimensions the invariant must ignore.
     */
    record TransitionCase(SyncState from,
                          TransitionEvent event,
                          SyncState expectedTo,
                          UUID storeId,
                          String entityType,
                          UUID entityId,
                          String field,
                          String beforeValue,
                          String afterValue) {}

    /**
     * Feature: advertising-workspace-rework, Property 5: Confirmed value changes only on effective.
     *
     * <p>Validates: Requirements 3.4, 3.5, 4.4, 4.7, 4.11, 6.4, 7.4, 7.5, 12.1.
     *
     * <p>For any legal transition, the affected entity's Amazon-confirmed value is written exactly
     * when the Operation becomes {@code effective} and is left untouched for every other resulting
     * Sync_State.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 5: confirmed value is written iff the resulting Sync_State is effective")
    void confirmedValueChangesIffEffective(@ForAll("legalTransitions") TransitionCase tc) {
        SecurityContextHolder.clearContext();

        // --- Collaborators (mocked exactly as OperationServiceImplTest wires them) ---------------
        PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
        DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);
        InFlightConflictLock inFlightConflictLock = Mockito.mock(InFlightConflictLock.class);
        IdempotencyService idempotencyService = Mockito.mock(IdempotencyService.class);
        EntityVersionGuard entityVersionGuard = Mockito.mock(EntityVersionGuard.class);
        WriteCapabilityService writeCapabilityService = Mockito.mock(WriteCapabilityService.class);
        OperationRecordService operationRecordService = Mockito.mock(OperationRecordService.class);
        OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
        // The real state machine is the sole authority for the resulting Sync_State (Req 4.1).
        OperationStateMachine stateMachine = new OperationStateMachine();
        ConfirmedValueWriter confirmedValueWriter = Mockito.mock(ConfirmedValueWriter.class);
        // No concurrent local change: the Req 5.8 guard stays dormant so a PLATFORM_EFFECTIVE edge
        // really lands in effective (default-stubbed false, made explicit for clarity).
        when(confirmedValueWriter.confirmedValueChangedSince(any(), any(), any(), any()))
                .thenReturn(false);
        OperationPendingChangeMapper pendingChangeMapper = Mockito.mock(OperationPendingChangeMapper.class);
        OperationOutboxMapper outboxMapper = Mockito.mock(OperationOutboxMapper.class);
        PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);
        WriteBackAlerting writeBackAlerting = Mockito.mock(WriteBackAlerting.class);

        OperationEntity operation = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(tc.storeId())
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(tc.from()))
                .entityType(tc.entityType())
                .entityId(tc.entityId())
                .field(tc.field())
                .beforeValue(tc.beforeValue())
                .afterValue(tc.afterValue())
                .build();
        when(operationRecordService.findById(operation.getId()))
                .thenReturn(java.util.Optional.of(operation));

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                new OperationJsonCodec(new ObjectMapper()), writeBackAlerting);

        OperationResult result = service.transition(operation.getId(), tc.event());

        // (1) The transition resolved to the independently expected target Sync_State.
        assertThat(result.getSyncState())
                .as("transition(%s, %s) must resolve to %s", tc.from(), tc.event(), tc.expectedTo())
                .isEqualTo(tc.expectedTo());

        // (2) The confirmed-value invariant: applyConfirmedValue is the ONLY mutation allowed to
        //     change the entity's Amazon-confirmed value, and it runs exactly when — and only when —
        //     the Operation becomes effective (Req 3.4/3.5/4.4/4.7/4.11/6.4/7.4/7.5/12.1).
        if (tc.expectedTo() == SyncState.EFFECTIVE) {
            verify(confirmedValueWriter, times(1)).applyConfirmedValue(
                    eq(tc.entityType()), eq(tc.entityId()), eq(tc.field()), eq(tc.afterValue()));
        } else {
            verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
        }
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Generates EVERY legal {@code (from, event)} transition edge of the Operation state machine,
     * each paired with the Sync_State it resolves to — transcribed INDEPENDENTLY from the prose of
     * Requirement 4.1 (and the {@link TransitionEvent} contract) rather than read from the production
     * transition table, so the test cannot trivially agree with the implementation. Every other
     * object dimension (Store, entity type/id, target field, before/after values) varies freely to
     * prove the confirmed-value invariant depends only on the resulting Sync_State.
     */
    @Provide
    Arbitrary<TransitionCase> legalTransitions() {
        Arbitrary<Edge> edges = Arbitraries.of(LEGAL_EDGES);
        Arbitrary<UUID> storeIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> entityTypes =
                Arbitraries.of("campaign", "ad_group", "keyword", "product_ad", "target", "negative_keyword");
        Arbitrary<UUID> entityIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> fields = Arbitraries.of("status", "bid", "budget", "state", "match_type", "dailyBudget");
        Arbitrary<String> beforeValues =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> afterValues =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);

        return Combinators.combine(edges, storeIds, entityTypes, entityIds, fields,
                        beforeValues, afterValues)
                .as((edge, storeId, entityType, entityId, field, before, after) ->
                        new TransitionCase(edge.from(), edge.event(), edge.to(),
                                storeId, entityType, entityId, field, before, after));
    }

    /** A legal {@code (from, event) -> to} edge used to seed {@link #legalTransitions()}. */
    private record Edge(SyncState from, TransitionEvent event, SyncState to) {}

    /**
     * The legal transition edges, transcribed INDEPENDENTLY from Requirement 4.1 / the
     * {@link TransitionEvent} documentation:
     *
     * <ul>
     *   <li>{@code pending} → awaiting_approval | submitting | cancelled | superseded</li>
     *   <li>{@code awaiting_approval} → pending (approve) | cancelled (reject/cancel) | superseded</li>
     *   <li>{@code submitting} → submitted (accepted) | pending (retryable) | failed (permanent reject)</li>
     *   <li>{@code submitted} → amazon-processing | effective | failed | expired | cancel_requested</li>
     *   <li>{@code amazon-processing} → effective | failed | expired | cancel_requested</li>
     *   <li>{@code cancel_requested} → cancelled | effective | reconciliation_required</li>
     *   <li>{@code reconciliation_required} → effective | failed | cancelled</li>
     *   <li>{@code expired} → effective | failed | reconciliation_required</li>
     *   <li>{@code failed} → pending (retry)</li>
     * </ul>
     */
    private static final Edge[] LEGAL_EDGES = new Edge[] {
            new Edge(SyncState.PENDING, TransitionEvent.REQUEST_APPROVAL, SyncState.AWAITING_APPROVAL),
            new Edge(SyncState.PENDING, TransitionEvent.SUBMIT, SyncState.SUBMITTING),
            new Edge(SyncState.PENDING, TransitionEvent.CANCEL, SyncState.CANCELLED),
            new Edge(SyncState.PENDING, TransitionEvent.SUPERSEDE, SyncState.SUPERSEDED),

            new Edge(SyncState.AWAITING_APPROVAL, TransitionEvent.APPROVE, SyncState.PENDING),
            new Edge(SyncState.AWAITING_APPROVAL, TransitionEvent.REJECT, SyncState.CANCELLED),
            new Edge(SyncState.AWAITING_APPROVAL, TransitionEvent.CANCEL, SyncState.CANCELLED),
            new Edge(SyncState.AWAITING_APPROVAL, TransitionEvent.SUPERSEDE, SyncState.SUPERSEDED),

            new Edge(SyncState.SUBMITTING, TransitionEvent.PLATFORM_ACCEPTED, SyncState.SUBMITTED),
            new Edge(SyncState.SUBMITTING, TransitionEvent.RETRYABLE_REJECT, SyncState.PENDING),
            new Edge(SyncState.SUBMITTING, TransitionEvent.PERMANENT_REJECT, SyncState.FAILED),

            new Edge(SyncState.SUBMITTED, TransitionEvent.PLATFORM_PROCESSING, SyncState.AMAZON_PROCESSING),
            new Edge(SyncState.SUBMITTED, TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE),
            new Edge(SyncState.SUBMITTED, TransitionEvent.PLATFORM_FAILED, SyncState.FAILED),
            new Edge(SyncState.SUBMITTED, TransitionEvent.TIMEOUT, SyncState.EXPIRED),
            new Edge(SyncState.SUBMITTED, TransitionEvent.REQUEST_CANCEL, SyncState.CANCEL_REQUESTED),

            new Edge(SyncState.AMAZON_PROCESSING, TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE),
            new Edge(SyncState.AMAZON_PROCESSING, TransitionEvent.PLATFORM_FAILED, SyncState.FAILED),
            new Edge(SyncState.AMAZON_PROCESSING, TransitionEvent.TIMEOUT, SyncState.EXPIRED),
            new Edge(SyncState.AMAZON_PROCESSING, TransitionEvent.REQUEST_CANCEL, SyncState.CANCEL_REQUESTED),

            new Edge(SyncState.CANCEL_REQUESTED, TransitionEvent.CANCEL_CONFIRMED, SyncState.CANCELLED),
            new Edge(SyncState.CANCEL_REQUESTED, TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE),
            new Edge(SyncState.CANCEL_REQUESTED, TransitionEvent.RECONCILE, SyncState.RECONCILIATION_REQUIRED),

            new Edge(SyncState.RECONCILIATION_REQUIRED, TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE),
            new Edge(SyncState.RECONCILIATION_REQUIRED, TransitionEvent.PLATFORM_FAILED, SyncState.FAILED),
            new Edge(SyncState.RECONCILIATION_REQUIRED, TransitionEvent.CANCEL_CONFIRMED, SyncState.CANCELLED),

            new Edge(SyncState.EXPIRED, TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE),
            new Edge(SyncState.EXPIRED, TransitionEvent.PLATFORM_FAILED, SyncState.FAILED),
            new Edge(SyncState.EXPIRED, TransitionEvent.RECONCILE, SyncState.RECONCILIATION_REQUIRED),

            new Edge(SyncState.FAILED, TransitionEvent.RETRY, SyncState.PENDING),
    };
}
