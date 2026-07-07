package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.GoogleAdsConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import com.adpilot.modules.apisync.vo.GoogleAdsPerformanceReportVo;
import com.adpilot.modules.apisync.vo.GoogleAdsReadResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
 * Unit tests for {@link GoogleAdsReadServiceImpl} (platform-workspace-rbac Req 6).
 *
 * <p>Covers the four read-side behaviors the GoogleAds_Module relies on:
 * displaying aggregated campaigns (Req 6.3) and date-ranged performance
 * reports (Req 6.4), surfacing a connect prompt when the Store has no active
 * Google Ads connection (Req 6.6), and returning an error indicator (the basis
 * for the frontend retry control) without mutating prior data when the
 * connector pull fails (Req 6.5).</p>
 *
 * <p>The data-access collaborators ({@link PlatformConnectionMapper},
 * {@link GoogleAdsConnector}, {@link CryptoUtil}) are mocked; a real
 * {@link ObjectMapper} is used since the service only invokes it to parse a
 * connection config blob (left absent here so decryption is never exercised).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleAdsReadServiceImpl — Google Ads read service (Req 6.3–6.6)")
class GoogleAdsReadServiceImplTest {

    @Mock
    private PlatformConnectionMapper platformConnectionMapper;

    @Mock
    private GoogleAdsConnector googleAdsConnector;

    @Mock
    private CryptoUtil cryptoUtil;

    private GoogleAdsReadServiceImpl service;

    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GoogleAdsReadServiceImpl(
                platformConnectionMapper, googleAdsConnector, cryptoUtil, new ObjectMapper());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** An active Google Ads connection with no stored config (decryption skipped). */
    private PlatformConnectionEntity activeConnection() {
        return PlatformConnectionEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .platform("google_ads")
                .status("connected")
                .configEncrypted(null)
                .build();
    }

    /** Build a single campaign/day ad-report record matching the connector's field shape. */
    private ExternalRecord row(String campaignId, String name, String status, String date,
                               Object budgetMicros, long impressions, long clicks,
                               Object costMicros, double conversions, Object conversionsValue) {
        Map<String, Object> campaign = new LinkedHashMap<>();
        campaign.put("id", campaignId);
        campaign.put("name", name);
        campaign.put("status", status);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("impressions", impressions);
        metrics.put("clicks", clicks);
        metrics.put("costMicros", costMicros);
        metrics.put("conversions", conversions);
        metrics.put("conversionsValue", conversionsValue);

        Map<String, Object> segments = new LinkedHashMap<>();
        segments.put("date", date);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("campaign", campaign);
        fields.put("metrics", metrics);
        fields.put("segments", segments);
        if (budgetMicros != null) {
            Map<String, Object> campaignBudget = new LinkedHashMap<>();
            campaignBudget.put("amountMicros", budgetMicros);
            fields.put("campaignBudget", campaignBudget);
        }

        String externalId = campaignId + ":" + date;
        return new ExternalRecord(externalId, "ad_report", Instant.now(), status, fields);
    }

    private void connectorReturns(ExternalRecord... rows) {
        when(googleAdsConnector.pullAdReports(any(ConnectionContext.class), any(), any(PageCursor.class)))
                .thenReturn(ExternalPage.last(new ArrayList<>(List.of(rows))));
    }

    // ── Req 6.3: campaign display ───────────────────────────────────────────────

    @Nested
    @DisplayName("getCampaigns (Req 6.1, 6.3)")
    class GetCampaigns {

        @Test
        @DisplayName("returns OK with aggregated name, status, budget, and metrics per campaign")
        void aggregatesCampaignRows() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            // Two days of the same campaign should aggregate into one campaign row.
            connectorReturns(
                    row("111", "Brand", "ENABLED", "2024-01-01", "5000000",
                            100, 10, "2000000", 2, "50"),
                    row("111", "Brand", "ENABLED", "2024-01-02", "5000000",
                            50, 5, "1000000", 1, "25"));

