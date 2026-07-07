package com.adpilot.modules.dashboard.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.audit.entity.AuditLogEntity;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.adpilot.modules.dashboard.service.DashboardService;
import com.adpilot.modules.dashboard.vo.DashboardSummaryVo;
import com.adpilot.modules.dashboard.vo.RecentAuditLogVo;
import com.adpilot.modules.dashboard.vo.TopProductVo;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.task.entity.OperationTaskEntity;
import com.adpilot.modules.task.mapper.OperationTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link DashboardService}. Every product/store/task figure is scoped to
 * the caller's accessible stores through the shared {@link DataScopeService}
 * query layer (Req 2.2 / 7.1), mirroring {@code AggregationServiceImpl} and
 * {@code AiDashboardServiceImpl}. This prevents cross-organization data leakage
 * and avoids loading the whole product table: inventory totals are computed with
 * a single scoped {@code SUM/COUNT} aggregate rather than {@code selectList(null)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final ProductMapper productMapper;
    private final StoreMapper storeMapper;
    private final OperationTaskMapper taskMapper;
    private final AuditLogMapper auditLogMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public DashboardSummaryVo getSummary() {
        // Resolve the caller's accessible stores via the shared data-scope layer,
        // then scope every product/store/task figure to those stores so no other
        // organization's data can bleed into the summary (Req 2.2 / 7.1).
        CurrentUser user = SecurityUtils.getCurrentUser();
        List<UUID> storeIds = accessibleStoreIds(user);

        long totalProducts = 0L;
        long activeProducts = 0L;
        long totalInventory = 0L;
        double inventoryValue = 0.0;
        long pendingTasks = 0L;
        List<TopProductVo> topProducts = new ArrayList<>();

        // An empty scope must add NO restriction bypass: MyBatis-Plus treats an
        // empty IN(...) as "no condition" (matching everything), so guard on it
        // and report zeros when the caller can see no stores.
        if (!storeIds.isEmpty()) {
            // 1. Count total products in scope.
            totalProducts = nz(productMapper.selectCount(scopedProducts(storeIds)));

            // 2. Count active products in scope.
            LambdaQueryWrapper<ProductEntity> activeWrapper = new LambdaQueryWrapper<>();
            activeWrapper.in(ProductEntity::getStoreId, storeIds)
                    .eq(ProductEntity::getStatus, "active");
            activeProducts = nz(productMapper.selectCount(activeWrapper));

            // 3. Sum inventory and inventory value with a single scoped aggregate,
            //    instead of loading every product row into memory.
            QueryWrapper<ProductEntity> aggWrapper = new QueryWrapper<>();
            aggWrapper.select(
                    "COALESCE(SUM(inventory), 0) AS total_inventory",
                    "COALESCE(SUM(price * inventory), 0) AS inventory_value")
                    .in("store_id", storeIds);
            List<Map<String, Object>> aggRows = productMapper.selectMaps(aggWrapper);
            if (aggRows != null && !aggRows.isEmpty() && aggRows.get(0) != null) {
                Map<String, Object> row = aggRows.get(0);
                Object inv = row.get("total_inventory");
                Object val = row.get("inventory_value");
                totalInventory = inv instanceof Number n ? n.longValue() : 0L;
                inventoryValue = val instanceof Number n ? n.doubleValue() : 0.0;
            }

            // 4. Count pending tasks (open or in_progress) for the accessible
            //    stores. Tasks carry a store_id, so we scope them consistently
            //    with products/stores. (OperationTaskServiceImpl's list path does
            //    not yet apply the data-scope layer; scoping here keeps the
            //    dashboard from leaking other orgs' task counts regardless.)
            LambdaQueryWrapper<OperationTaskEntity> taskWrapper = new LambdaQueryWrapper<>();
            taskWrapper.in(OperationTaskEntity::getStoreId, storeIds)
                    .in(OperationTaskEntity::getStatus, "open", "in_progress");
            pendingTasks = nz(taskMapper.selectCount(taskWrapper));

            // 5. Top 5 products by inventory value, scoped and limited in SQL so we
            //    never materialize the full (scoped) product set.
            topProducts = getTopProducts(storeIds);
        }

        // 6. Store count is the number of stores in the caller's scope.
        int storeCount = storeIds.size();

        // 7. Recent audit logs (unchanged): audit-log scoping is a separate
        //    concern and outside this fix's product/store/task surface.
        List<RecentAuditLogVo> recentAuditLogs = getRecentAuditLogs();

        return DashboardSummaryVo.builder()
                .totalProducts((int) totalProducts)
                .activeProducts((int) activeProducts)
                .totalInventory(totalInventory)
                .inventoryValue(inventoryValue)
                .storeCount(storeCount)
                .pendingTasks((int) pendingTasks)
                .topProducts(topProducts)
                .recentAuditLogs(recentAuditLogs)
                .build();
    }

    /**
     * Resolve the ids of the stores the caller may access by applying the shared
     * data-scope filter to the {@code stores} table, mirroring
     * {@code AggregationServiceImpl.accessibleStores}.
     */
    private List<UUID> accessibleStoreIds(CurrentUser user) {
        QueryWrapper<StoreEntity> wrapper = new QueryWrapper<>();
        dataScopeService.applyScope(
                wrapper,
                ScopeTarget.builder().storeIdColumn("id").ownerIdColumn("created_by").build(),
                user);
        List<StoreEntity> stores = storeMapper.selectList(wrapper);
        if (stores == null) {
            return List.of();
        }
        return stores.stream()
                .map(StoreEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    private LambdaQueryWrapper<ProductEntity> scopedProducts(List<UUID> storeIds) {
        LambdaQueryWrapper<ProductEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(ProductEntity::getStoreId, storeIds);
        return wrapper;
    }

    private List<TopProductVo> getTopProducts(List<UUID> storeIds) {
        QueryWrapper<ProductEntity> wrapper = new QueryWrapper<>();
        wrapper.in("store_id", storeIds)
                .orderByDesc("price * inventory")
                .last("LIMIT 5");
        List<ProductEntity> top = productMapper.selectList(wrapper);
        if (top == null) {
            return new ArrayList<>();
        }
        return top.stream()
                .map(p -> TopProductVo.builder()
                        .id(p.getId().toString())
                        .name(p.getName())
                        .sku(p.getSku())
                        .price(p.getPrice() != null ? p.getPrice().doubleValue() : 0.0)
                        .inventory(p.getInventory() != null ? p.getInventory() : 0)
                        .status(p.getStatus())
                        .build())
                .collect(Collectors.toList());
    }

    private List<RecentAuditLogVo> getRecentAuditLogs() {
        Page<AuditLogEntity> page = new Page<>(1, 5);
        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(AuditLogEntity::getCreatedAt);

        Page<AuditLogEntity> result = auditLogMapper.selectPage(page, wrapper);
        if (result == null || result.getRecords() == null) {
            return new ArrayList<>();
        }

        return result.getRecords().stream()
                .map(log -> RecentAuditLogVo.builder()
                        .id(log.getId().toString())
                        .action(log.getAction())
                        .entityType(log.getEntityType())
                        .userName(log.getUserId() != null ? log.getUserId().toString() : "system")
                        .createdAt(log.getCreatedAt() != null ? log.getCreatedAt().format(FORMATTER) : null)
                        .build())
                .collect(Collectors.toList());
    }

    private static long nz(Long value) {
        return value != null ? value : 0L;
    }
}
