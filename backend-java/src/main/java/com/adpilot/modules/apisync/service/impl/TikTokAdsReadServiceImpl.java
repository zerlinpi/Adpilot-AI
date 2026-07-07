package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.TikTokAdsReadConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.apisync.service.TikTokAdsReadService;
import com.adpilot.modules.apisync.vo.TikTokAdsCampaignVo;
import com.adpilot.modules.apisync.vo.TikTokAdsPerformanceReportVo;
import com.adpilot.modules.apisync.vo.TikTokAdsPerformanceRowVo;
import com.adpilot.modules.apisync.vo.TikTokAdsReadResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Default {@link TikTokAdsReadService}, symmetric to
 * {@link GoogleAdsReadServiceImpl}.
 *
 * <p>Resolves the Store's active TikTok Ads {@code platform_connections} row,
 * decrypts its credentials, and pages through the
 * {@link TikTokAdsReadConnector#pullAdReports} integrated-report feed,
 * aggregating the campaign/day rows into a campaign list and a date-ranged
 * performance report.</p>
 *
 * <p>The service performs no writes. When the Store has no active TikTok Ads
 * connection it returns {@link TikTokAdsReadResult.State#CONNECT_PROMPT} rather
 * than an error (never fabricated data); any failure pulling from the connector
 * is caught and returned as {@link TikTokAdsReadResult.State#ERROR} so the
 * caller leaves previously displayed data unchanged.</p>
 *
 * <p>Unlike Google Ads (whose cost/budget are in micros), TikTok reports spend
 * and budget directly in account currency, so no micros conversion is applied.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TikTokAdsReadServiceImpl implements TikTokAdsReadService {

    private static final String PLATFORM = "tiktok_ads";
    private static final String CONNECTED = ConnectionStatus.CONNECTED;

    /** Defensive paging cap to avoid an unbounded loop on a misbehaving feed. */
    private static final int MAX_PAGES = 1000;

    private final PlatformConnectionMapper platformConnectionMapper;
    private final TikTokAdsReadConnector tikTokAdsReadConnector;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;

    @Override
    public TikTokAdsReadResult<List<TikTokAdsCampaignVo>> getCampaigns(UUID storeId) {
        PlatformConnectionEntity connection = findActiveConnection(storeId);
        if (connection == null) {
            return TikTokAdsReadResult.connectPrompt();
        }
        try {
            List<ExternalRecord> rows = pullAllRows(connection, null);
            return TikTokAdsReadResult.ok(aggregateCampaigns(rows));
        } catch (Exception e) {
            log.warn("TikTok Ads campaign retrieval failed for store {}: {}", storeId, rootMessage(e));
            return TikTokAdsReadResult.error(rootMessage(e));
        }
    }

    @Override
    public TikTokAdsReadResult<TikTokAdsPerformanceReportVo> getPerformanceReport(
            UUID storeId, LocalDate from, LocalDate to) {
        LocalDate start = from;
        LocalDate end = to;
        // Normalize an inverted range so callers always get a sensible window.
        if (start != null && end != null && start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }

        PlatformConnectionEntity connection = findActiveConnection(storeId);
        if (connection == null) {
            return TikTokAdsReadResult.connectPrompt();
        }
        try {
            Instant since = start != null ? start.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
            List<ExternalRecord> rows = pullAllRows(connection, since);
            return TikTokAdsReadResult.ok(aggregatePerformance(rows, start, end));
        } catch (Exception e) {
            log.warn("TikTok Ads report retrieval failed for store {}: {}", storeId, rootMessage(e));
            return TikTokAdsReadResult.error(rootMessage(e));
        }
    }

    // ── connection resolution ───────────────────────────────────────────────

    /**
     * The most recently updated active TikTok Ads connection bound to the Store,
     * or {@code null} when none is connected (drives the connect prompt).
     */
    private PlatformConnectionEntity findActiveConnection(UUID storeId) {
        if (storeId == null) {
            return null;
        }
        LambdaQueryWrapper<PlatformConnectionEntity> w = new LambdaQueryWrapper<>();
        w.eq(PlatformConnectionEntity::getStoreId, storeId)
                .eq(PlatformConnectionEntity::getPlatform, PLATFORM)
                .eq(PlatformConnectionEntity::getStatus, CONNECTED)
                .orderByDesc(PlatformConnectionEntity::getUpdatedAt)
                .last("LIMIT 1");
        return platformConnectionMapper.selectOne(w);
    }

    // ── connector paging ──────────────────────────────────────────────────────

    private List<ExternalRecord> pullAllRows(PlatformConnectionEntity connection, Instant since) {
        ConnectionContext ctx = new ConnectionContext(
                connection.getId(), connection.getStoreId(), PLATFORM,
                decryptConfig(connection.getConfigEncrypted()));

        List<ExternalRecord> all = new ArrayList<>();
        PageCursor cursor = PageCursor.start();
        boolean hasMore = true;
        int pages = 0;
        while (hasMore && pages++ < MAX_PAGES) {
            ExternalPage page = tikTokAdsReadConnector.pullAdReports(ctx, since, cursor);
            if (page == null) {
                break;
            }
            if (page.records() != null) {
                all.addAll(page.records());
            }
            hasMore = page.hasMore() && page.next() != null;
            cursor = page.next();
        }
        return all;
    }

    // ── aggregation ─────────────────────────────────────────────────────────

    private List<TikTokAdsCampaignVo> aggregateCampaigns(List<ExternalRecord> rows) {
        // Preserve first-seen order of campaigns.
        Map<String, CampaignAccumulator> byCampaign = new LinkedHashMap<>();
        for (ExternalRecord row : rows) {
            Map<String, Object> campaign = nestedMap(row.fields(), "campaign");
            Map<String, Object> metrics = nestedMap(row.fields(), "metrics");
            String campaignId = str(campaign.get("id"));
            if (campaignId == null) {
                campaignId = row.externalId() != null ? row.externalId() : "unknown";
            }
            CampaignAccumulator acc = byCampaign.computeIfAbsent(campaignId, CampaignAccumulator::new);
            acc.name = firstNonNull(str(campaign.get("name")), acc.name);
            acc.status = firstNonNull(str(campaign.get("status")), row.status(), acc.status);
            BigDecimal budget = asDecimalOrNull(campaign.get("budget"));
            if (budget != null) {
                acc.budget = budget.setScale(2, RoundingMode.HALF_UP);
            }
            acc.add(metrics);
        }

        List<TikTokAdsCampaignVo> out = new ArrayList<>(byCampaign.size());
        for (CampaignAccumulator acc : byCampaign.values()) {
            out.add(TikTokAdsCampaignVo.builder()
                    .campaignId(acc.campaignId)
                    .name(acc.name)
                    .status(acc.status)
                    .budget(acc.budget)
                    .impressions(acc.impressions)
                    .clicks(acc.clicks)
                    .cost(acc.spend.setScale(2, RoundingMode.HALF_UP))
                    .conversions(acc.conversions)
                    .conversionValue(acc.conversionValue.signum() == 0 ? null
                            : acc.conversionValue.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }
        return out;
    }

    private TikTokAdsPerformanceReportVo aggregatePerformance(
            List<ExternalRecord> rows, LocalDate from, LocalDate to) {
        // Sum metrics per day so the report is date-keyed regardless of campaign.
        Map<LocalDate, DayAccumulator> byDate = new TreeMap<>();
        for (ExternalRecord row : rows) {
            Map<String, Object> segments = nestedMap(row.fields(), "segments");
            LocalDate date = parseDate(str(segments.get("date")));
            if (date == null) {
                continue;
            }
            if ((from != null && date.isBefore(from)) || (to != null && date.isAfter(to))) {
                continue;
            }
            DayAccumulator acc = byDate.computeIfAbsent(date, d -> new DayAccumulator());
            acc.add(nestedMap(row.fields(), "metrics"));
        }

        List<TikTokAdsPerformanceRowVo> dayRows = new ArrayList<>(byDate.size());
        long totalImpr = 0;
        long totalClicks = 0;
        BigDecimal totalSpend = BigDecimal.ZERO;
        double totalConv = 0;
        BigDecimal totalConvValue = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, DayAccumulator> e : byDate.entrySet()) {
            DayAccumulator acc = e.getValue();
            dayRows.add(TikTokAdsPerformanceRowVo.builder()
                    .date(e.getKey())
                    .impressions(acc.impressions)
                    .clicks(acc.clicks)
                    .cost(acc.spend.setScale(2, RoundingMode.HALF_UP))
                    .conversions(acc.conversions)
                    .conversionValue(acc.conversionValue.setScale(2, RoundingMode.HALF_UP))
                    .build());
            totalImpr += acc.impressions;
            totalClicks += acc.clicks;
            totalSpend = totalSpend.add(acc.spend);
            totalConv += acc.conversions;
            totalConvValue = totalConvValue.add(acc.conversionValue);
        }

        return TikTokAdsPerformanceReportVo.builder()
                .from(from)
                .to(to)
                .rows(dayRows)
                .totalImpressions(totalImpr)
                .totalClicks(totalClicks)
                .totalCost(totalSpend.setScale(2, RoundingMode.HALF_UP))
                .totalConversions(totalConv)
                .totalConversionValue(totalConvValue.setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    // ── accumulators ──────────────────────────────────────────────────────────

    private static final class CampaignAccumulator {
        final String campaignId;
        String name;
        String status;
        BigDecimal budget;
        long impressions;
        long clicks;
        BigDecimal spend = BigDecimal.ZERO;
        double conversions;
        BigDecimal conversionValue = BigDecimal.ZERO;

        CampaignAccumulator(String campaignId) {
            this.campaignId = campaignId;
        }

        void add(Map<String, Object> metrics) {
            impressions += asLong(metrics.get("impressions"));
            clicks += asLong(metrics.get("clicks"));
            spend = spend.add(asDecimal(metrics.get("spend")));
            conversions += asDouble(metrics.get("conversions"));
            conversionValue = conversionValue.add(asDecimal(metrics.get("conversionValue")));
        }
    }

    private static final class DayAccumulator {
        long impressions;
        long clicks;
        BigDecimal spend = BigDecimal.ZERO;
        double conversions;
        BigDecimal conversionValue = BigDecimal.ZERO;

        void add(Map<String, Object> metrics) {
            impressions += asLong(metrics.get("impressions"));
            clicks += asLong(metrics.get("clicks"));
            spend = spend.add(asDecimal(metrics.get("spend")));
            conversions += asDouble(metrics.get("conversions"));
            conversionValue = conversionValue.add(asDecimal(metrics.get("conversionValue")));
        }
    }

    // ── parsing helpers ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> fields, String key) {
        if (fields == null) {
            return Map.of();
        }
        Object v = fields.get(key);
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    private static long asLong(Object v) {
        BigDecimal d = asDecimalOrNull(v);
        return d == null ? 0L : d.longValue();
    }

    private static double asDouble(Object v) {
        BigDecimal d = asDecimalOrNull(v);
        return d == null ? 0d : d.doubleValue();
    }

    private static BigDecimal asDecimal(Object v) {
        BigDecimal d = asDecimalOrNull(v);
        return d == null ? BigDecimal.ZERO : d;
    }

    private static BigDecimal asDecimalOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private Map<String, String> decryptConfig(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> encrypted = objectMapper.readValue(
                    stored, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> plain = new LinkedHashMap<>();
            encrypted.forEach((k, v) -> plain.put(k, v == null ? null : cryptoUtil.decrypt(v)));
            return plain;
        } catch (Exception e) {
            log.warn("Failed to read TikTok Ads connection config: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }
}
