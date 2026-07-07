package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.TikTokAdsReadConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.apisync.vo.TikTokAdsCampaignVo;
import com.adpilot.modules.apisync.vo.TikTokAdsPerformanceReportVo;
import com.adpilot.modules.apisync.vo.TikTokAdsReadResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TikTokAdsReadServiceImpl}, symmetric to
 * {@code GoogleAdsReadServiceImplTest}.
 *
 * <p>Covers the four read-side behaviors the TikTok Ads read surface relies on:
 * displaying aggregated campaigns and date-ranged performance reports, surfacing
 * a connect prompt when the Store has no active TikTok Ads connection (never
 * fabricated data), and returning an error indicator without mutating prior data
 * when the connector pull fails.</p>
 *
 * <p>The data-access collaborators ({@link PlatformConnectionMapper},
 * {@link TikTokAdsReadConnector}, {@link CryptoUtil}) are mocked so the test
 * never touches the network; a real {@link ObjectMapper} is used since the
 * service only invokes it to parse a connection config blob (left absent here so
 * decryption is never exercised).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TikTokAdsReadServiceImpl — TikTok Ads read service")
class TikTokAdsReadServiceImplTest {

    @Mock
    private PlatformConnectionMapper platformConnectionMapper;

    @Mock
    private TikTokAdsReadConnector tikTokAdsReadConnector;

    @Mock
    private CryptoUtil cryptoUtil;

    private TikTokAdsReadServiceImpl service;

    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TikTokAdsReadServiceImpl(
                platformConnectionMapper, tikTokAdsReadConnector, cryptoUtil, new ObjectMapper());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** An active TikTok Ads connection with no stored config (decryption skipped). */
    private PlatformConnectionEntity activeConnection() {
        return PlatformConnectionEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .platform("tiktok_ads")
                .status("connected")
                .configEncrypted(null)
                .build();
    }

    /**
     * Build a single campaign/day ad-report record matching the canonical field
     * shape the {@link TikTokAdsReadConnector} emits (TikTok spend/budget are in
     * account currency, not micros).
     */
    private ExternalRecord row(String campaignId, String name, String status, String date,
                               Object budget, long impressions, long clicks,
                               Object spend, double conversions, Object conversionValue) {
        Map<String, Object> campaign = new LinkedHashMap<>();
        campaign.put("id", campaignId);
        campaign.put("name", name);
        campaign.put("status", status);
        campaign.put("budget", budget);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("impressions", impressions);
        metrics.put("clicks", clicks);
        metrics.put("spend", spend);
        metrics.put("conversions", conversions);
        metrics.put("conversionValue", conversionValue);

        Map<String, Object> segments = new LinkedHashMap<>();
        segments.put("date", date);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("campaign", campaign);
        fields.put("metrics", metrics);
        fields.put("segments", segments);

        String externalId = campaignId + ":" + date;
        return new ExternalRecord(externalId, "ad_report", Instant.now(), null, fields);
    }

    private void connectorReturns(ExternalRecord... rows) {
        when(tikTokAdsReadConnector.pullAdReports(any(ConnectionContext.class), any(), any(PageCursor.class)))
                .thenReturn(ExternalPage.last(new ArrayList<>(List.of(rows))));
    }

    // ── campaign display ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getCampaigns")
    class GetCampaigns {

        @Test
        @DisplayName("returns OK with aggregated name, status, budget, and metrics per campaign")
        void aggregatesCampaignRows() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            // Two days of the same campaign should aggregate into one campaign row.
            connectorReturns(
                    row("111", "Brand", "ENABLE", "2024-01-01", "5.00",
                            100, 10, "2.00", 2, "50"),
                    row("111", "Brand", "ENABLE", "2024-01-02", "5.00",
                            50, 5, "1.00", 1, "25"));

