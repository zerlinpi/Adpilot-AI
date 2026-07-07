package com.adpilot.modules.report.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.order.entity.OrderEntity;
import com.adpilot.modules.order.mapper.OrderMapper;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.report.entity.ReportEntity;
import com.adpilot.modules.report.mapper.ReportMapper;
import com.adpilot.modules.report.service.ReportService;
import com.adpilot.modules.store.service.StoreService;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;
    private final OrderMapper orderMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final ProductMapper productMapper;
    private final DataScopeService dataScopeService;
    private final StoreService storeService;

    /** Store-only scope target for reports (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<ReportEntity> listReports(int page, int pageSize) {
        Page<ReportEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<ReportEntity> wrapper = new QueryWrapper<>();
        // Store-scope the listing so a caller only sees reports for stores within
        // their effective data scope (empty scope short-circuits to no rows).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<ReportEntity> result = reportMapper.selectPage(pageParam, wrapper);
        return PageResponse.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public ReportEntity generateReport(Map<String, String> params, String userId) {
        String type = params.getOrDefault("type", "custom");
        String storeId = params.get("storeId");
        String title = params.get("title");
        String periodStartStr = params.get("periodStart");
        String periodEndStr = params.get("periodEnd");

        // Validate required fields
        if (title == null || title.isBlank()) {
            throw new BusinessException("VALIDATION_ERROR", "Report title is required");
        }
        if (periodStartStr == null || periodEndStr == null) {
            throw new BusinessException("VALIDATION_ERROR", "Period start and end dates are required");
        }

        LocalDate periodStart;
        LocalDate periodEnd;
        try {
            periodStart = LocalDate.parse(periodStartStr);
            periodEnd = LocalDate.parse(periodEndStr);
        } catch (Exception e) {
            throw new BusinessException("VALIDATION_ERROR", "Invalid date format. Use yyyy-MM-dd");
        }

        UUID storeUuid = parseStoreId(storeId);

        // Cross-tenant guard: before aggregating any store's data, confirm the
        // caller-supplied storeId is within their data scope (getStoreById throws
        // STORE_NOT_FOUND/STORE_FORBIDDEN otherwise). A report with no storeId would
        // aggregate across every tenant, so it is restricted to store admins.
        CurrentUser user = scopeUser();
        if (user != null) {
            if (storeUuid != null) {
                storeService.getStoreById(storeUuid.toString());
            } else if (!SecurityUtils.isStoreAdmin()) {
                throw new BusinessException(403, "REPORT_STORE_REQUIRED",
                        "A store within your data scope is required to generate a report");
            }
        }

        LocalDateTime startAt = periodStart.atStartOfDay();
        LocalDateTime endBefore = periodEnd.plusDays(1).atStartOfDay();

        LambdaQueryWrapper<OrderEntity> orderWrapper = new LambdaQueryWrapper<>();
        if (storeUuid != null) {
            orderWrapper.eq(OrderEntity::getStoreId, storeUuid);
        }
        orderWrapper.ge(OrderEntity::getPurchaseDate, startAt)
                .lt(OrderEntity::getPurchaseDate, endBefore);
        List<OrderEntity> orders = orderMapper.selectList(orderWrapper);

        LambdaQueryWrapper<PerformanceDailyEntity> adsWrapper = new LambdaQueryWrapper<>();
        if (storeUuid != null) {
            adsWrapper.eq(PerformanceDailyEntity::getStoreId, storeUuid);
        }
        adsWrapper.ge(PerformanceDailyEntity::getDate, periodStart)
                .le(PerformanceDailyEntity::getDate, periodEnd);
        List<PerformanceDailyEntity> performanceRows = performanceDailyMapper.selectList(adsWrapper);

        LambdaQueryWrapper<ProductEntity> productWrapper = new LambdaQueryWrapper<>();
        if (storeUuid != null) {
            productWrapper.eq(ProductEntity::getStoreId, storeUuid);
        }
        List<ProductEntity> products = productMapper.selectList(productWrapper);

        Map<String, Object> salesSummary = buildSalesSummary(orders);
        Map<String, Object> advertisingSummary = buildAdvertisingSummary(performanceRows);
        Map<String, Object> catalogSummary = buildCatalogSummary(products);

        Map<String, Object> reportData = new LinkedHashMap<>();
        reportData.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        reportData.put("type", type);
        reportData.put("periodStart", periodStartStr);
        reportData.put("periodEnd", periodEndStr);
        if (storeId != null) {
            reportData.put("storeId", storeId);
        }
        reportData.put("sales", salesSummary);
        reportData.put("advertising", advertisingSummary);
        reportData.put("catalog", catalogSummary);

        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(reportData);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize report data: {}", e.getMessage());
            dataJson = "{}";
        }

        ReportEntity entity = ReportEntity.builder()
                .storeId(storeId != null ? UUID.fromString(storeId) : null)
                .type(type)
                .title(title)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .data(dataJson)
                .summary(buildSummaryText(salesSummary, advertisingSummary, catalogSummary, periodStartStr, periodEndStr))
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .build();

        reportMapper.insert(entity);
        log.info("Report generated: id={}, type={}, title={}", entity.getId(), type, title);
        return entity;
    }

    private UUID parseStoreId(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(storeId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("VALIDATION_ERROR", "Invalid storeId");
        }
    }

    private Map<String, Object> buildSalesSummary(List<OrderEntity> orders) {
        BigDecimal revenue = BigDecimal.ZERO;
        int units = 0;
        Map<String, ProductRollup> bySku = new LinkedHashMap<>();
        for (OrderEntity order : orders) {
            int quantity = order.getQuantityOrdered() != null ? order.getQuantityOrdered() : 0;
            BigDecimal lineRevenue = money(order.getItemPrice())
                    .add(money(order.getShippingPrice()))
                    .subtract(money(order.getItemPromotionDiscount()))
                    .subtract(money(order.getShipPromotionDiscount()));
            revenue = revenue.add(lineRevenue);
            units += quantity;

            String sku = order.getSku() != null && !order.getSku().isBlank() ? order.getSku() : "unknown";
            ProductRollup rollup = bySku.computeIfAbsent(sku, key -> new ProductRollup(key, order.getProductName()));
            rollup.units += quantity;
            rollup.revenue = rollup.revenue.add(lineRevenue);
        }

        List<Map<String, Object>> topProducts = bySku.values().stream()
                .sorted(Comparator.comparing((ProductRollup item) -> item.revenue).reversed())
                .limit(5)
                .map(ProductRollup::toMap)
                .collect(Collectors.toCollection(ArrayList::new));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderRows", orders.size());
        result.put("unitsOrdered", units);
        result.put("revenue", revenue);
        result.put("topProducts", topProducts);
        return result;
    }

    private Map<String, Object> buildAdvertisingSummary(List<PerformanceDailyEntity> rows) {
        long impressions = 0L;
        int clicks = 0;
        int adOrders = 0;
        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal adSales = BigDecimal.ZERO;
        for (PerformanceDailyEntity row : rows) {
            impressions += row.getImpressions() != null ? row.getImpressions() : 0L;
            clicks += row.getClicks() != null ? row.getClicks() : 0;
            adOrders += row.getOrders() != null ? row.getOrders() : 0;
            spend = spend.add(money(row.getSpend()));
            adSales = adSales.add(money(row.getSales()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dailyRows", rows.size());
        result.put("impressions", impressions);
        result.put("clicks", clicks);
        result.put("orders", adOrders);
        result.put("spend", spend);
        result.put("sales", adSales);
        result.put("acos", adSales.signum() > 0 ? spend.divide(adSales, 4, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
        result.put("roas", spend.signum() > 0 ? adSales.divide(spend, 4, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
        return result;
    }

    private Map<String, Object> buildCatalogSummary(List<ProductEntity> products) {
        int activeProducts = 0;
        int totalInventory = 0;
        for (ProductEntity product : products) {
            if ("active".equalsIgnoreCase(product.getStatus())) {
                activeProducts++;
            }
            totalInventory += product.getInventory() != null ? product.getInventory() : 0;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("products", products.size());
        result.put("activeProducts", activeProducts);
        result.put("totalInventory", totalInventory);
        return result;
    }

    private BigDecimal money(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String buildSummaryText(Map<String, Object> sales, Map<String, Object> ads, Map<String, Object> catalog,
                                    String periodStart, String periodEnd) {
        return "Report generated from database rows for " + periodStart + " to " + periodEnd
                + ": orders=" + sales.get("orderRows")
                + ", revenue=" + sales.get("revenue")
                + ", adSpend=" + ads.get("spend")
                + ", products=" + catalog.get("products");
    }

    private static final class ProductRollup {
        private final String sku;
        private final String name;
        private int units;
        private BigDecimal revenue = BigDecimal.ZERO;

        private ProductRollup(String sku, String name) {
            this.sku = sku;
            this.name = name;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sku", sku);
            map.put("name", name);
            map.put("units", units);
            map.put("revenue", revenue);
            return map;
        }
    }
}
