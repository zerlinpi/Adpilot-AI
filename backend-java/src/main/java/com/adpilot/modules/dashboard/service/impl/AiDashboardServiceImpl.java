package com.adpilot.modules.dashboard.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.AiNotificationEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.AiNotificationMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.support.AdMetrics;
import com.adpilot.modules.automation.entity.AutomationExecutionEntity;
import com.adpilot.modules.automation.mapper.AutomationExecutionMapper;
import com.adpilot.modules.dashboard.service.AiDashboardService;
import com.adpilot.modules.dashboard.vo.AiActionVo;
import com.adpilot.modules.dashboard.vo.AiActionsVo;
import com.adpilot.modules.dashboard.vo.AiNotificationCategoryVo;
import com.adpilot.modules.dashboard.vo.AiNotificationsSummaryVo;
import com.adpilot.modules.dashboard.vo.AiUsageVo;
import com.adpilot.modules.dashboard.vo.MetricDeltaVo;
import com.adpilot.modules.dashboard.vo.SalesOverviewVo;
import com.adpilot.modules.dashboard.vo.SalesTrendVo;
import com.adpilot.modules.dashboard.vo.TrendPointVo;
import com.adpilot.modules.finance.service.CurrencyService;
import com.adpilot.modules.finance.vo.ConvertedAmount;
import com.adpilot.modules.order.entity.OrderEntity;
import com.adpilot.modules.order.mapper.OrderMapper;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Default {@link AiDashboardService}. Resolves the user's accessible stores
 * through {@link DataScopeService}, optionally narrows by {@code storeId} /
 * {@code marketplace}, aggregates {@code performance_daily} /
 * {@code orders} / {@code automation_executions}, normalizes amounts to the
 * requested reporting currency via {@link CurrencyService}, and computes
 * ACoS / TACoS / AI coverage with the pure {@link AdMetrics} helper (Req 18).
 */
@Slf4j
@Service
public class AiDashboardServiceImpl implements AiDashboardService {

    private static final int MONEY_SCALE = 2;
    private static final int DEFAULT_RANGE_DAYS = 30;

    /** Redis key prefix for the cached dashboard read panels (H1 optimization). */
    private static final String CACHE_KEY_PREFIX = "dashboard:ai:";

    private final DataScopeService dataScopeService;
    private final StoreMapper storeMapper;
    private final MarketplaceMapper marketplaceMapper;
    private final MarketplaceReferenceService marketplaceReferenceService;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final CampaignMapper campaignMapper;
    private final AutomationExecutionMapper automationExecutionMapper;
    private final OrderMapper orderMapper;
    private final AiNotificationMapper aiNotificationMapper;
    private final CurrencyService currencyService;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final String reportingCurrency;
    private final boolean cacheEnabled;
    private final long cacheTtlSeconds;