            TikTokAdsReadResult<List<TikTokAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isOk()).isTrue();
            assertThat(result.getData()).hasSize(1);
            TikTokAdsCampaignVo campaign = result.getData().get(0);
            assertThat(campaign.getCampaignId()).isEqualTo("111");
            assertThat(campaign.getName()).isEqualTo("Brand");
            assertThat(campaign.getStatus()).isEqualTo("ENABLE");
            assertThat(campaign.getBudget()).isEqualByComparingTo("5.00");
            assertThat(campaign.getImpressions()).isEqualTo(150);
            assertThat(campaign.getClicks()).isEqualTo(15);
            assertThat(campaign.getCost()).isEqualByComparingTo("3.00");
            assertThat(campaign.getConversions()).isEqualTo(3.0);
            assertThat(campaign.getConversionValue()).isEqualByComparingTo("75.00");
        }

        @Test
        @DisplayName("keeps distinct campaigns separate, preserving first-seen order")
        void separatesDistinctCampaigns() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            connectorReturns(
                    row("111", "Brand", "ENABLE", "2024-01-01", "5.00",
                            100, 10, "2.00", 2, "50"),
                    row("222", "Generic", "DISABLE", "2024-01-01", "3.00",
                            40, 4, "0.80", 1, "10"));

            TikTokAdsReadResult<List<TikTokAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isOk()).isTrue();
            assertThat(result.getData()).extracting(TikTokAdsCampaignVo::getCampaignId)
                    .containsExactly("111", "222");
            assertThat(result.getData().get(1).getStatus()).isEqualTo("DISABLE");
        }
    }

    // ── performance report display ─────────────────────────────────────────────

    @Nested
    @DisplayName("getPerformanceReport")
    class GetPerformanceReport {

        @Test
        @DisplayName("returns OK with per-day rows and range totals for the selected window")
        void aggregatesPerformanceByDay() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            connectorReturns(
                    row("111", "Brand", "ENABLE", "2024-01-01", "5.00",
                            100, 10, "2.00", 2, "50"),
                    row("111", "Brand", "ENABLE", "2024-01-02", "5.00",
                            50, 5, "1.00", 1, "25"));

            TikTokAdsReadResult<TikTokAdsPerformanceReportVo> result =
                    service.getPerformanceReport(storeId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));

            assertThat(result.isOk()).isTrue();
            TikTokAdsPerformanceReportVo report = result.getData();
            assertThat(report.getFrom()).isEqualTo(LocalDate.of(2024, 1, 1));
            assertThat(report.getTo()).isEqualTo(LocalDate.of(2024, 1, 2));
            assertThat(report.getRows()).hasSize(2);
            assertThat(report.getRows()).extracting(r -> r.getDate())
                    .containsExactly(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));
            assertThat(report.getTotalImpressions()).isEqualTo(150);
            assertThat(report.getTotalClicks()).isEqualTo(15);
            assertThat(report.getTotalCost()).isEqualByComparingTo("3.00");
            assertThat(report.getTotalConversions()).isEqualTo(3.0);
            assertThat(report.getTotalConversionValue()).isEqualByComparingTo("75.00");
        }

        @Test
        @DisplayName("excludes rows whose date falls outside the requested range")
        void filtersOutOfRangeDays() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            connectorReturns(
                    row("111", "Brand", "ENABLE", "2024-01-01", "5.00",
                            100, 10, "2.00", 2, "50"),
                    // Outside [2024-01-01, 2024-01-02] — must be filtered out.
                    row("111", "Brand", "ENABLE", "2024-02-15", "5.00",
                            999, 99, "9.00", 9, "999"));

            TikTokAdsReadResult<TikTokAdsPerformanceReportVo> result =
                    service.getPerformanceReport(storeId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));

            assertThat(result.isOk()).isTrue();
            assertThat(result.getData().getRows()).hasSize(1);
            assertThat(result.getData().getTotalImpressions()).isEqualTo(100);
        }
    }

    // ── connect prompt when no active connection ───────────────────────────────

    @Nested
    @DisplayName("connect prompt when no active connection")
    class ConnectPrompt {

        @Test
        @DisplayName("getCampaigns returns CONNECT_PROMPT and never calls the connector")
        void campaignsConnectPrompt() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            TikTokAdsReadResult<List<TikTokAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isConnectPrompt()).isTrue();
            assertThat(result.isError()).isFalse();
            assertThat(result.getData()).isNull();
            assertThat(result.getMessage()).isNotBlank();
            verifyNoInteractions(tikTokAdsReadConnector);
        }

        @Test
        @DisplayName("getPerformanceReport returns CONNECT_PROMPT and never calls the connector")
        void reportConnectPrompt() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            TikTokAdsReadResult<TikTokAdsPerformanceReportVo> result =
                    service.getPerformanceReport(storeId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));

            assertThat(result.isConnectPrompt()).isTrue();
            assertThat(result.getData()).isNull();
            verifyNoInteractions(tikTokAdsReadConnector);
        }

        @Test
        @DisplayName("a null storeId yields a connect prompt without querying the connector")
        void nullStoreIdConnectPrompt() {
            TikTokAdsReadResult<List<TikTokAdsCampaignVo>> result = service.getCampaigns(null);

            assertThat(result.isConnectPrompt()).isTrue();
            verifyNoInteractions(tikTokAdsReadConnector);
            verify(platformConnectionMapper, never()).selectOne(any());
        }
    }

    // ── error/retry path on retrieval failure ──────────────────────────────────

    @Nested
    @DisplayName("error indicator on retrieval failure")
    class RetrievalFailure {

        @Test
        @DisplayName("getCampaigns returns ERROR with the failure reason and no data when the connector throws")
        void campaignsErrorOnConnectorFailure() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            when(tikTokAdsReadConnector.pullAdReports(any(ConnectionContext.class), any(), any(PageCursor.class)))
                    .thenThrow(new RuntimeException("TikTok Ads API unavailable"));

            TikTokAdsReadResult<List<TikTokAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isError()).isTrue();
            assertThat(result.isOk()).isFalse();
            assertThat(result.getData()).isNull();
            assertThat(result.getMessage()).isEqualTo("TikTok Ads API unavailable");
        }

        @Test
        @DisplayName("getPerformanceReport returns ERROR with the failure reason when the connector throws")
        void reportErrorOnConnectorFailure() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            when(tikTokAdsReadConnector.pullAdReports(any(ConnectionContext.class), any(), any(PageCursor.class)))
                    .thenThrow(new RuntimeException("rate limited"));

            TikTokAdsReadResult<TikTokAdsPerformanceReportVo> result =
                    service.getPerformanceReport(storeId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));

            assertThat(result.isError()).isTrue();
            assertThat(result.getData()).isNull();
            assertThat(result.getMessage()).isEqualTo("rate limited");
        }

        @Test
        @DisplayName("unwraps the root cause message of a nested exception")
        void unwrapsRootCause() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            when(tikTokAdsReadConnector.pullAdReports(any(ConnectionContext.class), any(), any(PageCursor.class)))
                    .thenThrow(new IllegalStateException("wrapper",
                            new RuntimeException("token expired")));

            TikTokAdsReadResult<List<TikTokAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isError()).isTrue();
            assertThat(result.getMessage()).isEqualTo("token expired");
        }
    }
}