            GoogleAdsReadResult<List<GoogleAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isOk()).isTrue();
            assertThat(result.getData()).hasSize(1);
            GoogleAdsCampaignVo campaign = result.getData().get(0);
            assertThat(campaign.getCampaignId()).isEqualTo("111");
            assertThat(campaign.getName()).isEqualTo("Brand");
            assertThat(campaign.getStatus()).isEqualTo("ENABLED");
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
                    row("111", "Brand", "ENABLED", "2024-01-01", "5000000",
                            100, 10, "2000000", 2, "50"),
                    row("222", "Generic", "PAUSED", "2024-01-01", "3000000",
                            40, 4, "800000", 1, "10"));

            GoogleAdsReadResult<List<GoogleAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isOk()).isTrue();
            assertThat(result.getData()).extracting(GoogleAdsCampaignVo::getCampaignId)
                    .containsExactly("111", "222");
            assertThat(result.getData().get(1).getStatus()).isEqualTo("PAUSED");
        }
    }

    // ── Req 6.4: performance report display ─────────────────────────────────────

    @Nested
    @DisplayName("getPerformanceReport (Req 6.2, 6.4)")
    class GetPerformanceReport {

        @Test
        @DisplayName("returns OK with per-day rows and range totals for the selected window")
        void aggregatesPerformanceByDay() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            connectorReturns(
                    row("111", "Brand", "ENABLED", "2024-01-01", "5000000",
                            100, 10, "2000000", 2, "50"),
                    row("111", "Brand", "ENABLED", "2024-01-02", "5000000",
                            50, 5, "1000000", 1, "25"));

            GoogleAdsReadResult<GoogleAdsPerformanceReportVo> result =
                    service.getPerformanceReport(storeId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));

            assertThat(result.isOk()).isTrue();
            GoogleAdsPerformanceReportVo report = result.getData();
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
                    row("111", "Brand", "ENABLED", "2024-01-01", "5000000",
                            100, 10, "2000000", 2, "50"),
                    // Outside [2024-01-01, 2024-01-02] — must be filtered out.
                    row("111", "Brand", "ENABLED", "2024-02-15", "5000000",
                            999, 99, "9000000", 9, "999"));

            GoogleAdsReadResult<GoogleAdsPerformanceReportVo> result =
                    service.getPerformanceReport(storeId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));

            assertThat(result.isOk()).isTrue();
            assertThat(result.getData().getRows()).hasSize(1);
            assertThat(result.getData().getTotalImpressions()).isEqualTo(100);
        }
    }

    // ── Req 6.6: connect prompt when no active connection ───────────────────────

    @Nested
    @DisplayName("connect prompt when no active connection (Req 6.6)")
    class ConnectPrompt {

        @Test
        @DisplayName("getCampaigns returns CONNECT_PROMPT and never calls the connector")
        void campaignsConnectPrompt() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            GoogleAdsReadResult<List<GoogleAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isConnectPrompt()).isTrue();
            assertThat(result.isError()).isFalse();
            assertThat(result.getData()).isNull();
            assertThat(result.getMessage()).isNotBlank();
            verifyNoInteractions(googleAdsConnector);
        }

        @Test
        @DisplayName("getPerformanceReport returns CONNECT_PROMPT and never calls the connector")
        void reportConnectPrompt() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            GoogleAdsReadResult<GoogleAdsPerformanceReportVo> result =
                    service.getPerformanceReport(storeId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));

            assertThat(result.isConnectPrompt()).isTrue();
            assertThat(result.getData()).isNull();
            verifyNoInteractions(googleAdsConnector);
        }

        @Test
        @DisplayName("a null storeId yields a connect prompt without querying the connector")
        void nullStoreIdConnectPrompt() {
            GoogleAdsReadResult<List<GoogleAdsCampaignVo>> result = service.getCampaigns(null);

            assertThat(result.isConnectPrompt()).isTrue();
            verifyNoInteractions(googleAdsConnector);
            verify(platformConnectionMapper, never()).selectOne(any());
        }
    }

    // ── Req 6.5: error/retry path on retrieval failure ──────────────────────────

    @Nested
    @DisplayName("error indicator on retrieval failure (Req 6.5)")
    class RetrievalFailure {

        @Test
        @DisplayName("getCampaigns returns ERROR with the failure reason and no data when the connector throws")
        void campaignsErrorOnConnectorFailure() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            when(googleAdsConnector.pullAdReports(any(ConnectionContext.class), any(), any(PageCursor.class)))
                    .thenThrow(new RuntimeException("Google Ads API unavailable"));

            GoogleAdsReadResult<List<GoogleAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isError()).isTrue();
            assertThat(result.isOk()).isFalse();
            assertThat(result.getData()).isNull();
            assertThat(result.getMessage()).isEqualTo("Google Ads API unavailable");
        }

        @Test
        @DisplayName("getPerformanceReport returns ERROR with the failure reason when the connector throws")
        void reportErrorOnConnectorFailure() {
            when(platformConnectionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(activeConnection());
            when(googleAdsConnector.pullAdReports(any(ConnectionContext.class), any(), any(PageCursor.class)))
                    .thenThrow(new RuntimeException("rate limited"));

            GoogleAdsReadResult<GoogleAdsPerformanceReportVo> result =
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
            when(googleAdsConnector.pullAdReports(any(ConnectionContext.class), any(), any(PageCursor.class)))
                    .thenThrow(new IllegalStateException("wrapper",
                            new RuntimeException("token expired")));

            GoogleAdsReadResult<List<GoogleAdsCampaignVo>> result = service.getCampaigns(storeId);

            assertThat(result.isError()).isTrue();
            assertThat(result.getMessage()).isEqualTo("token expired");
        }
    }
}
