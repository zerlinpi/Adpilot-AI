package com.adpilot.modules.dashboard.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.audit.entity.AuditLogEntity;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.adpilot.modules.dashboard.vo.DashboardSummaryVo;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.task.entity.OperationTaskEntity;
import com.adpilot.modules.task.mapper.OperationTaskMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link DashboardServiceImpl#getSummary()} scopes every
 * product/store/task figure to the caller's accessible stores, so one
 * organization's data never bleeds into another's dashboard summary (H1).
 *
 * <p>The collaborators are Mockito stubs: {@link DataScopeService#applyScope} is
 * a no-op and {@link StoreMapper} returns only the caller's stores (modelling
 * the data-scope-filtered query). The product/task mappers <em>honor</em> the
 * {@code store_id IN (...)} filter carried by each wrapper, so the assertions
 * prove the service both filters correctly and never widens to other stores.
 */
class DashboardSummaryScopeTest {

    // Org A stores/data
    private final UUID storeA1 = UUID.randomUUID();
    private final UUID storeA2 = UUID.randomUUID();
    // Org B stores/data
    private final UUID storeB1 = UUID.randomUUID();

    private DataScopeService dataScopeService;
    private ProductMapper productMapper;
    private StoreMapper storeMapper;
    private OperationTaskMapper taskMapper;
    private AuditLogMapper auditLogMapper;

    // A shared "world" of rows across both orgs; the mappers only ever return the
    // rows whose store_id is present in the (scoped) wrapper.
    private List<ProductEntity> allProducts;
    private List<OperationTaskEntity> allTasks;

    @BeforeAll
    static void initTableInfo() {
        // The service builds MyBatis-Plus (Lambda)QueryWrappers whose column
        // references and bound parameters resolve table metadata. Outside a
        // running Spring/MyBatis context that metadata must be registered so the
        // SQL segment (and thus the parameter map) can be materialized.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProductEntity.class);
        TableInfoHelper.initTableInfo(assistant, OperationTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, StoreEntity.class);
    }

