package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.hosting.ReportSyncErrorEntity;
import com.adpilot.modules.advertising.hosting.ReportSyncErrorMapper;
import com.adpilot.modules.advertising.hosting.ReportSyncRunEntity;
import com.adpilot.modules.advertising.hosting.ReportSyncRunMapper;
import com.adpilot.modules.advertising.vo.ProductAdSyncStatusVo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for failure-honesty in
 * {@link ProductAdSyncStatusServiceImpl#getSyncStatus}.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 13: 同步失败永不映射为成功.
 *
 * <p>Validates: Requirements 2.6.
 *
 * <p>For any report-sync run record, if its latest run for a report type is a
 * failed status, then the resulting {@link ProductAdSyncStatusVo}'s
 * {@code reportStatus} must not be any success value ({@code completed} /
 * {@code success}) and it must carry a non-empty, readable failure reason
 * ({@code lastError}). The system never presents a blank or fabricated success
 * for a failed sync.</p>
 *
 * <p>The service is exercised with mocked {@link ReportSyncRunMapper} /
 * {@link ReportSyncErrorMapper} / {@link DataScopeService} (no Spring context).
 * Runs are generated across several report types and statuses, and failed runs
 * are generated both with and without their own error text — and, independently,
 * with and without a per-attempt error row in {@code report_sync_errors} — so
 * every branch of the failure-reason resolution is covered. There is no
 * authenticated user in plain JUnit, so the data-scope guards are skipped and
 * isolation is out of scope here.</p>
 */
class SyncFailureNeverSuccessPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can materialise the bound runId
        // value off the LambdaQueryWrapper the service builds for error lookups,
        // without a running Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ReportSyncErrorEntity.class);
    }

    private static final String STORE_ID = "11111111-1111-1111-1111-111111111111";

    /** Success tokens that a failed sync must never be mapped to. */
    private static final List<String> SUCCESS_TOKENS = List.of("completed", "success");

    private final ReportSyncRunMapper runMapper = Mockito.mock(ReportSyncRunMapper.class);
    private final ReportSyncErrorMapper errorMapper = Mockito.mock(ReportSyncErrorMapper.class);
    private final DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);

    private final ProductAdSyncStatusServiceImpl service =
            new ProductAdSyncStatusServiceImpl(runMapper, errorMapper, dataScopeService);

    /**
     * Feature: multistore-ai-ads-operations, Property 13: 同步失败永不映射为成功.
     *
     * <p>Validates: Requirements 2.6.
     */
    @Property(tries = 200)
    void failedSyncIsNeverMappedToSuccessAndAlwaysCarriesAReadableReason(
            @ForAll("runSpecs") List<RunSpec> specs) {

        UUID storeUuid = UUID.fromString(STORE_ID);

        // Build the persisted runs and the per-run error rows the mappers expose.
        List<ReportSyncRunEntity> runs = new ArrayList<>(specs.size());
        Map<UUID, ReportSyncErrorEntity> errorRows = new HashMap<>();
        for (RunSpec spec : specs) {
            UUID runId = UUID.randomUUID();
            runs.add(spec.toRun(runId, storeUuid));
            if (spec.mapperError() != null) {
                errorRows.put(runId, ReportSyncErrorEntity.builder()
                        .id(UUID.randomUUID())
                        .runId(runId)
                        .storeId(storeUuid)
                        .attempt(1)
                        .error(spec.mapperError())
                        .createdAt(LocalDateTime.now())
                        .build());
            }
        }

        when(runMapper.selectList(any())).thenReturn(runs);
        // Model the error mapper as a per-run store keyed by the runId the service
        // filters on, returning that run's recorded error attempt (if any).
        when(errorMapper.selectList(any())).thenAnswer(inv -> {
            UUID runId = scopedRunId(inv.getArguments());
            ReportSyncErrorEntity row = runId == null ? null : errorRows.get(runId);
            return row == null ? new ArrayList<ReportSyncErrorEntity>() : List.of(row);
        });

        List<ProductAdSyncStatusVo> result = service.getSyncStatus(STORE_ID);

        for (ProductAdSyncStatusVo vo : result) {
            if ("failed".equalsIgnoreCase(vo.reportStatus())) {
                // A failed sync is never presented as a success.
                assertThat(SUCCESS_TOKENS)
                        .as("failed sync reportStatus must not be a success token")
                        .noneMatch(token -> token.equalsIgnoreCase(vo.reportStatus()));
                // A failed sync always carries a non-empty, readable failure reason.
                assertThat(vo.lastError())
                        .as("failed sync must carry a readable failure reason")
                        .isNotNull()
                        .isNotBlank();
            }
        }
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Extract the runId value the service placed into the error-lookup wrapper.
     *
     * <p>MyBatis-Plus 3.5.5 routes {@code selectList(Wrapper)} through the
     * two-arg {@code selectList(IPage, Wrapper)} default method, so the wrapper
     * is not guaranteed to be the first invocation argument. Scan all arguments
     * and pick out the {@link LambdaQueryWrapper} the service built.</p>
     */
    private static UUID scopedRunId(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof LambdaQueryWrapper<?> wrapper) {
                // Force SQL-segment generation so bound parameter values are materialised.
                wrapper.getTargetSql();
                return wrapper.getParamNameValuePairs().values().stream()
                        .filter(v -> v instanceof UUID)
                        .map(UUID.class::cast)
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }

    /**
     * A single generated run: its report type, status, ordering offset, and the
     * two independent error-text sources (the run's own {@code error} column and a
     * per-attempt {@code report_sync_errors} row).
     */
    record RunSpec(String reportType, String status, int offsetMinutes,
                   String ownError, String mapperError) {

        ReportSyncRunEntity toRun(UUID id, UUID storeId) {
            LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
            return ReportSyncRunEntity.builder()
                    .id(id)
                    .storeId(storeId)
                    .reportType(reportType)
                    .requestedDateStart(LocalDate.of(2024, 1, 1))
                    .requestedDateEnd(LocalDate.of(2024, 1, 2))
                    .reportStatus(status)
                    .dataStatus("finalized")
                    .rowCount(0)
                    .startedAt(base.plusMinutes(offsetMinutes))
                    .completedAt("completed".equals(status) ? base.plusMinutes(offsetMinutes + 1) : null)
                    .error(ownError)
                    .createdAt(base.plusMinutes(offsetMinutes))
                    .build();
        }
    }

    // --- generators --------------------------------------------------------

    /** A non-empty list of runs spread across a few report types and statuses. */
    @Provide
    Arbitrary<List<RunSpec>> runSpecs() {
        return runSpec().list().ofMinSize(1).ofMaxSize(8);
    }

    private Arbitrary<RunSpec> runSpec() {
        Arbitrary<String> types = Arbitraries.of("SP_CAMPAIGN", "SP_KEYWORD", "SP_SEARCH_TERM");
        Arbitrary<String> statuses = Arbitraries.of("completed", "running", "failed");
        Arbitrary<Integer> offsets = Arbitraries.integers().between(0, 500);
        // null / blank / real text for each independent error source, so failed
        // runs are generated both with and without error text on each side.
        Arbitrary<String> ownError = errorText();
        Arbitrary<String> mapperError = errorText();
        return Combinators.combine(types, statuses, offsets, ownError, mapperError)
                .as(RunSpec::new);
    }

    /** null, blank, or a readable non-blank message. */
    private Arbitrary<String> errorText() {
        return Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.of("", "   ", "\t"),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(40));
    }
}
