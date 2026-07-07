package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.operation.SyncState;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scheduled worker that computes effect attribution after an Operation reaches {@code effective}.
 *
 * <p>When an Operation transitions to {@code effective}, the worker opens a measurement window
 * (configurable, default 7 days). After the window closes, it computes per-metric comparisons
 * (impressions, clicks, spend, sales, orders, ACoS) recording:</p>
 * <ul>
 *   <li>{@code observed_change} — raw difference between post-window and pre-window metric averages</li>
 *   <li>{@code estimated_incremental_impact} — null when no reliable baseline exists (honest admission)</li>
 *   <li>{@code attribution_confidence} — confidence score 0.0–1.0, lowered for overlapping windows</li>
 * </ul>
 *
 * <p>Records {@code attribution_method} and {@code method_version} in {@code effect_attributions}.</p>
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 8.6.</p>
 */
@Slf4j
@Component
public class AttributionWorker {

    /** The attribution method used: naive before/after comparison. */
    static final String ATTRIBUTION_METHOD = "naive_before_after";

    /** The version of the attribution method. */
    static final String METHOD_VERSION = "1.0";

    /** Metrics tracked for attribution. */
    static final List<String> TRACKED_METRICS = List.of(
            "impressions", "clicks", "spend", "sales", "orders", "acos");

    /** Base confidence when data is available and no overlaps. */
    static final BigDecimal BASE_CONFIDENCE = new BigDecimal("0.70");

    /** Confidence penalty per overlapping operation on the same campaign. */
    static final BigDecimal OVERLAP_PENALTY = new BigDecimal("0.15");

    /** Minimum confidence floor (never goes below this). */
    static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.10");

    /** Confidence penalty when pre-window data is insufficient (< measurementWindowDays). */
    static final BigDecimal INSUFFICIENT_DATA_PENALTY = new BigDecimal("0.20");

    private final OperationMapper operationMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final EffectAttributionMapper effectAttributionMapper;
    private final CampaignMapper campaignMapper;
    private final KeywordMapper keywordMapper;

    /** Measurement window duration in days (default 7). */
    private final int measurementWindowDays;

    public AttributionWorker(OperationMapper operationMapper,
                             PerformanceDailyMapper performanceDailyMapper,
                             EffectAttributionMapper effectAttributionMapper,
                             CampaignMapper campaignMapper,
                             KeywordMapper keywordMapper,
                             @Value("${adpilot.hosting.attribution-window-days:7}") int measurementWindowDays) {
        this.operationMapper = operationMapper;
        this.performanceDailyMapper = performanceDailyMapper;
        this.effectAttributionMapper = effectAttributionMapper;
        this.campaignMapper = campaignMapper;
        this.keywordMapper = keywordMapper;
        this.measurementWindowDays = measurementWindowDays > 0 ? measurementWindowDays : 7;
    }

    /**
     * Periodic tick: finds effective operations whose measurement window has closed
     * and computes attribution for each.
     */
    @Scheduled(fixedDelayString = "${adpilot.hosting.attribution-poll-ms:300000}")
    public void computeAttributions() {
        List<OperationEntity> candidates = findAttributionCandidates();
        if (candidates.isEmpty()) {
            return;
        }
        log.info("AttributionWorker: processing {} operations for effect attribution", candidates.size());

        for (OperationEntity op : candidates) {
            try {
                processAttribution(op);
            } catch (Exception e) {
                log.error("AttributionWorker: failed to compute attribution for operation {}",
                        op.getId(), e);
            }
        }
    }

