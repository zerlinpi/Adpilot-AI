package com.adpilot.modules.advertising.operation;

import java.util.UUID;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the supersession routing of {@link OperationServiceImpl#supersede(UUID)}
 * (task 7.5 lifecycle, task 7.11 property).
 *
 * <p>Feature: advertising-workspace-rework, Property 9: Supersession routes by submission state.
 *
 * <p>Validates: Requirements 4.11, 49.14.
 *
 * <p>Property 9 (transcribed from the design's Correctness Properties section): <em>When a newer
 * Operation replaces an in-flight Operation against the same object (for example after an
 * AI_Personality switch recomputes hosting Operations, Req 49.14), the replaced Operation is routed
 * DIRECTLY to {@code superseded} when it is not yet submitted ({@code pending} or
 * {@code awaiting_approval}), and is routed through the {@code cancel_requested} path (never directly
 * to {@code superseded}) when it is already submitted or in flight ({@code submitted} or
 * {@code amazon-processing}); in both cases the confirmed value is unchanged.</em>
 *
 * <p>The routing is driven through the real {@link OperationStateMachine} (the sole authority for
 * legal transitions) against mocked collaborators (mirroring {@code OperationServiceImplTest} and
 * the sibling {@code CancellationRoutingPropertyTest}). The generated current Sync_States span the
 * four states for which supersession is meaningful, and the other dimensions — Store, entity
 * type/id, target field, before/after values — vary freely to prove the routing decision depends
 * only on the submission state. For each generated Operation the property asserts:
 *
 * <ol>
 *   <li>a not-yet-submitted Operation ({@code pending}/{@code awaiting_approval}) settles directly as
 *       {@code superseded} (Req 4.11);</li>
 *   <li>an already-submitted / in-flight Operation ({@code submitted}/{@code amazon-processing})
 *       routes to {@code cancel_requested} and is NEVER directly {@code superseded} (Req 4.11);</li>
 *   <li>in every case the affected entity's Amazon-confirmed value is left untouched —
 *       {@link ConfirmedValueWriter#applyConfirmedValue} is never invoked.</li>
 * </ol>
 *
 * <p>The {@link SecurityContextHolder} is left empty so per-user data-scope validation is skipped,
 * keeping the focus on the supersession routing.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 9: Supersession routes by submission state")
class SupersessionRoutingPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** An arbitrary in-flight Operation being replaced by a newer one, in a supersedable Sync_State. */
    record SupersedeSpec(SyncState current,
                         UUID storeId,
                         String entityType,
                         UUID entityId,
                         String field,
                         String beforeValue,
                         String afterValue) {}

    /**
     * Feature: advertising-workspace-rework, Property 9: Supersession routes by submission state.
     *
     * <p>Validates: Requirements 4.11, 49.14.
     *
     * <p>For any Operation in a supersedable Sync_State, replacement by a newer Operation settles it
     * directly as {@code superseded} when it is not yet submitted ({@code pending}/{@code
     * awaiting_approval}) and routes to {@code cancel_requested} (never directly {@code superseded})
     * when it is already submitted or in flight ({@code submitted}/{@code amazon-processing}); the
     * confirmed value is never written in either case.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 9: supersession routes superseded vs cancel_requested by submission state and never writes the confirmed value")
    void supersedeRoutesBySubmissionState(@ForAll("supersedableOperations") SupersedeSpec spec) {
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
        // The real state machine is the sole authority for the resulting Sync_State.
        OperationStateMachine stateMachine = new OperationStateMachine();
        ConfirmedValueWriter confirmedValueWriter = Mockito.mock(ConfirmedValueWriter.class);
        OperationPendingChangeMapper pendingChangeMapper = Mockito.mock(OperationPendingChangeMapper.class);
        OperationOutboxMapper outboxMapper = Mockito.mock(OperationOutboxMapper.class);
        PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

        OperationEntity operation = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(spec.storeId())
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(spec.current()))
                .entityType(spec.entityType())
                .entityId(spec.entityId())
                .field(spec.field())
                .beforeValue(spec.beforeValue())
                .afterValue(spec.afterValue())
                .build();
        when(operationRecordService.findById(operation.getId())).thenReturn(java.util.Optional.of(operation));

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                new OperationJsonCodec(new ObjectMapper()),
                org.mockito.Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));

        OperationResult result = service.supersede(operation.getId());

        boolean notYetSubmitted =
                spec.current() == SyncState.PENDING || spec.current() == SyncState.AWAITING_APPROVAL;

        if (notYetSubmitted) {
            // (1) Not yet submitted: never reached the platform, so settle directly as superseded
            //     (Req 4.11, 49.14).
            assertThat(result.getSyncState())
                    .as("a not-yet-submitted Operation must be superseded directly")
                    .isEqualTo(SyncState.SUPERSEDED);
        } else {
            // (2) Already submitted / in flight: a newer Operation cannot locally guarantee the
            //     platform stops, so route through cancel_requested and NEVER flip straight to
            //     superseded (Req 4.11).
            assertThat(result.getSyncState())
                    .as("an already-submitted / in-flight Operation must route to cancel_requested")
                    .isEqualTo(SyncState.CANCEL_REQUESTED);
            assertThat(result.getSyncState())
                    .as("an already-submitted / in-flight Operation must never be directly superseded")
                    .isNotEqualTo(SyncState.SUPERSEDED);
        }

        // (3) In both cases the confirmed value is left unchanged: the confirmed-value write is the
        //     effective-only mutation and must never run on a supersession (Req 4.11, Property 5/9).
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Generates Operations in the four Sync_States for which supersession is meaningful — the
     * not-yet-submitted set ({@code pending}, {@code awaiting_approval}) and the submitted /
     * in-flight set ({@code submitted}, {@code amazon-processing}) — varying every other dimension
     * the routing decision must ignore.
     */
    @Provide
    Arbitrary<SupersedeSpec> supersedableOperations() {
        Arbitrary<SyncState> states = Arbitraries.of(
                SyncState.PENDING,
                SyncState.AWAITING_APPROVAL,
                SyncState.SUBMITTED,
                SyncState.AMAZON_PROCESSING);
        Arbitrary<UUID> storeIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> entityTypes =
                Arbitraries.of("campaign", "ad_group", "keyword", "product_ad", "target");
        Arbitrary<UUID> entityIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> fields = Arbitraries.of("status", "bid", "budget", "state", "match_type");
        Arbitrary<String> beforeValues =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> afterValues =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);

        return Combinators.combine(states, storeIds, entityTypes, entityIds, fields,
                        beforeValues, afterValues)
                .as(SupersedeSpec::new);
    }
}
