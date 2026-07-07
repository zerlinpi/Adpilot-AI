package com.adpilot.modules.advertising.operation;

import java.util.Optional;
import java.util.UUID;

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
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link OperationServiceImpl#retry(UUID)} (task 7.3 lifecycle, task 7.12
 * property).
 *
 * <p>Feature: advertising-workspace-rework, Property 10: Retry creates a fresh attempt without
 * mutating the original.
 *
 * <p>Validates: Requirements 4.2, 7.8.
 *
 * <p>Property 10 (transcribed from the design's Correctness Properties section): <em>For any
 * {@code failed} Operation that is retried, a new attempt is created in {@code pending} with a new
 * {@code attemptId}, a new {@code submissionIdempotencyKey}, and an incremented {@code attemptNumber}
 * under the same {@code logicalOperationId} and {@code logicalIdempotencyKey}, and the original
 * failed Operation_Record is left unchanged.</em>
 *
 * <p>The retry is driven through the real {@link OperationServiceImpl} against mocked collaborators
 * (mirroring {@code OperationServiceImplTest} / {@code CancellationRoutingPropertyTest}). The
 * generated failed Operations vary every dimension that the fresh-attempt invariants must preserve or
 * rotate — source, entity type/id, target field, before/after values, the prior {@code attemptNumber},
 * and whether the Store is write-capable — to prove the invariants hold across the input space. For
 * each generated failed Operation the property captures the {@link OperationRecordCommand} handed to
 * persistence and asserts:
 *
 * <ol>
 *   <li>the new attempt is {@code pending} (Req 4.2);</li>
 *   <li>it carries the SAME {@code logicalOperationId} and {@code logicalIdempotencyKey} (Req 4.2);</li>
 *   <li>it carries a FRESH {@code attemptId} (distinct from the original) and a FRESH
 *       {@code submissionIdempotencyKey} (distinct from the prior attempt's key, Req 5.2/5.7);</li>
 *   <li>its {@code attemptNumber} is exactly the original's + 1 (Req 4.2);</li>
 *   <li>the retried change is preserved — scope/source/entity/field and the failed before/after
 *       values are copied forward (Req 7.8);</li>
 *   <li>the original failed Operation_Record is never mutated — its fields are unchanged and no
 *       update is issued against it.</li>
 * </ol>
 *
 * <p>The {@link SecurityContextHolder} is left empty so per-user data-scope validation is skipped,
 * keeping the focus on the retry's fresh-attempt construction.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 10: Retry creates a fresh attempt without mutating the original")
class RetryFreshAttemptPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** A fixed, distinctive submission key minted for the retry attempt. */
    private static final String FRESH_SUBMISSION_KEY = "FRESH-SUBMISSION-KEY";

    /** The prior attempt's submission key — only needs to differ from {@link #FRESH_SUBMISSION_KEY}. */
    private static final String PRIOR_SUBMISSION_KEY = "prior-submission-key";

    /** An arbitrary {@code failed} Operation awaiting an operator retry. */
    record RetrySpec(UUID storeId,
                     OperationSource source,
                     String entityType,
                     UUID entityId,
                     String field,
                     String beforeRaw,
                     String afterRaw,
                     int priorAttemptNumber,
                     String priorSubmissionKey,
                     boolean writeCapable) {}

    /**
     * Feature: advertising-workspace-rework, Property 10: Retry creates a fresh attempt without
     * mutating the original.
     *
     * <p>Validates: Requirements 4.2, 7.8.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 10: retry mints a fresh pending attempt under the same logical identity and never mutates the original")
    void retryCreatesFreshAttemptWithoutMutatingOriginal(@ForAll("failedOperations") RetrySpec spec) {
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
        OperationStateMachine stateMachine = new OperationStateMachine();
        ConfirmedValueWriter confirmedValueWriter = Mockito.mock(ConfirmedValueWriter.class);
        OperationPendingChangeMapper pendingChangeMapper = Mockito.mock(OperationPendingChangeMapper.class);
        OperationOutboxMapper outboxMapper = Mockito.mock(OperationOutboxMapper.class);
        PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

        // A fresh per-attempt submission key — distinct from the prior attempt's key (Req 5.2/5.7).
        when(idempotencyService.newSubmissionIdempotencyKey()).thenReturn(FRESH_SUBMISSION_KEY);
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(spec.writeCapable());
        when(platformConnectionMapper.selectList(any()))
                .thenReturn(java.util.List.of(connectedConnection("amazon")));
        // record() echoes a persisted entity reflecting the resolved command.
        when(operationRecordService.record(any())).thenAnswer(inv -> persist(inv.getArgument(0)));

        // The original failed Operation_Record under test.
        UUID originalAttemptId = UUID.randomUUID();
        UUID logicalOperationId = UUID.randomUUID();
        String beforeJson = jsonQuote(spec.beforeRaw());
        String afterJson = jsonQuote(spec.afterRaw());
        OperationEntity original = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(spec.storeId())
                .operationSource(OperationMachineValues.toValue(spec.source()))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(SyncState.FAILED))
                .entityType(spec.entityType())
                .entityId(spec.entityId())
                .field(spec.field())
                .logicalOperationId(logicalOperationId)
                .logicalIdempotencyKey("logical-key")
                .attemptId(originalAttemptId)
                .submissionIdempotencyKey(spec.priorSubmissionKey())
                .attemptNumber(spec.priorAttemptNumber())
                .beforeValue(beforeJson)
                .afterValue(afterJson)
                .reversible(true)
                .affectedCount(1)
                .statusReason("platform rejected")
                .build();
        when(operationRecordService.findById(original.getId())).thenReturn(Optional.of(original));

        // --- Snapshot the original's fields to prove immutability after the retry ----------------
        String snapSyncState = original.getSyncState();
        String snapSubmissionKey = original.getSubmissionIdempotencyKey();
        Integer snapAttemptNumber = original.getAttemptNumber();
        UUID snapAttemptId = original.getAttemptId();
        String snapBefore = original.getBeforeValue();
        String snapAfter = original.getAfterValue();
        String snapStatusReason = original.getStatusReason();

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                new OperationJsonCodec(new ObjectMapper()),
                Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));

        ArgumentCaptor<OperationRecordCommand> commandCaptor =
                ArgumentCaptor.forClass(OperationRecordCommand.class);

        OperationResult result = service.retry(original.getId());

        // (1) The new attempt is pending (Req 4.2).
        assertThat(result.getSyncState())
                .as("retry must create a fresh attempt in the pending Sync_State")
                .isEqualTo(SyncState.PENDING);
        assertThat(result.isCoalesced()).isFalse();

        verify(operationRecordService).record(commandCaptor.capture());
        OperationRecordCommand cmd = commandCaptor.getValue();

        // (2) Same logical identity (Req 4.2).
        assertThat(cmd.getLogicalOperationId())
                .as("the retry attempt shares the original's logicalOperationId")
                .isEqualTo(logicalOperationId);
        assertThat(cmd.getLogicalIdempotencyKey())
                .as("the retry attempt shares the original's logicalIdempotencyKey")
                .isEqualTo("logical-key");

        // (3) Fresh attempt identity + fresh submission key (Req 4.2, 5.2/5.7).
        assertThat(cmd.getAttemptId())
                .as("the retry attempt carries a fresh attemptId distinct from the original")
                .isNotNull()
                .isNotEqualTo(originalAttemptId);
        assertThat(cmd.getSubmissionIdempotencyKey())
                .as("the retry attempt carries a freshly minted submissionIdempotencyKey")
                .isEqualTo(FRESH_SUBMISSION_KEY)
                .isNotEqualTo(spec.priorSubmissionKey());

        // (4) Incremented attemptNumber (Req 4.2).
        assertThat(cmd.getAttemptNumber())
                .as("the retry attempt increments attemptNumber by exactly one")
                .isEqualTo(spec.priorAttemptNumber() + 1);

        // The new attempt is a platform_mutation pending attempt (state/scope separation, Req 3.7).
        assertThat(cmd.getSyncState()).isEqualTo(SyncState.PENDING);
        assertThat(cmd.getExecutionStatus()).isNull();

        // (5) The retried change is preserved — scope/source/entity/field + before/after (Req 7.8).
        assertThat(cmd.getStoreId()).isEqualTo(spec.storeId());
        assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
        assertThat(cmd.getOperationSource()).isEqualTo(spec.source());
        assertThat(cmd.getEntityType()).isEqualTo(spec.entityType());
        assertThat(cmd.getEntityId()).isEqualTo(spec.entityId());
        assertThat(cmd.getField()).isEqualTo(spec.field());
        assertThat(cmd.getBeforeValue()).isEqualTo(spec.beforeRaw());
        assertThat(cmd.getAfterValue()).isEqualTo(spec.afterRaw());

        // The fresh pending attempt drives re-submission via an overlay and, when the Store is
        // write-capable, a fresh Outbox entry carrying the new submission key (Req 6.1, 53.3).
        verify(pendingChangeMapper, times(1)).insert(any());
        verify(outboxMapper, times(spec.writeCapable() ? 1 : 0)).insert(any());

        // (6) The original failed Operation_Record is left UNCHANGED.
        verify(operationMapper, never()).update(any(), any());
        assertThat(original.getSyncState()).isEqualTo(snapSyncState);
        assertThat(original.getSubmissionIdempotencyKey()).isEqualTo(snapSubmissionKey);
        assertThat(original.getAttemptNumber()).isEqualTo(snapAttemptNumber);
        assertThat(original.getAttemptId()).isEqualTo(snapAttemptId);
        assertThat(original.getBeforeValue()).isEqualTo(snapBefore);
        assertThat(original.getAfterValue()).isEqualTo(snapAfter);
        assertThat(original.getStatusReason()).isEqualTo(snapStatusReason);
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Generates {@code failed} Operations spanning every dimension the fresh-attempt invariants must
     * preserve or rotate: source, entity type/id, target field, before/after values, the prior 1-based
     * {@code attemptNumber}, and the write-capability of the Store.
     */
    @Provide
    Arbitrary<RetrySpec> failedOperations() {
        Arbitrary<OperationSource> sources = Arbitraries.of(
                OperationSource.MANUAL,
                OperationSource.RECOMMENDATION,
                OperationSource.AI_HOSTING,
                OperationSource.ONE_CLICK_OPTIMIZE);
        Arbitrary<String> entityTypes =
                Arbitraries.of("campaign", "ad_group", "keyword", "product_ad", "target");
        Arbitrary<UUID> entityIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> fields = Arbitraries.of("status", "bid", "budget", "state", "match_type");
        Arbitrary<String> beforeValues =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> afterValues =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<Integer> priorAttempts = Arbitraries.integers().between(1, 10);
        Arbitrary<Boolean> writeCapable = Arbitraries.of(true, false);

        // jqwik's Combinators.combine supports at most 8 arbitraries, so the opaque Store id and the
        // prior attempt's submission key (which only needs to differ from the freshly minted key) are
        // fixed inside the mapping rather than generated as separate dimensions.
        return Combinators.combine(sources, entityTypes, entityIds, fields,
                        beforeValues, afterValues, priorAttempts, writeCapable)
                .as((source, entityType, entityId, field, before, after, priorAttempt, writeCap) ->
                        new RetrySpec(UUID.randomUUID(), source, entityType, entityId, field,
                                before, after, priorAttempt, PRIOR_SUBMISSION_KEY, writeCap));
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** JSON-encode a plain string value the way the Operation_Record stores before/after values. */
    private static String jsonQuote(String raw) {
        return "\"" + raw + "\"";
    }

    private static PlatformConnectionEntity connectedConnection(String platform) {
        PlatformConnectionEntity c = new PlatformConnectionEntity();
        c.setPlatform(platform);
        c.setStatus("connected");
        return c;
    }

    /** Echo a persisted entity reflecting the resolved command (mirrors OperationServiceImplTest). */
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
                .submissionIdempotencyKey(c.getSubmissionIdempotencyKey())
                .attemptNumber(c.getAttemptNumber())
                .syncState(OperationMachineValues.toValue(c.getSyncState()))
                .executionStatus(OperationMachineValues.toValue(c.getExecutionStatus()))
                .build();
    }
}
