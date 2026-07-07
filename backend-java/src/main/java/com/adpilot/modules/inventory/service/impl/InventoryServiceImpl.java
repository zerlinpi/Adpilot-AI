package com.adpilot.modules.inventory.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.inventory.dto.ReplenishmentPlanUpdateRequest;
import com.adpilot.modules.inventory.entity.InventoryForecastEntity;
import com.adpilot.modules.inventory.entity.InventorySnapshotEntity;
import com.adpilot.modules.inventory.entity.ReplenishmentPlanEntity;
import com.adpilot.modules.inventory.mapper.InventoryForecastMapper;
import com.adpilot.modules.inventory.mapper.InventorySnapshotMapper;
import com.adpilot.modules.inventory.mapper.ReplenishmentPlanMapper;
import com.adpilot.modules.inventory.service.InventoryService;
import com.adpilot.modules.inventory.vo.InventoryHealthVo;
import com.adpilot.modules.inventory.vo.InventoryItemVo;
import com.adpilot.modules.inventory.vo.ReplenishmentPlanVo;
import com.adpilot.modules.order.entity.OrderEntity;
import com.adpilot.modules.order.mapper.OrderMapper;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.support.StoreScopeRef;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventorySnapshotMapper snapshotMapper;
    private final InventoryForecastMapper forecastMapper;
    private final ReplenishmentPlanMapper replenishmentMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final StoreMapper storeMapper;
    private final MarketplaceReferenceService marketplaceReferenceService;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int LOW_STOCK_DAYS = 14;
    private static final int OVERSTOCK_DAYS = 120;
    private static final int LEAD_TIME_DAYS = 14;

    private final DataScopeService dataScopeService;

    /** Store + product + owner scope target for replenishment plans (Req 7.1.5). */
    private static final ScopeTarget PLAN_SCOPE = ScopeTarget.builder()
            .storeIdColumn("store_id")
            .productIdColumn("product_id")
            .ownerIdColumn("created_by")
            .build();

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    @Transactional
    public InventoryHealthVo getInventoryHealth(String storeId) {
        UUID storeUuid = UUID.fromString(storeId);
        assertStoreReadable(storeUuid);
        // "Today" is the store's marketplace civil day, not the server JVM zone,
        // since snapshots/forecasts are partitioned by marketplace-local date.
        LocalDate today = LocalDate.now(zoneForStore(storeUuid));
        refreshForecastsFromOrders(storeUuid, today);

        // Get latest snapshots for each product
        LambdaQueryWrapper<InventorySnapshotEntity> snapshotWrapper = new LambdaQueryWrapper<>();
        snapshotWrapper.eq(InventorySnapshotEntity::getStoreId, storeUuid)
                .le(InventorySnapshotEntity::getSnapshotDate, today)
                .orderByDesc(InventorySnapshotEntity::getSnapshotDate);
        List<InventorySnapshotEntity> allSnapshots = snapshotMapper.selectList(snapshotWrapper);

        // Keep only the latest snapshot per product
        Map<UUID, InventorySnapshotEntity> latestSnapshots = new LinkedHashMap<>();
        for (InventorySnapshotEntity snap : allSnapshots) {
            latestSnapshots.putIfAbsent(snap.getProductId(), snap);
        }

        // Get forecasts
        LambdaQueryWrapper<InventoryForecastEntity> forecastWrapper = new LambdaQueryWrapper<>();
        forecastWrapper.eq(InventoryForecastEntity::getStoreId, storeUuid)
                .le(InventoryForecastEntity::getForecastDate, today)
                .orderByDesc(InventoryForecastEntity::getForecastDate)
                .orderByDesc(InventoryForecastEntity::getCreatedAt);
        List<InventoryForecastEntity> allForecasts = forecastMapper.selectList(forecastWrapper);

        Map<UUID, InventoryForecastEntity> latestForecasts = new LinkedHashMap<>();
        for (InventoryForecastEntity fc : allForecasts) {
            latestForecasts.putIfAbsent(fc.getProductId(), fc);
        }

        // Get products for name/SKU lookup
        Set<UUID> productIds = latestSnapshots.keySet();
        Map<UUID, ProductEntity> productMap = new HashMap<>();
        if (!productIds.isEmpty()) {
            LambdaQueryWrapper<ProductEntity> productWrapper = new LambdaQueryWrapper<>();
            productWrapper.in(ProductEntity::getId, productIds);
            productMapper.selectList(productWrapper).forEach(p -> productMap.put(p.getId(), p));
        }

        // Build inventory items
        List<InventoryItemVo> stockoutRisks = new ArrayList<>();
        List<InventoryItemVo> overstockRisks = new ArrayList<>();
        List<InventoryItemVo> notSafeToScale = new ArrayList<>();
        List<InventoryItemVo> clearanceCandidates = new ArrayList<>();

        BigDecimal totalValue = BigDecimal.ZERO;
        int totalDaysOfSupply = 0;
        int daysCount = 0;

        for (Map.Entry<UUID, InventorySnapshotEntity> entry : latestSnapshots.entrySet()) {
            UUID productId = entry.getKey();
            InventorySnapshotEntity snapshot = entry.getValue();
            InventoryForecastEntity forecast = latestForecasts.get(productId);
            ProductEntity product = productMap.get(productId);

            int inventory = snapshot.getTotalInventory() != null ? snapshot.getTotalInventory() : 0;
            BigDecimal velocity = forecast != null && forecast.getDailySalesVelocity() != null
                    ? forecast.getDailySalesVelocity() : BigDecimal.ZERO;
            int dailyVelocity = velocity.intValue();
            int daysOfSupply = dailyVelocity > 0 ? inventory / dailyVelocity : (inventory > 0 ? 999 : 0);
            String stockoutDate = forecast != null && forecast.getStockoutDate() != null
                    ? forecast.getStockoutDate().toString() : null;
            String risk = forecast != null ? forecast.getStockoutRisk() : "low";
            double invValue = snapshot.getInventoryValue() != null
                    ? snapshot.getInventoryValue().doubleValue() : 0;

            totalValue = totalValue.add(snapshot.getInventoryValue() != null
                    ? snapshot.getInventoryValue() : BigDecimal.ZERO);
            if (daysOfSupply > 0 && daysOfSupply < 999) {
                totalDaysOfSupply += daysOfSupply;
                daysCount++;
            }

            InventoryItemVo item = InventoryItemVo.builder()
                    .id(productId.toString())
                    .sku(product != null ? product.getSku() : null)
                    .productName(product != null ? product.getName() : null)
                    .inventory(inventory)
                    .dailyVelocity(dailyVelocity)
                    .daysOfSupply(daysOfSupply)
                    .stockoutDate(stockoutDate)
                    .risk(risk)
                    .inventoryValue(invValue)
                    .recommendation(buildInventoryRecommendation(daysOfSupply, risk, inventory))
                    .build();

            // Categorize
            if ("high".equalsIgnoreCase(risk) || daysOfSupply <= LOW_STOCK_DAYS) {
                stockoutRisks.add(item);
            }
            if (daysOfSupply >= OVERSTOCK_DAYS && inventory > 0) {
                overstockRisks.add(item);
            }
            if ("high".equalsIgnoreCase(risk) || "medium".equalsIgnoreCase(risk)) {
                notSafeToScale.add(item);
            }
            if (daysOfSupply >= OVERSTOCK_DAYS * 2) {
                clearanceCandidates.add(item);
            }
        }

        int avgDays = daysCount > 0 ? totalDaysOfSupply / daysCount : 0;

        return InventoryHealthVo.builder()
                .totalInventoryValue(totalValue.doubleValue())
                .lowStockCount(stockoutRisks.size())
                .overstockCount(overstockRisks.size())
                .avgDaysOfSupply(avgDays)
                .stockoutRisks(stockoutRisks)
                .overstockRisks(overstockRisks)
                .notSafeToScale(notSafeToScale)
                .clearanceCandidates(clearanceCandidates)
                .build();
    }

    @Override
    @Transactional
    public List<InventoryItemVo> getInventorySnapshots(String storeId) {
        UUID storeUuid = UUID.fromString(storeId);
        assertStoreReadable(storeUuid);
        // Marketplace-local "today" for day partitioning (not the JVM zone).
        LocalDate today = LocalDate.now(zoneForStore(storeUuid));
        refreshForecastsFromOrders(storeUuid, today);

        LambdaQueryWrapper<InventorySnapshotEntity> snapshotQuery = new LambdaQueryWrapper<>();
        snapshotQuery.eq(InventorySnapshotEntity::getStoreId, storeUuid)
                .le(InventorySnapshotEntity::getSnapshotDate, today)
                .orderByDesc(InventorySnapshotEntity::getSnapshotDate)
                .orderByDesc(InventorySnapshotEntity::getCreatedAt);
        Map<UUID, InventorySnapshotEntity> latestSnapshots = new LinkedHashMap<>();
        for (InventorySnapshotEntity snapshot : snapshotMapper.selectList(snapshotQuery)) {
            latestSnapshots.putIfAbsent(snapshot.getProductId(), snapshot);
        }
        if (latestSnapshots.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<InventoryForecastEntity> forecastQuery = new LambdaQueryWrapper<>();
        forecastQuery.eq(InventoryForecastEntity::getStoreId, storeUuid)
                .le(InventoryForecastEntity::getForecastDate, today)
                .orderByDesc(InventoryForecastEntity::getForecastDate)
                .orderByDesc(InventoryForecastEntity::getCreatedAt);
        Map<UUID, InventoryForecastEntity> latestForecasts = new LinkedHashMap<>();
        for (InventoryForecastEntity forecast : forecastMapper.selectList(forecastQuery)) {
            latestForecasts.putIfAbsent(forecast.getProductId(), forecast);
        }

        LambdaQueryWrapper<ProductEntity> productQuery = new LambdaQueryWrapper<>();
        productQuery.in(ProductEntity::getId, latestSnapshots.keySet());
        Map<UUID, ProductEntity> products = new HashMap<>();
        productMapper.selectList(productQuery).forEach(product -> products.put(product.getId(), product));

        return latestSnapshots.entrySet().stream().map(entry -> {
            UUID productId = entry.getKey();
            InventorySnapshotEntity snapshot = entry.getValue();
            InventoryForecastEntity forecast = latestForecasts.get(productId);
            ProductEntity product = products.get(productId);
            int inventory = snapshot.getTotalInventory() != null ? snapshot.getTotalInventory() : 0;
            BigDecimal velocity = forecast != null && forecast.getDailySalesVelocity() != null
                    ? forecast.getDailySalesVelocity() : BigDecimal.ZERO;
            int dailyVelocity = velocity.intValue();
            int daysOfSupply = dailyVelocity > 0 ? inventory / dailyVelocity : (inventory > 0 ? 999 : 0);
            String risk = forecast != null && forecast.getStockoutRisk() != null
                    ? forecast.getStockoutRisk() : "low";
            return InventoryItemVo.builder()
                    .id(productId.toString())
                    .sku(product != null ? product.getSku() : null)
                    .productName(product != null ? product.getName() : null)
                    .inventory(inventory)
                    .dailyVelocity(dailyVelocity)
                    .daysOfSupply(daysOfSupply)
                    .stockoutDate(forecast != null && forecast.getStockoutDate() != null
                            ? forecast.getStockoutDate().toString() : null)
                    .risk(risk)
                    .inventoryValue(snapshot.getInventoryValue() != null
                            ? snapshot.getInventoryValue().doubleValue() : 0)
                    .recommendation(buildInventoryRecommendation(daysOfSupply, risk, inventory))
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    public List<ReplenishmentPlanVo> getReplenishmentPlans(String storeId, String status) {
        QueryWrapper<ReplenishmentPlanEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq("store_id", storeId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq("status", status);
        }
        CurrentUser user = scopeUser();
        dataScopeService.applyScope(wrapper, PLAN_SCOPE, user);
        wrapper.orderByDesc("created_at");

        List<ReplenishmentPlanEntity> plans = replenishmentMapper.selectList(wrapper);
        if (plans.isEmpty()) {
            return Collections.emptyList();
        }

        // Product lookup
        Set<UUID> productIds = plans.stream()
                .map(ReplenishmentPlanEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ProductEntity> productMap = new HashMap<>();
        if (!productIds.isEmpty()) {
            LambdaQueryWrapper<ProductEntity> pw = new LambdaQueryWrapper<>();
            pw.in(ProductEntity::getId, productIds);
            productMapper.selectList(pw).forEach(p -> productMap.put(p.getId(), p));
        }

        return plans.stream()
                .map(plan -> toReplenishmentVo(plan, productMap))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ReplenishmentPlanVo generateReplenishmentPlan(String storeId, String userId) {
        List<ReplenishmentPlanVo> created = generateReplenishmentPlans(storeId, userId);
        return created.isEmpty() ? null : created.get(0);
    }

    @Override
    @Transactional
    public List<ReplenishmentPlanVo> generateReplenishmentPlans(String storeId, String userId) {
        UUID storeUuid = UUID.fromString(storeId);
        assertStoreWritable(storeUuid);
        // Marketplace-local "today" for day partitioning (not the JVM zone).
        LocalDate today = LocalDate.now(zoneForStore(storeUuid));
        refreshForecastsFromOrders(storeUuid, today);

        List<InventoryForecastEntity> riskyForecasts = latestRiskForecasts(storeUuid);
        if (riskyForecasts.isEmpty()) {
            return Collections.emptyList();
        }

        // Latest snapshot per product.
        LambdaQueryWrapper<InventorySnapshotEntity> snapshotWrapper = new LambdaQueryWrapper<>();
        snapshotWrapper.eq(InventorySnapshotEntity::getStoreId, storeUuid)
                .le(InventorySnapshotEntity::getSnapshotDate, today)
                .orderByDesc(InventorySnapshotEntity::getSnapshotDate);
        List<InventorySnapshotEntity> allSnapshots = snapshotMapper.selectList(snapshotWrapper);
        Map<UUID, InventorySnapshotEntity> latestSnapshots = new LinkedHashMap<>();
        for (InventorySnapshotEntity snap : allSnapshots) {
            latestSnapshots.putIfAbsent(snap.getProductId(), snap);
        }

        // Product lookup for SKU / cost / name.
        Set<UUID> productIds = riskyForecasts.stream()
                .map(InventoryForecastEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ProductEntity> productMap = new HashMap<>();
        if (!productIds.isEmpty()) {
            LambdaQueryWrapper<ProductEntity> pw = new LambdaQueryWrapper<>();
            pw.in(ProductEntity::getId, productIds);
            productMapper.selectList(pw).forEach(p -> productMap.put(p.getId(), p));
        }

        // Skip products that already have an active (non-cancelled/rejected) plan
        // so repeated clicks do not pile up duplicate rows.
        LambdaQueryWrapper<ReplenishmentPlanEntity> existingWrapper = new LambdaQueryWrapper<>();
        existingWrapper.eq(ReplenishmentPlanEntity::getStoreId, storeUuid)
                .notIn(ReplenishmentPlanEntity::getStatus, "cancelled", "rejected");
        Set<UUID> productsWithActivePlan = replenishmentMapper.selectList(existingWrapper).stream()
                .map(ReplenishmentPlanEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        List<ReplenishmentPlanVo> created = new ArrayList<>();
        for (InventoryForecastEntity forecast : riskyForecasts) {
            UUID productId = forecast.getProductId();
            if (productId == null || productsWithActivePlan.contains(productId)) {
                continue;
            }
            ReplenishmentPlanEntity plan = buildPlanEntity(
                    forecast, latestSnapshots.get(productId), productMap.get(productId), today, userId);
            replenishmentMapper.insert(plan);
            productsWithActivePlan.add(productId);

            Map<UUID, ProductEntity> pm = new HashMap<>();
            pm.put(productId, productMap.get(productId));
            created.add(toReplenishmentVo(plan, pm));
        }

        log.info("Generated {} replenishment plan(s) for store {}", created.size(), storeId);
        return created;
    }

    /**
     * Builds (without persisting) a replenishment plan entity for one at-risk
     * forecast, computing recommended quantity and estimated costs. Shared by the
     * single- and multi-plan generators so the calculation stays consistent.
     */
    private ReplenishmentPlanEntity buildPlanEntity(InventoryForecastEntity forecast,
                                                    InventorySnapshotEntity snapshot,
                                                    ProductEntity product,
                                                    LocalDate today,
                                                    String userId) {
        UUID productId = forecast.getProductId();
        int currentInventory = snapshot != null && snapshot.getTotalInventory() != null
                ? snapshot.getTotalInventory() : 0;
        int recommendedQty = forecast.getRecommendedReplenishmentQty() != null
                ? forecast.getRecommendedReplenishmentQty() : 0;

        // If no recommended qty, calculate: 60 days of supply - current inventory
        if (recommendedQty <= 0) {
            BigDecimal velocity = forecast.getDailySalesVelocity() != null
                    ? forecast.getDailySalesVelocity() : BigDecimal.ZERO;
            int targetDays = 60;
            recommendedQty = Math.max(0, velocity.intValue() * targetDays - currentInventory);
        }

        LocalDate expectedStockout = forecast.getStockoutDate();
        LocalDate expectedArrival = today.plusDays(LEAD_TIME_DAYS);

        BigDecimal unitCost = product != null && product.getCost() != null
                ? product.getCost() : BigDecimal.ZERO;
        BigDecimal purchaseCost = unitCost.multiply(new BigDecimal(recommendedQty));
        BigDecimal shippingCost = purchaseCost.multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP);

        String reason = String.format("库存风险等级: %s, 当前库存: %d, 日均销量: %s, 预计可用天数: %d",
                forecast.getStockoutRisk(),
                currentInventory,
                forecast.getDailySalesVelocity(),
                forecast.getDaysOfSupply());

        return ReplenishmentPlanEntity.builder()
                .storeId(forecast.getStoreId())
                .productId(productId)
                .status("draft")
                .recommendedQty(recommendedQty)
                .reason(reason)
                .expectedStockoutDate(expectedStockout)
                .expectedArrivalDate(expectedArrival)
                .purchaseCost(purchaseCost)
                .shippingCost(shippingCost)
                .createdBy(parseUuidOrNull(userId))
                .build();
    }

    @Override
    @Transactional
    public ReplenishmentPlanVo approvePlan(String id, int approvedQty, String userId) {
        UUID planUuid = UUID.fromString(id);
        ReplenishmentPlanEntity plan = replenishmentMapper.selectById(planUuid);
        if (plan == null) {
            throw new BusinessException("PLAN_NOT_FOUND", "补货计划不存在: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(plan, user);
        }
        if (!"draft".equals(plan.getStatus()) && !"pending".equals(plan.getStatus())) {
            throw new BusinessException("INVALID_STATUS", "只能审批草稿或待审批状态的补货计划，当前状态: " + plan.getStatus());
        }

        String previousStatus = plan.getStatus();
        int qty = approvedQty > 0 ? approvedQty : plan.getRecommendedQty();
        plan.setApprovedQty(qty);
        plan.setStatus("approved");
        plan.setUpdatedBy(parseUuidOrNull(userId));
        plan.setUpdatedAt(java.time.LocalDateTime.now());
        replenishmentMapper.updateById(plan);

        // Audit log
        auditLogService.createLog(
                parseUuidOrNull(userId),
                plan.getStoreId(),
                "APPROVE",
                "replenishment_plan",
                planUuid,
                Map.of("approvedQty", qty, "previousStatus", previousStatus));

        log.info("Replenishment plan approved: id={}, qty={}", id, qty);

        ProductEntity product = productMapper.selectById(plan.getProductId());
        Map<UUID, ProductEntity> pm = new HashMap<>();
        if (product != null) pm.put(plan.getProductId(), product);
        return toReplenishmentVo(plan, pm);
    }

    @Override
    @Transactional
    public ReplenishmentPlanVo updatePlan(String id, ReplenishmentPlanUpdateRequest request, String userId) {
        UUID planUuid = UUID.fromString(id);
        ReplenishmentPlanEntity plan = replenishmentMapper.selectById(planUuid);
        if (plan == null) {
            throw new BusinessException("PLAN_NOT_FOUND", "Replenishment plan not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(plan, user);
        }
        if (!"draft".equals(plan.getStatus()) && !"pending".equals(plan.getStatus())) {
            throw new BusinessException("INVALID_STATUS",
                    "Only draft or pending replenishment plans can be edited");
        }
        if (request == null) {
            throw new BusinessException("INVALID_REQUEST", "Request body is required");
        }

        if (request.getRecommendedQty() != null) {
            if (request.getRecommendedQty() < 0) {
                throw new BusinessException("INVALID_QUANTITY", "recommendedQty cannot be negative");
            }
            plan.setRecommendedQty(request.getRecommendedQty());
        }
        if (request.getApprovedQty() != null) {
            if (request.getApprovedQty() < 0) {
                throw new BusinessException("INVALID_QUANTITY", "approvedQty cannot be negative");
            }
            plan.setApprovedQty(request.getApprovedQty());
        }
        if (request.getReason() != null) {
            plan.setReason(request.getReason());
        }
        if (request.getExpectedStockoutDate() != null) {
            plan.setExpectedStockoutDate(request.getExpectedStockoutDate());
        }
        if (request.getExpectedArrivalDate() != null) {
            plan.setExpectedArrivalDate(request.getExpectedArrivalDate());
        }
        if (request.getPurchaseCost() != null) {
            if (request.getPurchaseCost().signum() < 0) {
                throw new BusinessException("INVALID_COST", "purchaseCost cannot be negative");
            }
            plan.setPurchaseCost(request.getPurchaseCost());
        }
        if (request.getShippingCost() != null) {
            if (request.getShippingCost().signum() < 0) {
                throw new BusinessException("INVALID_COST", "shippingCost cannot be negative");
            }
            plan.setShippingCost(request.getShippingCost());
        }

        plan.setUpdatedBy(parseUuidOrNull(userId));
        plan.setUpdatedAt(java.time.LocalDateTime.now());
        replenishmentMapper.updateById(plan);

        auditLogService.createLog(
                parseUuidOrNull(userId),
                plan.getStoreId(),
                "UPDATE",
                "replenishment_plan",
                planUuid,
                Map.of("status", plan.getStatus()));

        ProductEntity product = productMapper.selectById(plan.getProductId());
        Map<UUID, ProductEntity> pm = new HashMap<>();
        if (product != null) pm.put(plan.getProductId(), product);
        return toReplenishmentVo(plan, pm);
    }

    @Override
    @Transactional
    public ReplenishmentPlanVo cancelPlan(String id, String userId) {
        UUID planUuid = UUID.fromString(id);
        ReplenishmentPlanEntity plan = replenishmentMapper.selectById(planUuid);
        if (plan == null) {
            throw new BusinessException("PLAN_NOT_FOUND", "补货计划不存在: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(plan, user);
        }
        if (!"draft".equals(plan.getStatus()) && !"pending".equals(plan.getStatus())) {
            throw new BusinessException("INVALID_STATUS", "只能取消草稿或待审批状态的补货计划");
        }

        String previousStatus = plan.getStatus();
        plan.setStatus("cancelled");
        plan.setUpdatedBy(parseUuidOrNull(userId));
        plan.setUpdatedAt(java.time.LocalDateTime.now());
        replenishmentMapper.updateById(plan);

        // Audit log
        auditLogService.createLog(
                parseUuidOrNull(userId),
                plan.getStoreId(),
                "CANCEL",
                "replenishment_plan",
                planUuid,
                Map.of("previousStatus", previousStatus));

        log.info("Replenishment plan cancelled: id={}", id);

        ProductEntity product = productMapper.selectById(plan.getProductId());
        Map<UUID, ProductEntity> pm = new HashMap<>();
        if (product != null) pm.put(plan.getProductId(), product);
        return toReplenishmentVo(plan, pm);
    }

    // ---- Private helpers ----

    private void refreshForecastsFromOrders(UUID storeId, LocalDate forecastDate) {
        LambdaQueryWrapper<ProductEntity> productQuery = new LambdaQueryWrapper<>();
        productQuery.eq(ProductEntity::getStoreId, storeId);
        List<ProductEntity> products = productMapper.selectList(productQuery);
        if (products == null || products.isEmpty()) {
            return;
        }

        LambdaQueryWrapper<OrderEntity> orderQuery = new LambdaQueryWrapper<>();
        orderQuery.eq(OrderEntity::getStoreId, storeId)
                .ge(OrderEntity::getPurchaseDate, forecastDate.minusDays(30).atStartOfDay())
                .notIn(OrderEntity::getOrderStatus, "cancelled", "canceled", "refunded");
        List<OrderEntity> orders = orderMapper.selectList(orderQuery);
        Map<String, Integer> soldBySku = new HashMap<>();
        if (orders != null) {
            for (OrderEntity order : orders) {
                if (order.getSku() != null && !order.getSku().isBlank()) {
                    soldBySku.merge(order.getSku(),
                            order.getQuantityOrdered() != null ? order.getQuantityOrdered() : 0,
                            Integer::sum);
                }
            }
        }

        LambdaQueryWrapper<InventorySnapshotEntity> snapshotQuery = new LambdaQueryWrapper<>();
        snapshotQuery.eq(InventorySnapshotEntity::getStoreId, storeId)
                .le(InventorySnapshotEntity::getSnapshotDate, forecastDate)
                .orderByDesc(InventorySnapshotEntity::getSnapshotDate)
                .orderByDesc(InventorySnapshotEntity::getCreatedAt);
        Map<UUID, InventorySnapshotEntity> latestSnapshots = new LinkedHashMap<>();
        List<InventorySnapshotEntity> snapshots = snapshotMapper.selectList(snapshotQuery);
        if (snapshots != null) {
            for (InventorySnapshotEntity snapshot : snapshots) {
                latestSnapshots.putIfAbsent(snapshot.getProductId(), snapshot);
            }
        }

        LambdaQueryWrapper<InventoryForecastEntity> existingQuery = new LambdaQueryWrapper<>();
        existingQuery.eq(InventoryForecastEntity::getStoreId, storeId)
                .eq(InventoryForecastEntity::getForecastDate, forecastDate);
        Map<UUID, InventoryForecastEntity> existingByProduct = new HashMap<>();
        List<InventoryForecastEntity> existing = forecastMapper.selectList(existingQuery);
        if (existing != null) {
            existing.forEach(item -> existingByProduct.put(item.getProductId(), item));
        }

        for (ProductEntity product : products) {
            int sold = soldBySku.getOrDefault(product.getSku(), 0);
            BigDecimal velocity = BigDecimal.valueOf(sold)
                    .divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
            InventorySnapshotEntity snapshot = latestSnapshots.get(product.getId());
            int inventory = snapshot != null && snapshot.getTotalInventory() != null
                    ? snapshot.getTotalInventory()
                    : (product.getInventory() != null ? product.getInventory() : 0);
            int daysOfSupply = velocity.signum() > 0
                    ? BigDecimal.valueOf(inventory).divide(velocity, 0, RoundingMode.FLOOR).intValue()
                    : (inventory > 0 ? 999 : 0);
            String stockoutRisk = daysOfSupply <= 7 && velocity.signum() > 0
                    ? "high"
                    : (daysOfSupply <= LOW_STOCK_DAYS && velocity.signum() > 0 ? "medium" : "low");
            String overstockRisk = daysOfSupply >= OVERSTOCK_DAYS ? "high" : "low";
            int recommendedQty = Math.max(0,
                    velocity.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.CEILING).intValue() - inventory);
            LocalDate stockoutDate = velocity.signum() > 0
                    ? forecastDate.plusDays(Math.max(0,
                            BigDecimal.valueOf(inventory).divide(velocity, 0, RoundingMode.CEILING).longValue()))
                    : null;

            InventoryForecastEntity existingForecast = existingByProduct.get(product.getId());
            boolean create = existingForecast == null;
            InventoryForecastEntity forecast = existingForecast != null
                    ? existingForecast : new InventoryForecastEntity();
            if (create) {
                forecast.setId(UUID.randomUUID());
                forecast.setStoreId(storeId);
                forecast.setProductId(product.getId());
                forecast.setForecastDate(forecastDate);
                forecast.setCreatedAt(LocalDateTime.now());
            }
            forecast.setDailySalesVelocity(velocity);
            forecast.setOrganicSalesVelocity(velocity);
            forecast.setAdDrivenSalesVelocity(BigDecimal.ZERO);
            forecast.setDaysOfSupply(daysOfSupply);
            forecast.setStockoutDate(stockoutDate);
            forecast.setStockoutRisk(stockoutRisk);
            forecast.setOverstockRisk(overstockRisk);
            forecast.setRecommendedReplenishmentQty(recommendedQty);
            forecast.setConfidenceScore(Math.min(95, 50 + sold));
            forecast.setUpdatedAt(LocalDateTime.now());
            if (create) {
                forecastMapper.insert(forecast);
            } else {
                forecastMapper.updateById(forecast);
            }
        }
    }

    private List<InventoryForecastEntity> latestRiskForecasts(UUID storeId) {
        LambdaQueryWrapper<InventoryForecastEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryForecastEntity::getStoreId, storeId)
                .le(InventoryForecastEntity::getForecastDate, LocalDate.now(zoneForStore(storeId)))
                .orderByDesc(InventoryForecastEntity::getForecastDate)
                .orderByDesc(InventoryForecastEntity::getCreatedAt);

        Map<UUID, InventoryForecastEntity> latestByProduct = new LinkedHashMap<>();
        for (InventoryForecastEntity forecast : forecastMapper.selectList(wrapper)) {
            latestByProduct.putIfAbsent(forecast.getProductId(), forecast);
        }
        return latestByProduct.values().stream()
                .filter(forecast -> "high".equalsIgnoreCase(forecast.getStockoutRisk())
                        || "medium".equalsIgnoreCase(forecast.getStockoutRisk()))
                .collect(Collectors.toList());
    }

    /**
     * Resolve the civil-day timezone for a store via its marketplace. Falls back to
     * UTC (never the JVM default) when the store is unknown or has no marketplace.
     */
    private ZoneId zoneForStore(UUID storeId) {
        UUID marketplaceId = null;
        if (storeId != null) {
            StoreEntity store = storeMapper.selectById(storeId);
            if (store != null) {
                marketplaceId = store.getMarketplaceId();
            }
        }
        return marketplaceReferenceService.timezoneForMarketplace(marketplaceId);
    }

    private void assertStoreReadable(UUID storeId) {
        dataScopeService.assertCanRead(StoreScopeRef.of(storeId), scopeUser());
    }

    private void assertStoreWritable(UUID storeId) {
        dataScopeService.assertCanWrite(StoreScopeRef.of(storeId), scopeUser());
    }

    /** Safely parse a UUID string, returning null for null/blank or non-UUID values
     *  (e.g. the literal "system" used for background/service actions). */
    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private ReplenishmentPlanVo toReplenishmentVo(ReplenishmentPlanEntity plan, Map<UUID, ProductEntity> productMap) {
        ProductEntity product = productMap.get(plan.getProductId());
        return ReplenishmentPlanVo.builder()
                .id(plan.getId().toString())
                .sku(product != null ? product.getSku() : null)
                .productName(product != null ? product.getName() : null)
                .status(plan.getStatus())
                .recommendedQty(plan.getRecommendedQty() != null ? plan.getRecommendedQty() : 0)
                .approvedQty(plan.getApprovedQty())
                .reason(plan.getReason())
                .expectedStockoutDate(plan.getExpectedStockoutDate() != null
                        ? plan.getExpectedStockoutDate().toString() : null)
                .expectedArrivalDate(plan.getExpectedArrivalDate() != null
                        ? plan.getExpectedArrivalDate().toString() : null)
                .purchaseCost(plan.getPurchaseCost() != null ? plan.getPurchaseCost().doubleValue() : null)
                .shippingCost(plan.getShippingCost() != null ? plan.getShippingCost().doubleValue() : null)
                .createdAt(plan.getCreatedAt() != null ? plan.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    private String buildInventoryRecommendation(int daysOfSupply, String risk, int inventory) {
        if (inventory <= 0) {
            return "已断货，需立即补货。";
        }
        if ("high".equalsIgnoreCase(risk)) {
            return String.format("库存仅剩 %d 天，建议立即发起补货。", daysOfSupply);
        }
        if ("medium".equalsIgnoreCase(risk)) {
            return String.format("库存可支撑 %d 天，建议提前安排补货。", daysOfSupply);
        }
        if (daysOfSupply >= OVERSTOCK_DAYS) {
            return String.format("库存过多（%d 天），建议减少采购或促销清仓。", daysOfSupply);
        }
        return String.format("库存健康，可支撑 %d 天。", daysOfSupply);
    }
}
