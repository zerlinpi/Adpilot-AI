package com.adpilot.modules.insights.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.support.AdMetrics;
import com.adpilot.modules.insights.service.InsightsService;
import com.adpilot.modules.insights.entity.DataSourceActivationEntity;
import com.adpilot.modules.insights.mapper.DataSourceActivationMapper;
import com.adpilot.modules.insights.vo.AmcTemplatesVo;
import com.adpilot.modules.insights.vo.AmcTemplatesVo.AmcTemplateVo;
import com.adpilot.modules.insights.vo.BrandMetricsVo;
import com.adpilot.modules.insights.vo.CustomReportVo;
import com.adpilot.modules.insights.vo.DataSourceActivationVo;
import com.adpilot.modules.insights.vo.MarketInsightsVo;
import com.adpilot.modules.insights.vo.ProductInsightsVo;
import com.adpilot.modules.insights.vo.ProductListItemVo;
import com.adpilot.modules.insights.vo.SqpVo;
import com.adpilot.modules.order.entity.OrderEntity;
import com.adpilot.modules.order.mapper.OrderMapper;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.report.entity.ReportEntity;
import com.adpilot.modules.report.mapper.ReportMapper;
import com.adpilot.modules.store.entity.StoreEntity;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Default {@link InsightsService}. Resolves the user's accessible stores through
 * {@link DataScopeService}, optionally narrows by {@code storeId}, and computes
 * the Data Insights surfaces from the project's own stored data
 * ({@code products}, {@code performance_daily}, {@code orders}, {@code reports}).
 *
 * <p>Surfaces that depend on Amazon-side data the project does not ingest
 * (brand analytics, SQP, AMC) return an explicit "requires activation" / empty
 * payload rather than fabricating data (Req 30.7, 30.8).</p>
 */
@Slf4j
@Service
public class InsightsServiceImpl implements InsightsService {

    private static final int MONEY_SCALE = 2;
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int LOW_INVENTORY_THRESHOLD = 10;

    /** Data-source keys gating the brand / SQP and AMC surfaces (item 8). */
    private static final String SOURCE_BRAND_ANALYTICS = "brand_analytics";
    private static final String SOURCE_AMC = "amc";
    private static final java.util.Set<String> VALID_SOURCES =
            java.util.Set.of(SOURCE_BRAND_ANALYTICS, SOURCE_AMC);
    private static final DateTimeFormatter ACTIVATION_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Redis key prefix for the cached product-list insights surface (H1 optimization). */
    private static final String CACHE_KEY_PREFIX = "insights:products:";

    private final DataScopeService dataScopeService;
    private final StoreMapper storeMapper;
    private final MarketplaceReferenceService marketplaceReferenceService;
    private final ProductMapper productMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final OrderMapper orderMapper;
    private final ReportMapper reportMapper;
    private final DataSourceActivationMapper dataSourceActivationMapper;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final String reportingCurrency;
    private final long customReportQuotaTotal;
    private final boolean cacheEnabled;
    private final long cacheTtlSeconds;

