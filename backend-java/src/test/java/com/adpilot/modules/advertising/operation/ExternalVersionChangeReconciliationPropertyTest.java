package com.adpilot.modules.advertising.operation;

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
 * Property-based test for the effective-with-external-version-change reconciliation of
 * {@link OperationServiceImpl#transition(UUID, TransitionEvent)} (task 7.8 lifecycle, task 7.13
 * property).
 *
 * <p>Feature: advertising-workspace-rework, Property 17: Effective-with-external-version-change
 * requires explicit reconciliation.
 *
 * <p>Validates: Requirements 5.8.
 *
 * <p>Property 17 (transcribed from the design's Correctness Properties section): <em>For any
 * Operation that becomes {@code effective} while the local object's optimistic-lock version changed
 * during external execution, the system records both the platform-confirmed value and the
 * conflicting local change and requires explicit operator resolution rather than overwriting
 * last-writer-wins.</em>
 *
 * <p>The reconciliation guard is driven through the real {@link OperationStateMachine} (the sole
 * authority for legal transitions) against mocked collaborators (mirroring {@code
 * OperationServiceImplTest} and the sibling routing property tests). Every generated Operation is in
 * one of the five in-flight Sync_States from which {@link TransitionEvent#PLATFORM_EFFECTIVE} legally
 * reaches {@code effective} ({@code submitted}, {@code amazon-processing}, {@code cancel_requested},
 * {@code expired}, {@code reconciliation_required}). A boolean dimension decides whether another
 * writer changed the entity's confirmed value during external execution (the
 * {@link ConfirmedValueWriter#confirmedValueChangedSince} probe), and the remaining dimensions —
 * Store, entity type/id, target field, the platform-confirmed (after) value, the recorded before
 * value, and the conflicting local value — vary freely to prove the routing depends only on whether
 * a divergence was detected. For each generated Operation the property asserts:
 *
 * <ol>
 *   <li><b>no concurrent change</b> → the Operation settles {@code effective} and the
 *       platform-confirmed value is applied to the entity exactly once (last-writer-wins is fine
 *       when nothing else changed);</li>
 *   <li><b>external version change</b> → the Operation routes to {@code reconciliation_required}
 *       (never {@code effective}), the entity's confirmed value is NEVER overwritten, and BOTH the
 *       platform-confirmed value and the conflicting local value are recorded on the Operation for
 *       explicit operator resolution.</li>
 * </ol>
 *
 * <p>The {@link SecurityContextHolder} is left empty so per-user data-scope validation is skipped,
 * keeping the focus on the reconciliation routing.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 17: Effective-with-external-version-change requires explicit reconciliation")
class ExternalVersionChangeReconciliationPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** An in-flight Operation that the platform confirms effective, with/without a concurrent local change. */
    record EffectiveSpec(SyncState current,
                         boolean externalChange,
                         UUID storeId,
                         String entityType,
                         UUID entityId,
                         String field,
                         ValueTokens tokens) {}

    /**
     * Distinct value tokens grouped into one arbitrary so the top-level {@code combine} stays within
     * jqwik's 8-arbitrary limit: the recorded before value, the platform-confirmed (after) value, and
     * the conflicting local value another writer set during execution.
     */
    record ValueTokens(String beforeToken, String afterToken, String conflictingLocalToken) {}

    /**
     * Feature: advertising-workspace-rework, Property 17: Effective-with-external-version-change
     * requires explicit reconciliation.
     *
     * <p>Validates: Requirements 5.8.
     *
     * <p>For any in-flight Operation the platform confirms effective: when a concurrent local change
     * is detected the Operation routes to {@code reconciliation_required} (never {@code effective}),
     * the entity's confirmed value is never overwritten, and both the platform-confirmed value and
     * the conflicting local value are recorded for explicit operator resolution; when no concurrent
     * change is detected the Operation settles {@code effective} and applies the platform-confirmed
     * value exactly once.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 17: an external version change at effective time routes to reconciliation_required and never silently overwrites the local value")
    void effectiveWithExternalVersionChangeRequiresExplicitReconciliation(
            @ForAll("effectiveOperations") EffectiveSpec spec) {
        SecurityContextHolder.clearContext();

        OperationJsonCodec codec = new OperationJsonCodec(new ObjectMapper());

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

        ValueTokens tokens = spec.tokens();

        // The reconciliation guard's concurrent-change probe is the single dimension that decides the
        // route. The conflicting current local value is what another writer set during execution.
        when(confirmedValueWriter.confirmedValueChangedSince(any(), any(), any(), any()))
                .thenReturn(spec.externalChange());
        when(confirmedValueWriter.readConfirmedValue(eq(spec.entityType()), any(), eq(spec.field())))
                .thenReturn(tokens.conflictingLocalToken());

        String beforeJson = codec.toJson(tokens.beforeToken());
        String afterJson = codec.toJson(tokens.afterToken());

        OperationEntity operation = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(spec.storeId())
                .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(spec.current()))
                .entityType(spec.entityType())
                .entityId(spec.entityId())
                .field(spec.field())
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("logical-key")
                .attemptId(UUID.randomUUID())
                .attemptNumber(1)
                .beforeValue(beforeJson)
                .afterValue(afterJson)
                .reversible(true)
                .affectedCount(1)
                .createdAt(java.time.LocalDateTime.now())
                .build();
        when(operationRecordService.findById(operation.getId())).thenReturn(Optional.of(operation));

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                codec, Mockito.mock(WriteBackAlerting.class));

        OperationResult result = service.transition(operation.getId(), TransitionEvent.PLATFORM_EFFECTIVE);

        if (spec.externalChange()) {
            // (1) A concurrent local change diverged the entity from this Operation's recorded before
            //     value during external execution: NO last-writer-wins. The Operation routes to
            //     reconciliation_required and requires explicit operator resolution (Req 5.8).
            assertThat(result.getSyncState())
                    .as("an external version change at effective time must route to reconciliation_required")
                    .isEqualTo(SyncState.RECONCILIATION_REQUIRED);
            assertThat(result.getSyncState())
                    .as("the Operation must NOT silently become effective when the local value diverged")
                    .isNotEqualTo(SyncState.EFFECTIVE);

            // (2) The entity's confirmed value is NEVER overwritten — the effective-only write must
            //     not run on the reconciliation route (Req 5.8).
            verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());

            // (3) BOTH the platform-confirmed value and the conflicting local value are recorded on
            //     the Operation so an operator can explicitly resolve the divergence (Req 5.8).
            assertThat(operation.getPlatformResult())
                    .as("the reconciliation conflict payload must record both values for explicit resolution")
                    .isNotNull()
                    .contains("EFFECTIVE_WITH_EXTERNAL_VERSION_CHANGE")
                    .contains("platformConfirmedValue")
                    .contains("conflictingLocalValue")
                    .contains(tokens.afterToken())
                    .contains(tokens.conflictingLocalToken());

            // reconciliation_required is an Unsettled_State: the pending-change overlay stays open.
            verify(pendingChangeMapper, never()).update(any(), any());
        } else {
            // (4) No concurrent change detected: the effective transition proceeds and the
            //     platform-confirmed (after) value is applied to the entity exactly once (Req 7.4).
            assertThat(result.getSyncState())
                    .as("with no concurrent change the Operation settles effective")
                    .isEqualTo(SyncState.EFFECTIVE);
            verify(confirmedValueWriter, times(1))
                    .applyConfirmedValue(spec.entityType(), spec.entityId(), spec.field(), afterJson);
        }
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Generates in-flight Operations the platform confirms effective. The current Sync_State spans
     * the five states from which {@link TransitionEvent#PLATFORM_EFFECTIVE} legally reaches {@code
     * effective}; a boolean controls whether a concurrent local change is detected; distinct value
     * tokens make the recorded platform-confirmed and conflicting-local values independently
     * assertable; and every other dimension varies to prove the routing ignores it.
     */
    @Provide
    Arbitrary<EffectiveSpec> effectiveOperations() {
        Arbitrary<SyncState> states = Arbitraries.of(
                SyncState.SUBMITTED,
                SyncState.AMAZON_PROCESSING,
                SyncState.CANCEL_REQUESTED,
                SyncState.EXPIRED,
                SyncState.RECONCILIATION_REQUIRED);
        Arbitrary<Boolean> externalChange = Arbitraries.of(true, false);
        Arbitrary<UUID> storeIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> entityTypes =
                Arbitraries.of("campaign", "ad_group", "keyword", "product_ad", "target");
        Arbitrary<UUID> entityIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> fields = Arbitraries.of("status", "bid", "budget", "state", "matchType");

        // Distinct, prefixed alphanumeric tokens so the recorded platform-confirmed value and the
        // conflicting local value never collide and survive JSON round-tripping as quoted strings.
        Arbitrary<String> beforeTokens =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(8).map(s -> "before-" + s);
        Arbitrary<String> afterTokens =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(8).map(s -> "after-" + s);
        Arbitrary<String> conflictTokens =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(8).map(s -> "conflict-" + s);
        Arbitrary<ValueTokens> tokens =
                Combinators.combine(beforeTokens, afterTokens, conflictTokens).as(ValueTokens::new);

        return Combinators.combine(states, externalChange, storeIds, entityTypes, entityIds, fields, tokens)
                .as(EffectiveSpec::new);
    }
}
