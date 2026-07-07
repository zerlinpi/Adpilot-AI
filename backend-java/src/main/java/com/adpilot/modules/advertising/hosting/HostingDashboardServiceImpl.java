package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.support.InstantRange;
import com.adpilot.modules.advertising.support.MarketplaceTimezone;
import com.adpilot.modules.advertising.vo.EffectAttributionVo;
import com.adpilot.modules.advertising.vo.HostingAnalyticsVo;
import com.adpilot.modules.advertising.vo.HostingDashboardSummaryVo;
import com.adpilot.modules.advertising.vo.HostingDecisionDetailVo;
import com.adpilot.modules.advertising.vo.HostingDecisionVo;
import com.adpilot.modules.advertising.vo.HostingHealthVo;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.feishu.entity.FeishuIntegrationEntity;
import com.adpilot.modules.feishu.mapper.FeishuIntegrationMapper;
import com.adpilot.modules.inventory.entity.InventorySnapshotEntity;
import com.adpilot.modules.inventory.mapper.InventorySnapshotMapper;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link HostingDashboardService} (Requirements 11, 27, 29, 30.5).
 *
 * <p>Reads real data from the {@code campaigns}, {@code ai_decisions}, {@code operations}, and
 * {@code effect_attributions} tables via MyBatis-Plus mappers. "Today" and period windows are
 * resolved in the store's Marketplace_Timezone (Req 11.2, 27.2). The estimated-savings and
 * analytics-rate math is delegated to the pure {@link HostingAnalyticsCalculator} so it can be
 * property-tested in isolation (Properties 39, 40).</p>
 *
 * <p>The summary endpoint optionally caches its result in Redis with a 60-second TTL (Req 27.4);
 * all Redis access fails open so a Redis outage degrades to a direct query rather than an error
 * (Req 30.3).</p>
 */
@Slf4j
@Service
public class HostingDashboardServiceImpl implements HostingDashboardService {

    private static final String AI_HOSTING_SOURCE = OperationMachineValues.toValue(OperationSource.AI_HOSTING);
    private static final String SYNC_AWAITING_APPROVAL = OperationMachineValues.toValue(SyncState.AWAITING_APPROVAL);
    private static final String SYNC_EFFECTIVE = OperationMachineValues.toValue(SyncState.EFFECTIVE);
    private static final String SYNC_FAILED = OperationMachineValues.toValue(SyncState.FAILED);

    private static final String CACHE_KEY_PREFIX = "hosting:dashboard:summary:";
    private static final int MAX_DECISION_LIMIT = 200;
    private static final int DEFAULT_DECISION_LIMIT = 50;

    private final CampaignMapper campaignMapper;
    private final AiDecisionMapper aiDecisionMapper;
    private final OperationMapper operationMapper;
    private final EffectAttributionMapper effectAttributionMapper;
    private final StoreMapper storeMapper;
    private final MarketplaceMapper marketplaceMapper;
    private final PlatformConnectionMapper platformConnectionMapper;
    private final InventorySnapshotMapper inventorySnapshotMapper;
    private final FeishuIntegrationMapper feishuIntegrationMapper;
    private final LearningPeriodService learningPeriodService;
    private final AiDecisionService aiDecisionService;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private final boolean cacheEnabled;
    private final long cacheTtlSeconds;

