package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.RankMonitorConverter;
import com.adpilot.modules.advertising.dto.RankMonitorCreateRequest;
import com.adpilot.modules.advertising.entity.RankMonitorSnapshotEntity;
import com.adpilot.modules.advertising.entity.RankMonitorTaskEntity;
import com.adpilot.modules.advertising.mapper.RankMonitorSnapshotMapper;
import com.adpilot.modules.advertising.mapper.RankMonitorTaskMapper;
import com.adpilot.modules.advertising.service.RankMonitorService;
import com.adpilot.modules.advertising.support.RankQuota;
import com.adpilot.modules.advertising.vo.RankMonitorQuotaVo;
import com.adpilot.modules.advertising.vo.RankMonitorTaskVo;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rank Monitoring implementation (Req 28). The consumed quota is the count of
 * {@code active} rank-monitor tasks for the store; the total quota is a
 * configurable per-store allowance. Admission is delegated to the pure
 * {@link RankQuota} helper so the boundary behaviour is identical to the one
 * the property test pins down (Property 9).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankMonitorServiceImpl implements RankMonitorService {

    private final RankMonitorTaskMapper taskMapper;
    private final RankMonitorSnapshotMapper snapshotMapper;
    private final StoreMapper storeMapper;
    private final ProductMapper productMapper;
    private final DataScopeService dataScopeService;

    /** Total rank-monitoring quota per store (e.g. 400 keywords). Configurable. */
    @Value("${adpilot.rank-monitor.quota-total:400}")
    private int quotaTotal;

    /** Store scope target — rank_monitor_tasks carries no owner column. */
    private static final ScopeTarget RANK_SCOPE = ScopeTarget.store("store_id");

    /** A task in this status consumes a monitoring slot. */
    private static final String ACTIVE = "active";

    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    @Transactional
    public RankMonitorTaskVo createTask(RankMonitorCreateRequest request) {
        UUID storeUuid = parseUuid(request.getStoreId(), "storeId");

        // Quota check (Req 28.4): permit iff consumed < total. At consumed == total
        // any further add is rejected. The decision uses the pure RankQuota rule.
        int consumed = countActiveTasks(storeUuid);
        if (!RankQuota.permitsAdd(consumed, quotaTotal)) {
            throw new BusinessException("RANK_QUOTA_EXHAUSTED",
                    "Rank monitoring quota exhausted (" + consumed + "/" + quotaTotal + ")");
        }

        UUID productUuid = (request.getProductId() != null && !request.getProductId().isBlank())
                ? parseUuid(request.getProductId(), "productId") : null;

        // Keep products store-scoped: a task may only target a product that
        // belongs to the same store, so different stores stay managed distinctly.
        String productName = null;
        if (productUuid != null) {
            ProductEntity product = productMapper.selectById(productUuid);
            if (product == null) {
                throw new BusinessException("INVALID_PRODUCT", "Product not found: " + productUuid);
            }
            if (product.getStoreId() == null || !product.getStoreId().equals(storeUuid)) {
                throw new BusinessException("PRODUCT_STORE_MISMATCH",
                        "Product does not belong to the selected store");
            }
            productName = product.getName();
        }

        RankMonitorTaskEntity entity = RankMonitorTaskEntity.builder()
                .storeId(storeUuid)
                .productId(productUuid)
                .keywordText(request.getKeywordText().trim())
                .status(ACTIVE)
                .build();
        taskMapper.insert(entity);

        log.info("Rank monitor task created: id={}, keyword={}, store={}",
                entity.getId(), entity.getKeywordText(), storeUuid);
        return RankMonitorConverter.toVo(entity, null, storeName(storeUuid), productName);
    }

    @Override
    public List<RankMonitorTaskVo> listTasks(String storeId) {
        QueryWrapper<RankMonitorTaskEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", parseUuid(storeId, "storeId"));
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, RANK_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        List<RankMonitorTaskEntity> tasks = taskMapper.selectList(wrapper);
        Map<UUID, RankMonitorSnapshotEntity> latestByTask = latestSnapshots(tasks);
        Map<UUID, String> storeNames = storeNames(tasks);
        Map<UUID, String> productNames = productNames(tasks);

        return tasks.stream()
                .map(t -> RankMonitorConverter.toVo(
                        t,
                        latestByTask.get(t.getId()),
                        t.getStoreId() != null ? storeNames.get(t.getStoreId()) : null,
                        t.getProductId() != null ? productNames.get(t.getProductId()) : null))
                .collect(Collectors.toList());
    }

    @Override
    public RankMonitorQuotaVo getQuota(String storeId) {
        int consumed = 0;
        if (storeId != null && !storeId.isBlank()) {
            consumed = countActiveTasks(parseUuid(storeId, "storeId"));
        }
        return RankMonitorQuotaVo.builder()
                .consumed(consumed)
                .total(quotaTotal)
                .remaining(RankQuota.remaining(consumed, quotaTotal))
                .exhausted(RankQuota.isExhausted(consumed, quotaTotal))
                .build();
    }

    /** Count the store's active (quota-consuming) rank-monitor tasks. */
    private int countActiveTasks(UUID storeUuid) {
        QueryWrapper<RankMonitorTaskEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("store_id", storeUuid);
        wrapper.eq("status", ACTIVE);
        Long count = taskMapper.selectCount(wrapper);
        return count != null ? count.intValue() : 0;
    }

    /** Single-name lookup for a store, tolerant of a missing/renamed store. */
    private String storeName(UUID storeUuid) {
        if (storeUuid == null) {
            return null;
        }
        StoreEntity store = storeMapper.selectById(storeUuid);
        return store != null ? store.getName() : null;
    }

    /** Batch-resolve store id -> store name for the given tasks (avoids N+1). */
    private Map<UUID, String> storeNames(List<RankMonitorTaskEntity> tasks) {
        Set<UUID> ids = tasks.stream()
                .map(RankMonitorTaskEntity::getStoreId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        Map<UUID, String> names = new HashMap<>();
        for (StoreEntity store : storeMapper.selectBatchIds(new ArrayList<>(ids))) {
            names.put(store.getId(), store.getName());
        }
        return names;
    }

    /** Batch-resolve product id -> product name for the given tasks (avoids N+1). */
    private Map<UUID, String> productNames(List<RankMonitorTaskEntity> tasks) {
        Set<UUID> ids = tasks.stream()
                .map(RankMonitorTaskEntity::getProductId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        Map<UUID, String> names = new HashMap<>();
        for (ProductEntity product : productMapper.selectBatchIds(new ArrayList<>(ids))) {
            names.put(product.getId(), product.getName());
        }
        return names;
    }

    /**
     * Build a map of task id -> most recent snapshot for the given tasks. A
     * single query ordered by capture time avoids an N+1 fetch; the first
     * snapshot seen per task is the latest.
     */
    private Map<UUID, RankMonitorSnapshotEntity> latestSnapshots(List<RankMonitorTaskEntity> tasks) {
        Map<UUID, RankMonitorSnapshotEntity> latest = new HashMap<>();
        if (tasks.isEmpty()) {
            return latest;
        }
        List<UUID> taskIds = tasks.stream().map(RankMonitorTaskEntity::getId).collect(Collectors.toList());
        QueryWrapper<RankMonitorSnapshotEntity> wrapper = new QueryWrapper<>();
        wrapper.in("task_id", taskIds);
        wrapper.orderByDesc("captured_at");
        for (RankMonitorSnapshotEntity snapshot : snapshotMapper.selectList(wrapper)) {
            latest.putIfAbsent(snapshot.getTaskId(), snapshot);
        }
        return latest;
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException("INVALID_ID", "Invalid " + field + ": " + value);
        }
    }
}