    /**
     * Finds effective operations whose measurement window has closed and that have
     * not yet been attributed. The measurement window starts at the operation's
     * updated_at timestamp (when it reached effective) and lasts measurementWindowDays.
     */
    List<OperationEntity> findAttributionCandidates() {
        LocalDateTime windowCutoff = LocalDateTime.now().minusDays(measurementWindowDays);

        // Find effective operations updated before the cutoff (window has closed)
        LambdaQueryWrapper<OperationEntity> opQuery = new LambdaQueryWrapper<>();
        opQuery.eq(OperationEntity::getSyncState, SyncState.EFFECTIVE.name().toLowerCase())
                .eq(OperationEntity::getOperationScope, "platform_mutation")
                .le(OperationEntity::getUpdatedAt, windowCutoff);

        List<OperationEntity> effectiveOps = operationMapper.selectList(opQuery);
        if (effectiveOps.isEmpty()) {
            return Collections.emptyList();
        }

        // Filter out operations that already have attribution records
        List<UUID> opIds = effectiveOps.stream()
                .map(OperationEntity::getId)
                .collect(Collectors.toList());

        LambdaQueryWrapper<EffectAttributionEntity> attrQuery = new LambdaQueryWrapper<>();
        attrQuery.in(EffectAttributionEntity::getOperationId, opIds);
        List<EffectAttributionEntity> existing = effectAttributionMapper.selectList(attrQuery);

        Set<UUID> alreadyAttributed = existing.stream()
                .map(EffectAttributionEntity::getOperationId)
                .collect(Collectors.toSet());

        return effectiveOps.stream()
                .filter(op -> !alreadyAttributed.contains(op.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Processes attribution for a single operation: computes per-metric observed change,
     * determines attribution confidence (lowered for overlapping windows), and persists results.
     */
    void processAttribution(OperationEntity operation) {
        LocalDateTime effectiveAt = operation.getUpdatedAt();
        LocalDateTime windowStart = effectiveAt;
        LocalDateTime windowEnd = effectiveAt.plusDays(measurementWindowDays);

        // Resolve campaign ID for the operation (for overlap detection)
        UUID campaignId = resolveCampaignId(operation);

        // Count overlapping operations on the same campaign within this measurement window
        int overlapCount = countOverlappingOperations(operation, campaignId, windowStart, windowEnd);

        // Compute confidence score
        BigDecimal confidence = computeConfidence(operation, overlapCount);

        // Get pre-window and post-window performance data
        LocalDate preStart = effectiveAt.toLocalDate().minusDays(measurementWindowDays);
        LocalDate preEnd = effectiveAt.toLocalDate().minusDays(1);
        LocalDate postStart = effectiveAt.toLocalDate();
        LocalDate postEnd = effectiveAt.toLocalDate().plusDays(measurementWindowDays - 1);

        List<PerformanceDailyEntity> preData = fetchPerformanceData(
                operation.getStoreId(), campaignId, preStart, preEnd);
        List<PerformanceDailyEntity> postData = fetchPerformanceData(
                operation.getStoreId(), campaignId, postStart, postEnd);

        // If pre-window data is insufficient, lower confidence further
        if (preData.size() < measurementWindowDays) {
            confidence = confidence.subtract(INSUFFICIENT_DATA_PENALTY);
            if (confidence.compareTo(MIN_CONFIDENCE) < 0) {
                confidence = MIN_CONFIDENCE;
            }
        }

        // Compute and persist per-metric attribution
        for (String metric : TRACKED_METRICS) {
            BigDecimal preAvg = computeMetricAverage(preData, metric);
            BigDecimal postAvg = computeMetricAverage(postData, metric);
            BigDecimal observedChange = postAvg.subtract(preAvg);

            EffectAttributionEntity attribution = EffectAttributionEntity.builder()
                    .operationId(operation.getId())
                    .storeId(operation.getStoreId())
                    .metricType(metric)
                    .observedChange(observedChange)
                    // Req 8.2: null when no reliable baseline exists
                    .estimatedIncrementalImpact(null)
                    .attributionConfidence(confidence)
                    .attributionMethod(ATTRIBUTION_METHOD)
                    .methodVersion(METHOD_VERSION)
                    .measurementWindowStart(windowStart)
                    .measurementWindowEnd(windowEnd)
                    .build();

            effectAttributionMapper.insert(attribution);
        }

        log.info("AttributionWorker: computed attribution for operation {} with confidence {} ({} overlaps)",
                operation.getId(), confidence, overlapCount);
    }

    /**
     * Resolves the campaign ID for the given operation. If the operation's entity_type
     * is 'campaign', returns the entity_id directly. For 'keyword' entities, looks up
     * the keyword's campaign_id.
     */
    UUID resolveCampaignId(OperationEntity operation) {
        String entityType = operation.getEntityType();
        if ("campaign".equalsIgnoreCase(entityType)) {
            return operation.getEntityId();
        }
        if ("keyword".equalsIgnoreCase(entityType)) {
            KeywordEntity keyword = keywordMapper.selectById(operation.getEntityId());
            if (keyword != null) {
                return keyword.getCampaignId();
            }
        }
        // Fallback: use the entity_id itself (best effort)
        return operation.getEntityId();
    }

    /**
     * Counts other effective operations on the same campaign that have overlapping
     * measurement windows (Req 8.5).
     */
    int countOverlappingOperations(OperationEntity currentOp, UUID campaignId,
                                   LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (campaignId == null) {
            return 0;
        }

        // Find effective operations on the same store that became effective within
        // a window that overlaps with ours
        LambdaQueryWrapper<OperationEntity> query = new LambdaQueryWrapper<>();
        query.eq(OperationEntity::getStoreId, currentOp.getStoreId())
                .eq(OperationEntity::getSyncState, SyncState.EFFECTIVE.name().toLowerCase())
                .eq(OperationEntity::getOperationScope, "platform_mutation")
                .ne(OperationEntity::getId, currentOp.getId())
                // Their effective time must overlap with our measurement window:
                // they became effective at most measurementWindowDays before our window ends
                // AND at least before our window ends (their window started before our window ended)
                .ge(OperationEntity::getUpdatedAt, windowStart.minusDays(measurementWindowDays))
                .le(OperationEntity::getUpdatedAt, windowEnd);

        List<OperationEntity> overlapping = operationMapper.selectList(query);

        // Filter to only operations targeting the same campaign
        return (int) overlapping.stream()
                .filter(op -> campaignId.equals(resolveCampaignId(op)))
                .count();
    }

    /**
     * Computes the attribution confidence based on data quality and overlap count (Req 8.5).
     * Starts from BASE_CONFIDENCE and reduces by OVERLAP_PENALTY per overlapping operation,
     * with a floor at MIN_CONFIDENCE.
     */
    BigDecimal computeConfidence(OperationEntity operation, int overlapCount) {
        BigDecimal confidence = BASE_CONFIDENCE;

        // Lower confidence for each overlapping operation (Req 8.5)
        if (overlapCount > 0) {
            BigDecimal penalty = OVERLAP_PENALTY.multiply(BigDecimal.valueOf(overlapCount));
            confidence = confidence.subtract(penalty);
        }

        // Enforce minimum floor
        if (confidence.compareTo(MIN_CONFIDENCE) < 0) {
            confidence = MIN_CONFIDENCE;
        }

        return confidence;
    }

    /**
     * Fetches campaign-level performance data for the given date range.
     */
    List<PerformanceDailyEntity> fetchPerformanceData(UUID storeId, UUID campaignId,
                                                      LocalDate startDate, LocalDate endDate) {
        if (campaignId == null) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<PerformanceDailyEntity> query = new LambdaQueryWrapper<>();
        query.eq(PerformanceDailyEntity::getStoreId, storeId)
                .eq(PerformanceDailyEntity::getCampaignId, campaignId)
                .ge(PerformanceDailyEntity::getDate, startDate)
                .le(PerformanceDailyEntity::getDate, endDate)
                .eq(PerformanceDailyEntity::getEntityType, "campaign");

        return performanceDailyMapper.selectList(query);
    }

    /**
     * Computes the average of a metric across the given performance data entries.
     * Returns BigDecimal.ZERO when the data list is empty.
     */
    BigDecimal computeMetricAverage(List<PerformanceDailyEntity> data, String metric) {
        if (data == null || data.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (PerformanceDailyEntity row : data) {
            BigDecimal value = extractMetric(row, metric);
            sum = sum.add(value);
        }

        return sum.divide(BigDecimal.valueOf(data.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * Extracts the numeric value of the specified metric from a performance row.
     */
    BigDecimal extractMetric(PerformanceDailyEntity row, String metric) {
        if (row == null || metric == null) {
            return BigDecimal.ZERO;
        }
        return switch (metric.toLowerCase()) {
            case "impressions" -> row.getImpressions() != null
                    ? BigDecimal.valueOf(row.getImpressions()) : BigDecimal.ZERO;
            case "clicks" -> row.getClicks() != null
                    ? BigDecimal.valueOf(row.getClicks()) : BigDecimal.ZERO;
            case "spend" -> row.getSpend() != null ? row.getSpend() : BigDecimal.ZERO;
            case "sales" -> row.getSales() != null ? row.getSales() : BigDecimal.ZERO;
            case "orders" -> row.getOrders() != null
                    ? BigDecimal.valueOf(row.getOrders()) : BigDecimal.ZERO;
            case "acos" -> row.getAcos() != null ? row.getAcos() : BigDecimal.ZERO;
            default -> BigDecimal.ZERO;
        };
    }
}
