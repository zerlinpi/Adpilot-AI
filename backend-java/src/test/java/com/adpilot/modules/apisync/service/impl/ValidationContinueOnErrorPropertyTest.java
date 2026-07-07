package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.connector.PlatformDataConnector;
import com.adpilot.modules.apisync.entity.ApiSyncJobEntity;
import com.adpilot.modules.apisync.entity.ApiSyncLogEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.entity.SyncRecordErrorEntity;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.mapper.SyncRecordErrorMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.apisync.model.UpsertResult;
import com.adpilot.modules.apisync.service.UpsertService;
import com.adpilot.modules.apisync.service.WatermarkStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_ORDER;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the {@link SyncJobRunnerImpl} orchestration together
 * with the real {@link DefaultRecordMapper} and {@link DefaultDataQualityValidator}.
 *
 * <p>Feature: core-platform-completion, Property 6: Validation excludes invalid
 * records, logs each, and processing continues with consistent counts.</p>
 *
 * <p>For any batch mixing valid and invalid records, every invalid record is
 * excluded from upsert and produces exactly one recorded data-quality/record
 * error, every valid record is upserted, and processed-count plus failed-count
 * equals the number of records attempted (and the job still completes).</p>
 *
 * <p>The connector and the four mappers are replaced with stateful in-memory
 * mocks (mirroring the {@code UpsertServicePropertyTest} mocking style) so the
 * runner's connector &rarr; mapper &rarr; validator &rarr; upsert pipeline is
 * exercised end-to-end without a database or network.</p>
 *
 * <p>Validates: Requirements 1.3.2, 1.3.4, 1.4.1, 1.4.2, 1.4.3</p>
 */
class ValidationContinueOnErrorPropertyTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String PLATFORM = "woocommerce";

    private static final List<String> VALID_CURRENCIES = List.of("USD", "EUR", "GBP", "CNY");
    /** Non-blank values that are present but fail the 3-letter currency-code rule. */
    private static final List<String> BAD_CURRENCIES = List.of("US", "DOLLAR", "12$", "USDD");
    private static final List<String> ACTIVE_STATUSES =
            List.of("processing", "completed", "pending", "active", "published");

    /** The single rule each invalid record breaks (all other rules stay satisfied). */
    enum InvalidKind { NEGATIVE_AMOUNT, BAD_CURRENCY, MISSING_REQUIRED }

    /** A generated record together with its expected validity. */
    record TaggedRecord(ExternalRecord record, boolean valid) {
    }

    /** A generated job: one entity type and a batch of records mixing valid/invalid. */
    record Scenario(String entityType, List<TaggedRecord> records, int pageSize) {
    }

    // Feature: core-platform-completion, Property 6: Validation excludes invalid records, logs each, and processing continues with consistent counts
    @Property(tries = 200)
    void validationExcludesInvalidRecordsAndProcessingContinues(@ForAll("scenarios") Scenario scenario) {
        List<TaggedRecord> tagged = scenario.records();
        List<ExternalRecord> batch = tagged.stream().map(TaggedRecord::record).toList();

        Set<String> expectedValidIds = tagged.stream()
                .filter(TaggedRecord::valid)
                .map(t -> t.record().externalId())
                .collect(Collectors.toSet());
        Set<String> expectedInvalidIds = tagged.stream()
                .filter(t -> !t.valid())
                .map(t -> t.record().externalId())
                .collect(Collectors.toSet());

        int total = batch.size();
        int expectedValid = expectedValidIds.size();
        int expectedInvalid = expectedInvalidIds.size();

        // --- stateful in-memory backing stores -------------------------------
        Map<UUID, ApiSyncJobEntity> jobs = new ConcurrentHashMap<>();
        List<SyncRecordErrorEntity> recordErrors = new ArrayList<>();
        List<ApiSyncLogEntity> logs = new ArrayList<>();
        Set<String> upsertedExternalIds = new HashSet<>();

        SyncJobRunnerImpl runner = buildRunner(scenario, jobs, recordErrors, logs, upsertedExternalIds);

        // Seed a running job, then drive the synchronous execute() entry point.
        UUID jobId = UUID.randomUUID();
        ApiSyncJobEntity job = ApiSyncJobEntity.builder()
                .id(jobId)
                .connectionId(CONNECTION_ID)
                .syncType("incremental")
                .entityType(scenario.entityType())
                .status(SyncJobRunnerImpl.STATUS_RUNNING)
                .totalRecords(0)
                .recordsProcessed(0)
                .failedRecords(0)
                .build();
        jobs.put(jobId, job);

        runner.execute(jobId);

        ApiSyncJobEntity finished = jobs.get(jobId);

        // Req 1.3.3 / continue-on-error: the job runs to completion despite invalid records.
        assertThat(finished.getStatus())
                .as("job completes even when some records are invalid")
                .isEqualTo(SyncJobRunnerImpl.STATUS_COMPLETED);

        // Req 1.4.2 / 1.4.3: every valid record is processed, every invalid record is counted as failed.
        assertThat(finished.getRecordsProcessed())
                .as("processed count equals the number of valid records")
                .isEqualTo(expectedValid);
        assertThat(finished.getFailedRecords())
                .as("failed count equals the number of invalid records")
                .isEqualTo(expectedInvalid);

        // Req 1.3.2: processed + failed accounts for every record attempted, with no double counting.
        assertThat(finished.getRecordsProcessed() + finished.getFailedRecords())
                .as("processed + failed equals records attempted")
                .isEqualTo(total);
        assertThat(finished.getTotalRecords())
                .as("total records equals records attempted")
                .isEqualTo(total);

        // Req 1.4.2: exactly one data-quality/record error per invalid record, keyed to that record.
        assertThat(recordErrors)
                .as("exactly one record error per invalid record")
                .hasSize(expectedInvalid);
        Set<String> erroredIds = recordErrors.stream()
                .map(SyncRecordErrorEntity::getExternalEntityId)
                .collect(Collectors.toSet());
        assertThat(erroredIds)
                .as("the errored external ids are exactly the invalid records")
                .isEqualTo(expectedInvalidIds);

        // Req 1.4.2: invalid records are excluded from upsert; valid records are upserted.
        assertThat(upsertedExternalIds)
                .as("upserted external ids are exactly the valid records")
                .isEqualTo(expectedValidIds);
    }

    // --- runner wiring --------------------------------------------------------

    private SyncJobRunnerImpl buildRunner(Scenario scenario,
                                          Map<UUID, ApiSyncJobEntity> jobs,
                                          List<SyncRecordErrorEntity> recordErrors,
                                          List<ApiSyncLogEntity> logs,
                                          Set<String> upsertedExternalIds) {
        // api_sync_jobs: store-by-id with merge-on-update semantics.
        ApiSyncJobMapper jobMapper = mock(ApiSyncJobMapper.class);
        when(jobMapper.selectById(any())).thenAnswer(inv -> jobs.get(inv.getArgument(0)));
        when(jobMapper.insert(any(ApiSyncJobEntity.class))).thenAnswer(inv -> {
            ApiSyncJobEntity e = inv.getArgument(0);
            jobs.put(e.getId(), e);
            return 1;
        });
        when(jobMapper.updateById(any(ApiSyncJobEntity.class))).thenAnswer(inv -> {
            ApiSyncJobEntity update = inv.getArgument(0);
            ApiSyncJobEntity existing = jobs.get(update.getId());
            if (existing == null) {
                return 0;
            }
            mergeJob(existing, update);
            return 1;
        });

        // api_sync_logs / sync_record_errors: capture inserts (Req 1.3.4 / 1.4.2).
        ApiSyncLogMapper logMapper = mock(ApiSyncLogMapper.class);
        when(logMapper.insert(any(ApiSyncLogEntity.class))).thenAnswer(inv -> {
            logs.add(inv.getArgument(0));
            return 1;
        });
        SyncRecordErrorMapper errorMapper = mock(SyncRecordErrorMapper.class);
        when(errorMapper.insert(any(SyncRecordErrorEntity.class))).thenAnswer(inv -> {
            recordErrors.add(inv.getArgument(0));
            return 1;
        });

        // platform_connections: a single connection with no stored config (so CryptoUtil is untouched).
        PlatformConnectionEntity connection = PlatformConnectionEntity.builder()
                .id(CONNECTION_ID)
                .storeId(STORE_ID)
                .platform(PLATFORM)
                .configEncrypted(null)
                .build();
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        when(connectionMapper.selectById(any())).thenReturn(connection);

        // Real mapping + validation are exactly the components under test.
        DefaultRecordMapper recordMapper = new DefaultRecordMapper();
        DefaultDataQualityValidator validator = new DefaultDataQualityValidator();

        // Stateful upsert mock: records which external ids were actually upserted.
        UpsertService upsertService = mock(UpsertService.class);
        when(upsertService.upsert(any(), any(MappedRecord.class))).thenAnswer(inv -> {
            MappedRecord mapped = inv.getArgument(1);
            boolean firstSight = upsertedExternalIds.add(mapped.externalId());
            UUID internalId = UUID.randomUUID();
            return firstSight ? UpsertResult.created(internalId) : UpsertResult.updated(internalId);
        });

        // No prior watermark -> full pull selects every record in the batch.
        WatermarkStore watermarkStore = mock(WatermarkStore.class);
        when(watermarkStore.get(any(), anyString())).thenReturn(Optional.empty());

        // Credentials accepted so the pipeline proceeds to pull (Req 1.1.5 happy path).
        PlatformConnector platformConnector = mock(PlatformConnector.class);
        when(platformConnector.test(any(ConnectionContext.class)))
                .thenReturn(PlatformConnector.TestResult.ok("ok"));

        PlatformDataConnector dataConnector = new BatchConnector(
                scenario.entityType(),
                scenario.records().stream().map(TaggedRecord::record).toList(),
                scenario.pageSize());

        return new SyncJobRunnerImpl(
                connectionMapper, jobMapper, logMapper, errorMapper,
                recordMapper, validator, upsertService, watermarkStore,
                platformConnector, mock(CryptoUtil.class), new ObjectMapper(),
                List.of(dataConnector),
                4, 100);
    }

    private static void mergeJob(ApiSyncJobEntity existing, ApiSyncJobEntity update) {
        if (update.getStatus() != null) {
            existing.setStatus(update.getStatus());
        }
        if (update.getTotalRecords() != null) {
            existing.setTotalRecords(update.getTotalRecords());
        }
        if (update.getRecordsProcessed() != null) {
            existing.setRecordsProcessed(update.getRecordsProcessed());
        }
        if (update.getFailedRecords() != null) {
            existing.setFailedRecords(update.getFailedRecords());
        }
        if (update.getCompletedAt() != null) {
            existing.setCompletedAt(update.getCompletedAt());
        }
        if (update.getErrorMessage() != null) {
            existing.setErrorMessage(update.getErrorMessage());
        }
    }

    /**
     * In-memory {@link PlatformDataConnector} that serves a fixed batch of
     * records for one entity type, paged by an opaque integer cursor token.
     */
    private static final class BatchConnector implements PlatformDataConnector {
        private final String entityType;
        private final List<ExternalRecord> records;
        private final int pageSize;

        BatchConnector(String entityType, List<ExternalRecord> records, int pageSize) {
            this.entityType = entityType;
            this.records = records;
            this.pageSize = Math.max(1, pageSize);
        }

        @Override
        public String platform() {
            return PLATFORM;
        }

        @Override
        public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
            return ENTITY_ORDER.equals(entityType) ? page(cursor) : ExternalPage.empty();
        }

        @Override
        public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
            return ENTITY_PRODUCT.equals(entityType) ? page(cursor) : ExternalPage.empty();
        }

        private ExternalPage page(PageCursor cursor) {
            int start = (cursor == null || cursor.isStart()) ? 0 : Integer.parseInt(cursor.token());
            int end = Math.min(start + pageSize, records.size());
            List<ExternalRecord> slice = new ArrayList<>(records.subList(start, end));
            boolean hasMore = end < records.size();
            PageCursor next = hasMore ? PageCursor.of(String.valueOf(end)) : null;
            return new ExternalPage(slice, next, hasMore);
        }
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Arbitraries.of(ENTITY_ORDER, ENTITY_PRODUCT).flatMap(entityType ->
                Combinators.combine(
                                recordSpecs().list().ofMinSize(1).ofMaxSize(30),
                                Arbitraries.integers().between(1, 8))
                        .as((specs, pageSize) -> {
                            List<TaggedRecord> tagged = new ArrayList<>(specs.size());
                            for (int i = 0; i < specs.size(); i++) {
                                tagged.add(specs.get(i).toTaggedRecord(entityType, i));
                            }
                            return new Scenario(entityType, tagged, pageSize);
                        }));
    }

    private Arbitrary<RecordSpec> recordSpecs() {
        return Combinators.combine(
                        Arbitraries.of(true, false),                       // valid?
                        Arbitraries.of(InvalidKind.values()),              // how it is invalid (if invalid)
                        Arbitraries.of(ACTIVE_STATUSES.toArray(new String[0])),
                        Arbitraries.longs().between(0L, 10_000_00L),        // amount units (cents)
                        Arbitraries.of(VALID_CURRENCIES.toArray(new String[0])),
                        Arbitraries.of(BAD_CURRENCIES.toArray(new String[0])),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12),
                        Arbitraries.longs().between(1L, 4_000_000_000_000L))
                .as(RecordSpec::new);
    }

    /** Raw generated values for one record before an external id is assigned. */
    record RecordSpec(boolean valid, InvalidKind kind, String status, long amountUnits,
                      String validCurrency, String badCurrency, String text, long changedAtMillis) {

        TaggedRecord toTaggedRecord(String entityType, int index) {
            String externalId = "rec-" + index + "-" + UUID.randomUUID();
            Instant changedAt = Instant.ofEpochMilli(changedAtMillis);
            BigDecimal goodAmount = new BigDecimal(amountUnits).movePointLeft(2);     // >= 0
            BigDecimal badAmount = new BigDecimal(-(amountUnits + 1)).movePointLeft(2); // < 0

            Map<String, Object> fields = new LinkedHashMap<>();
            String recordStatus;

            if (ENTITY_ORDER.equals(entityType)) {
                // Defaults that satisfy every order rule.
                recordStatus = status;
                fields.put("totalAmount", goodAmount);
                fields.put("currency", validCurrency);
                if (!valid) {
                    switch (kind) {
                        case NEGATIVE_AMOUNT -> fields.put("totalAmount", badAmount);
                        case BAD_CURRENCY -> fields.put("currency", badCurrency);
                        case MISSING_REQUIRED -> recordStatus = null; // no order_status anywhere
                    }
                }
            } else {
                // Defaults that satisfy every product rule (status is not validated).
                recordStatus = "active";
                fields.put("sku", text);
                fields.put("title", text);
                fields.put("price", goodAmount);
                fields.put("currency", validCurrency);
                if (!valid) {
                    switch (kind) {
                        case NEGATIVE_AMOUNT -> fields.put("price", badAmount);
                        case BAD_CURRENCY -> fields.put("currency", badCurrency);
                        case MISSING_REQUIRED -> fields.remove("sku"); // required sku absent
                    }
                }
            }

            ExternalRecord record = new ExternalRecord(
                    externalId, entityType, changedAt, recordStatus, fields);
            return new TaggedRecord(record, valid);
        }
    }
}