    public HostingDashboardServiceImpl(CampaignMapper campaignMapper,
                                       AiDecisionMapper aiDecisionMapper,
                                       OperationMapper operationMapper,
                                       EffectAttributionMapper effectAttributionMapper,
                                       StoreMapper storeMapper,
                                       MarketplaceMapper marketplaceMapper,
                                       PlatformConnectionMapper platformConnectionMapper,
                                       InventorySnapshotMapper inventorySnapshotMapper,
                                       FeishuIntegrationMapper feishuIntegrationMapper,
                                       LearningPeriodService learningPeriodService,
                                       AiDecisionService aiDecisionService,
                                       ObjectMapper objectMapper,
                                       RedisTemplate<String, Object> redisTemplate,
                                       @Value("${adpilot.hosting.dashboard.cache-enabled:true}") boolean cacheEnabled,
                                       @Value("${adpilot.hosting.dashboard.cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.campaignMapper = campaignMapper;
        this.aiDecisionMapper = aiDecisionMapper;
        this.operationMapper = operationMapper;
        this.effectAttributionMapper = effectAttributionMapper;
        this.storeMapper = storeMapper;
        this.marketplaceMapper = marketplaceMapper;
        this.platformConnectionMapper = platformConnectionMapper;
        this.inventorySnapshotMapper = inventorySnapshotMapper;
        this.feishuIntegrationMapper = feishuIntegrationMapper;
        this.learningPeriodService = learningPeriodService;
        this.aiDecisionService = aiDecisionService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Summary (Req 11.1, 27)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public HostingDashboardSummaryVo getSummary(UUID storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId must not be null");
        }

        HostingDashboardSummaryVo cached = readCachedSummary(storeId);
        if (cached != null) {
            return cached;
        }

        HostingDashboardSummaryVo summary = computeSummary(storeId);
        writeCachedSummary(storeId, summary);
        return summary;
    }

    private HostingDashboardSummaryVo computeSummary(UUID storeId) {
        StoreEntity store = requireStore(storeId);
        LocalDateTime[] today = todayRange(store);
        LocalDateTime start = today[0];
        LocalDateTime end = today[1];

        long hostedCampaigns = countHostedCampaigns(storeId);
        long todayDecisions = nz(aiDecisionMapper.selectCount(new LambdaQueryWrapper<AiDecisionEntity>()
                .eq(AiDecisionEntity::getStoreId, storeId)
                .ge(AiDecisionEntity::getCreatedAt, start)
                .lt(AiDecisionEntity::getCreatedAt, end)));

        long awaitingApproval = nz(operationMapper.selectCount(hostingOps(storeId)
                .eq(OperationEntity::getSyncState, SYNC_AWAITING_APPROVAL)));

        long effectiveToday = nz(operationMapper.selectCount(hostingOps(storeId)
                .eq(OperationEntity::getSyncState, SYNC_EFFECTIVE)
                .ge(OperationEntity::getUpdatedAt, start)
                .lt(OperationEntity::getUpdatedAt, end)));

        long failedToday = nz(operationMapper.selectCount(hostingOps(storeId)
                .eq(OperationEntity::getSyncState, SYNC_FAILED)
                .ge(OperationEntity::getUpdatedAt, start)
                .lt(OperationEntity::getUpdatedAt, end)));

        Instant now = Instant.now();
        BigDecimal savings7d = estimatedSavings(storeId, now.minus(Duration.ofDays(7)));
        BigDecimal savings30d = estimatedSavings(storeId, now.minus(Duration.ofDays(30)));

        List<HostingDashboardSummaryVo.LearningPeriodVo> learningPeriods = learningPeriods(storeId);

        return HostingDashboardSummaryVo.builder()
                .storeId(storeId.toString())
                .hostedCampaignsCount(hostedCampaigns)
                .todayDecisionsCount(todayDecisions)
                .awaitingApprovalCount(awaitingApproval)
                .effectiveTodayCount(effectiveToday)
                .failedTodayCount(failedToday)
                .estimatedSavings7d(savings7d)
                .estimatedSavings30d(savings30d)
                .estimatedSavingsLabel("estimate")
                .learningPeriodCampaignsCount(learningPeriods.size())
                .learningPeriods(learningPeriods)
                .build();
    }

    private long countHostedCampaigns(UUID storeId) {
        return nz(campaignMapper.selectCount(new LambdaQueryWrapper<CampaignEntity>()
                .eq(CampaignEntity::getStoreId, storeId)
                .eq(CampaignEntity::getHostingEnabled, true)));
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal estimatedSavings(UUID storeId, Instant since) {
        LocalDateTime sinceLdt = LocalDateTime.ofInstant(since, ZoneId.systemDefault());
        List<EffectAttributionEntity> rows = effectAttributionMapper.selectList(
                new LambdaQueryWrapper<EffectAttributionEntity>()
                        .eq(EffectAttributionEntity::getStoreId, storeId)
                        .ge(EffectAttributionEntity::getCreatedAt, sinceLdt));
        return HostingAnalyticsCalculator.estimatedSpendSavings(rows);
    }

    private List<HostingDashboardSummaryVo.LearningPeriodVo> learningPeriods(UUID storeId) {
        List<CampaignEntity> hosted = campaignMapper.selectList(new LambdaQueryWrapper<CampaignEntity>()
                .eq(CampaignEntity::getStoreId, storeId)
                .eq(CampaignEntity::getHostingEnabled, true)
                .select(CampaignEntity::getId));
        if (hosted.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = hosted.stream().map(CampaignEntity::getId).collect(Collectors.toList());
        List<LearningPeriodStatus> statuses = learningPeriodService.getStatuses(ids);

        List<HostingDashboardSummaryVo.LearningPeriodVo> result = new ArrayList<>();
        for (int i = 0; i < ids.size() && i < statuses.size(); i++) {
            LearningPeriodStatus status = statuses.get(i);
            if (status != null && status.inLearningPeriod()) {
                result.add(HostingDashboardSummaryVo.LearningPeriodVo.builder()
                        .campaignId(ids.get(i).toString())
                        .daysRemaining(status.daysRemaining())
                        .totalDays(status.totalDays())
                        .build());
            }
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Decision list + detail (Req 11.4, 11.5, 13)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public List<HostingDecisionVo> listDecisions(UUID storeId, int limit) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId must not be null");
        }
        int bounded = limit <= 0 ? DEFAULT_DECISION_LIMIT : Math.min(limit, MAX_DECISION_LIMIT);

        List<AiDecisionEntity> decisions = aiDecisionMapper.selectList(
                new LambdaQueryWrapper<AiDecisionEntity>()
                        .eq(AiDecisionEntity::getStoreId, storeId)
                        .orderByDesc(AiDecisionEntity::getCreatedAt)
                        .last("limit " + bounded));

        List<HostingDecisionVo> result = new ArrayList<>(decisions.size());
        for (AiDecisionEntity d : decisions) {
            result.add(toDecisionVo(d));
        }
        return result;
    }

    @Override
    public HostingDecisionDetailVo getDecisionDetail(AiDecisionEntity decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        HostingDecisionVo summary = toDecisionVo(decision);

        DecisionSnapshot snapshot = null;
        try {
            snapshot = aiDecisionService.deserializeSnapshot(decision);
        } catch (RuntimeException ex) {
            log.warn("Failed to deserialize decision snapshot for decision {}: {}",
                    decision.getId(), ex.getMessage());
        }

        List<EffectAttributionVo> attributions = List.of();
        if (decision.getPromotedOperationId() != null) {
            attributions = effectAttributionMapper.selectList(
                            new LambdaQueryWrapper<EffectAttributionEntity>()
                                    .eq(EffectAttributionEntity::getOperationId, decision.getPromotedOperationId())
                                    .orderByAsc(EffectAttributionEntity::getMetricType))
                    .stream().map(this::toAttributionVo).collect(Collectors.toList());
        }

        return HostingDecisionDetailVo.builder()
                .decision(summary)
                .snapshot(snapshot)
                .attributions(attributions)
                .build();
    }

    private HostingDecisionVo toDecisionVo(AiDecisionEntity d) {
        HostingDecisionVo.HostingDecisionVoBuilder builder = HostingDecisionVo.builder()
                .id(idOrNull(d.getId()))
                .storeId(idOrNull(d.getStoreId()))
                .campaignId(idOrNull(d.getCampaignId()))
                .engine(d.getEngine())
                .decisionType(d.getDecisionType())
                .executionMode(d.getExecutionMode())
                .routingOutcome(d.getRoutingOutcome())
                .riskScore(d.getRiskScore())
                .promotedOperationId(idOrNull(d.getPromotedOperationId()))
                .createdAt(d.getCreatedAt())
                .expiresAt(d.getExpiresAt());

        if (d.getCampaignId() != null) {
            CampaignEntity campaign = campaignMapper.selectById(d.getCampaignId());
            if (campaign != null) {
                builder.campaignName(campaign.getName());
            }
        }

        if (d.getPromotedOperationId() != null) {
            OperationEntity op = operationMapper.selectById(d.getPromotedOperationId());
            if (op != null) {
                builder.syncState(op.getSyncState())
                        .field(op.getField())
                        .beforeValue(parseJson(op.getBeforeValue()))
                        .afterValue(parseJson(op.getAfterValue()));
            }
        }
        return builder.build();
    }

    private EffectAttributionVo toAttributionVo(EffectAttributionEntity e) {
        return EffectAttributionVo.builder()
                .metricType(e.getMetricType())
                .observedChange(e.getObservedChange())
                .estimatedIncrementalImpact(e.getEstimatedIncrementalImpact())
                .attributionConfidence(e.getAttributionConfidence())
                .attributionMethod(e.getAttributionMethod())
                .methodVersion(e.getMethodVersion())
                .measurementWindowStart(e.getMeasurementWindowStart())
                .measurementWindowEnd(e.getMeasurementWindowEnd())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Analytics (Req 29)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public HostingAnalyticsVo getAnalytics(UUID storeId, String period) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId must not be null");
        }
        int periodDays = parsePeriodDays(period);
        String normalizedPeriod = periodDays + "d";
        LocalDateTime since = LocalDateTime.ofInstant(
                Instant.now().minus(Duration.ofDays(periodDays)), ZoneId.systemDefault());

        List<AiDecisionEntity> decisions = aiDecisionMapper.selectList(
                new LambdaQueryWrapper<AiDecisionEntity>()
                        .eq(AiDecisionEntity::getStoreId, storeId)
                        .ge(AiDecisionEntity::getCreatedAt, since));

        // Join each decision to its promoted operation (when promoted) for the rate computation.
        Map<UUID, OperationEntity> opsById = loadPromotedOperations(decisions);

        List<HostingAnalyticsCalculator.DecisionOutcome> outcomes = new ArrayList<>(decisions.size());
        Map<UUID, String> engineByOperation = new LinkedHashMap<>();
        for (AiDecisionEntity d : decisions) {
            OperationEntity op = d.getPromotedOperationId() == null
                    ? null : opsById.get(d.getPromotedOperationId());
            boolean promoted = d.getPromotedOperationId() != null;
            outcomes.add(new HostingAnalyticsCalculator.DecisionOutcome(
                    d.getEngine(),
                    d.getExecutionMode(),
                    d.getRiskScore(),
                    promoted,
                    op == null ? null : op.getSyncState(),
                    op == null ? null : op.getStatusReason()));
            if (op != null) {
                engineByOperation.put(op.getId(), d.getEngine());
            }
        }

        HostingAnalyticsCalculator.RateResult rates = HostingAnalyticsCalculator.computeRates(outcomes);

        // Attribution aggregates over the same window (all estimates, Req 29.3).
        List<EffectAttributionEntity> attributions = effectAttributionMapper.selectList(
                new LambdaQueryWrapper<EffectAttributionEntity>()
                        .eq(EffectAttributionEntity::getStoreId, storeId)
                        .ge(EffectAttributionEntity::getCreatedAt, since));

        AttributionAggregates agg = aggregateAttributions(attributions);

        List<HostingAnalyticsVo.EngineBreakdownVo> perEngine = buildEngineBreakdown(
                rates.perEngine(), attributions, engineByOperation);

        List<HostingAnalyticsVo.FailureReasonVo> topFailures = rates.topFailureReasons().stream()
                .map(rc -> HostingAnalyticsVo.FailureReasonVo.builder()
                        .reason(rc.reason()).count(rc.count()).build())
                .collect(Collectors.toList());

        return HostingAnalyticsVo.builder()
                .storeId(storeId.toString())
                .period(normalizedPeriod)
                .totalDecisions(rates.totalDecisions())
                .autoExecutedCount(rates.autoExecutedCount())
                .approvalRequiredCount(rates.approvalRequiredCount())
                .attemptedCount(rates.attemptedCount())
                .effectiveCount(rates.effectiveCount())
                .successRate(rates.successRate())
                .averageRiskScore(rates.averageRiskScore())
                .topFailureReasons(topFailures)
                .averageAcosImprovement(agg.averageAcosImprovement())
                .totalEstimatedSpendSaved(HostingAnalyticsCalculator.estimatedSpendSavings(attributions))
                .totalEstimatedSalesLift(agg.totalSalesLift())
                .averageAttributionConfidence(agg.averageConfidence())
                .perEngine(perEngine)
                .impactLabel("estimate")
                .build();
    }

    private Map<UUID, OperationEntity> loadPromotedOperations(List<AiDecisionEntity> decisions) {
        List<UUID> opIds = decisions.stream()
                .map(AiDecisionEntity::getPromotedOperationId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (opIds.isEmpty()) {
            return Map.of();
        }
        List<OperationEntity> ops = operationMapper.selectBatchIds(opIds);
        Map<UUID, OperationEntity> byId = new LinkedHashMap<>();
        for (OperationEntity op : ops) {
            byId.put(op.getId(), op);
        }
        return byId;
    }

    private List<HostingAnalyticsVo.EngineBreakdownVo> buildEngineBreakdown(
            List<HostingAnalyticsCalculator.EngineRate> engineRates,
            List<EffectAttributionEntity> attributions,
            Map<UUID, String> engineByOperation) {

        // Partition attribution rows by the engine that produced their operation.
        Map<String, List<EffectAttributionEntity>> attrByEngine = new LinkedHashMap<>();
        for (EffectAttributionEntity a : attributions) {
            String engine = a.getOperationId() == null ? null : engineByOperation.get(a.getOperationId());
            if (engine == null) {
                continue;
            }
            attrByEngine.computeIfAbsent(engine, k -> new ArrayList<>()).add(a);
        }

        List<HostingAnalyticsVo.EngineBreakdownVo> result = new ArrayList<>();
        for (HostingAnalyticsCalculator.EngineRate er : engineRates) {
            BigDecimal saved = HostingAnalyticsCalculator.estimatedSpendSavings(
                    attrByEngine.getOrDefault(er.engine(), List.of()));
            result.add(HostingAnalyticsVo.EngineBreakdownVo.builder()
                    .engine(er.engine())
                    .totalDecisions(er.totalDecisions())
                    .attemptedCount(er.attemptedCount())
                    .effectiveCount(er.effectiveCount())
                    .successRate(er.successRate())
                    .estimatedSpendSaved(saved)
                    .build());
        }
        return result;
    }

    private record AttributionAggregates(
            BigDecimal averageAcosImprovement,
            BigDecimal totalSalesLift,
            BigDecimal averageConfidence) {
    }

    private AttributionAggregates aggregateAttributions(List<EffectAttributionEntity> attributions) {
        BigDecimal acosSum = BigDecimal.ZERO;
        long acosCount = 0;
        BigDecimal salesSum = BigDecimal.ZERO;
        BigDecimal confidenceSum = BigDecimal.ZERO;
        long confidenceCount = 0;

        for (EffectAttributionEntity a : attributions) {
            if (a == null) {
                continue;
            }
            if (HostingAnalyticsCalculator.METRIC_ACOS.equalsIgnoreCase(a.getMetricType())
                    && a.getEstimatedIncrementalImpact() != null) {
                // ACoS improvement is the magnitude of a negative (downward) ACoS change.
                acosSum = acosSum.add(a.getEstimatedIncrementalImpact().negate());
                acosCount++;
            }
            if (HostingAnalyticsCalculator.METRIC_SALES.equalsIgnoreCase(a.getMetricType())
                    && a.getEstimatedIncrementalImpact() != null) {
                salesSum = salesSum.add(a.getEstimatedIncrementalImpact());
            }
            if (a.getAttributionConfidence() != null) {
                confidenceSum = confidenceSum.add(a.getAttributionConfidence());
                confidenceCount++;
            }
        }

        BigDecimal avgAcos = acosCount == 0 ? BigDecimal.ZERO
                : acosSum.divide(BigDecimal.valueOf(acosCount), 6, RoundingMode.HALF_UP);
        BigDecimal avgConfidence = confidenceCount == 0 ? BigDecimal.ZERO
                : confidenceSum.divide(BigDecimal.valueOf(confidenceCount), 6, RoundingMode.HALF_UP);
        return new AttributionAggregates(avgAcos, salesSum, avgConfidence);
    }

    private int parsePeriodDays(String period) {
        if (period == null) {
            return 7;
        }
        return switch (period.trim().toLowerCase()) {
            case "30d" -> 30;
            case "90d" -> 90;
            case "7d", "" -> 7;
            default -> 7;
        };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Health (Req 30.5)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public HostingHealthVo getHealth() {
        Map<String, String> deps = new LinkedHashMap<>();
        deps.put("amazon_api", probeAmazonApi());
        deps.put("inventory_service", probeInventoryService());
        deps.put("redis", probeRedis());
        deps.put("feishu", probeFeishu());

        String overall = worstStatus(deps.values());
        return HostingHealthVo.builder()
                .overall(overall)
                .dependencies(deps)
                .checkedAt(LocalDateTime.now())
                .build();
    }

    private String probeAmazonApi() {
        try {
            List<String> amazonPlatforms = List.of("amazon_ads", "amazon-ads", "amazon");
            long connected = nz(platformConnectionMapper.selectCount(new LambdaQueryWrapper<PlatformConnectionEntity>()
                    .in(PlatformConnectionEntity::getPlatform, amazonPlatforms)
                    .in(PlatformConnectionEntity::getStatus, List.of(ConnectionStatus.CONNECTED, "active", "authorized"))));
            if (connected > 0) {
                return "healthy";
            }
            long configured = nz(platformConnectionMapper.selectCount(new LambdaQueryWrapper<PlatformConnectionEntity>()
                    .in(PlatformConnectionEntity::getPlatform, amazonPlatforms)));
            return configured > 0 ? "degraded" : "unavailable";
        } catch (Exception ex) {
            log.warn("Hosting health: Amazon API connection probe failed: {}", ex.getMessage());
            return "unavailable";
        }
    }

    private String probeInventoryService() {
        try {
            long snapshots = nz(inventorySnapshotMapper.selectCount(new LambdaQueryWrapper<InventorySnapshotEntity>()));
            return snapshots > 0 ? "healthy" : "unavailable";
        } catch (Exception ex) {
            log.warn("Hosting health: inventory data probe failed: {}", ex.getMessage());
            return "unavailable";
        }
    }

    private String probeRedis() {
        try {
            var factory = redisTemplate.getConnectionFactory();
            if (factory == null) {
                return "unavailable";
            }
            factory.getConnection().ping();
            return "healthy";
        } catch (Exception ex) {
            log.warn("Hosting health: Redis probe failed: {}", ex.getMessage());
            return "unavailable";
        }
    }

    private String probeFeishu() {
        try {
            long dispatchable = nz(feishuIntegrationMapper.selectCount(new LambdaQueryWrapper<FeishuIntegrationEntity>()
                    .eq(FeishuIntegrationEntity::getStatus, "active")
                    .and(w -> w.isNotNull(FeishuIntegrationEntity::getWebhookUrlEncrypted)
                            .or()
                            .isNotNull(FeishuIntegrationEntity::getDefaultChatId))));
            if (dispatchable > 0) {
                return "healthy";
            }
            long active = nz(feishuIntegrationMapper.selectCount(new LambdaQueryWrapper<FeishuIntegrationEntity>()
                    .eq(FeishuIntegrationEntity::getStatus, "active")));
            return active > 0 ? "degraded" : "unavailable";
        } catch (Exception ex) {
            log.warn("Hosting health: Feishu integration probe failed: {}", ex.getMessage());
            return "unavailable";
        }
    }

    private static String worstStatus(Iterable<String> statuses) {
        String worst = "healthy";
        for (String s : statuses) {
            if ("unavailable".equals(s)) {
                return "unavailable";
            }
            if ("degraded".equals(s)) {
                worst = "degraded";
            }
        }
        return worst;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Redis cache for summary (Req 27.4, 30.3 fail-open)
    // ──────────────────────────────────────────────────────────────────────────

    private HostingDashboardSummaryVo readCachedSummary(UUID storeId) {
        if (!cacheEnabled) {
            return null;
        }
        try {
            Object raw = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + storeId);
            if (raw instanceof String json) {
                return objectMapper.readValue(json, HostingDashboardSummaryVo.class);
            }
        } catch (Exception ex) {
            log.warn("Hosting dashboard summary cache read failed for store {}: {}", storeId, ex.getMessage());
        }
        return null;
    }

    private void writeCachedSummary(UUID storeId, HostingDashboardSummaryVo summary) {
        if (!cacheEnabled) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(summary);
            redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + storeId, json,
                    Duration.ofSeconds(Math.max(cacheTtlSeconds, 1)));
        } catch (JsonProcessingException ex) {
            log.warn("Hosting dashboard summary cache serialize failed for store {}: {}", storeId, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Hosting dashboard summary cache write failed for store {}: {}", storeId, ex.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private LambdaQueryWrapper<OperationEntity> hostingOps(UUID storeId) {
        return new LambdaQueryWrapper<OperationEntity>()
                .eq(OperationEntity::getStoreId, storeId)
                .eq(OperationEntity::getOperationSource, AI_HOSTING_SOURCE);
    }

    private StoreEntity requireStore(UUID storeId) {
        StoreEntity store = storeMapper.selectById(storeId);
        if (store == null) {
            throw new IllegalStateException("Store not found: " + storeId);
        }
        return store;
    }

    /**
     * Resolve "today" in the store's Marketplace_Timezone and express the boundaries as
     * {@link LocalDateTime} in the server zone for comparison against persisted timestamps.
     */
    private LocalDateTime[] todayRange(StoreEntity store) {
        String timezone = resolveTimezone(store);
        InstantRange range = MarketplaceTimezone.currentDayBoundary(timezone, Instant.now());
        ZoneId sys = ZoneId.systemDefault();
        return new LocalDateTime[]{
                LocalDateTime.ofInstant(range.startInclusive(), sys),
                LocalDateTime.ofInstant(range.endExclusive(), sys)
        };
    }

    private String resolveTimezone(StoreEntity store) {
        if (store.getMarketplaceId() != null) {
            MarketplaceEntity marketplace = marketplaceMapper.selectById(store.getMarketplaceId());
            if (marketplace != null && marketplace.getTimezone() != null && !marketplace.getTimezone().isBlank()) {
                return marketplace.getTimezone();
            }
        }
        // Degrade gracefully to UTC rather than failing the dashboard when a timezone is unset.
        log.warn("Marketplace timezone unset for store {}; falling back to UTC for day boundary", store.getId());
        return "UTC";
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException ex) {
            // Not valid JSON — return the raw string so the caller still sees the value.
            return json;
        }
    }

    private static String idOrNull(UUID id) {
        return id == null ? null : id.toString();
    }
}
