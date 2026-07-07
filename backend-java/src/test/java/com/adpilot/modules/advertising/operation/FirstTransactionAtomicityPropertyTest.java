package com.adpilot.modules.advertising.operation;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Integration test for the atomic, externally side-effect-free first transaction of
 * {@link OperationServiceImpl#createOperation(CreateOperationCommand)} (task 6.6).
 *
 * <p>Feature: advertising-workspace-rework, Property 19: First transaction is atomic and
 * side-effect-free externally.
 *
 * <p>Validates: Requirements 6.1, 6.3.
 *
 * <p>Requirement 6.1 mandates that recording a {@code platform_mutation} persists the
 * Operation_Record, the pending-change record, the audit log entry, and the Outbox entry in ONE
 * transaction so that <em>either all four persist or none persist</em>, and that this first
 * transaction performs NO external platform (Amazon) call. Requirement 6.3 mandates that no external
 * platform request happens inside a database transaction at all — the asynchronous Outbox worker is
 * the only thing that submits to the platform, and it does so outside any transaction.
 *
 * <p><strong>Why this is a genuine atomicity test, not a structural one.</strong> The project ships
 * no test-database infrastructure (no H2, no Testcontainers, no DB-backed {@code @SpringBootTest});
 * the schema is MySQL-8.0-specific and cannot be replayed into an embedded engine (see the
 * documented convention in {@code AuthorizationAndScheduledOptimizerIntegrationTest} and
 * {@code SchemaStructuralSmokeTest}). So this test wires the real {@link OperationServiceImpl} behind
 * a <em>real</em> Spring {@link TransactionInterceptor} driven by a real
 * {@link AbstractPlatformTransactionManager}, and backs each of the four record types with a
 * transaction-aware in-memory store. Each store buffers inserts per transaction and only makes them
 * visible on commit, discarding them on rollback — exactly the all-or-none guarantee a real RDBMS
 * gives. Because the service method is annotated {@code @Transactional}, the interceptor opens a real
 * transaction, and an exception thrown mid-persistence triggers a real rollback. This lets the test
 * assert the two halves of Property 19 against many randomized commands:
 *
 * <ol>
 *   <li><b>All-or-none atomicity.</b> On a clean run all four records become visible together; when
 *       the final Outbox write fails, the rollback discards the Operation, pending-change, and audit
 *       writes too — none of the four is visible.</li>
 *   <li><b>No external side effect.</b> A {@link PlatformWriteConnector} mock is present for the run
 *       and is never invoked during {@code createOperation} — the first transaction calls no platform
 *       connector at all (Req 6.3); submission is deferred to the Outbox worker.</li>
 * </ol>
 *
 * <p>The asserted oracle is transcribed from Requirements 6.1/6.3 ("all four or none", "no platform
 * call in the transaction"), independent of the service's internal ordering of the writes.
 */
@Label("Feature: advertising-workspace-rework, Property 19: First transaction is atomic and side-effect-free externally")
class FirstTransactionAtomicityPropertyTest {

    /** A randomized platform_mutation creation request. */
    record MutationCommand(UUID storeId, String entityType, UUID entityId, String field,
                           String beforeValue, String afterValue) {}

    /**
     * Feature: advertising-workspace-rework, Property 19: First transaction is atomic and
     * side-effect-free externally.
     *
     * <p>Validates: Requirements 6.1, 6.3.
     *
     * <p>For any write-capable {@code platform_mutation} creation: (1) a successful first transaction
     * makes all four of {Operation_Record, pending-change, audit, Outbox} visible together and never
     * calls the platform connector; and (2) when persistence fails part-way (the Outbox write throws),
     * the transaction rolls back and none of the four records is visible — and still no platform
     * connector call occurred.
     */
    @Property(tries = 100)
    @Label("Property 19: the first transaction persists all four records or none, and never calls the platform")
    void firstTransactionIsAtomicAndCallsNoPlatform(@ForAll("mutationCommands") MutationCommand cmd) {
        SecurityContextHolder.clearContext();

        // --- (1) Clean run: all four records persist atomically, no platform call. --------------
        Harness ok = new Harness(/* failOnOutbox= */ false);
        OperationResult result = ok.service.createOperation(toCommand(cmd));

        assertThat(result.getSyncState())
                .as("a write-capable platform_mutation with no approval lands pending")
                .isEqualTo(SyncState.PENDING);
        assertThat(ok.operations.committed())
                .as("the Operation_Record is committed").isEqualTo(1);
        assertThat(ok.pendingChanges.committed())
                .as("the pending-change record is committed").isEqualTo(1);
        assertThat(ok.audits.committed())
                .as("the audit entry is committed").isEqualTo(1);
        assertThat(ok.outbox.committed())
                .as("the Outbox entry is committed").isEqualTo(1);
        // Req 6.3: no external platform request happens inside the first transaction.
        verifyNoInteractions(ok.connector);

        // --- (2) Failure run: the Outbox write throws → the whole transaction rolls back. -------
        Harness fail = new Harness(/* failOnOutbox= */ true);
        assertThatThrownBy(() -> fail.service.createOperation(toCommand(cmd)))
                .as("a persistence failure propagates out of the transactional method")
                .isInstanceOf(RuntimeException.class);

        assertThat(fail.operations.committed())
                .as("on rollback the Operation_Record is NOT visible").isZero();
        assertThat(fail.pendingChanges.committed())
                .as("on rollback the pending-change record is NOT visible").isZero();
        assertThat(fail.audits.committed())
                .as("on rollback the audit entry is NOT visible").isZero();
        assertThat(fail.outbox.committed())
                .as("on rollback the Outbox entry is NOT visible").isZero();
        // Even on the failing path, the first transaction never reached out to the platform.
        verifyNoInteractions(fail.connector);
    }

    private static CreateOperationCommand toCommand(MutationCommand cmd) {
        return CreateOperationCommand.builder()
                .storeId(cmd.storeId())
                .operationSource(OperationSource.MANUAL)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(cmd.entityType())
                .entityId(cmd.entityId())
                .field(cmd.field())
                .beforeValue(cmd.beforeValue())
                .afterValue(cmd.afterValue())
                .logicalIdempotencyKey(UUID.randomUUID().toString())
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // Test harness: the real service behind a real transactional proxy, backed by transaction-aware
    // in-memory stores so commit/rollback are genuinely observable.
    // ---------------------------------------------------------------------------------------------

    private static final class Harness {
        final TxStore operations = new TxStore();
        final TxStore pendingChanges = new TxStore();
        final TxStore audits = new TxStore();
        final TxStore outbox = new TxStore();
        final PlatformWriteConnector connector = mock(PlatformWriteConnector.class);
        final OperationService service;

        Harness(boolean failOnOutbox) {
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

            // Pipeline collaborators resolve to the "create a fresh, write-capable, pending op" path.
            when(inFlightConflictLock.findInFlightOperation(anyString(), any(), any()))
                    .thenReturn(Optional.empty());
            when(idempotencyService.findLogicalOperation(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
            when(platformConnectionMapper.selectList(any()))
                    .thenReturn(List.of(connectedConnection("amazon")));

            // Each persistence call writes into its transaction-aware store (insert returns rows-affected).
            when(operationRecordService.record(any())).thenAnswer(inv -> {
                OperationEntity entity = persist(inv.getArgument(0));
                operations.insert();
                return entity;
            });
            when(pendingChangeMapper.insert(any())).thenAnswer(inv -> pendingChanges.insert());
            doAnswer(inv -> {
                audits.insert();
                return null;
            }).when(auditLogService).createLog(any(), any(), anyString(), anyString(), any(), any());
            when(outboxMapper.insert(any())).thenAnswer(inv -> {
                if (failOnOutbox) {
                    // Simulate a persistence failure on the LAST write of the transaction so the
                    // earlier three writes are already buffered and must be rolled back with it.
                    throw new RuntimeException("simulated outbox persistence failure");
                }
                return outbox.insert();
            });

            OperationServiceImpl impl = new OperationServiceImpl(
                    permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                    entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                    stateMachine, confirmedValueWriter,
                    pendingChangeMapper, outboxMapper, platformConnectionMapper, auditLogService,
                    new OperationJsonCodec(new ObjectMapper()),
                    org.mockito.Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));

            this.service = transactionalProxy(impl);
        }

        /** Wrap the impl in a real Spring transaction interceptor honouring its {@code @Transactional}. */
        private static OperationService transactionalProxy(OperationServiceImpl impl) {
            PlatformTransactionManager txManager = new SimpleTransactionManager();
            TransactionInterceptor interceptor = new TransactionInterceptor(
                    txManager, new AnnotationTransactionAttributeSource());
            ProxyFactory factory = new ProxyFactory(impl);
            factory.addAdvice(interceptor);
            return (OperationService) factory.getProxy();
        }
    }

    /**
     * A transaction-aware insert counter. While a Spring transaction synchronization is active an
     * insert is buffered and only added to the committed count on commit (discarded on rollback),
     * mirroring an RDBMS's all-or-none visibility. Outside a transaction it commits immediately.
     */
    private static final class TxStore {
        private final AtomicInteger committed = new AtomicInteger(0);
        private final ThreadLocal<int[]> pending = new ThreadLocal<>();

        int insert() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                int[] buffer = pending.get();
                if (buffer == null) {
                    buffer = new int[]{0};
                    pending.set(buffer);
                    final int[] toFlush = buffer;
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            pending.remove();
                            if (status == STATUS_COMMITTED) {
                                committed.addAndGet(toFlush[0]);
                            }
                        }
                    });
                }
                buffer[0]++;
            } else {
                committed.incrementAndGet();
            }
            return 1;
        }

        int committed() {
            return committed.get();
        }
    }

    /**
     * A minimal resourceless {@link AbstractPlatformTransactionManager}. It binds no datasource; the
     * base class still drives the synchronization lifecycle (begin opens synchronizations, commit
     * fires {@code afterCompletion(COMMITTED)}, rollback fires {@code afterCompletion(ROLLED_BACK)}),
     * which is exactly what the transaction-aware {@link TxStore}s observe.
     */
    private static final class SimpleTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No external resource to bind.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // Nothing to flush; the base class triggers the commit synchronizations.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Nothing to undo; the base class triggers the rollback synchronizations.
        }
    }

    private static PlatformConnectionEntity connectedConnection(String platform) {
        PlatformConnectionEntity c = new PlatformConnectionEntity();
        c.setPlatform(platform);
        c.setStatus("connected");
        return c;
    }

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

    // --- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<MutationCommand> mutationCommands() {
        Arbitrary<String> entityType = Arbitraries.of("campaign", "keyword", "ad_group", "target", "product_ad");
        Arbitrary<String> field = Arbitraries.of("status", "bid", "daily_budget", "match_type");
        Arbitrary<String> values = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12);
        return Combinators.combine(
                        Arbitraries.create(UUID::randomUUID),
                        entityType,
                        Arbitraries.create(UUID::randomUUID),
                        field,
                        values,
                        values)
                .as(MutationCommand::new);
    }
}
