package com.adpilot.common.security;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.user.entity.DataScope;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link DataScopeServiceImpl}, the shared data-scope query
 * layer that resolves a user's effective scope, contributes a row-level filter to
 * list queries, and guards single-record reads/writes (tasks 9.1, 9.3).
 *
 * <p>Feature: core-platform-completion, Property 10: Data-scope filtering restricts
 * reads and writes to permitted records across every scope dimension.
 *
 * <p>For any user with an effective data scope (all-company, department,
 * assigned-store, assigned-product, or own) and any dataset, the records the guard
 * admits are <em>exactly</em> those permitted by that scope, a single-record read or
 * a create/modify targeting an out-of-scope record is rejected with HTTP 403, and a
 * super-administrator is granted access to every record.
 *
 * <p>The RBAC mappers ({@link UserRoleMapper}, {@link DataScopeMapper},
 * {@link UserStoreMapper}) are backed by stateful in-memory data per iteration so the
 * real resolution logic runs end to end. {@code applyScope} produces a
 * {@code QueryWrapper} fragment that cannot be executed without a database, so the
 * scoped result set is modelled by filtering the in-memory dataset with the same
 * membership rule the guard enforces, and the guard's admitted set is asserted to
 * equal it.
 *
 * Validates: Requirements 2.2.1, 2.2.3, 2.2.4, 2.2.5, 7.1.1, 7.1.2, 7.1.3, 7.1.5, 10.1.5
 */
class DataScopeServicePropertyTest {

    private static final String SUPER_ADMIN_ROLE = "super_admin";

    // Small fixed identifier pools so generated assignments and records overlap often,
    // exercising both in-scope and out-of-scope outcomes within each dimension.
    private static final List<String> STORE_POOL = uuidPool(4);
    private static final List<String> PRODUCT_POOL = uuidPool(4);
    private static final List<String> DEPT_POOL = uuidPool(3);
    private static final List<String> OWNER_POOL = uuidPool(4);

    /** The effective scope tier under test. */
    enum Dimension {
        ALL_COMPANY, DEPARTMENT, ASSIGNED_STORE, ASSIGNED_PRODUCT, OWN, SUPER_ADMIN
    }

