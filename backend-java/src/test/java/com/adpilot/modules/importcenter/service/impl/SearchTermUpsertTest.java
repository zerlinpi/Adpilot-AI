package com.adpilot.modules.importcenter.service.impl;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.SearchTermEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.importcenter.entity.RawSearchTermReportEntity;
import com.adpilot.modules.importcenter.mapper.ImportJobMapper;
import com.adpilot.modules.importcenter.mapper.ImportRowErrorMapper;
import com.adpilot.modules.importcenter.mapper.RawSearchTermReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M4 — {@link ImportServiceImpl#upsertSearchTerm} correctness:
 *
 * <ol>
 *   <li><b>NPE hardening.</b> Aggregating into an existing row whose counters /
 *       amounts are {@code null} must not throw; nulls are treated as zero.</li>
 *   <li><b>Additive grain.</b> Importing the same
 *       {@code (campaign_id, ad_group_id, search_term)} grain twice must land in a
 *       single row whose metrics are the SUM of both imports (additive semantics,
 *       unlike the performance-daily overwrite).</li>
 * </ol>
 *
 * <p>The {@link SearchTermMapper} is a stateful stub modelling the unique-grain
 * table: {@code selectOne} returns the stored row (or {@code null}), {@code insert}
 * stores it once, and {@code updateById} mutates the stored instance in place.</p>
 */
class SearchTermUpsertTest {

    private static ImportServiceImpl serviceWith(SearchTermMapper searchTermMapper) {
        return new ImportServiceImpl(
                mock(ImportJobMapper.class),
                mock(ImportRowErrorMapper.class),
                mock(RawSearchTermReportMapper.class),
                mock(CampaignMapper.class),
                mock(AdGroupMapper.class),
                searchTermMapper,
                mock(PerformanceDailyMapper.class),
                mock(com.adpilot.modules.store.mapper.StoreMapper.class),
                mock(com.adpilot.modules.store.service.MarketplaceReferenceService.class),
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(com.adpilot.common.security.DataScopeService.class));
    }

    private static SearchTermMapper statefulMapper(List<SearchTermEntity> table) {
        SearchTermMapper mapper = mock(SearchTermMapper.class);
        when(mapper.selectOne(any())).thenAnswer(inv -> table.isEmpty() ? null : table.get(0));
        when(mapper.insert(any(SearchTermEntity.class))).thenAnswer(inv -> {
            SearchTermEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            table.add(e);
            return 1;
        });
        when(mapper.updateById(any(SearchTermEntity.class))).thenReturn(1);
        return mapper;
    }

    private static void invokeUpsert(ImportServiceImpl service, RawSearchTermReportEntity raw,
                                     CampaignEntity campaign) {
        ReflectionTestUtils.invokeMethod(service, "upsertSearchTerm", raw, campaign, null);
    }

    private static RawSearchTermReportEntity raw(UUID storeId, String term, String spend, String sales,
                                                 int clicks, long impressions, int orders) {
        return RawSearchTermReportEntity.builder()
                .storeId(storeId)
                .customerSearchTerm(term)
                .spend(new BigDecimal(spend))
                .sales(new BigDecimal(sales))
                .clicks(clicks)
                .impressions((int) impressions)
                .orders(orders)
                .build();
    }

    @Test
    void aggregatingIntoExistingRowWithNullMetricsDoesNotNpeAndSumsCorrectly() {
        List<SearchTermEntity> table = new ArrayList<>();
        UUID storeId = UUID.randomUUID();
        CampaignEntity campaign = CampaignEntity.builder().id(UUID.randomUUID()).build();

        // A legacy/partial row with every metric NULL (no @Builder.Default applied
        // because it came from the DB with nulls).
        SearchTermEntity legacy = new SearchTermEntity();
        legacy.setId(UUID.randomUUID());
        legacy.setCampaignId(campaign.getId());
        legacy.setStoreId(storeId);
        legacy.setSearchTerm("running shoes");
        legacy.setImpressions(null);
        legacy.setClicks(null);
        legacy.setSpend(null);
        legacy.setSales(null);
        legacy.setOrders(null);
        table.add(legacy);

        SearchTermMapper mapper = statefulMapper(table);
        ImportServiceImpl service = serviceWith(mapper);

        assertThatCode(() ->
                invokeUpsert(service, raw(storeId, "running shoes", "3.50", "20.00", 4, 100, 2), campaign))
                .doesNotThrowAnyException();

        // Nulls treated as zero, so totals equal the incoming raw values exactly.
        SearchTermEntity row = table.get(0);
        assertThat(row.getImpressions()).isEqualTo(100L);
        assertThat(row.getClicks()).isEqualTo(4);
        assertThat(row.getSpend()).isEqualByComparingTo("3.50");
        assertThat(row.getSales()).isEqualByComparingTo("20.00");
        assertThat(row.getOrders()).isEqualTo(2);
        // Derived: acos = 3.50 / 20.00 = 0.175
        assertThat(row.getAcos()).isEqualByComparingTo("0.175");
        verify(mapper, times(1)).updateById(any(SearchTermEntity.class));
        verify(mapper, times(1)).selectOne(any());
    }

    @Test
    void sameGrainImportedTwiceAggregatesIntoOneRow() {
        List<SearchTermEntity> table = new ArrayList<>();
        UUID storeId = UUID.randomUUID();
        CampaignEntity campaign = CampaignEntity.builder().id(UUID.randomUUID()).build();

        SearchTermMapper mapper = statefulMapper(table);
        ImportServiceImpl service = serviceWith(mapper);

        // First import inserts a fresh row.
        invokeUpsert(service, raw(storeId, "wireless earbuds", "5.00", "30.00", 6, 200, 3), campaign);
        // Second import of the SAME grain aggregates additively into the same row.
        invokeUpsert(service, raw(storeId, "wireless earbuds", "2.50", "10.00", 4, 100, 1), campaign);

        // Exactly one row: inserted once, updated (aggregated) once.
        verify(mapper, times(1)).insert(any(SearchTermEntity.class));
        verify(mapper, times(1)).updateById(any(SearchTermEntity.class));
        assertThat(table).hasSize(1);

        SearchTermEntity row = table.get(0);
        // Additive totals: 200+100, 6+4, 5.00+2.50, 30.00+10.00, 3+1.
        assertThat(row.getImpressions()).isEqualTo(300L);
        assertThat(row.getClicks()).isEqualTo(10);
        assertThat(row.getSpend()).isEqualByComparingTo("7.50");
        assertThat(row.getSales()).isEqualByComparingTo("40.00");
        assertThat(row.getOrders()).isEqualTo(4);
    }
}
