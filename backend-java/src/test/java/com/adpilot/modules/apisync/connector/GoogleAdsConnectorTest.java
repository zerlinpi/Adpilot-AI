package com.adpilot.modules.apisync.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for {@link GoogleAdsConnector}: GAQL URL building, query
 * construction (with/without an incremental watermark), request-body shape
 * (including page-token continuation), and customer-id normalization. No HTTP is
 * performed; only the pure helper methods are exercised.
 */
@DisplayName("GoogleAdsConnector — GAQL URL/query/paging building")
class GoogleAdsConnectorTest {

    private GoogleAdsConnector connector;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // The token client is never invoked by the pure helpers under test.
        connector = new GoogleAdsConnector(objectMapper, new GoogleAdsTokenClient(objectMapper, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)), new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
    }

    @Test
    @DisplayName("platform() returns 'google_ads' and self-validates credentials")
    void platformKey() {
        assertThat(connector.platform()).isEqualTo("google_ads");
        assertThat(connector.selfValidatesCredentials()).isTrue();
    }

    @Test
    @DisplayName("search URL targets the versioned googleAds:search endpoint")
    void searchUrl() {
        assertThat(connector.buildSearchUrl("1234567890"))
                .isEqualTo("https://googleads.googleapis.com/v17/customers/1234567890/googleAds:search");
    }

    @Test
    @DisplayName("customer id normalization strips dashes and whitespace")
    void normalizeCustomerId() {
        assertThat(GoogleAdsConnector.normalizeCustomerId("123-456-7890")).isEqualTo("1234567890");
        assertThat(GoogleAdsConnector.normalizeCustomerId(" 123 456 ")).isEqualTo("123456");
        assertThat(GoogleAdsConnector.normalizeCustomerId(null)).isEqualTo("");
    }

    @Test
    @DisplayName("GAQL query has no date filter for a full pull")
    void gaqlFullPull() {
        String gaql = connector.buildGaqlQuery(null);
        assertThat(gaql).contains("FROM campaign");
        assertThat(gaql).contains("metrics.impressions");
        assertThat(gaql).contains("metrics.clicks");
        assertThat(gaql).contains("metrics.cost_micros");
        assertThat(gaql).contains("metrics.conversions");
        assertThat(gaql).doesNotContain("WHERE");
    }

    @Test
    @DisplayName("GAQL query adds a segments.date lower bound for an incremental pull")
    void gaqlIncremental() {
        Instant since = Instant.parse("2024-03-15T10:00:00Z");
        String gaql = connector.buildGaqlQuery(since);
        assertThat(gaql).contains("WHERE segments.date >= '2024-03-15'");
    }

    @Test
    @DisplayName("search body contains the query and no pageToken on the first page")
    void bodyFirstPage() throws Exception {
        String body = connector.buildSearchBody(null, null);
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.has("query")).isTrue();
        assertThat(node.has("pageToken")).isFalse();
    }

    @Test
    @DisplayName("search body carries the pageToken continuation when paging")
    void bodyWithPageToken() throws Exception {
        String body = connector.buildSearchBody(null, "next-page-abc");
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.path("pageToken").asText()).isEqualTo("next-page-abc");
    }

    @Test
    @DisplayName("pullOrders and pullProducts are unsupported")
    void unsupportedFeeds() {
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> connector.pullOrders(null, null, null));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> connector.pullProducts(null, null, null));
    }
}