    public AiDashboardServiceImpl(DataScopeService dataScopeService,
                                  StoreMapper storeMapper,
                                  MarketplaceMapper marketplaceMapper,
                                  MarketplaceReferenceService marketplaceReferenceService,
                                  PerformanceDailyMapper performanceDailyMapper,
                                  CampaignMapper campaignMapper,
                                  AutomationExecutionMapper automationExecutionMapper,
                                  OrderMapper orderMapper,
                                  AiNotificationMapper aiNotificationMapper,
                                  CurrencyService currencyService,
                                  ObjectMapper objectMapper,
                                  RedisTemplate<String, Object> redisTemplate,
                                  @Value("${adpilot.aggregation.reporting-currency:USD}") String reportingCurrency,
                                  @Value("${adpilot.dashboard.cache-enabled:true}") boolean cacheEnabled,
                                  @Value("${adpilot.dashboard.cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.dataScopeService = dataScopeService;
        this.storeMapper = storeMapper;
        this.marketplaceMapper = marketplaceMapper;
        this.marketplaceReferenceService = marketplaceReferenceService;
        this.performanceDailyMapper = performanceDailyMapper;
        this.campaignMapper = campaignMapper;
        this.automationExecutionMapper = automationExecutionMapper;
        this.orderMapper = orderMapper;
        this.aiNotificationMapper = aiNotificationMapper;
        this.currencyService = currencyService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.reportingCurrency = normalizeCurrency(reportingCurrency, "USD");
        this.cacheEnabled = cacheEnabled;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    // =====================================================================
    // Req 18.1 — Sales Overview
    // =====================================================================

    @Override
    public SalesOverviewVo getSalesOverview(String storeId, String marketplace, String currency,
                                            LocalDate start, LocalDate end) {
        Scope scope = resolveScope(storeId, marketplace, currency, start, end);
        return cached(CACHE_KEY_PREFIX + "overview:" + scopeKey(scope),
                SalesOverviewVo.class, () -> computeSalesOverview(scope));
    }

    private SalesOverviewVo computeSalesOverview(Scope scope) {
        Totals current = aggregateTotals(scope, scope.start, scope.end);
        LocalDate[] prev = previousPeriod(scope.start, scope.end);
        Totals previous = aggregateTotals(scope, prev[0], prev[1]);

        return SalesOverviewVo.builder()
                .currency(scope.targetCurrency)
                .totalSales(delta(current.totalSales, previous.totalSales))
                .adSpend(delta(current.spend, previous.spend))
                .adSales(delta(current.sales, previous.sales))
                .adOrders(delta(BigDecimal.valueOf(current.orders), BigDecimal.valueOf(previous.orders)))
                .tacos(delta(AdMetrics.tacos(current.spend, current.totalSales),
                        AdMetrics.tacos(previous.spend, previous.totalSales)))
                .acos(delta(AdMetrics.acos(current.spend, current.sales),
                        AdMetrics.acos(previous.spend, previous.sales)))
                .build();
    }

    // =====================================================================
    // Req 18.2 — Sales Trend
    // =====================================================================

    @Override
    public SalesTrendVo getSalesTrend(String storeId, String marketplace, String currency,
                                      LocalDate start, LocalDate end, String granularity) {
        Scope scope = resolveScope(storeId, marketplace, currency, start, end);
        String gran = normalizeGranularity(granularity);
        return cached(CACHE_KEY_PREFIX + "trend:" + gran + ":" + scopeKey(scope),
                SalesTrendVo.class, () -> computeSalesTrend(scope, gran));
    }

    private SalesTrendVo computeSalesTrend(Scope scope, String gran) {
        // Batch performance + orders across the whole scope, then aggregate per store in memory.
        Map<UUID, List<PerformanceDailyEntity>> perfByStore = performanceRowsByStore(scope.storeIds, scope.start, scope.end);
        Map<UUID, List<OrderEntity>> ordersByStore = ordersByStore(scope.storeIds, scope.start, scope.end);

        // Bucket key -> [spend, sales, totalSales] accumulated in the target currency.
        Map<String, BigDecimal[]> buckets = new LinkedHashMap<>();
        for (StoreEntity store : scope.stores) {
            String storeCurrency = scope.currencyFor(store);
            for (PerformanceDailyEntity row : perfByStore.getOrDefault(store.getId(), List.of())) {
                String key = bucketKey(row.getDate(), gran);
                if (key == null) {
                    continue;
                }
                BigDecimal[] acc = buckets.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                acc[0] = acc[0].add(convert(nz(row.getSpend()), storeCurrency, scope.targetCurrency, scope.end));
                acc[1] = acc[1].add(convert(nz(row.getSales()), storeCurrency, scope.targetCurrency, scope.end));
            }
            // 总销售额 per bucket comes from orders (same source as the overview).
            for (OrderEntity order : ordersByStore.getOrDefault(store.getId(), List.of())) {
                if (order.getPurchaseDate() == null) {
                    continue;
                }
                String key = bucketKey(order.getPurchaseDate().toLocalDate(), gran);
                if (key == null) {
                    continue;
                }
                BigDecimal[] acc = buckets.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                acc[2] = acc[2].add(convert(orderRevenue(order), storeCurrency, scope.targetCurrency, scope.end));
            }
        }

        List<TrendPointVo> points = buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> TrendPointVo.builder()
                        .period(e.getKey())
                        .spend(scale(e.getValue()[0]))
                        .sales(scale(e.getValue()[1]))
                        .totalSales(scale(e.getValue()[2]))
                        .build())
                .collect(Collectors.toList());

        return SalesTrendVo.builder()
                .granularity(gran)
                .currency(scope.targetCurrency)
                .points(points)
                .build();
    }

    // =====================================================================
    // Req 18.3 — AI Actions
    // =====================================================================

    /** The standard SP AI_Action set, in display order: key -> Chinese label. */
    private static final List<String[]> STANDARD_ACTIONS = List.of(
            new String[]{"keyword_harvesting", "关键词收割"},
            new String[]{"negative_keywords", "关键词否定"},
            new String[]{"bid_optimization", "竞价优化"},
            new String[]{"budget_optimization", "预算优化"},
            new String[]{"ad_structure_optimization", "广告结构优化"},
            new String[]{"dayparting_budget", "分时段预算"},
            new String[]{"dayparting_bid", "分时竞价"},
            new String[]{"quick_search_test", "快搜测试"});

    @Override
    public AiActionsVo getAiActions(String storeId, String marketplace, String currency,
                                    LocalDate start, LocalDate end) {
        Scope scope = resolveScope(storeId, marketplace, currency, start, end);
        return cached(CACHE_KEY_PREFIX + "actions:" + scopeKey(scope),
                AiActionsVo.class, () -> computeAiActions(scope));
    }

    private AiActionsVo computeAiActions(Scope scope) {
        Map<String, long[]> counts = new HashMap<>();           // key -> [count]
        Map<String, Set<String>> affected = new HashMap<>();     // key -> distinct entity ids

        if (!scope.storeIds.isEmpty()) {
            LambdaQueryWrapper<AutomationExecutionEntity> w = new LambdaQueryWrapper<>();
            w.in(AutomationExecutionEntity::getStoreId, scope.storeIds)
                    .ge(AutomationExecutionEntity::getCreatedAt, scope.start.atStartOfDay())
                    .le(AutomationExecutionEntity::getCreatedAt, scope.end.atTime(LocalTime.MAX));
            List<AutomationExecutionEntity> executions = safe(automationExecutionMapper.selectList(w));
            for (AutomationExecutionEntity exec : executions) {
                String key = classifyAction(exec.getActionType());
                if (key == null) {
                    continue;
                }
                counts.computeIfAbsent(key, k -> new long[]{0L})[0]++;
                if (exec.getEntityId() != null) {
                    affected.computeIfAbsent(key, k -> new HashSet<>()).add(exec.getEntityId().toString());
                }
            }
        }

        List<AiActionVo> actions = new ArrayList<>();
        for (String[] def : STANDARD_ACTIONS) {
            String key = def[0];
            long count = counts.containsKey(key) ? counts.get(key)[0] : 0L;
            long affectedCount = affected.containsKey(key) ? affected.get(key).size() : 0L;
            actions.add(AiActionVo.builder()
                    .key(key)
                    .label(def[1])
                    .count(count)
                    .affectedCampaigns(affectedCount)
                    .build());
        }

        return AiActionsVo.builder()
                .currency(scope.targetCurrency)
                .actions(actions)
                .build();
    }

    /** Maps a raw {@code action_type} into one of the standard AI_Action buckets, or {@code null}. */
    private static String classifyAction(String actionType) {
        if (!StringUtils.hasText(actionType)) {
            return null;
        }
        String t = actionType.toLowerCase();
        boolean daypart = t.contains("daypart") || t.contains("hourly") || t.contains("分时");
        if (daypart && t.contains("budget")) {
            return "dayparting_budget";
        }
        if (daypart && t.contains("bid")) {
            return "dayparting_bid";
        }
        if (t.contains("harvest")) {
            return "keyword_harvesting";
        }
        if (t.contains("negative") || t.contains("negate")) {
            return "negative_keywords";
        }
        if (t.contains("structure")) {
            return "ad_structure_optimization";
        }
        if (t.contains("budget")) {
            return "budget_optimization";
        }
        if (t.contains("bid")) {
            return "bid_optimization";
        }
        if (t.contains("quick") || t.contains("search_test")) {
            return "quick_search_test";
        }
        return null;
    }

    // =====================================================================
    // Req 18.4 — AI Usage
    // =====================================================================

    @Override
    public AiUsageVo getAiUsage(String storeId, String marketplace, String currency,
                                LocalDate start, LocalDate end) {
        Scope scope = resolveScope(storeId, marketplace, currency, start, end);
        return cached(CACHE_KEY_PREFIX + "usage:" + scopeKey(scope),
                AiUsageVo.class, () -> computeAiUsage(scope));
    }

    private AiUsageVo computeAiUsage(Scope scope) {
        BigDecimal totalAdSpend = BigDecimal.ZERO;
        BigDecimal aiAdSpend = BigDecimal.ZERO;
        BigDecimal aiAdSales = BigDecimal.ZERO;

        // Batch performance rows and AI-managed campaign ids across the whole scope.
        Map<UUID, List<PerformanceDailyEntity>> perfByStore = performanceRowsByStore(scope.storeIds, scope.start, scope.end);
        Map<UUID, Set<UUID>> aiCampaignsByStore = aiManagedCampaignIdsByStore(scope.storeIds);

        for (StoreEntity store : scope.stores) {
            String storeCurrency = scope.currencyFor(store);
            Set<UUID> aiCampaignIds = aiCampaignsByStore.getOrDefault(store.getId(), Set.of());

            for (PerformanceDailyEntity row : perfByStore.getOrDefault(store.getId(), List.of())) {
                BigDecimal spend = convert(nz(row.getSpend()), storeCurrency, scope.targetCurrency, scope.end);
                totalAdSpend = totalAdSpend.add(spend);
                if (row.getCampaignId() != null && aiCampaignIds.contains(row.getCampaignId())) {
                    aiAdSpend = aiAdSpend.add(spend);
                    aiAdSales = aiAdSales.add(
                            convert(nz(row.getSales()), storeCurrency, scope.targetCurrency, scope.end));
                }
            }
        }

        return AiUsageVo.builder()
                .currency(scope.targetCurrency)
                .coveragePercent(AdMetrics.aiCoverage(aiAdSpend, totalAdSpend))
                .aiAdSpend(scale(aiAdSpend))
                .aiAdSales(scale(aiAdSales))
                .build();
    }

    private Map<UUID, Set<UUID>> aiManagedCampaignIdsByStore(List<UUID> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<CampaignEntity> w = new LambdaQueryWrapper<>();
        w.in(CampaignEntity::getStoreId, storeIds)
                .and(q -> q.eq(CampaignEntity::getAiManaged, true)
                        .or().eq(CampaignEntity::getHostingEnabled, true));
        Map<UUID, Set<UUID>> byStore = new HashMap<>();
        for (CampaignEntity c : safe(campaignMapper.selectList(w))) {
            if (c.getStoreId() == null || c.getId() == null) {
                continue;
            }
            byStore.computeIfAbsent(c.getStoreId(), k -> new HashSet<>()).add(c.getId());
        }
        return byStore;
    }

    // =====================================================================
    // Req 18.5 — AI Notifications summary (live from the ai_notifications table)
    // =====================================================================

    /**
     * The four dashboard notification categories, in display order, as
     * {dashboardKey, label, aiNotificationCategory} triples.
     *
     * <p>The dashboard uses descriptive keys (e.g. {@code one_click_optimization})
     * while the backing {@code ai_notifications} table stores shorter category
     * codes (e.g. {@code one_click_optimize}, see
     * {@link com.adpilot.modules.advertising.service.impl.AiNotificationServiceImpl}).
     * The third element maps each dashboard key to the persisted category code:
     * <ul>
     *   <li>{@code core_ops_attention}      → {@code core_ops}</li>
     *   <li>{@code one_click_optimization}  → {@code one_click_optimize}</li>
     *   <li>{@code discover_high_potential} → {@code high_potential}</li>
     *   <li>{@code ai_target_correction}    → {@code target_correction}</li>
     * </ul>
     */
    private static final List<String[]> NOTIFICATION_CATEGORIES = List.of(
            new String[]{"core_ops_attention", "广告运营核心关注", "core_ops"},
            new String[]{"one_click_optimization", "广告活动一键优化", "one_click_optimize"},
            new String[]{"discover_high_potential", "发现高潜广告活动", "high_potential"},
            new String[]{"ai_target_correction", "AI目标修正待确认", "target_correction"});

    /** The lifecycle state that counts as pending(待处理) in ai_notifications. */
    private static final String PENDING_STATE = "pending";

    @Override
    public AiNotificationsSummaryVo getAiNotificationsSummary(String storeId, String marketplace,
                                                              LocalDate start, LocalDate end) {
        // Resolve scope so this endpoint enforces the same access rules and the
        // same store/marketplace + date window as the other dashboard panels.
        Scope scope = resolveScope(storeId, marketplace, null, start, end);

        String key = CACHE_KEY_PREFIX + "notifications:" + scopeKey(scope);
        AiNotificationsSummaryVo hit = readCache(key, AiNotificationsSummaryVo.class);
        if (hit != null) {
            return hit;
        }

        AiNotificationsSummaryVo result = computeAiNotificationsSummary(scope);
        // Only cache real (available) summaries so a transient query failure is
        // never pinned in the cache for the TTL window.
        if (result.isAvailable()) {
            writeCache(key, result);
        }
        return result;
    }

    private AiNotificationsSummaryVo computeAiNotificationsSummary(Scope scope) {
        try {
            // Count pending notifications per persisted category, scoped to the
            // accessible stores and the resolved date window. ai_notifications has
            // no marketplace column, so the marketplace filter is applied via the
            // store set resolved above.
            Map<String, Long> pendingByCategory = new HashMap<>();
            if (!scope.storeIds.isEmpty()) {
                LambdaQueryWrapper<AiNotificationEntity> w = new LambdaQueryWrapper<>();
                w.in(AiNotificationEntity::getStoreId, scope.storeIds)
                        .eq(AiNotificationEntity::getState, PENDING_STATE)
                        .ge(AiNotificationEntity::getCreatedAt, scope.start.atStartOfDay())
                        .le(AiNotificationEntity::getCreatedAt, scope.end.atTime(LocalTime.MAX));
                for (AiNotificationEntity n : safe(aiNotificationMapper.selectList(w))) {
                    if (n.getCategory() == null) {
                        continue;
                    }
                    pendingByCategory.merge(n.getCategory(), 1L, Long::sum);
                }
            }

            List<AiNotificationCategoryVo> categories = NOTIFICATION_CATEGORIES.stream()
                    .map(def -> AiNotificationCategoryVo.builder()
                            .key(def[0])
                            .label(def[1])
                            .pendingCount(pendingByCategory.getOrDefault(def[2], 0L))
                            .build())
                    .collect(Collectors.toList());

            return AiNotificationsSummaryVo.builder()
                    .available(true)
                    .categories(categories)
                    .build();
        } catch (Exception e) {
            // Degrade gracefully only on an actual query error: report every
            // category with a 0 pending count and flag the summary unavailable
            // rather than failing the whole dashboard.
            log.warn("AI notifications summary query failed; returning unavailable summary: {}", e.getMessage());
            List<AiNotificationCategoryVo> categories = NOTIFICATION_CATEGORIES.stream()
                    .map(def -> AiNotificationCategoryVo.builder()
                            .key(def[0])
                            .label(def[1])
                            .pendingCount(0L)
                            .build())
                    .collect(Collectors.toList());
            return AiNotificationsSummaryVo.builder()
                    .available(false)
                    .categories(categories)
                    .build();
        }
    }

    // =====================================================================
    // Aggregation helpers
    // =====================================================================

    /** Running monetary/count totals over a date range, in the target currency. */
    private static final class Totals {
        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal totalSales = BigDecimal.ZERO;
        long orders = 0L;
    }

    private Totals aggregateTotals(Scope scope, LocalDate start, LocalDate end) {
        Totals totals = new Totals();
        // Batch each entity type once across the whole scope, then aggregate per store in memory.
        Map<UUID, List<PerformanceDailyEntity>> perfByStore = performanceRowsByStore(scope.storeIds, start, end);
        Map<UUID, List<OrderEntity>> ordersByStore = ordersByStore(scope.storeIds, start, end);
        for (StoreEntity store : scope.stores) {
            String storeCurrency = scope.currencyFor(store);

            for (PerformanceDailyEntity row : perfByStore.getOrDefault(store.getId(), List.of())) {
                totals.spend = totals.spend.add(convert(nz(row.getSpend()), storeCurrency, scope.targetCurrency, end));
                totals.sales = totals.sales.add(convert(nz(row.getSales()), storeCurrency, scope.targetCurrency, end));
                if (row.getOrders() != null) {
                    totals.orders += row.getOrders();
                }
            }

            for (OrderEntity order : ordersByStore.getOrDefault(store.getId(), List.of())) {
                totals.totalSales = totals.totalSales.add(
                        convert(orderRevenue(order), storeCurrency, scope.targetCurrency, end));
            }
        }
        return totals;
    }

    /**
     * Batches {@code performance_daily} for every store in the scope with a single
     * {@code IN (...)} query and groups the rows by store, replacing the previous
     * per-store N+1 query pattern (H1 optimization).
     */
    private Map<UUID, List<PerformanceDailyEntity>> performanceRowsByStore(List<UUID> storeIds,
                                                                           LocalDate start, LocalDate end) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<PerformanceDailyEntity> w = new LambdaQueryWrapper<>();
        w.in(PerformanceDailyEntity::getStoreId, storeIds);
        if (start != null) {
            w.ge(PerformanceDailyEntity::getDate, start);
        }
        if (end != null) {
            w.le(PerformanceDailyEntity::getDate, end);
        }
        return safe(performanceDailyMapper.selectList(w)).stream()
                .filter(r -> r.getStoreId() != null)
                .collect(Collectors.groupingBy(PerformanceDailyEntity::getStoreId));
    }

