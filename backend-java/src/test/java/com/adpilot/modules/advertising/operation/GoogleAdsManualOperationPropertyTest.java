package com.adpilot.modules.advertising.operation;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.OperationOutboxEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.operation.alert.WriteBackAlerting;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test that a manual Google Ads action drives the platform
 * write-back pipeline ({@link OperationServiceImpl}) to a single
 * {@code platform_mutation} Operation and a single Outbox entry routed to the
 * Google Ads write connector.
 *
 * <p>Feature: platform-workspace-rbac, Property 13: Manual Google Ads action
 * produces a platform-mutation Operation.
 *
 * <p>Validates: Requirements 7.1, 7.2.
 *
 * <p>A manual Google Ads campaign-create or bid/budget/status-change from an
 * authorized operator is submitted through the same generic Operation pipeline
 * that backs every manual advertising write: it builds a
 * {@link CreateOperationCommand} with {@link OperationScope#PLATFORM_MUTATION}
 * and the source appropriate to the action ({@link OperationSource#CREATION} for
 * a create, {@link OperationSource#MANUAL} for an adjustment), and
 * {@link OperationService#createOperation(CreateOperationCommand)} persists
 * exactly one Operation and — because the Store is write-capable and its active
 * connection is {@code google_ads} — exactly one {@code operation_outbox} row
 * whose {@code platform} is {@code google_ads}. The {@code OutboxWorker} and
 * {@code StatusPoller} discover the {@code GoogleAdsWriteConnector} by that
 * {@code platform} value, so a {@code google_ads}-platform Outbox row is exactly
 * "routed to the GoogleAdsWriteConnector". These properties assert, across many
 * generated stores/campaigns/fields:
 * <ul>
 *   <li>exactly one Operation is recorded, carrying
 *       {@code PLATFORM_MUTATION} and the expected {@code OperationSource}; and</li>
 *   <li>exactly one Outbox entry is enqueued for the same Operation and Store,
 *       on the {@code google_ads} platform (the GoogleAdsWriteConnector route).</li>
 * </ul>
 *
 * <p>The mapper/collaborator dependencies are modelled with Mockito (mirroring
 * {@code TableViewIsolationPropertyTest}): the Store is write-capable, holds a
 * connected {@code google_ads} connection, and the persistence layer echoes the
 * recorded command back as the stored Operation, so the test exercises the real
 * pipeline branching (scope/source classification, write-capability resolution,
 * and Outbox routing) without a running Spring/MyBatis context.
 */
class GoogleAdsManualOperationPropertyTest {

    private static final String GOOGLE_ADS = "google_ads";
    private static final String STATUS_CONNECTED = "connected";

    /** Permission an authorized independent-site advertising operator holds (Req 7.5). */
    private static final String INDEPENDENT_ADS_PERMISSION = "advertising:independent:manage";

    /**
     * Feature: platform-workspace-rbac, Property 13: Manual Google Ads action
     * produces a platform-mutation Operation.
     *
     * <p>Validates: Requirements 7.1, 7.2.
     *
     * <p>A manual Google Ads campaign creation produces exactly one
     * {@code platform_mutation} Operation with {@link OperationSource#CREATION}
     * and exactly one {@code google_ads} Outbox entry (Req 7.1).
     */
    @Property(tries = 100)
    void manualCampaignCreateProducesCreationPlatformMutationOperationRoutedToGoogleAds(
            @ForAll("storeIds") UUID storeId,
            @ForAll("entityIds") UUID campaignId,
            @ForAll("campaignNames") String campaignName) {

        Fixture f = new Fixture(storeId);

        CreateOperationCommand create = CreateOperationCommand.builder()
                .storeId(storeId)
                .operationSource(OperationSource.CREATION)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType("campaign")
                .entityId(campaignId)
                // A campaign create is a whole-object create: no single writable field.
                .field(null)
                .afterValue(campaignName)
                .requiredPermission(INDEPENDENT_ADS_PERMISSION)
                .build();

        OperationResult result = f.service.createOperation(create);

        assertManualGoogleAdsOperation(f, result, storeId, OperationSource.CREATION);
    }

    /**
     * Feature: platform-workspace-rbac, Property 13: Manual Google Ads action
     * produces a platform-mutation Operation.
     *
     * <p>Validates: Requirements 7.1, 7.2.
     *
     * <p>A manual Google Ads bid/budget/status change produces exactly one
     * {@code platform_mutation} Operation with {@link OperationSource#MANUAL} and
     * exactly one {@code google_ads} Outbox entry (Req 7.2).
     */
    @Property(tries = 100)
    void manualBidBudgetStatusChangeProducesManualPlatformMutationOperationRoutedToGoogleAds(
            @ForAll("storeIds") UUID storeId,
            @ForAll("entityIds") UUID campaignId,
            @ForAll("adjustableFields") String field,
            @ForAll("changeValues") String afterValue) {

        Fixture f = new Fixture(storeId);

        CreateOperationCommand adjust = CreateOperationCommand.builder()
                .storeId(storeId)
                .operationSource(OperationSource.MANUAL)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType("campaign")
                .field(field)
                .entityId(campaignId)
                .beforeValue("before")
                .afterValue(afterValue)
                .requiredPermission(INDEPENDENT_ADS_PERMISSION)
                .build();

        OperationResult result = f.service.createOperation(adjust);

        assertManualGoogleAdsOperation(f, result, storeId, OperationSource.MANUAL);
    }

    // --- shared assertion --------------------------------------------------

    /**
     * Assert the manual Google Ads action produced exactly one
     * {@code platform_mutation} Operation with the expected source and exactly
     * one Outbox entry routed to the {@code google_ads} connector.
     */
    private void assertManualGoogleAdsOperation(Fixture f,
                                                OperationResult result,
                                                UUID storeId,
                                                OperationSource expectedSource) {
        // Exactly one Operation was created (not coalesced into a prior one).
        assertThat(result).isNotNull();
        assertThat(result.isCoalesced()).isFalse();
        assertThat(result.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);

        // Exactly one Operation_Record was persisted, classified as a
        // platform_mutation with the expected immutable source.
        ArgumentCaptor<OperationRecordCommand> recordCaptor =
                ArgumentCaptor.forClass(OperationRecordCommand.class);
        verify(f.operationRecordService, times(1)).record(recordCaptor.capture());
        OperationRecordCommand recorded = recordCaptor.getValue();
        assertThat(recorded.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
        assertThat(recorded.getOperationSource()).isEqualTo(expectedSource);
        assertThat(recorded.getStoreId()).isEqualTo(storeId);
        // A write-capable platform_mutation is submission-ready (pending), never local-only.
        assertThat(recorded.getSyncState()).isEqualTo(SyncState.PENDING);

        // Exactly one Outbox entry was enqueued, on the google_ads platform — the
        // route the OutboxWorker uses to reach the GoogleAdsWriteConnector — for
        // the same Operation and Store.
        ArgumentCaptor<OperationOutboxEntity> outboxCaptor =
                ArgumentCaptor.forClass(OperationOutboxEntity.class);
        verify(f.outboxMapper, times(1)).insert(outboxCaptor.capture());
        OperationOutboxEntity outbox = outboxCaptor.getValue();
        assertThat(outbox.getPlatform()).isEqualTo(GOOGLE_ADS);
        assertThat(outbox.getStoreId()).isEqualTo(storeId);
        assertThat(outbox.getOperationId()).isEqualTo(result.getOperationId());
        assertThat(outbox.getStatus()).isEqualTo("pending");
    }

    // --- fixture -----------------------------------------------------------

    /**
     * A freshly mocked {@link OperationServiceImpl} wired so a Google Ads Store is
     * write-capable and its active connection is {@code google_ads}. The
     * persistence layer echoes the recorded command back as the stored Operation
     * so the pipeline's post-record branching (Outbox write) runs unchanged.
     */
    private static final class Fixture {

        final OperationServiceImpl service;
        final OperationRecordService operationRecordService;
        final OperationOutboxMapper outboxMapper;

        Fixture(UUID storeId) {
            PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
            DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);
            InFlightConflictLock inFlightConflictLock = Mockito.mock(InFlightConflictLock.class);
            IdempotencyService idempotencyService = Mockito.mock(IdempotencyService.class);
            EntityVersionGuard entityVersionGuard = Mockito.mock(EntityVersionGuard.class);
            WriteCapabilityService writeCapabilityService = Mockito.mock(WriteCapabilityService.class);
            this.operationRecordService = Mockito.mock(OperationRecordService.class);
            OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
            OperationStateMachine stateMachine = Mockito.mock(OperationStateMachine.class);
            ConfirmedValueWriter confirmedValueWriter = Mockito.mock(ConfirmedValueWriter.class);
            OperationPendingChangeMapper pendingChangeMapper = Mockito.mock(OperationPendingChangeMapper.class);
            this.outboxMapper = Mockito.mock(OperationOutboxMapper.class);
            PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
            AuditLogService auditLogService = Mockito.mock(AuditLogService.class);
            OperationJsonCodec jsonCodec = new OperationJsonCodec(new ObjectMapper());
            WriteBackAlerting writeBackAlerting = Mockito.mock(WriteBackAlerting.class);

            // Authorized operator: holds the required functional permission (Req 7.1/7.2 authorize).
            when(permissionChecker.hasPermission(any())).thenReturn(true);

            // No conflicting in-flight Operation on the object (object/field is free).
            when(inFlightConflictLock.findInFlightOperation(any(), any(), any()))
                    .thenReturn(Optional.empty());

            // The Store is write-capable, so a platform_mutation resolves to pending and
            // enqueues an Outbox entry (Req 7.1/7.2 routing).
            when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);

            // The Store's single active connection is google_ads, so the Outbox row's
            // platform — the GoogleAdsWriteConnector route — is google_ads.
            PlatformConnectionEntity connection = PlatformConnectionEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .platform(GOOGLE_ADS)
                    .status(STATUS_CONNECTED)
                    .build();
            when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connection));

            // Persistence echoes the recorded command back as the stored Operation, with a
            // generated id, so the pipeline can build its result and the Outbox payload.
            when(operationRecordService.record(any())).thenAnswer(inv -> {
                OperationRecordCommand cmd = inv.getArgument(0);
                return OperationEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(cmd.getStoreId())
                        .operationSource(OperationMachineValues.toValue(cmd.getOperationSource()))
                        .operationScope(OperationMachineValues.toValue(cmd.getOperationScope()))
                        .entityType(cmd.getEntityType())
                        .entityId(cmd.getEntityId())
                        .field(cmd.getField())
                        .logicalOperationId(cmd.getLogicalOperationId())
                        .logicalIdempotencyKey(cmd.getLogicalIdempotencyKey())
                        .attemptId(cmd.getAttemptId())
                        .syncState(OperationMachineValues.toValue(cmd.getSyncState()))
                        .executionStatus(OperationMachineValues.toValue(cmd.getExecutionStatus()))
                        .build();
            });

            this.service = new OperationServiceImpl(
                    permissionChecker,
                    dataScopeService,
                    inFlightConflictLock,
                    idempotencyService,
                    entityVersionGuard,
                    writeCapabilityService,
                    operationRecordService,
                    operationMapper,
                    stateMachine,
                    confirmedValueWriter,
                    pendingChangeMapper,
                    outboxMapper,
                    platformConnectionMapper,
                    auditLogService,
                    jsonCodec,
                    writeBackAlerting);
        }
    }

    // --- generators --------------------------------------------------------

    @Provide
    Arbitrary<UUID> storeIds() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }

    @Provide
    Arbitrary<UUID> entityIds() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }

    /** Google Ads campaign names: non-blank, bounded length. */
    @Provide
    Arbitrary<String> campaignNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(60);
    }

    /** The writable fields a manual Google Ads adjustment may target (Req 7.2). */
    @Provide
    Arbitrary<String> adjustableFields() {
        return Arbitraries.of("bid", "budget", "status");
    }

    /** Proposed change values (a bid/budget number or a status token). */
    @Provide
    Arbitrary<String> changeValues() {
        return Arbitraries.oneOf(
                Arbitraries.of("ENABLED", "PAUSED"),
                Arbitraries.doubles().between(0.01, 10_000.0).map(d -> Double.toString(d)));
    }
}
