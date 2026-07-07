package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.MetricQuarantineEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.SearchTermDailyEntity;
import com.adpilot.modules.advertising.mapper.MetricQuarantineMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SearchTermDailyMapper;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of {@link ReportIngestionService} that routes report rows
 * to the appropriate performance store based on report type, resolves external entity
 * IDs to internal UUIDs, and quarantines unresolved rows.
 *
 * <p>Validates: Requirements 2.3, 2.4, 2.5, 31.1, 32.1, 32.4.</p>
 */
@Slf4j
@Service
public class ReportIngestionServiceImpl implements ReportIngestionService {

    /** Default finalization lag in days — rows for dates older than this are marked finalized. */
    static final int DEFAULT_FINALIZATION_LAG_DAYS = 3;

    static final String PLATFORM = "amazon_ads";
    static final String DATA_STATUS_PRELIMINARY = "preliminary";
    static final String DATA_STATUS_FINALIZED = "finalized";

    private final PerformanceDailyMapper performanceDailyMapper;
    private final SearchTermDailyMapper searchTermDailyMapper;
    private final MetricQuarantineMapper metricQuarantineMapper;
    private final ExternalEntityMappingMapper externalEntityMappingMapper;
    private final ObjectMapper objectMapper;

    public ReportIngestionServiceImpl(PerformanceDailyMapper performanceDailyMapper,
                                      SearchTermDailyMapper searchTermDailyMapper,
                                      MetricQuarantineMapper metricQuarantineMapper,
                                      ExternalEntityMappingMapper externalEntityMappingMapper,
                                      ObjectMapper objectMapper) {
        this.performanceDailyMapper = performanceDailyMapper;
        this.searchTermDailyMapper = searchTermDailyMapper;
        this.metricQuarantineMapper = metricQuarantineMapper;
        this.externalEntityMappingMapper = externalEntityMappingMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public IngestionResult ingest(UUID storeId, ReportLifecycleResult result) {
        return ingest(storeId, result, DEFAULT_FINALIZATION_LAG_DAYS);
    }

    @Override
    @Transactional
    public IngestionResult ingest(UUID storeId, ReportLifecycleResult result, int finalizationLagDays) {
        if (result == null || result.rows() == null || result.rows().isEmpty()) {
            return IngestionResult.empty();
        }

        ReportType reportType = result.reportType();
        List<Map<String, Object>> rows = result.rows();

        if (reportType == ReportType.SP_SEARCH_TERM) {
            return ingestSearchTermRows(storeId, rows, finalizationLagDays, reportType);
        } else {
            // SP_CAMPAIGN and SP_KEYWORD both go to performance_daily
            return ingestPerformanceRows(storeId, rows, finalizationLagDays, reportType);
        }
    }

    // ── Performance daily ingestion (campaign/keyword reports) ────────────────────

    private IngestionResult ingestPerformanceRows(UUID storeId, List<Map<String, Object>> rows,
                                                   int finalizationLagDays, ReportType reportType) {
        int inserted = 0;
        int updated = 0;
        int versionBumped = 0;
        int quarantined = 0;

        for (Map<String, Object> row : rows) {
            String entityType = resolveEntityType(reportType, row);
            String externalEntityId = extractExternalEntityId(row, entityType);

            if (externalEntityId == null || externalEntityId.isBlank()) {
                quarantineRow(storeId, reportType, entityType, externalEntityId, row,
                        "Missing external entity ID");
                quarantined++;
                continue;
            }

            // Resolve external id to internal UUID
            UUID internalEntityId = resolveInternalEntityId(storeId, entityType, externalEntityId);
            if (internalEntityId == null) {
                quarantineRow(storeId, reportType, entityType, externalEntityId, row,
                        "Unresolved external entity ID");
                quarantined++;
                continue;
            }

            // Also resolve campaign_id for keyword rows
            UUID campaignId = null;
            String externalCampaignId = stringValue(row, "campaignId");
            if (externalCampaignId != null) {
                campaignId = resolveInternalEntityId(storeId, "campaign", externalCampaignId);
            }

            LocalDate reportDate = extractReportDate(row);
            if (reportDate == null) {
                quarantineRow(storeId, reportType, entityType, externalEntityId, row,
                        "Missing or invalid report_date");
                quarantined++;
                continue;
            }

            String dataStatus = deriveDataStatus(reportDate, finalizationLagDays);

            // Check for existing row (idempotent upsert)
            PerformanceDailyEntity existing = findExistingPerformanceRow(
                    storeId, entityType, internalEntityId, reportDate);

            if (existing == null) {
                // Insert new row
                PerformanceDailyEntity entity = buildPerformanceDailyEntity(
                        storeId, entityType, internalEntityId, campaignId, reportDate,
                        dataStatus, row);
                performanceDailyMapper.insert(entity);
                inserted++;
            } else {
                // Check if metrics have changed (backfill scenario)
                boolean metricsChanged = havePerformanceMetricsChanged(existing, row);
                if (metricsChanged) {
                    // Bump data_version on changed backfill
                    updatePerformanceDailyEntity(existing, row, dataStatus);
                    existing.setDataVersion(existing.getDataVersion() + 1);
                    performanceDailyMapper.updateById(existing);
                    versionBumped++;
                } else {
                    // Idempotent — same data, update data_status if needed
                    if (!existing.getDataStatus().equals(dataStatus)) {
                        existing.setDataStatus(dataStatus);
                        performanceDailyMapper.updateById(existing);
                    }
                    updated++;
                }
            }
        }

        log.info("Performance ingestion for store {}: {} inserted, {} updated, {} version-bumped, {} quarantined (total {})",
                storeId, inserted, updated, versionBumped, quarantined, rows.size());

        return new IngestionResult(inserted, updated, versionBumped, quarantined, rows.size());
    }

    // ── Search term daily ingestion ──────────────────────────────────────────────

    private IngestionResult ingestSearchTermRows(UUID storeId, List<Map<String, Object>> rows,
                                                  int finalizationLagDays, ReportType reportType) {
        int inserted = 0;
        int updated = 0;
        int versionBumped = 0;
        int quarantined = 0;

        for (Map<String, Object> row : rows) {
            String externalCampaignId = stringValue(row, "campaignId");
            String externalAdGroupId = stringValue(row, "adGroupId");
            String searchTerm = stringValue(row, "searchTerm");

            if (searchTerm == null || searchTerm.isBlank()) {
                quarantineRow(storeId, reportType, "search_term", null, row,
                        "Missing search term");
                quarantined++;
                continue;
            }

            // Resolve campaign
            UUID campaignId = null;
            if (externalCampaignId != null) {
                campaignId = resolveInternalEntityId(storeId, "campaign", externalCampaignId);
                if (campaignId == null) {
                    quarantineRow(storeId, reportType, "campaign", externalCampaignId, row,
                            "Unresolved campaign external ID");
                    quarantined++;
                    continue;
                }
            } else {
                quarantineRow(storeId, reportType, "campaign", null, row,
                        "Missing campaign ID for search term row");
                quarantined++;
                continue;
            }

            // Resolve ad group
            UUID adGroupId = null;
            if (externalAdGroupId != null) {
                adGroupId = resolveInternalEntityId(storeId, "ad_group", externalAdGroupId);
                if (adGroupId == null) {
                    quarantineRow(storeId, reportType, "ad_group", externalAdGroupId, row,
                            "Unresolved ad group external ID");
                    quarantined++;
                    continue;
                }
            } else {
                quarantineRow(storeId, reportType, "ad_group", null, row,
                        "Missing ad group ID for search term row");
                quarantined++;
                continue;
            }

            LocalDate reportDate = extractReportDate(row);
            if (reportDate == null) {
                quarantineRow(storeId, reportType, "search_term", null, row,
                        "Missing or invalid report_date");
                quarantined++;
                continue;
            }

            String dataStatus = deriveDataStatus(reportDate, finalizationLagDays);

            // Check for existing row (idempotent upsert by unique key)
            SearchTermDailyEntity existing = findExistingSearchTermRow(
                    storeId, campaignId, adGroupId, searchTerm, reportDate);

            if (existing == null) {
                SearchTermDailyEntity entity = buildSearchTermDailyEntity(
                        storeId, campaignId, adGroupId, searchTerm, reportDate,
                        dataStatus, row);
                searchTermDailyMapper.insert(entity);
                inserted++;
            } else {
                boolean metricsChanged = haveSearchTermMetricsChanged(existing, row);
                if (metricsChanged) {
                    updateSearchTermDailyEntity(existing, row, dataStatus);
                    existing.setDataVersion(existing.getDataVersion() + 1);
                    searchTermDailyMapper.updateById(existing);
                    versionBumped++;
                } else {
                    if (!existing.getDataStatus().equals(dataStatus)) {
                        existing.setDataStatus(dataStatus);
                        searchTermDailyMapper.updateById(existing);
                    }
                    updated++;
                }
            }
        }

        log.info("Search-term ingestion for store {}: {} inserted, {} updated, {} version-bumped, {} quarantined (total {})",
                storeId, inserted, updated, versionBumped, quarantined, rows.size());

        return new IngestionResult(inserted, updated, versionBumped, quarantined, rows.size());
    }

    // ── Data status derivation ───────────────────────────────────────────────────

    /**
     * Derives data_status from the age of the report date relative to today.
     * Rows for dates older than {@code finalizationLagDays} from today are {@code finalized};
     * otherwise they are {@code preliminary}.
     */
    String deriveDataStatus(LocalDate reportDate, int finalizationLagDays) {
        LocalDate finalizationCutoff = LocalDate.now().minusDays(finalizationLagDays);
        if (reportDate.isBefore(finalizationCutoff) || reportDate.isEqual(finalizationCutoff)) {
            return DATA_STATUS_FINALIZED;
        }
        return DATA_STATUS_PRELIMINARY;
    }

    // ── External ID resolution ───────────────────────────────────────────────────

    /**
     * Resolves an Amazon external entity ID to an internal UUID via
     * {@code external_entity_mappings}. Returns null if no mapping exists
     * — the caller routes unresolved rows to quarantine.
     */
    UUID resolveInternalEntityId(UUID storeId, String entityType, String externalEntityId) {
        if (externalEntityId == null || externalEntityId.isBlank()) {
            return null;
        }

        LambdaQueryWrapper<ExternalEntityMappingEntity> query = new LambdaQueryWrapper<>();
        query.eq(ExternalEntityMappingEntity::getStoreId, storeId)
                .eq(ExternalEntityMappingEntity::getPlatform, PLATFORM)
                .eq(ExternalEntityMappingEntity::getExternalEntityType, entityType)
                .eq(ExternalEntityMappingEntity::getExternalEntityId, externalEntityId);

        ExternalEntityMappingEntity mapping = externalEntityMappingMapper.selectOne(query);
        return mapping != null ? mapping.getInternalEntityId() : null;
    }

    // ── Quarantine routing ───────────────────────────────────────────────────────

    private void quarantineRow(UUID storeId, ReportType reportType, String entityType,
                               String externalEntityId, Map<String, Object> row, String reason) {
        LocalDate reportDate = extractReportDate(row);

        MetricQuarantineEntity quarantine = MetricQuarantineEntity.builder()
                .storeId(storeId)
                .reportType(reportType.name())
                .externalEntityType(entityType)
                .externalEntityId(externalEntityId)
                .reportDate(reportDate)
                .rawRow(serializeRow(row))
                .reason(reason)
                .resolved(false)
                .createdAt(LocalDateTime.now())
                .build();

        metricQuarantineMapper.insert(quarantine);
    }

    // ── Lookup helpers ───────────────────────────────────────────────────────────

    private PerformanceDailyEntity findExistingPerformanceRow(UUID storeId, String entityType,
                                                              UUID entityId, LocalDate reportDate) {
        LambdaQueryWrapper<PerformanceDailyEntity> query = new LambdaQueryWrapper<>();
        query.eq(PerformanceDailyEntity::getStoreId, storeId)
                .eq(PerformanceDailyEntity::getEntityType, entityType)
                .eq(PerformanceDailyEntity::getEntityId, entityId)
                .eq(PerformanceDailyEntity::getDate, reportDate);
        return performanceDailyMapper.selectOne(query);
    }

    private SearchTermDailyEntity findExistingSearchTermRow(UUID storeId, UUID campaignId,
                                                            UUID adGroupId, String searchTerm,
                                                            LocalDate reportDate) {
        LambdaQueryWrapper<SearchTermDailyEntity> query = new LambdaQueryWrapper<>();
        query.eq(SearchTermDailyEntity::getStoreId, storeId)
                .eq(SearchTermDailyEntity::getCampaignId, campaignId)
                .eq(SearchTermDailyEntity::getAdGroupId, adGroupId)
                .eq(SearchTermDailyEntity::getSearchTerm, searchTerm)
                .eq(SearchTermDailyEntity::getReportDate, reportDate);
        return searchTermDailyMapper.selectOne(query);
    }

    // ── Entity builders ──────────────────────────────────────────────────────────

    private PerformanceDailyEntity buildPerformanceDailyEntity(UUID storeId, String entityType,
                                                               UUID entityId, UUID campaignId,
                                                               LocalDate reportDate,
                                                               String dataStatus,
                                                               Map<String, Object> row) {
        return PerformanceDailyEntity.builder()
                .storeId(storeId)
                .entityType(entityType)
                .entityId(entityId)
                .campaignId(campaignId)
                .date(reportDate)
                .impressions(longValue(row, "impressions"))
                .clicks(intValue(row, "clicks"))
                .spend(decimalValue(row, "spend"))
                .sales(decimalValue(row, "sales"))
                .orders(intValue(row, "orders"))
                .acos(decimalValue(row, "acos"))
                .roas(decimalValue(row, "roas"))
                .ctr(decimalValue(row, "ctr"))
                .cvr(decimalValue(row, "cvr"))
                .avgCpc(decimalValue(row, "avgCpc", "cpc"))
                .currency(stringValue(row, "currency"))
                .dataStatus(dataStatus)
                .dataVersion(1)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private SearchTermDailyEntity buildSearchTermDailyEntity(UUID storeId, UUID campaignId,
                                                              UUID adGroupId, String searchTerm,
                                                              LocalDate reportDate,
                                                              String dataStatus,
                                                              Map<String, Object> row) {
        return SearchTermDailyEntity.builder()
                .storeId(storeId)
                .campaignId(campaignId)
                .adGroupId(adGroupId)
                .searchTerm(searchTerm)
                .reportDate(reportDate)
                .impressions(longValue(row, "impressions"))
                .clicks(intValue(row, "clicks"))
                .orders(intValue(row, "orders"))
                .spend(decimalValue(row, "spend"))
                .sales(decimalValue(row, "sales"))
                .acos(decimalValue(row, "acos"))
                .currency(stringValue(row, "currency"))
                .dataStatus(dataStatus)
                .dataVersion(1)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── Update helpers (backfill) ────────────────────────────────────────────────

    private void updatePerformanceDailyEntity(PerformanceDailyEntity entity,
                                              Map<String, Object> row, String dataStatus) {
        entity.setImpressions(longValue(row, "impressions"));
        entity.setClicks(intValue(row, "clicks"));
        entity.setSpend(decimalValue(row, "spend"));
        entity.setSales(decimalValue(row, "sales"));
        entity.setOrders(intValue(row, "orders"));
        entity.setAcos(decimalValue(row, "acos"));
        entity.setRoas(decimalValue(row, "roas"));
        entity.setCtr(decimalValue(row, "ctr"));
        entity.setCvr(decimalValue(row, "cvr"));
        entity.setAvgCpc(decimalValue(row, "avgCpc", "cpc"));
        entity.setDataStatus(dataStatus);
        String currency = stringValue(row, "currency");
        if (currency != null) {
            entity.setCurrency(currency);
        }
    }

    private void updateSearchTermDailyEntity(SearchTermDailyEntity entity,
                                             Map<String, Object> row, String dataStatus) {
        entity.setImpressions(longValue(row, "impressions"));
        entity.setClicks(intValue(row, "clicks"));
        entity.setOrders(intValue(row, "orders"));
        entity.setSpend(decimalValue(row, "spend"));
        entity.setSales(decimalValue(row, "sales"));
        entity.setAcos(decimalValue(row, "acos"));
        entity.setDataStatus(dataStatus);
        String currency = stringValue(row, "currency");
        if (currency != null) {
            entity.setCurrency(currency);
        }
    }

    // ── Metric change detection ──────────────────────────────────────────────────

    private boolean havePerformanceMetricsChanged(PerformanceDailyEntity existing,
                                                   Map<String, Object> row) {
        return !Objects.equals(existing.getImpressions(), longValue(row, "impressions"))
                || !Objects.equals(existing.getClicks(), intValue(row, "clicks"))
                || !decimalEquals(existing.getSpend(), decimalValue(row, "spend"))
                || !decimalEquals(existing.getSales(), decimalValue(row, "sales"))
                || !Objects.equals(existing.getOrders(), intValue(row, "orders"))
                || !decimalEquals(existing.getAcos(), decimalValue(row, "acos"))
                || !decimalEquals(existing.getRoas(), decimalValue(row, "roas"));
    }

    private boolean haveSearchTermMetricsChanged(SearchTermDailyEntity existing,
                                                  Map<String, Object> row) {
        return !Objects.equals(existing.getImpressions(), longValue(row, "impressions"))
                || !Objects.equals(existing.getClicks(), intValue(row, "clicks"))
                || !Objects.equals(existing.getOrders(), intValue(row, "orders"))
                || !decimalEquals(existing.getSpend(), decimalValue(row, "spend"))
                || !decimalEquals(existing.getSales(), decimalValue(row, "sales"))
                || !decimalEquals(existing.getAcos(), decimalValue(row, "acos"));
    }

    // ── Row extraction helpers ───────────────────────────────────────────────────

    private String resolveEntityType(ReportType reportType, Map<String, Object> row) {
        if (reportType == ReportType.SP_KEYWORD) {
            return "keyword";
        }
        // SP_CAMPAIGN or fallback
        return "campaign";
    }

    private String extractExternalEntityId(Map<String, Object> row, String entityType) {
        if ("keyword".equals(entityType)) {
            return stringValue(row, "keywordId");
        }
        return stringValue(row, "campaignId");
    }

    private LocalDate extractReportDate(Map<String, Object> row) {
        Object dateObj = row.get("date");
        if (dateObj == null) {
            dateObj = row.get("reportDate");
        }
        if (dateObj == null) {
            dateObj = row.get("report_date");
        }

        if (dateObj instanceof LocalDate ld) {
            return ld;
        }
        if (dateObj instanceof String dateStr && !dateStr.isBlank()) {
            try {
                return LocalDate.parse(dateStr);
            } catch (Exception e) {
                log.warn("Failed to parse report date '{}': {}", dateStr, e.getMessage());
                return null;
            }
        }
        return null;
    }

    private String serializeRow(Map<String, Object> row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize quarantine row: {}", e.getMessage());
            return "{}";
        }
    }

    // ── Value extraction helpers ─────────────────────────────────────────────────

    static String stringValue(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Integer intValue(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal decimalValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object val = row.get(key);
            if (val == null) continue;
            if (val instanceof BigDecimal bd) return bd;
            if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            try {
                return new BigDecimal(val.toString());
            } catch (NumberFormatException e) {
                // Try next key
            }
        }
        return BigDecimal.ZERO;
    }

    private static boolean decimalEquals(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }
}