    /**
     * Batches {@code orders} for every store in the scope with a single
     * {@code IN (...)} query and groups the rows by store (H1 optimization).
     */
    private Map<UUID, List<OrderEntity>> ordersByStore(List<UUID> storeIds, LocalDate start, LocalDate end) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<>();
        w.in(OrderEntity::getStoreId, storeIds);
        if (start != null) {
            w.ge(OrderEntity::getPurchaseDate, start.atStartOfDay());
        }
        if (end != null) {
            w.le(OrderEntity::getPurchaseDate, end.atTime(LocalTime.MAX));
        }
        return safe(orderMapper.selectList(w)).stream()
                .filter(o -> o.getStoreId() != null)
                .collect(Collectors.groupingBy(OrderEntity::getStoreId));
    }

    /** Revenue for a single order line: item + shipping, net of promotions. */
    private static BigDecimal orderRevenue(OrderEntity o) {
        return nz(o.getItemPrice())
                .add(nz(o.getShippingPrice()))
                .subtract(nz(o.getItemPromotionDiscount()))
                .subtract(nz(o.getShipPromotionDiscount()));
    }

    private MetricDeltaVo delta(BigDecimal value, BigDecimal previous) {
        BigDecimal v = scale(nz(value));
        BigDecimal p = scale(nz(previous));
        BigDecimal d = v.subtract(p);
        BigDecimal pct = p.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : d.divide(p, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return MetricDeltaVo.builder()
                .value(v)
                .previous(p)
                .delta(d.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .deltaPct(pct)
                .build();
    }

    // =====================================================================
    // Redis caching (H1 optimization — short TTL, keyed by resolved scope)
    // =====================================================================

    /**
     * Reads a cached value if present, otherwise computes it and caches the
     * result. Fails open: any Redis/serialization error degrades to a direct
     * computation rather than surfacing an error (mirrors HostingDashboardServiceImpl).
     *
     * <p>The cache key embeds the resolved store-scope so a user never observes
     * another scope's cached data.</p>
     */
    private <T> T cached(String key, Class<T> type, Supplier<T> compute) {
        T hit = readCache(key, type);
        if (hit != null) {
            return hit;
        }
        T value = compute.get();
        writeCache(key, value);
        return value;
    }

    private <T> T readCache(String key, Class<T> type) {
        if (!cacheEnabled) {
            return null;
        }
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw instanceof String json) {
                return objectMapper.readValue(json, type);
            }
        } catch (Exception ex) {
            log.warn("Dashboard cache read failed for {}: {}", key, ex.getMessage());
        }
        return null;
    }

    private void writeCache(String key, Object value) {
        if (!cacheEnabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(Math.max(cacheTtlSeconds, 1)));
        } catch (Exception ex) {
            log.warn("Dashboard cache write failed for {}: {}", key, ex.getMessage());
        }
    }

    /**
     * Builds a cache key fragment from a resolved scope. Store ids are sorted so
     * the key is stable regardless of store ordering, and the fragment includes
     * the target currency and date window that determine the panel's contents.
     */
    private String scopeKey(Scope scope) {
        String stores = scope.storeIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .sorted()
                .collect(Collectors.joining(","));
        return stores + "|" + scope.targetCurrency + "|" + scope.start + "|" + scope.end;
    }

    // =====================================================================
    // Scope resolution
    // =====================================================================

    /** Resolved request scope: the eligible stores, currency map, target currency, and date range. */
    private final class Scope {
        final List<StoreEntity> stores;
        final List<UUID> storeIds;
        final Map<UUID, String> currencyByMarketplace;
        final String targetCurrency;
        final LocalDate start;
        final LocalDate end;

        Scope(List<StoreEntity> stores, Map<UUID, String> currencyByMarketplace,
              String targetCurrency, LocalDate start, LocalDate end) {
            this.stores = stores;
            this.storeIds = stores.stream().map(StoreEntity::getId).collect(Collectors.toList());
            this.currencyByMarketplace = currencyByMarketplace;
            this.targetCurrency = targetCurrency;
            this.start = start;
            this.end = end;
        }

        String currencyFor(StoreEntity store) {
            String c = store.getMarketplaceId() != null ? currencyByMarketplace.get(store.getMarketplaceId()) : null;
            return StringUtils.hasText(c) ? c : targetCurrency;
        }
    }

    private Scope resolveScope(String storeId, String marketplace, String currency,
                               LocalDate start, LocalDate end) {
        // Default to the most recent 30 days when no range is supplied, so that
        // period-over-period deltas are always well-defined.
        LocalDate effEnd = end != null ? end : LocalDate.now();
        LocalDate effStart = start != null ? start : effEnd.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (effStart.isAfter(effEnd)) {
            LocalDate tmp = effStart;
            effStart = effEnd;
            effEnd = tmp;
        }

        CurrentUser user = SecurityUtils.getCurrentUser();
        Map<UUID, String> currencyByMarketplace = marketplaceReferenceService.currencyByMarketplaceId();
        UUID marketplaceId = resolveMarketplaceId(marketplace);

        List<StoreEntity> stores = accessibleStores(user).stream()
                .filter(s -> !StringUtils.hasText(storeId)
                        || (s.getId() != null && s.getId().toString().equalsIgnoreCase(storeId.trim())))
                .filter(s -> marketplaceId == null || marketplaceId.equals(s.getMarketplaceId()))
                .collect(Collectors.toList());

        String target;
        if (StringUtils.hasText(currency)) {
            target = normalizeCurrency(currency, reportingCurrency);
        } else if (stores.size() == 1) {
            String c = currencyByMarketplace.get(stores.get(0).getMarketplaceId());
            target = StringUtils.hasText(c) ? c : reportingCurrency;
        } else {
            target = reportingCurrency;
        }

        return new Scope(stores, currencyByMarketplace, target, effStart, effEnd);
    }

    private List<StoreEntity> accessibleStores(CurrentUser user) {
        QueryWrapper<StoreEntity> wrapper = new QueryWrapper<>();
        dataScopeService.applyScope(
                wrapper,
                ScopeTarget.builder().storeIdColumn("id").ownerIdColumn("created_by").build(),
                user);
        return safe(storeMapper.selectList(wrapper));
    }

    /** Resolves a marketplace parameter (UUID id or marketplace code) to a marketplace id, or {@code null}. */
    private UUID resolveMarketplaceId(String marketplace) {
        if (!StringUtils.hasText(marketplace)) {
            return null;
        }
        String value = marketplace.trim();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            // Not a UUID: treat as a marketplace code (e.g. "US").
        }
        LambdaQueryWrapper<MarketplaceEntity> w = new LambdaQueryWrapper<>();
        w.eq(MarketplaceEntity::getCode, value).last("limit 1");
        MarketplaceEntity m = marketplaceMapper.selectOne(w);
        return m != null ? m.getId() : null;
    }

    // =====================================================================
    // small utilities
    // =====================================================================

    /** Converts an amount best-effort; when no rate exists, keeps the original value rather than dropping it. */
    private BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency, LocalDate date) {
        BigDecimal value = nz(amount);
        if (!StringUtils.hasText(fromCurrency) || !StringUtils.hasText(toCurrency)
                || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return value;
        }
        ConvertedAmount converted = currencyService.convert(value, fromCurrency, toCurrency, date);
        return converted.unconverted() ? value : converted.converted();
    }

    private static LocalDate[] previousPeriod(LocalDate start, LocalDate end) {
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1);
        return new LocalDate[]{prevStart, prevEnd};
    }

    private static String normalizeGranularity(String granularity) {
        if (!StringUtils.hasText(granularity)) {
            return "day";
        }
        String g = granularity.trim().toLowerCase();
        return switch (g) {
            case "week", "month", "day" -> g;
            default -> "day";
        };
    }

    private static String bucketKey(LocalDate date, String granularity) {
        if (date == null) {
            return null;
        }
        return switch (granularity) {
            case "month" -> String.format("%04d-%02d", date.getYear(), date.getMonthValue());
            case "week" -> String.format("%04d-W%02d",
                    date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            default -> date.toString();
        };
    }

    private static <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalizeCurrency(String code, String fallback) {
        if (!StringUtils.hasText(code)) {
            return fallback;
        }
        return code.trim().toUpperCase();
    }
}
