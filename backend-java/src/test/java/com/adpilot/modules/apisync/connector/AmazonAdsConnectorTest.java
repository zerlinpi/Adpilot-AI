package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.advertising.hosting.ReportLifecycleClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the Amazon Ads ad-report metric mapping (Req 8.1.3): Amazon's
 * per-metric key variants are folded onto the internal advertising performance
 * field names so the mapping/upsert pipeline can persist them.
 */
class AmazonAdsConnectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AmazonAdsConnector connector =
            new AmazonAdsConnector(mapper, new AmazonLwaClient(mapper, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)), mock(ReportLifecycleClient.class), new com.adpilot.common.config.HttpClientFactory(10, 30, 60));

    @Test
    void mapsAmazonMetricVariantsToInternalFieldNames() {
        ObjectNode row = mapper.createObjectNode();
        row.put("campaignId", "C-100");
        row.put("date", "2024-03-01");
        row.put("impressions", 1000);
        row.put("clicks", 50);
        row.put("cost", 12.50);
        row.put("attributedSales14d", 200.00);
        row.put("attributedConversions14d", 8);

        Map<String, Object> fields = connector.mapMetrics(row);

        assertThat(fields.get(AmazonAdsConnector.F_EXTERNAL_CAMPAIGN_ID)).isEqualTo("C-100");
        assertThat(fields.get(AmazonAdsConnector.F_DATE)).isEqualTo("2024-03-01");
        assertThat(fields.get(AmazonAdsConnector.F_ENTITY_TYPE)).isEqualTo("campaign");
        assertThat(((Number) fields.get(AmazonAdsConnector.F_IMPRESSIONS)).longValue()).isEqualTo(1000L);
        assertThat(((Number) fields.get(AmazonAdsConnector.F_CLICKS)).intValue()).isEqualTo(50);
        assertThat(((Number) fields.get(AmazonAdsConnector.F_SPEND)).doubleValue()).isEqualTo(12.50);
        // attributedSales14d -> sales, attributedConversions14d -> orders
        assertThat(((Number) fields.get(AmazonAdsConnector.F_SALES)).doubleValue()).isEqualTo(200.00);
        assertThat(((Number) fields.get(AmazonAdsConnector.F_ORDERS)).intValue()).isEqualTo(8);
    }

    @Test
    void prefersCanonicalKeysOverAmazonVariants() {
        ObjectNode row = mapper.createObjectNode();
        row.put("campaign_id", "C-200");
        row.put("reportDate", "2024-03-02");
        row.put("spend", 5.0);
        row.put("cost", 9.0);          // should be ignored in favor of "spend"
        row.put("sales", 30.0);
        row.put("attributedSales14d", 99.0); // ignored in favor of "sales"
        row.put("orders", 3);

        Map<String, Object> fields = connector.mapMetrics(row);

        assertThat(fields.get(AmazonAdsConnector.F_EXTERNAL_CAMPAIGN_ID)).isEqualTo("C-200");
        assertThat(fields.get(AmazonAdsConnector.F_DATE)).isEqualTo("2024-03-02");
        assertThat(((Number) fields.get(AmazonAdsConnector.F_SPEND)).doubleValue()).isEqualTo(5.0);
        assertThat(((Number) fields.get(AmazonAdsConnector.F_SALES)).doubleValue()).isEqualTo(30.0);
        assertThat(((Number) fields.get(AmazonAdsConnector.F_ORDERS)).intValue()).isEqualTo(3);
    }

    @Test
    void omitsMetricsThatAreAbsent() {
        ObjectNode row = mapper.createObjectNode();
        row.put("campaignId", "C-300");
        row.put("date", "2024-03-03");
        row.put("impressions", 10);

        Map<String, Object> fields = connector.mapMetrics(row);

        assertThat(fields).containsKey(AmazonAdsConnector.F_IMPRESSIONS);
        assertThat(fields).doesNotContainKeys(
                AmazonAdsConnector.F_SPEND,
                AmazonAdsConnector.F_SALES,
                AmazonAdsConnector.F_ORDERS,
                AmazonAdsConnector.F_CLICKS);
    }
}
