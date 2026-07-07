package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.converter.CampaignConverter;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.support.CampaignTableColumns;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.tableview.dto.ExportRequest;
import com.adpilot.modules.tableview.filter.FilterTranslator;
import com.adpilot.modules.tableview.filter.FilterValidator;
import com.adpilot.modules.tableview.support.CsvWriter;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the fidelity of the server-side CSV export produced by
 * {@link CampaignServiceImpl#exportCsv}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 12: Export reflects the
 * full filtered, sorted result over visible columns.
 *
 * <p>Validates: Requirements 2.8.
 *
 * <p>Requirement 2.8 says the export endpoint returns a {@code text/csv} response
 * over the <em>full</em> filtered, sorted result set — every page, not only the
 * rendered page — restricted to the supplied visible columns in their configured
 * order. The export delegates filtering and sorting to the database (translated
 * onto a {@code QueryWrapper} and read back through {@code selectPage}); this test
 * therefore models the database as the paged read source. The mocked
 * {@link CampaignMapper#selectPage} serves successive pages of a generated
 * "full filtered, sorted result" (the reference list), so the unit under test is
 * the exporter's own responsibility: paging the source to exhaustion, emitting one
 * row per record in the source's order, and projecting exactly the visible columns
 * (header + cells) in their configured order.
 *
 * <p>For every dataset (including datasets larger than one read page), every sort
 * descriptor, and every non-empty visible-column selection, the produced CSV must
 * equal — byte for byte — the header of visible-column titles followed by one CSV
 * row per record across all pages, each row carrying exactly the visible columns
 * in the supplied order. Equality enforces both halves of "exactly": no record may
 * be dropped (the export reads every page, not just the first) or duplicated, the
 * row order must match the source order, and no hidden or reordered column may
 * appear.
 */
class CampaignExportFidelityPropertyTest {

    /** Mirrors the private streaming page size in {@link CampaignServiceImpl}. */
    private static final int EXPORT_PAGE_SIZE = 500;

    /**
     * Curated set of addressable, CSV-safe column keys the export may project.
     * Their rendered values never contain a comma, quote, or newline, so the CSV
     * can be split structurally; escaping itself is covered by {@code CsvWriter}.
     */
    private static final List<String> COLUMN_POOL = List.of(
            "id", "storeId", "name", "campaignType", "status", "budget", "budgetType",
            "spend", "sales", "orders", "impressions", "clicks",
            "hostingEnabled", "aiManaged", "portfolio");

    /** Sentinel meaning "no value" for the optional sort field / direction. */
    private static final String NONE = "__none__";

    /**
     * Feature: platform-ux-logistics-enhancements, Property 12: Export reflects the
     * full filtered, sorted result over visible columns.
     *
     * <p>Validates: Requirements 2.8.
     *
     * <p>The CSV produced for any result set (across all read pages), any sort, and
     * any visible-column selection equals exactly the projected reference: a header
     * of the visible-column titles plus one row per record in source order, each row
     * carrying exactly the visible columns in their configured order.
     */
    @Property(tries = 100)
    void exportReflectsTheFullSortedResultOverVisibleColumns(
            @ForAll("datasets") List<CampaignEntity> reference,
            @ForAll("visibleColumns") List<String> visibleColumns,
            @ForAll("sortFields") String sortField,
            @ForAll("sortDirs") String sortDir) {

        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        FilterTranslator filterTranslator = new FilterTranslator(new FilterValidator());

        CampaignServiceImpl service = new CampaignServiceImpl(
                campaignMapper, performanceDailyMapper, operationMapper, new ObjectMapper(),
                filterTranslator, dataScopeService);

        // Model the database as a paged read source over the full filtered, sorted
        // result. The exporter pushes the filter/sort onto a QueryWrapper that this
        // mock ignores; the mock returns the requested page of the reference list so
        // the unit under test is the exporter's paging + projection, not the DB.
        when(campaignMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            IPage<CampaignEntity> page = invocation.getArgument(0);
            int from = (int) ((page.getCurrent() - 1) * page.getSize());
            if (from >= reference.size()) {
                page.setRecords(List.of());
                return page;
            }
            int to = (int) Math.min(reference.size(), from + page.getSize());
            page.setRecords(new ArrayList<>(reference.subList(from, to)));
            return page;
        });

        ExportRequest request = new ExportRequest();
        request.setVisibleColumns(visibleColumns);
        // A sort descriptor exercises the exporter's sort-validation/order-by path;
        // the simulated source already returns the sorted result, so source order is
        // authoritative regardless of the column chosen.
        request.setSortField(NONE.equals(sortField) ? null : sortField);
        request.setSortDir(NONE.equals(sortDir) ? null : sortDir);

        StringWriter writer = new StringWriter();
        service.exportCsv(request, writer);
        String csv = writer.toString();

        // --- Expected projection of the full reference over the visible columns ---
        List<String> expectedLines = new ArrayList<>();
        expectedLines.add(visibleColumns.stream()
                .map(CampaignTableColumns::header)
                .map(CsvWriter::escape)
                .collect(Collectors.joining(",")));
        for (CampaignEntity entity : reference) {
            CampaignVo vo = CampaignConverter.toVo(entity);
            expectedLines.add(visibleColumns.stream()
                    .map(key -> CsvWriter.escape(CampaignTableColumns.cell(vo, key)))
                    .collect(Collectors.joining(",")));
        }
        String expected = String.join("\r\n", expectedLines) + "\r\n";

        // Exact fidelity: full set across all pages, in order, over exactly the
        // visible columns (header + cells) in their configured order.
        assertThat(csv).isEqualTo(expected);

        // Independent structural checks reinforcing the equality above.
        List<String> lines = splitCsvLines(csv);
        // Header + one row per record across every page (not just the first page).
        assertThat(lines).hasSize(reference.size() + 1);
        // Header is exactly the visible-column titles in their configured order.
        assertThat(lines.get(0))
                .isEqualTo(visibleColumns.stream()
                        .map(CampaignTableColumns::header)
                        .collect(Collectors.joining(",")));
        // Every data row carries exactly one cell per visible column.
        for (int i = 1; i < lines.size(); i++) {
            assertThat(lines.get(i).split(",", -1)).hasSize(visibleColumns.size());
        }
    }

    /** Split a CRLF-terminated CSV into its lines, dropping the trailing empty. */
    private static List<String> splitCsvLines(String csv) {
        List<String> lines = new ArrayList<>(List.of(csv.split("\r\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    // --- generators -----------------------------------------------------------

    /**
     * A full result set. Most datasets are small (single read page); a meaningful
     * fraction exceed {@link #EXPORT_PAGE_SIZE} so the export must read multiple
     * pages — including exact-multiple sizes that end on an empty trailing page.
     */
    @Provide
    Arbitrary<List<CampaignEntity>> datasets() {
        Arbitrary<List<CampaignEntity>> small = campaign().list().ofMinSize(0).ofMaxSize(60);
        Arbitrary<List<CampaignEntity>> multiPage =
                campaign().list().ofMinSize(EXPORT_PAGE_SIZE).ofMaxSize(EXPORT_PAGE_SIZE * 2);
        return Arbitraries.frequencyOf(
                Tuple.of(8, small),
                Tuple.of(2, multiPage));
    }

    /** A non-empty, duplicate-free selection of visible columns, in arbitrary order. */
    @Provide
    Arbitrary<List<String>> visibleColumns() {
        return Arbitraries.of(COLUMN_POOL)
                .list().ofMinSize(1).ofMaxSize(COLUMN_POOL.size()).uniqueElements();
    }

    /** A valid sort field, or the {@link #NONE} sentinel for the default sort. */
    @Provide
    Arbitrary<String> sortFields() {
        return Arbitraries.of("name", "budget", "status", "createdAt", "id", NONE);
    }

    /** A sort direction, or the {@link #NONE} sentinel for the default direction. */
    @Provide
    Arbitrary<String> sortDirs() {
        return Arbitraries.of("asc", "desc", NONE);
    }

    /**
     * A single campaign entity with CSV-safe field values. {@code id} and
     * {@code storeId} are always present (the converter dereferences them) and a
     * fresh UUID makes each record distinct.
     */
    private Arbitrary<CampaignEntity> campaign() {
        Arbitrary<UUID> id = Arbitraries.randomValue(r -> UUID.randomUUID());
        Arbitrary<UUID> storeId = Arbitraries.randomValue(r -> UUID.randomUUID());
        Arbitrary<String> name = Arbitraries.strings()
                .withCharRange('a', 'z').numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> status = Arbitraries.of("enabled", "paused", "archived");
        Arbitrary<String> type = Arbitraries.of("SP", "SB", "SD");
        Arbitrary<Integer> budget = Arbitraries.integers().between(0, 100_000);
        Arbitrary<Integer> counts = Arbitraries.integers().between(0, 100_000);
        Arbitrary<Integer> flags = Arbitraries.integers().between(0, 7);

        return Combinators.combine(id, storeId, name, status, type, budget, counts, flags)
                .as((cid, sid, cname, cstatus, ctype, cbudget, ccounts, cflags) ->
                        CampaignEntity.builder()
                                .id(cid)
                                .storeId(sid)
                                .name(cname)
                                .status(cstatus)
                                .campaignType(ctype)
                                .budget(BigDecimal.valueOf(cbudget))
                                .budgetType((cflags % 2 == 0) ? "daily" : "lifetime")
                                .spend(BigDecimal.valueOf((long) cbudget + ccounts))
                                .sales(BigDecimal.valueOf((long) ccounts * 2))
                                .orders(ccounts % 1000)
                                .impressions((long) ccounts * 10)
                                .clicks(ccounts % 500)
                                .hostingEnabled((cflags & 1) != 0)
                                .aiManaged((cflags & 2) != 0)
                                .portfolio(((cflags & 4) != 0) ? cname + "PF" : null)
                                .build());
    }
}
