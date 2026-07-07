package com.adpilot.modules.importcenter.service.impl;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.importcenter.entity.RawSearchTermReportEntity;
import com.adpilot.modules.importcenter.mapper.ImportJobMapper;
import com.adpilot.modules.importcenter.mapper.ImportRowErrorMapper;
import com.adpilot.modules.importcenter.mapper.RawSearchTermReportMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that committing a performance row is idempotent per
 * {@code (store_id, entity_type, entity_id, report_date)} — the same columns as
 * the {@code uq_perf_daily} unique constraint (H2).
 *
 * <p>Re-importing the same day is a full restatement, so the second import must
 * <em>overwrite</em> the metrics rather than insert a second (double-counting)
 * row. The {@link PerformanceDailyMapper} is a stateful stub that models the
 * unique-key table: {@code selectOne} returns the currently stored row (or
 * {@code null}), {@code insert} adds it once, and {@code updateById} replaces
 * the stored metrics in place.
 */
class PerformanceDailyUpsertTest {

    @Test
    void reimportingSameDayOverwritesInsteadOfDoubleCounting() {
        // --- stateful "table" keyed by the unique tuple (single logical key here)
        List<PerformanceDailyEntity> table = new ArrayList<>();
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);

        when(performanceDailyMapper.selectOne(any()))
                .thenAnswer(inv -> table.isEmpty() ? null : table.get(0));
        when(performanceDailyMapper.insert(any(PerformanceDailyEntity.class))).thenAnswer(inv -> {
            PerformanceDailyEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            table.add(e);
            return 1;
        });
        // updateById mutates the already-stored instance (the service updates the
        // very object selectOne returned), so no extra bookkeeping is needed.
        when(performanceDailyMapper.updateById(any(PerformanceDailyEntity.class))).thenReturn(1);

        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceReferenceService marketplaceReferenceService = mock(MarketplaceReferenceService.class);
        when(marketplaceReferenceService.timezoneForMarketplace(any())).thenReturn(ZoneId.of("UTC"));

        ImportServiceImpl service = new ImportServiceImpl(
                mock(ImportJobMapper.class),
                mock(ImportRowErrorMapper.class),
                mock(RawSearchTermReportMapper.class),
                mock(CampaignMapper.class),
                mock(AdGroupMapper.class),
                mock(SearchTermMapper.class),
                performanceDailyMapper,
                storeMapper,
                marketplaceReferenceService,
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(com.adpilot.common.security.DataScopeService.class));

        UUID storeId = UUID.randomUUID();
        CampaignEntity campaign = CampaignEntity.builder().id(UUID.randomUUID()).build();
        LocalDate day = LocalDate.of(2024, 6, 1);

        // First import of the day: spend 10, sales 40, clicks 5, orders 2, impressions 100.
        invokeUpsert(service, raw(storeId, day, "10.00", "40.00", 5, 100, 2), campaign);
        // Re-import the SAME (store, entity, date) with restated values.
        invokeUpsert(service, raw(storeId, day, "12.50", "50.00", 6, 120, 3), campaign);

        // Exactly one row, inserted once and overwritten once (never double-inserted).
        verify(performanceDailyMapper, times(1)).insert(any(PerformanceDailyEntity.class));
        verify(performanceDailyMapper, times(1)).updateById(any(PerformanceDailyEntity.class));
        assertThat(table).hasSize(1);