    // Feature: core-platform-completion, Property 10: Data-scope filtering restricts reads and writes to permitted records across every scope dimension
    @Property(tries = 200)
    void scopeFilteringAdmitsExactlyPermittedRecordsAcrossEveryDimension(@ForAll("scenarios") Scenario s) {
        DataScopeServiceImpl service = buildService(s);
        CurrentUser user = s.user();

        // Model the scoped query result (Req 2.2.1 / 7.1.5): the records permitted by
        // the effective scope are exactly those satisfying the membership rule.
        List<ScopedRecord> permitted = s.dataset.stream().filter(r -> inScope(s, r)).toList();
        List<ScopedRecord> admitted = s.dataset.stream()
                .filter(r -> guardAdmitsRead(service, r, user))
                .toList();

        // The guard admits exactly the permitted set — no more, no fewer.
        assertThat(admitted).containsExactlyElementsOf(permitted);

        for (ScopedRecord record : s.dataset) {
            boolean expectedInScope = inScope(s, record);
            if (expectedInScope) {
                // In-scope: single-record read and create/modify both succeed.
                assertThatCode(() -> service.assertCanRead(record, user)).doesNotThrowAnyException();
                assertThatCode(() -> service.assertCanWrite(record, user)).doesNotThrowAnyException();
            } else {
                // Out-of-scope: read (Req 2.2.2) and write (Req 2.2.3) are rejected with HTTP 403.
                assertThatThrownBy(() -> service.assertCanRead(record, user))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(403));
                assertThatThrownBy(() -> service.assertCanWrite(record, user))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(403));
            }
        }

        // Super-admin and all-company grant access to every record (Req 2.2.5 / 7.1.3).
        if (s.dimension == Dimension.SUPER_ADMIN || s.dimension == Dimension.ALL_COMPANY) {
            assertThat(admitted).containsExactlyElementsOf(s.dataset);
        }
    }

    // --- wiring ---------------------------------------------------------------

    private DataScopeServiceImpl buildService(Scenario s) {
        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);

        UUID roleId = UUID.randomUUID();

        // user_roles: the user holds a single role carrying the generated data scope.
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRole.builder().id(UUID.randomUUID()).userId(UUID.fromString(s.userId)).roleId(roleId).build()));

        // data_scopes: one scope row for that role, of the dimension under test.
        List<DataScope> scopes = new ArrayList<>();
        if (s.dimension != Dimension.SUPER_ADMIN) {
            scopes.add(DataScope.builder()
                    .id(UUID.randomUUID())
                    .roleId(roleId)
                    .scopeType(scopeCode(s.dimension))
                    .productIds(new ArrayList<>(s.assignedProductIds))
                    .build());
        }
        when(dataScopeMapper.selectList(any())).thenReturn(scopes);

        // user_stores: assigned-store ids are sourced from user_stores, never the data_scopes JSON.
        List<UserStoreEntity> userStores = s.assignedStoreIds.stream()
                .map(storeId -> UserStoreEntity.builder()
                        .id(UUID.randomUUID())
                        .userId(UUID.fromString(s.userId))
                        .storeId(UUID.fromString(storeId))
                        .build())
                .toList();
        when(userStoreMapper.selectList(any())).thenReturn(userStores);

        return new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper,
                org.mockito.Mockito.mock(com.adpilot.modules.store.mapper.StoreMapper.class));
    }

    private static boolean guardAdmitsRead(DataScopeServiceImpl service, ScopedRecord record, CurrentUser user) {
        try {
            service.assertCanRead(record, user);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /** The expected membership rule per dimension, defined independently of the implementation. */
    private static boolean inScope(Scenario s, ScopedRecord r) {
        return switch (s.dimension) {
            case SUPER_ADMIN, ALL_COMPANY -> true;
            case DEPARTMENT -> s.userDepartmentId.equalsIgnoreCase(r.departmentId);
            case ASSIGNED_STORE -> containsIgnoreCase(s.assignedStoreIds, r.storeId);
            case ASSIGNED_PRODUCT -> containsIgnoreCase(s.assignedProductIds, r.productId);
            case OWN -> s.userId.equalsIgnoreCase(r.ownerId);
        };
    }

    private static boolean containsIgnoreCase(Set<String> ids, String value) {
        return value != null && ids.stream().anyMatch(id -> id.equalsIgnoreCase(value));
    }

    private static String scopeCode(Dimension d) {
        return switch (d) {
            case ALL_COMPANY -> "all_company";
            case DEPARTMENT -> "department";
            case ASSIGNED_STORE -> "assigned_store";
            case ASSIGNED_PRODUCT -> "assigned_product";
            case OWN, SUPER_ADMIN -> "own";
        };
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Dimension> dimension = Arbitraries.of(Dimension.values());
        Arbitrary<Set<String>> assignedStores = Arbitraries.of(STORE_POOL).set().ofMaxSize(3);
        Arbitrary<Set<String>> assignedProducts = Arbitraries.of(PRODUCT_POOL).set().ofMaxSize(3);
        Arbitrary<String> userDepartment = Arbitraries.of(DEPT_POOL);
        Arbitrary<String> userId = Arbitraries.of(OWNER_POOL);
        Arbitrary<List<ScopedRecord>> dataset = records().list().ofMinSize(1).ofMaxSize(12);

        return Combinators.combine(dimension, assignedStores, assignedProducts, userDepartment, userId, dataset)
                .as(Scenario::new);
    }

    private Arbitrary<ScopedRecord> records() {
        return Combinators.combine(
                Arbitraries.of(STORE_POOL),
                Arbitraries.of(PRODUCT_POOL),
                Arbitraries.of(DEPT_POOL),
                Arbitraries.of(OWNER_POOL)
        ).as(ScopedRecord::new);
    }

    private static List<String> uuidPool(int size) {
        List<String> pool = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            pool.add(UUID.randomUUID().toString());
        }
        return List.copyOf(pool);
    }

    // --- test fixtures --------------------------------------------------------

    /** A data-scoped record exposing all four scope dimensions to the reflective guard. */
    static class ScopedRecord {
        final String storeId;
        final String productId;
        final String departmentId;
        final String ownerId;

        ScopedRecord(String storeId, String productId, String departmentId, String ownerId) {
            this.storeId = storeId;
            this.productId = productId;
            this.departmentId = departmentId;
            this.ownerId = ownerId;
        }
    }

    /** A complete data-scope scenario: the user, their assignments, and a dataset. */
    static class Scenario {
        final Dimension dimension;
        final Set<String> assignedStoreIds;
        final Set<String> assignedProductIds;
        final String userDepartmentId;
        final String userId;
        final List<ScopedRecord> dataset;

        Scenario(Dimension dimension, Set<String> assignedStoreIds, Set<String> assignedProductIds,
                 String userDepartmentId, String userId, List<ScopedRecord> dataset) {
            this.dimension = dimension;
            this.assignedStoreIds = new LinkedHashSet<>(assignedStoreIds);
            this.assignedProductIds = new LinkedHashSet<>(assignedProductIds);
            this.userDepartmentId = userDepartmentId;
            this.userId = userId;
            this.dataset = dataset;
        }

        CurrentUser user() {
            Set<String> roles = new LinkedHashSet<>();
            if (dimension == Dimension.SUPER_ADMIN) {
                roles.add(SUPER_ADMIN_ROLE);
            } else {
                roles.add("operator");
            }
            return CurrentUser.builder()
                    .userId(userId)
                    .orgId(UUID.randomUUID().toString())
                    .email("user@example.com")
                    .name("Test User")
                    .roles(roles)
                    .permissions(List.of())
                    .departmentId(userDepartmentId)
                    .build();
        }
    }
}
