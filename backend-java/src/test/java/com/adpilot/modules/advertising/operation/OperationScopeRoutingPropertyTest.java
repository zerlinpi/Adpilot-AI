package com.adpilot.modules.advertising.operation;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for operation-scope routing through the {@link OperationServiceImpl#createOperation}
 * pipeline (task 6.1).
 *
 * <p>Feature: advertising-workspace-rework, Property 1: Operation scope determines routing.
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.6, 3.7, 6.2, 9.1, 9.2, 9.3, 21.3.
 *
 * <p>For any Operation, it is submitted through Operation_Write_Back / the Write_Connector (i.e. it
 * produces an Outbox entry) <em>if and only if</em> its {@code operationScope} is
 * {@code platform_mutation}; a {@code local_configuration} Operation is never submitted to the
 * platform and never produces an Outbox entry, regardless of its Operation_Source.
 *
 * <p>The property is universally quantified over BOTH operation scopes and ALL five
 * {@link OperationSource} values. To isolate the routing decision from the write-capability gate
 * (Property 2) and the approval gate, every {@code platform_mutation} is created against a
 * write-capable Store with no approval required, so it resolves to {@code pending} — the one state
 * that is submission-ready and must write an Outbox entry. The dual assertion (Outbox written iff
 * scope is {@code platform_mutation}, plus the scope-correct state field) gives the full
 * if-and-only-if.
 *
 * <p>The {@link SecurityContextHolder} is left empty so the acting user resolves to the reserved
 * system actor and per-user data-scope validation is skipped, keeping the focus on routing.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 1: Operation scope determines routing")
class OperationScopeRoutingPropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 1: Operation scope determines routing.
     *
     * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.6, 3.7, 6.2, 9.1, 9.2, 9.3, 21.3.
     */
    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    @Label("Property 1: an Outbox entry is produced iff operationScope is platform_mutation, for every Operation_Source")
    void scopeDeterminesRoutingForEverySource(
            @ForAll("scopes") OperationScope scope,
            @ForAll("sources") OperationSource source,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 20) String entityType,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 20) String field,
            @ForAll @AlphaChars @StringLength(max = 12) String beforeValue,
            @ForAll @AlphaChars @StringLength(max = 12) String afterValue) {

        // --- Arrange: fresh mocked collaborators per iteration so verify() counts are clean. ---
        SecurityContextHolder.clearContext();

        PermissionChecker permissionChecker = mock(PermissionChecker.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        InFlightConflictLock inFlightConflictLock = mock(InFlightConflictLock.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        EntityVersionGuard entityVersionGuard = mock(EntityVersionGuard.class);
        WriteCapabilityService writeCapabilityService = mock(WriteCapabilityService.class);
        OperationRecordService operationRecordService = mock(OperationRecordService.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationStateMachine stateMachine = new OperationStateMachine();
        ConfirmedValueWriter confirmedValueWriter = mock(ConfirmedValueWriter.class);
        OperationPendingChangeMapper pendingChangeMapper = mock(OperationPendingChangeMapper.class);
        OperationOutboxMapper outboxMapper = mock(OperationOutboxMapper.class);
        PlatformConnectionMapper platformConnectionMapper = mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                new OperationJsonCodec(new ObjectMapper()),
                org.mockito.Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));

        when(operationRecordService.record(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
        // A write-capable Store with a connected connection: isolates routing from Property 2's gate.
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connectedConnection("amazon")));

        CreateOperationCommand command = CreateOperationCommand.builder()
                .storeId(UUID.randomUUID())
                .operationSource(source)
                .operationScope(scope)
                .entityType(entityType)
                .entityId(UUID.randomUUID())
                .field(field)
                .beforeValue(beforeValue)
                .afterValue(afterValue)
                .approvalRequired(false)
                .logicalIdempotencyKey(UUID.randomUUID().toString())
                .build();

        // --- Act ---
        OperationResult result = service.createOperation(command);

        // --- Assert: routing is decided purely by operationScope, identically for every source. ---
        if (scope == OperationScope.PLATFORM_MUTATION) {
            // A platform_mutation routes to the platform: it produces exactly one Outbox entry and
            // carries a Sync_State (pending), never an executionStatus (Req 2.1, 2.2, 2.3, 9.1-9.3).
            verify(outboxMapper, times(1)).insert(any());
            assertThat(result.getSyncState())
                    .as("platform_mutation must carry a Sync_State (source=%s)", source)
                    .isEqualTo(SyncState.PENDING);
            assertThat(result.getExecutionStatus())
                    .as("platform_mutation must not carry an executionStatus (source=%s)", source)
                    .isNull();
        } else {
            // A local_configuration is never submitted and never produces an Outbox entry; it carries
            // an executionStatus and never a Sync_State, regardless of source (Req 2.6, 3.7, 6.2, 21.3).
            verify(outboxMapper, never()).insert(any());
            verify(writeCapabilityService, never()).isWriteCapable(any());
            assertThat(result.getExecutionStatus())
                    .as("local_configuration must carry an executionStatus (source=%s)", source)
                    .isEqualTo(ExecutionStatus.APPLIED);
            assertThat(result.getSyncState())
                    .as("local_configuration must never carry a Sync_State (source=%s)", source)
                    .isNull();
        }
        // The persisted scope always round-trips to the requested scope (the routing key is immutable).
        assertThat(result.getOperationScope()).isEqualTo(scope);

        SecurityContextHolder.clearContext();
    }

    // --- generators --------------------------------------------------------

    /** Both execution scopes — the single key that decides routing. */
    @Provide
    Arbitrary<OperationScope> scopes() {
        return Arbitraries.of(OperationScope.values());
    }

    /** Every Operation_Source — routing must be independent of the source (Req 3.7). */
    @Provide
    Arbitrary<OperationSource> sources() {
        return Arbitraries.of(OperationSource.values());
    }

    // --- helpers -----------------------------------------------------------

    private static PlatformConnectionEntity connectedConnection(String platform) {
        PlatformConnectionEntity c = new PlatformConnectionEntity();
        c.setPlatform(platform);
        c.setStatus("connected");
        return c;
    }

    /** Echo a persisted entity reflecting the resolved record command (mirrors OperationServiceImplTest). */
    private static OperationEntity persist(OperationRecordCommand c) {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
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
}
