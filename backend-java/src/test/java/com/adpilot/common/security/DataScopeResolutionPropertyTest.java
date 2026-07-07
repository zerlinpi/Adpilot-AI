package com.adpilot.common.security;

import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.user.entity.DataScope;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link DataScopeServiceImpl#resolve(CurrentUser)} (task 9.1).
 *
 * Feature: core-platform-completion, Property 11: Effective data scope is the
 * broadest applicable across a user's roles.
 *
 * <p>For any user holding a set of roles with assorted data scopes, the resolved
 * effective scope equals the broadest tier by the precedence
 * all-company &rarr; department &rarr; assigned-store/assigned-product &rarr; own,
 * and the records it permits equal the union of the records permitted by each
 * individual role's scope (Req 7.1.4). Within the assigned-store/assigned-product
 * tier the permitted stores are the union of the user's {@code user_stores}
 * assignments and the permitted products are the union of every assigned-product
 * role's product ids.
 *
 * <p>The three RBAC mappers ({@code user_roles}, {@code data_scopes},
 * {@code user_stores}) are backed by stateful in-memory stubs seeded with the
 * generated user's roles and per-role scopes.
 *
 * Validates: Requirements 7.1.4
 */
class DataScopeResolutionPropertyTest {

    /** Fixed pools so generated ids overlap across roles, making unions meaningful. */
    private static final List<UUID> PRODUCT_POOL = List.of(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID());

    private static final List<UUID> STORE_POOL = List.of(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID());

    /** A single role's data scope: its tier plus (for assigned-product) its product ids. */
    private record RoleScope(ScopeType type, List<UUID> productIds) {
    }

    // Feature: core-platform-completion, Property 11: Effective data scope is the broadest applicable across a user's roles
    @Property(tries = 200)
    void resolvesBroadestTierAndUnionsPermittedRecords(
            @ForAll("roleScopeLists") List<RoleScope> roleScopes,
            @ForAll("storeAssignments") Set<UUID> storeAssignments) {

        UUID userId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();

        // --- seed the stateful in-memory mappers for this single user -------------
        List<UserRole> userRoles = new ArrayList<>();
        List<DataScope> dataScopes = new ArrayList<>();
        for (RoleScope rs : roleScopes) {
            UUID roleId = UUID.randomUUID();
            userRoles.add(UserRole.builder().id(UUID.randomUUID()).userId(userId).roleId(roleId).build());
            dataScopes.add(DataScope.builder()
                    .id(UUID.randomUUID())
                    .roleId(roleId)
                    .scopeType(code(rs.type()))
                    .productIds(rs.productIds().stream().map(UUID::toString).collect(Collectors.toList()))
                    .build());
        }
        List<UserStoreEntity> userStores = storeAssignments.stream()
                .map(sid -> UserStoreEntity.builder().id(UUID.randomUUID()).userId(userId).storeId(sid).build())
                .collect(Collectors.toList());

        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);
        when(userRoleMapper.selectList(any())).thenReturn(userRoles);
        when(dataScopeMapper.selectList(any())).thenReturn(dataScopes);
        when(userStoreMapper.selectList(any())).thenReturn(userStores);

        DataScopeServiceImpl service =
                new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper,
                        org.mockito.Mockito.mock(com.adpilot.modules.store.mapper.StoreMapper.class));

        CurrentUser user = CurrentUser.builder()
                .userId(userId.toString())
                .departmentId(departmentId.toString())
                .orgId(UUID.randomUUID().toString())
                .email("user@example.com")
                .roles(Set.of()) // non-super-admin
                .permissions(List.of())
                .build();

        // --- expected resolution --------------------------------------------------
        int bestRank = roleScopes.stream().mapToInt(rs -> rs.type().rank()).min().orElse(ScopeType.OWN.rank());

        EffectiveScope resolved = service.resolve(user);

        assertThat(resolved.isSuperAdmin()).isFalse();

        if (bestRank == ScopeType.ALL_COMPANY.rank()) {
            // Broadest tier wins outright and imposes no row restriction (Req 7.1.4).
            assertThat(resolved.getType()).isEqualTo(ScopeType.ALL_COMPANY);
            assertThat(resolved.isUnrestricted()).isTrue();
        } else if (bestRank == ScopeType.DEPARTMENT.rank()) {
            assertThat(resolved.getType()).isEqualTo(ScopeType.DEPARTMENT);
            assertThat(resolved.getDepartmentId()).isEqualTo(departmentId);
        } else if (bestRank == ScopeType.OWN.rank()) {
            assertThat(resolved.getType()).isEqualTo(ScopeType.OWN);
            assertThat(resolved.getUserId()).isEqualTo(userId);
        } else {
            // Assigned-store / assigned-product peer tier: union the permitted records.
            boolean hasStore = roleScopes.stream().anyMatch(rs -> rs.type() == ScopeType.ASSIGNED_STORE);
            boolean hasProduct = roleScopes.stream().anyMatch(rs -> rs.type() == ScopeType.ASSIGNED_PRODUCT);

            Set<UUID> expectedStoreIds = hasStore ? new HashSet<>(storeAssignments) : Set.of();
            Set<UUID> expectedProductIds = hasProduct
                    ? roleScopes.stream()
                        .filter(rs -> rs.type() == ScopeType.ASSIGNED_PRODUCT)
                        .flatMap(rs -> rs.productIds().stream())
                        .collect(Collectors.toSet())
                    : Set.of();

            assertThat(resolved.getType())
                    .isEqualTo(hasStore ? ScopeType.ASSIGNED_STORE : ScopeType.ASSIGNED_PRODUCT);
            // Permitted stores = union of the user's store assignments (Req 7.1.4 union).
            assertThat(resolved.getStoreIds()).isEqualTo(expectedStoreIds);
            // Permitted products = union across every assigned-product role (Req 7.1.4 union).
            assertThat(resolved.getProductIds()).isEqualTo(expectedProductIds);
        }
    }

    // --- generators -----------------------------------------------------------

    /**
     * A non-empty list of role scopes drawn from every tier, so the broadest-tier
     * selection (Req 7.1.4) is exercised across all precedence combinations.
     * Assigned-product roles carry a (possibly empty, possibly overlapping) set of
     * product ids so the union semantics are tested.
     */
    @Provide
    Arbitrary<List<RoleScope>> roleScopeLists() {
        Arbitrary<ScopeType> typeArb = Arbitraries.of(
                ScopeType.ALL_COMPANY, ScopeType.DEPARTMENT,
                ScopeType.ASSIGNED_STORE, ScopeType.ASSIGNED_PRODUCT, ScopeType.OWN);

        Arbitrary<RoleScope> roleScopeArb = typeArb.flatMap(type -> {
            if (type == ScopeType.ASSIGNED_PRODUCT) {
                return Arbitraries.of(PRODUCT_POOL).set().ofMaxSize(4)
                        .map(ids -> new RoleScope(type, new ArrayList<>(ids)));
            }
            return Arbitraries.just(new RoleScope(type, List.of()));
        });

        return roleScopeArb.list().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<Set<UUID>> storeAssignments() {
        return Arbitraries.of(STORE_POOL).set().ofMaxSize(4);
    }

    private static String code(ScopeType type) {
        return type.name().toLowerCase();
    }
}