    @BeforeEach
    void setUp() {
        dataScopeService = mock(DataScopeService.class); // applyScope is a no-op void
        productMapper = mock(ProductMapper.class);
        storeMapper = mock(StoreMapper.class);
        taskMapper = mock(OperationTaskMapper.class);
        auditLogMapper = mock(AuditLogMapper.class);

        allProducts = new ArrayList<>();
        // Org A: 2 active + 1 archived across two stores.
        allProducts.add(product(storeA1, "active", 10, "5.00"));
        allProducts.add(product(storeA1, "active", 4, "2.50"));
        allProducts.add(product(storeA2, "archived", 100, "9.99"));
        // Org B: data that must never appear for org A.
        allProducts.add(product(storeB1, "active", 999, "50.00"));

        allTasks = new ArrayList<>();
        allTasks.add(task(storeA1, "open"));
        allTasks.add(task(storeA2, "in_progress"));
        allTasks.add(task(storeA1, "closed"));   // not pending
        allTasks.add(task(storeB1, "open"));       // org B — must not be counted for A

        // Product mappers honor the store_id IN(...) filter on the wrapper.
        when(productMapper.selectCount(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            Set<UUID> ids = storeIdsIn(w);
            boolean activeOnly = flatBoundValues(w).contains("active");
            return allProducts.stream()
                    .filter(p -> ids.contains(p.getStoreId()))
                    .filter(p -> !activeOnly || "active".equals(p.getStatus()))
                    .count();
        });
        when(productMapper.selectMaps(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            Set<UUID> ids = storeIdsIn(w);
            long totalInventory = 0L;
            double inventoryValue = 0.0;
            for (ProductEntity p : allProducts) {
                if (!ids.contains(p.getStoreId())) continue;
                int qty = p.getInventory() != null ? p.getInventory() : 0;
                totalInventory += qty;
                inventoryValue += qty * p.getPrice().doubleValue();
            }
            return List.of(Map.of(
                    "total_inventory", totalInventory,
                    "inventory_value", BigDecimal.valueOf(inventoryValue)));
        });
        when(productMapper.selectList(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            Set<UUID> ids = storeIdsIn(w);
            return allProducts.stream()
                    .filter(p -> ids.contains(p.getStoreId()))
                    .sorted(Comparator.comparingDouble(
                            (ProductEntity p) -> p.getPrice().doubleValue()
                                    * (p.getInventory() != null ? p.getInventory() : 0)).reversed())
                    .limit(5)
                    .collect(Collectors.toList());
        });
        when(taskMapper.selectCount(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            Set<UUID> ids = storeIdsIn(w);
            Set<String> statuses = flatBoundValues(w).stream()
                    .filter(v -> v instanceof String)
                    .map(Object::toString)
                    .collect(Collectors.toSet());
            return allTasks.stream()
                    .filter(t -> ids.contains(t.getStoreId()))
                    .filter(t -> statuses.contains(t.getStatus()))
                    .count();
        });
        when(auditLogMapper.selectPage(any(), any()))
                .thenAnswer(inv -> new Page<AuditLogEntity>(1, 5));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void summaryIsScopedToOrgAStoresOnly() {
        // Data-scope layer resolves org A's two stores for this caller.
        when(storeMapper.selectList(any())).thenReturn(List.of(store(storeA1), store(storeA2)));
        authenticate();

        DashboardServiceImpl service = new DashboardServiceImpl(
                productMapper, storeMapper, taskMapper, auditLogMapper, dataScopeService);

        DashboardSummaryVo summary = service.getSummary();

        // Only org A's 3 products / 2 active are counted (org B's 999-inventory
        // product is excluded).
        assertThat(summary.getTotalProducts()).isEqualTo(3);
        assertThat(summary.getActiveProducts()).isEqualTo(2);
        assertThat(summary.getStoreCount()).isEqualTo(2);
        // Inventory: 10 + 4 + 100 = 114 (org B's 999 must not leak in).
        assertThat(summary.getTotalInventory()).isEqualTo(114L);
        // Value: 10*5 + 4*2.5 + 100*9.99 = 50 + 10 + 999 = 1059.
        assertThat(summary.getInventoryValue()).isEqualTo(1059.0);
        // Pending tasks: open(A1) + in_progress(A2) = 2 (org B's open task excluded).
        assertThat(summary.getPendingTasks()).isEqualTo(2);
        // Top products never include org B's store.
        assertThat(summary.getTopProducts()).isNotEmpty();
    }

    @Test
    void twoOrgsDoNotBleedIntoEachOther() {
        DashboardServiceImpl service = new DashboardServiceImpl(
                productMapper, storeMapper, taskMapper, auditLogMapper, dataScopeService);

        // Caller scoped to org A.
        when(storeMapper.selectList(any())).thenReturn(List.of(store(storeA1), store(storeA2)));
        authenticate();
        DashboardSummaryVo orgA = service.getSummary();

        // Caller scoped to org B.
        when(storeMapper.selectList(any())).thenReturn(List.of(store(storeB1)));
        DashboardSummaryVo orgB = service.getSummary();

        // Org A sees only its own totals.
        assertThat(orgA.getTotalProducts()).isEqualTo(3);
        assertThat(orgA.getTotalInventory()).isEqualTo(114L);
        assertThat(orgA.getPendingTasks()).isEqualTo(2);

        // Org B sees only its own single product/task; none of A's data leaks in.
        assertThat(orgB.getTotalProducts()).isEqualTo(1);
        assertThat(orgB.getStoreCount()).isEqualTo(1);
        assertThat(orgB.getTotalInventory()).isEqualTo(999L);
        assertThat(orgB.getPendingTasks()).isEqualTo(1);
    }

    @Test
    void emptyScopeReportsZerosAndNeverWidens() {
        // A caller who can access no store must see zeros, NOT the whole table
        // (guards against an empty IN(...) collapsing to "match everything").
        when(storeMapper.selectList(any())).thenReturn(List.of());
        authenticate();

        DashboardServiceImpl service = new DashboardServiceImpl(
                productMapper, storeMapper, taskMapper, auditLogMapper, dataScopeService);

        DashboardSummaryVo summary = service.getSummary();

        assertThat(summary.getTotalProducts()).isZero();
        assertThat(summary.getActiveProducts()).isZero();
        assertThat(summary.getStoreCount()).isZero();
        assertThat(summary.getTotalInventory()).isZero();
        assertThat(summary.getInventoryValue()).isEqualTo(0.0);
        assertThat(summary.getPendingTasks()).isZero();
        assertThat(summary.getTopProducts()).isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    /** Extracts the store-id UUIDs bound to a wrapper's {@code store_id IN(...)} clause. */
    private static Set<UUID> storeIdsIn(Wrapper<?> wrapper) {
        return flatBoundValues(wrapper).stream()
                .filter(v -> v instanceof UUID)
                .map(UUID.class::cast)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * All values bound to a wrapper, with any {@code IN(...)} collections flattened
     * to their elements (MyBatis-Plus binds the collection as a single param value).
     */
    private static List<Object> flatBoundValues(Wrapper<?> wrapper) {
        List<Object> flat = new ArrayList<>();
        for (Object v : boundValues(wrapper)) {
            flatten(v, flat);
        }
        return flat;
    }

    private static void flatten(Object value, List<Object> out) {
        if (value instanceof Collection<?> c) {
            c.forEach(e -> flatten(e, out));
        } else if (value != null) {
            out.add(value);
        }
    }

    private static Collection<Object> boundValues(Wrapper<?> wrapper) {
        if (wrapper instanceof AbstractWrapper<?, ?, ?> aw) {
            // Force the SQL to materialize so the parameter map is populated.
            aw.getTargetSql();
            Map<String, Object> pairs = aw.getParamNameValuePairs();
            return pairs != null ? new ArrayList<>(pairs.values()) : List.of();
        }
        return List.of();
    }

    private static ProductEntity product(UUID storeId, String status, int inventory, String price) {
        return ProductEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .sku("SKU-" + UUID.randomUUID())
                .name("Product")
                .status(status)
                .inventory(inventory)
                .price(new BigDecimal(price))
                .build();
    }

    private static OperationTaskEntity task(UUID storeId, String status) {
        return OperationTaskEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .title("Task")
                .taskType("manual")
                .status(status)
                .build();
    }

    private static StoreEntity store(UUID id) {
        return StoreEntity.builder().id(id).name("Store-" + id).build();
    }

    private static void authenticate() {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .orgId(UUID.randomUUID().toString())
                .email("manager@example.com")
                .name("Manager")
                .roles(Set.of("operator"))
                .permissions(List.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
