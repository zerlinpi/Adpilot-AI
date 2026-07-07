package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.advertising.hosting.ReportDateRange;
import com.adpilot.modules.advertising.hosting.ReportLifecycleClient;
import com.adpilot.modules.advertising.hosting.ReportLifecycleResult;
import com.adpilot.modules.advertising.hosting.ReportType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PlatformDataConnector} for the Amazon Advertising API, pulling
 * advertising performance reports (Req 8.1.1). Authenticates with a Login-with-
 * Amazon (LWA) access token using the Amazon Ads required scheme (Req 8.1.2):
 * {@code Authorization: Bearer <token>} plus the
 * {@code Amazon-Advertising-API-ClientId} and {@code Amazon-Advertising-API-Scope}
 * (profile) headers.
 *
 * <h2>Metric mapping (Req 8.1.3)</h2>
 * <p>Each report row is normalized into the internal advertising performance
 * field names ({@code impressions}, {@code clicks}, {@code spend},
 * {@code sales}, {@code orders}, plus optional derived ratios) so the mapping/
 * upsert pipeline can persist it into the Store's {@code performance_daily}
 * entity. Amazon's per-metric key variants (e.g. {@code cost},
 * {@code attributedSales14d}, {@code attributedConversions14d}) are folded onto
 * the internal names by {@link #mapMetrics}.</p>
 *
 * <h2>Re-auth (Req 8.1.5)</h2>
 * <p>The LWA token is refreshed via {@link AmazonLwaClient} before the pull; an
 * expired/invalid token throws {@link ReauthRequiredException}.</p>
 */
@Slf4j
@Component
public class AmazonAdsConnector extends AbstractAmazonConnector {

    static final String PLATFORM = "amazon_ads";

    // Internal advertising-performance field names (align with performance_daily).
    static final String F_IMPRESSIONS = "impressions";
    static final String F_CLICKS = "clicks";
    static final String F_SPEND = "spend";
    static final String F_SALES = "sales";
    static final String F_ORDERS = "orders";
    static final String F_ACOS = "acos";
    static final String F_ROAS = "roas";
    static final String F_CTR = "ctr";
    static final String F_CVR = "cvr";
    static final String F_AVG_CPC = "avg_cpc";
    static final String F_DATE = "date";
    static final String F_CAMPAIGN_ID = "campaign_id";
    static final String F_EXTERNAL_CAMPAIGN_ID = "external_campaign_id";
    static final String F_ENTITY_TYPE = "entity_type";

    private final ReportLifecycleClient reportLifecycleClient;

    public AmazonAdsConnector(ObjectMapper objectMapper, AmazonLwaClient lwaClient,
                              ReportLifecycleClient reportLifecycleClient,
                              HttpClientFactory httpClientFactory) {
        super(objectMapper, lwaClient, httpClientFactory);
        this.reportLifecycleClient = reportLifecycleClient;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public ExternalPage pullAdReports(ConnectionContext ctx, Instant since, PageCursor cursor) {
        LocalDate endDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate startDate = since != null
                ? since.atZone(ZoneOffset.UTC).toLocalDate()
                : endDate.minusDays(30);
        if (startDate.isAfter(endDate)) startDate = endDate;

        ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(
                ctx, ReportType.SP_CAMPAIGN, new ReportDateRange(startDate, endDate));
        List<ExternalRecord> records = new ArrayList<>();
        if (result.rows() != null) {
            for (Map<String, Object> source : result.rows()) {
                JsonNode row = objectMapper.valueToTree(source);
                ExternalRecord record = toReportRecord(row);
                if (record != null) records.add(record);
            }
        }
        log.info("Amazon Ads report lifecycle produced {} record(s)", records.size());
        return ExternalPage.last(records);
    }

    /**
     * Amazon Ads does not expose order/product feeds; orders/products are pulled
     * via the SP-API connector instead.
     */
    @Override
    public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
        throw new UnsupportedOperationException(
                "Amazon Ads connector pulls advertising reports, not orders");
    }

    @Override
    public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
        throw new UnsupportedOperationException(
                "Amazon Ads connector pulls advertising reports, not products");
    }

    // ── metric mapping (Req 8.1.3) ────────────────────────────────────────────────

    private ExternalRecord toReportRecord(JsonNode row) {
        if (row == null || !row.isObject()) {
            return null;
        }
        Map<String, Object> fields = mapMetrics(row);
        String campaignId = (String) fields.get(F_EXTERNAL_CAMPAIGN_ID);
        String date = (String) fields.get(F_DATE);
        // Idempotency anchor: one performance row per campaign per day (Req 8.1.4).
        String externalId = (campaignId == null ? "campaign" : campaignId)
                + "#" + (date == null ? "" : date);
        Instant changedAt = parseInstant(date);
        return new ExternalRecord(externalId, "ad_report", changedAt, null, fields);
    }

    /**
     * Fold Amazon's per-metric key variants onto the internal advertising
     * performance field names. Unknown keys are preserved under their original
     * names so nothing is lost.
     */
    Map<String, Object> mapMetrics(JsonNode row) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(F_ENTITY_TYPE, "campaign");

        putFirst(fields, F_EXTERNAL_CAMPAIGN_ID, row, "campaignId", "campaign_id");
        putFirst(fields, F_CAMPAIGN_ID, row, "campaignId", "campaign_id");
        putFirst(fields, F_DATE, row, "date", "reportDate", "day");

        putFirstNumber(fields, F_IMPRESSIONS, row, "impressions");
        putFirstNumber(fields, F_CLICKS, row, "clicks");
        putFirstNumber(fields, F_SPEND, row, "spend", "cost");
        putFirstNumber(fields, F_SALES, row,
                "sales", "sales14d", "attributedSales14d", "attributedSales7d",
                "attributedSales30d", "attributedSales1d");
        putFirstNumber(fields, F_ORDERS, row,
                "orders", "purchases", "purchases14d", "attributedConversions14d",
                "attributedConversions7d", "attributedUnitsOrdered14d");
        // Optional pre-computed ratios; the mapper/calculator may recompute.
        putFirstNumber(fields, F_ACOS, row, "acos", "ACOS");
        putFirstNumber(fields, F_ROAS, row, "roas", "ROAS");
        putFirstNumber(fields, F_CTR, row, "ctr", "clickThroughRate");
        putFirstNumber(fields, F_CVR, row, "cvr", "conversionRate");
        putFirstNumber(fields, F_AVG_CPC, row, "avgCpc", "costPerClick", "cpc");

        return fields;
    }

    private void putFirst(Map<String, Object> fields, String target, JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode v = row.get(key);
            if (v != null && !v.isNull()) {
                String s = v.asText(null);
                if (s != null && !s.isBlank()) {
                    fields.put(target, s);
                    return;
                }
            }
        }
    }

    private void putFirstNumber(Map<String, Object> fields, String target, JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode v = row.get(key);
            if (v != null && !v.isNull()) {
                if (v.isNumber()) {
                    fields.put(target, v.numberValue());
                    return;
                }
                String s = v.asText(null);
                if (s != null && !s.isBlank()) {
                    fields.put(target, s);
                    return;
                }
            }
        }
    }

}