        PerformanceDailyEntity row = table.get(0);
        // Latest values win (overwrite, not additive: spend would be 22.50 if additive).
        assertThat(row.getSpend()).isEqualByComparingTo("12.50");
        assertThat(row.getSales()).isEqualByComparingTo("50.00");
        assertThat(row.getClicks()).isEqualTo(6);
        assertThat(row.getImpressions()).isEqualTo(120L);
        assertThat(row.getOrders()).isEqualTo(3);
        // Derived metric reflects the restated values: acos = 12.50 / 50.00 = 0.25.
        assertThat(row.getAcos()).isEqualByComparingTo("0.25");
        // Key columns are stable.
        assertThat(row.getStoreId()).isEqualTo(storeId);
        assertThat(row.getEntityType()).isEqualTo("campaign");
        assertThat(row.getEntityId()).isEqualTo(campaign.getId());
        assertThat(row.getDate()).isEqualTo(day);
    }

    /**
     * M2: when a report line carries no explicit {@code reportDate}, the fallback
     * "today" must be computed in the store's <em>marketplace</em> timezone (via
     * {@link MarketplaceReferenceService}), not the server JVM zone. We pin the
     * marketplace to UTC+14 (Pacific/Kiritimati) — the earliest civil day on earth —
     * so the resolved date equals that zone's "today" and the marketplace lookup is
     * proven to drive the result.
     */
    @Test
    void nullReportDateDefaultsToMarketplaceZoneToday() {
        List<PerformanceDailyEntity> table = new ArrayList<>();
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        when(performanceDailyMapper.selectOne(any()))
                .thenAnswer(inv -> table.isEmpty() ? null : table.get(0));
        when(performanceDailyMapper.insert(any(PerformanceDailyEntity.class))).thenAnswer(inv -> {
            PerformanceDailyEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            table.add(e);
            return 1;
        });

        UUID storeId = UUID.randomUUID();
        UUID marketplaceId = UUID.randomUUID();
        StoreEntity store = StoreEntity.builder().id(storeId).marketplaceId(marketplaceId).build();
        StoreMapper storeMapper = mock(StoreMapper.class);
        when(storeMapper.selectById(storeId)).thenReturn(store);

        ZoneId marketplaceZone = ZoneId.of("Pacific/Kiritimati"); // UTC+14
        MarketplaceReferenceService marketplaceReferenceService = mock(MarketplaceReferenceService.class);
        when(marketplaceReferenceService.timezoneForMarketplace(marketplaceId)).thenReturn(marketplaceZone);

        ImportServiceImpl service = new ImportServiceImpl(
                mock(ImportJobMapper.class),
                mock(ImportRowErrorMapper.class),
                mock(RawSearchTermReportMapper.class),
                mock(CampaignMapper.class),
                mock(AdGroupMapper.class),
                mock(SearchTermMapper.class),
                performanceDailyMapper,
                storeMapper,
                marketplaceReferenceService,
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(com.adpilot.common.security.DataScopeService.class));

        CampaignEntity campaign = CampaignEntity.builder().id(UUID.randomUUID()).build();
        RawSearchTermReportEntity raw = RawSearchTermReportEntity.builder()
                .storeId(storeId)
                .reportDate(null) // no explicit date -> fallback to marketplace "today"
                .customerSearchTerm("term")
                .spend(new BigDecimal("1.00"))
                .sales(new BigDecimal("2.00"))
                .clicks(1)
                .impressions(10)
                .orders(1)
                .build();

        invokeUpsert(service, raw, campaign);

        assertThat(table).hasSize(1);
        assertThat(table.get(0).getDate()).isEqualTo(LocalDate.now(marketplaceZone));
        // The marketplace timezone lookup is what drove the date resolution.
        verify(marketplaceReferenceService).timezoneForMarketplace(marketplaceId);
    }

    private static void invokeUpsert(ImportServiceImpl service, RawSearchTermReportEntity raw, CampaignEntity campaign) {
        ReflectionTestUtils.invokeMethod(service, "upsertPerformanceDaily", raw, campaign, null);
    }

    private static RawSearchTermReportEntity raw(UUID storeId, LocalDate date, String spend, String sales,
                                                 int clicks, int impressions, int orders) {
        return RawSearchTermReportEntity.builder()
                .storeId(storeId)
                .reportDate(date)
                .customerSearchTerm("term")
                .spend(new BigDecimal(spend))
                .sales(new BigDecimal(sales))
                .clicks(clicks)
                .impressions(impressions)
                .orders(orders)
                .build();
    }
}