    public InsightsServiceImpl(DataScopeService dataScopeService,
                               StoreMapper storeMapper,
                               MarketplaceReferenceService marketplaceReferenceService,
                               ProductMapper productMapper,
                               PerformanceDailyMapper performanceDailyMapper,
                               OrderMapper orderMapper,
                               ReportMapper reportMapper,
                               DataSourceActivationMapper dataSourceActivationMapper,
                               ObjectMapper objectMapper,
                               RedisTemplate<String, Object> redisTemplate,
                               @Value("${adpilot.aggregation.reporting-currency:USD}") String reportingCurrency,
                               @Value("${adpilot.insights.custom-report-quota:20}") long customReportQuotaTotal,
                               @Value("${adpilot.insights.cache-enabled:true}") boolean cacheEnabled,
                               @Value("${adpilot.insights.cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.dataScopeService = dataScopeService;
        this.storeMapper = storeMapper;
        this.marketplaceReferenceService = marketplaceReferenceService;
        this.productMapper = productMapper;
        this.performanceDailyMapper = performanceDailyMapper;
        this.orderMapper = orderMapper;
        this.reportMapper = reportMapper;
        this.dataSourceActivationMapper = dataSourceActivationMapper;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.reportingCurrency = normalizeCurrency(reportingCurrency, "USD");
        this.customReportQuotaTotal = customReportQuotaTotal;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    // =====================================================================
    // Req 30.1 / 30.2 — Product list + custom-report quota
    // =====================================================================

    @Override
    public ProductInsightsVo getProductList(String storeId, LocalDate start, LocalDate end) {
        Scope scope = resolveScope(storeId, start, end);
        return cached(CACHE_KEY_PREFIX + scopeKey(scope),
                ProductInsightsVo.class, () -> computeProductList(scope));
    }

    private ProductInsightsVo computeProductList(Scope scope) {
        // Batch products / performance / orders across the whole scope with a single
        // IN (...) query each, then group by store in memory (H1 optimization —
        // replaces the previous per-store N+1 query pattern).
        Map<UUID, List<ProductEntity>> productsByStore = productsByStore(scope.storeIds);
        Map<UUID, List<PerformanceDailyEntity>> perfByStore = performanceRowsByStore(scope.storeIds, scope.start, scope.end);
        Map<UUID, List<OrderEntity>> ordersByStore = ordersByStore(scope.storeIds, scope.start, scope.end);

        List<ProductListItemVo> items = new ArrayList<>();
        for (StoreEntity store : scope.stores) {
            List<ProductEntity> products = productsByStore.getOrDefault(store.getId(), List.of());
            if (products.isEmpty()) {
                continue;
            }

            // Index ad performance by the entity the row attributes to, so each
            // product can pick up only the spend/sales recorded against it.
            List<PerformanceDailyEntity> perf = perfByStore.getOrDefault(store.getId(), List.of());
            Map<UUID, BigDecimal[]> adByEntity = new HashMap<>(); // entityId -> [spend, sales, orders]
            for (PerformanceDailyEntity row : perf) {
                UUID key = row.getEntityId();
                if (key == null) {
                    continue;
                }
                BigDecimal[] acc = adByEntity.computeIfAbsent(key,
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                acc[0] = acc[0].add(nz(row.getSpend()));
                acc[1] = acc[1].add(nz(row.getSales()));
                acc[2] = acc[2].add(BigDecimal.valueOf(row.getOrders() != null ? row.getOrders() : 0));
            }

            // Index total sales / orders by ASIN from the orders table.
            Map<String, BigDecimal[]> ordersByAsin = new HashMap<>(); // asin -> [sales, orders]
            for (OrderEntity order : ordersByStore.getOrDefault(store.getId(), List.of())) {
                String asin = order.getAsin();
                if (!StringUtils.hasText(asin)) {
                    continue;
                }
                BigDecimal[] acc = ordersByAsin.computeIfAbsent(asin.trim().toUpperCase(),
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                acc[0] = acc[0].add(orderRevenue(order));
                acc[1] = acc[1].add(BigDecimal.ONE);
            }

            for (ProductEntity p : products) {
                BigDecimal[] ad = adByEntity.getOrDefault(p.getId(),
                        new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                BigDecimal adSpend = ad[0];
                BigDecimal adSales = ad[1];

                String asinKey = StringUtils.hasText(p.getAsin()) ? p.getAsin().trim().toUpperCase() : null;
                BigDecimal[] ord = asinKey != null
                        ? ordersByAsin.getOrDefault(asinKey, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO})
                        : new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
                BigDecimal totalSales = ord[0];
                long totalOrders = ord[1].longValue();

                items.add(ProductListItemVo.builder()
                        .productId(p.getId() != null ? p.getId().toString() : null)
                        .parentAsin(p.getAsin())
                        .asin(p.getAsin())
                        .sku(p.getSku())
                        .name(p.getName())
                        .adSpend(scale(adSpend))
                        .adSales(scale(adSales))
                        .tacos(AdMetrics.tacos(adSpend, totalSales))
                        .totalSales(scale(totalSales))
                        .totalOrders(totalOrders)
                        .inventory(p.getInventory())
                        .inventoryStatus(inventoryStatus(p.getInventory()))
                        .build());
            }
        }

        // Custom-report quota (Req 30.2): consumed = custom reports stored for
        // the scope; total = configured quota.
        List<CustomReportVo> customReports = customReportsFor(scope.storeIds);

        return ProductInsightsVo.builder()
                .currency(scope.targetCurrency)
                .items(items)
                .customReportConsumed(customReports.size())
                .customReportTotal(customReportQuotaTotal)
                .customReports(customReports)
                .build();
    }

    private static String inventoryStatus(Integer inventory) {
        int qty = inventory != null ? inventory : 0;
        if (qty <= 0) {
            return "out_of_stock";
        }
        if (qty <= LOW_INVENTORY_THRESHOLD) {
            return "low";
        }
        return "healthy";
    }

    private Map<UUID, List<ProductEntity>> productsByStore(List<UUID> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<ProductEntity> w = new LambdaQueryWrapper<>();
        w.in(ProductEntity::getStoreId, storeIds);
        return safe(productMapper.selectList(w)).stream()
                .filter(p -> p.getStoreId() != null)
                .collect(Collectors.groupingBy(ProductEntity::getStoreId));
    }

    private List<CustomReportVo> customReportsFor(List<UUID> storeIds) {
        if (storeIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<ReportEntity> w = new LambdaQueryWrapper<>();
        w.in(ReportEntity::getStoreId, storeIds)
                .eq(ReportEntity::getType, "custom")
                .orderByDesc(ReportEntity::getCreatedAt);
        return safe(reportMapper.selectList(w)).stream()
                .map(r -> CustomReportVo.builder()
                        .id(r.getId() != null ? r.getId().toString() : null)
                        .title(r.getTitle())
                        .type(r.getType())
                        .periodStart(r.getPeriodStart() != null ? r.getPeriodStart().toString() : null)
                        .periodEnd(r.getPeriodEnd() != null ? r.getPeriodEnd().toString() : null)
                        .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null)
                        .build())
                .collect(Collectors.toList());
    }

    // =====================================================================
    // Req 30.3 — Brand metrics (requires brand-analytics activation)
    // =====================================================================

    @Override
    public BrandMetricsVo getBrandMetrics(String storeId, LocalDate start, LocalDate end) {
        // Enforce the same access rules as the other surfaces.
        resolveScope(storeId, start, end);

        // Gate behind brand-analytics activation (item 8). Until the store
        // activates the source, the surface stays gated.
        if (!isActivated(storeId, SOURCE_BRAND_ANALYTICS)) {
            return BrandMetricsVo.builder()
                    .requiresActivation(true)
                    .message("品牌指标来源于亚马逊品牌分析（Brand Analytics），需先激活品牌数据源后方可展示。")
                    .totalBrandCustomers(null)
                    .engagementRate(null)
                    .conversionRate(null)
                    .newToBrandSalesShare(null)
                    .build();
        }

        // Activated: unlock the surface. The project does not ingest Brand
        // Analytics data, so honestly report an empty state rather than
        // fabricating Amazon-side figures.
        return BrandMetricsVo.builder()
                .requiresActivation(false)
                .message("品牌数据源已激活。当前暂无可用的品牌分析数据，接入数据后将在此展示。")
                .totalBrandCustomers(null)
                .engagementRate(null)
                .conversionRate(null)
                .newToBrandSalesShare(null)
                .build();
    }

    // =====================================================================
    // Req 30.4 — Market insights (no market-monitoring data stored)
    // =====================================================================

    @Override
    public MarketInsightsVo getMarketInsights(String storeId) {
        resolveScope(storeId, null, null);
        return MarketInsightsVo.builder()
                .requiresActivation(false)
                .message("暂无市场监控报告。")
                .reports(List.of())
                .build();
    }

    // =====================================================================
    // Req 30.5 — SQP analysis (requires brand-analytics activation)
    // =====================================================================

    @Override
    public SqpVo getSqp(String storeId, LocalDate start, LocalDate end) {
        resolveScope(storeId, start, end);
        if (!isActivated(storeId, SOURCE_BRAND_ANALYTICS)) {
            return SqpVo.builder()
                    .requiresActivation(true)
                    .message("SQP（搜索词表现）来源于亚马逊品牌分析搜索词数据，需先激活品牌数据源后方可展示。")
                    .rows(List.of())
                    .build();
        }
        // Activated: unlock the surface. No SQP source is ingested, so report an
        // empty state honestly rather than fabricating data.
        return SqpVo.builder()
                .requiresActivation(false)
                .message("品牌数据源已激活。当前暂无可用的 SQP 搜索词数据，接入数据后将在此展示。")
                .rows(List.of())
                .build();
    }

    // =====================================================================
    // Req 30.6 / 30.7 — AMC data studio (templates rendered, execution gated)
    // =====================================================================

    private static final List<String[]> AMC_MODEL_TEMPLATES = List.of(
            new String[]{"path_to_conversion", "转化路径分析", "分析消费者从首次曝光到转化的多触点路径。", "归因分析"},
            new String[]{"new_to_brand", "品牌新客分析", "衡量广告带来的品牌新客与复购贡献。", "受众分析"},
            new String[]{"audience_overlap", "受众重叠分析", "评估不同广告活动之间的受众重叠程度。", "受众分析"},
            new String[]{"reach_frequency", "触达与频次分析", "分析广告触达人数与曝光频次分布。", "媒介分析"});

    private static final List<String[]> AMC_AUDIENCE_TEMPLATES = List.of(
            new String[]{"cart_abandoners", "加购未购买人群", "创建加入购物车但未完成购买的再营销受众。", "再营销"},
            new String[]{"repeat_buyers", "复购人群", "创建在指定周期内多次购买的高价值受众。", "忠诚度"},
            new String[]{"lapsed_customers", "流失客户人群", "创建一段时间内未再购买的召回受众。", "召回"},
            new String[]{"high_value_viewers", "高潜浏览人群", "创建高频浏览但尚未购买的潜在受众。", "拓新"});

    @Override
    public AmcTemplatesVo getAmcModels(String storeId) {
        resolveScope(storeId, null, null);
        return amcTemplates(AMC_MODEL_TEMPLATES, isActivated(storeId, SOURCE_AMC));
    }

    @Override
    public AmcTemplatesVo getAmcAudiences(String storeId) {
        resolveScope(storeId, null, null);
        return amcTemplates(AMC_AUDIENCE_TEMPLATES, isActivated(storeId, SOURCE_AMC));
    }

    private AmcTemplatesVo amcTemplates(List<String[]> defs, boolean activated) {
        List<AmcTemplateVo> templates = defs.stream()
                .map(d -> AmcTemplateVo.builder()
                        .key(d[0])
                        .name(d[1])
                        .description(d[2])
                        .category(d[3])
                        .activationRequired(!activated)
                        .build())
                .collect(Collectors.toList());
        String message = activated
                ? "AMC 数据工作室已激活。可基于已接入的数据运行模型与创建受众。"
                : "AMC 数据工作室需激活亚马逊营销云（Amazon Marketing Cloud）实例后方可运行模型与创建受众。";
        return AmcTemplatesVo.builder()
                .activated(activated)
                .message(message)
                .templates(templates)
                .build();
    }

    // =====================================================================
    // item 8 — Data source activation
    // =====================================================================

    @Override
    public DataSourceActivationVo getActivation(String storeId, String source) {
        UUID storeUuid = requireStore(storeId);
        String src = normalizeSource(source);
        DataSourceActivationEntity entity = findActivation(storeUuid, src);
        boolean activated = entity != null && Boolean.TRUE.equals(entity.getActivated());
        return DataSourceActivationVo.builder()
                .storeId(storeId)
                .source(src)
                .activated(activated)
                .activatedAt(entity != null && entity.getActivatedAt() != null
                        ? entity.getActivatedAt().format(ACTIVATION_TS) : null)
                .message(activated ? "数据源已激活。" : "数据源尚未激活，点击「激活」后即可使用已存储的数据。")
                .build();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public DataSourceActivationVo activate(String storeId, String source) {
        UUID storeUuid = requireStore(storeId);
        String src = normalizeSource(source);
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        UUID userUuid = parseUuidOrNull(userId);

        DataSourceActivationEntity entity = findActivation(storeUuid, src);
        LocalDateTime now = LocalDateTime.now();
        if (entity == null) {
            entity = DataSourceActivationEntity.builder()
                    .storeId(storeUuid)
                    .source(src)
                    .activated(true)
                    .activatedBy(userUuid)
                    .activatedAt(now)
                    .build();
            dataSourceActivationMapper.insert(entity);
        } else {
            entity.setActivated(true);
            entity.setActivatedBy(userUuid);
            entity.setActivatedAt(now);
            dataSourceActivationMapper.updateById(entity);
        }
        log.info("Data source '{}' activated for store {}", src, storeId);
        return DataSourceActivationVo.builder()
                .storeId(storeId)
                .source(src)
                .activated(true)
                .activatedAt(now.format(ACTIVATION_TS))
                .message("数据源已激活，相关洞察界面已解锁。")
                .build();
    }

    private boolean isActivated(String storeId, String source) {
        UUID storeUuid = parseUuidOrNull(storeId);
        if (storeUuid == null) {
            return false;
        }
        DataSourceActivationEntity entity = findActivation(storeUuid, source);
        return entity != null && Boolean.TRUE.equals(entity.getActivated());
    }

    private DataSourceActivationEntity findActivation(UUID storeUuid, String source) {
        LambdaQueryWrapper<DataSourceActivationEntity> w = new LambdaQueryWrapper<>();
        w.eq(DataSourceActivationEntity::getStoreId, storeUuid)
                .eq(DataSourceActivationEntity::getSource, source)
                .last("LIMIT 1");
        return dataSourceActivationMapper.selectOne(w);
    }

    private UUID requireStore(String storeId) {
        UUID storeUuid = parseUuidOrNull(storeId);
        if (storeUuid == null) {
            throw new BusinessException("INVALID_STORE", "A valid storeId is required");
        }
        // Reuse access scoping so a user can only activate sources for an
        // accessible store.
        List<StoreEntity> stores = accessibleStores(SecurityUtils.getCurrentUser());
        boolean allowed = stores.stream().anyMatch(s -> storeUuid.equals(s.getId()));
        if (!allowed) {
            throw new BusinessException("STORE_FORBIDDEN", "Store is not accessible: " + storeId);
        }
        return storeUuid;
    }

    private String normalizeSource(String source) {
        String src = StringUtils.hasText(source) ? source.trim().toLowerCase() : SOURCE_BRAND_ANALYTICS;
        if (!VALID_SOURCES.contains(src)) {
            throw new BusinessException("INVALID_SOURCE", "Unsupported data source: " + source);
        }
        return src;
    }

    private static UUID parseUuidOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // =====================================================================
    // Aggregation helpers
    // =====================================================================

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
        if (cacheEnabled) {
            try {
                Object raw = redisTemplate.opsForValue().get(key);
                if (raw instanceof String json) {
                    return objectMapper.readValue(json, type);
                }
            } catch (Exception ex) {
                log.warn("Insights cache read failed for {}: {}", key, ex.getMessage());
            }
        }
        T value = compute.get();
        if (cacheEnabled) {
            try {
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value),
                        Duration.ofSeconds(Math.max(cacheTtlSeconds, 1)));
            } catch (Exception ex) {
                log.warn("Insights cache write failed for {}: {}", key, ex.getMessage());
            }
        }
        return value;
    }

    /**
     * Builds a cache key fragment from a resolved scope. Store ids are sorted so
     * the key is stable regardless of store ordering, and the fragment includes
     * the target currency and date window that determine the surface's contents.
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

    private final class Scope {
        final List<StoreEntity> stores;
        final List<UUID> storeIds;
        final String targetCurrency;
        final LocalDate start;
        final LocalDate end;

        Scope(List<StoreEntity> stores, String targetCurrency, LocalDate start, LocalDate end) {
            this.stores = stores;
            this.storeIds = stores.stream().map(StoreEntity::getId).collect(Collectors.toList());
            this.targetCurrency = targetCurrency;
            this.start = start;
            this.end = end;
        }
    }

    private Scope resolveScope(String storeId, LocalDate start, LocalDate end) {
        LocalDate effEnd = end != null ? end : LocalDate.now();
        LocalDate effStart = start != null ? start : effEnd.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (effStart.isAfter(effEnd)) {
            LocalDate tmp = effStart;
            effStart = effEnd;
            effEnd = tmp;
        }

        CurrentUser user = SecurityUtils.getCurrentUser();
        Map<UUID, String> currencyByMarketplace = marketplaceReferenceService.currencyByMarketplaceId();

        List<StoreEntity> stores = accessibleStores(user).stream()
                .filter(s -> !StringUtils.hasText(storeId)
                        || (s.getId() != null && s.getId().toString().equalsIgnoreCase(storeId.trim())))
                .collect(Collectors.toList());

        String target;
        if (stores.size() == 1) {
            String c = currencyByMarketplace.get(stores.get(0).getMarketplaceId());
            target = StringUtils.hasText(c) ? c : reportingCurrency;
        } else {
            target = reportingCurrency;
        }

        return new Scope(stores, target, effStart, effEnd);
    }

    private List<StoreEntity> accessibleStores(CurrentUser user) {
        QueryWrapper<StoreEntity> wrapper = new QueryWrapper<>();
        dataScopeService.applyScope(
                wrapper,
                ScopeTarget.builder().storeIdColumn("id").ownerIdColumn("created_by").build(),
                user);
        return safe(storeMapper.selectList(wrapper));
    }

    // =====================================================================
    // small utilities
    // =====================================================================

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
