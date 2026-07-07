package com.adpilot.common.security;

import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.user.entity.DataScope;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for backward-compatible preservation of the existing
 * {@code assigned_store} data scope once the Store_Group_Scope dimension is layered
 * onto the RBAC model.
 *
 * <p>Feature: platform-workspace-rbac, Property 25: Backward-compatible
 * assigned-store scope is preserved.
 *
 * <p>Validates: Requirements 18.4.
 *
 * <p>Requirement 18.4: WHERE an existing account has an {@code assigned_store} data
 * scope, THE Data_Scope_Service SHALL continue to honor that account's store
 * assignments alongside the new Store_Group_Scope evaluation.
 *
 * <p>The store access an account is granted for an {@code assigned_store} scope is
 * sourced exclusively from its {@code user_stores} rows (never from the
 * {@code data_scopes.store_ids} JSON). These properties assert that for any account
 * holding an {@code assigned_store} scope and an arbitrary set of {@code user_stores}
 * rows:
 * <ul>
 *   <li>{@link DataScopeServiceImpl#resolve} returns the {@code ASSIGNED_STORE} tier
 *       whose {@code storeIds} is exactly the set of {@code user_stores} store ids; and</li>
 *   <li>the result is unchanged when rows representing the new Store_Group_Scope
 *       dimension (persisted with the documented {@code assigned_store_group}
 *       scope_type, plus the peer {@code assigned_product} dimension) co-exist on the
 *       account's roles; and</li>
 *   <li>{@link DataScopeServiceImpl#applyScope} filters a store-scoped query to
 *       exactly those {@code user_stores} ids.</li>
 * </ul>
 *
 * <p>The mappers are modelled as fixed-result stores (the mock-mapper modelling
 * pattern from {@code TableViewIsolationPropertyTest}) so the resolution logic is
 * exercised without a running Spring/MyBatis context.
 */
class AssignedStoreScopePreservationPropertyTest {

    /**
     * Feature: platform-workspace-rbac, Property 25: Backward-compatible
     * assigned-store scope is preserved.
     *
     * <p>Validates: Requirements 18.4.
     *
     * <p>An account with an {@code assigned_store} scope resolves to the
     * {@code ASSIGNED_STORE} tier whose granted stores are exactly its
     * {@code user_stores} rows, even when Store_Group_Scope and assigned-product
     * dimension rows co-exist on the account's roles.
     */
    @Property(tries = 200)
    void assignedStoreScopeHonorsExactlyUserStoresWhenStoreGroupDimensionCoexists(
            @ForAll("accounts") Account account) {

        DataScopeServiceImpl service = serviceFor(account);

        EffectiveScope scope = service.resolve(account.principal());

        // The new Store_Group_Scope dimension never broadens or narrows the legacy
        // assigned-store honoring: the tier stays ASSIGNED_STORE...
        assertThat(scope.getType()).isEqualTo(ScopeType.ASSIGNED_STORE);
        assertThat(scope.isSuperAdmin()).isFalse();
        assertThat(scope.isUnrestricted()).isFalse();

        // ...and the granted stores are exactly the account's user_stores rows.
        assertThat(scope.getStoreIds()).isEqualTo(account.userStoreIds());
    }

    /**
     * Feature: platform-workspace-rbac, Property 25: Backward-compatible
     * assigned-store scope is preserved.
     *
     * <p>Validates: Requirements 18.4.
     *
     * <p>A store-scoped list query for such an account is filtered to exactly the
     * account's {@code user_stores} ids (and to nothing else), confirming the store
     * access remains honored end-to-end through the shared query layer.
     */
    @Property(tries = 200)
    void applyScopeFiltersStoreQueryToExactlyUserStores(
            @ForAll("accounts") Account account) {

        DataScopeServiceImpl service = serviceFor(account);

        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        service.applyScope(wrapper, ScopeTarget.store("store_id"), account.principal());

        Set<String> expected = account.userStoreIds().stream()
                .map(UUID::toString)
                .collect(Collectors.toSet());

        Set<String> filtered = boundFilterValues(wrapper);

        // Every honored store id is present in the query, and no foreign id leaks in.
        assertThat(filtered).isEqualTo(expected);
    }

    // --- service / mapper modelling ---------------------------------------

    private static DataScopeServiceImpl serviceFor(Account account) {
        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);

        when(userRoleMapper.selectList(any())).thenReturn(account.userRoleRows());
        when(dataScopeMapper.selectList(any())).thenReturn(account.dataScopeRows());
        when(userStoreMapper.selectList(any())).thenReturn(account.userStoreRows());

        return new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper,
                org.mockito.Mockito.mock(com.adpilot.modules.store.mapper.StoreMapper.class));
    }

    /**
     * Force MyBatis-Plus to materialise the wrapper's bound parameter values, then
     * return the distinct string values it placed into the {@code IN (...)} segment.
     */
    private static Set<String> boundFilterValues(QueryWrapper<Object> wrapper) {
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return pairs.values().stream()
                .filter(v -> v instanceof String)
                .map(String.class::cast)
                .collect(Collectors.toSet());
    }

    // --- generated model ---------------------------------------------------

    /**
     * A non-super-admin account holding an {@code assigned_store} scope, a set of
     * {@code user_stores} assignments, and (optionally) co-existing Store_Group_Scope
     * and assigned-product dimension rows that must not disturb the assigned-store
     * honoring.
     */
    record Account(UUID userId,
                   List<UUID> roleIds,
                   Set<UUID> userStoreIds,
                   List<DataScope> dataScopeRows) {

        CurrentUser principal() {
            return CurrentUser.builder()
                    .userId(userId.toString())
                    .email("user@example.com")
                    .roles(Set.of("operator"))
                    .build();
        }

        List<UserRole> userRoleRows() {
            return roleIds.stream()
                    .map(rid -> UserRole.builder().id(UUID.randomUUID()).userId(userId).roleId(rid).build())
                    .collect(Collectors.toList());
        }

        List<UserStoreEntity> userStoreRows() {
            return userStoreIds.stream()
                    .map(sid -> UserStoreEntity.builder()
                            .id(UUID.randomUUID()).userId(userId).storeId(sid).build())
                    .collect(Collectors.toList());
        }
    }

    @Provide
    Arbitrary<Account> accounts() {
        Arbitrary<UUID> userId = uuid();
        // 1..3 roles for the account.
        Arbitrary<List<UUID>> roleIds = uuid().list().ofMinSize(1).ofMaxSize(3).uniqueElements();
        // 1..6 distinct assigned stores (the legacy user_stores rows).
        Arbitrary<Set<UUID>> storeIds = uuid().set().ofMinSize(1).ofMaxSize(6);

        return Combinators.combine(userId, roleIds, storeIds)
                .as((uid, roles, stores) -> {
                    List<DataScope> scopes = new ArrayList<>();
                    // The account always has at least one assigned_store scope on some role.
                    scopes.add(scopeRow(roles.get(0), "assigned_store"));

                    // Model the new Store_Group_Scope dimension and the assigned-product
                    // peer co-existing on the account's roles. These rows live at (or
                    // below) the assigned-store precedence tier and must not change the
                    // honored store set. The store_group rows even carry a JSON store_ids
                    // payload to prove assigned-store honoring ignores data_scopes JSON.
                    for (UUID role : roles) {
                        scopes.add(storeGroupScopeRow(role));
                    }
                    if (roles.size() > 1) {
                        scopes.add(scopeRow(roles.get(1), "assigned_product"));
                    }
                    return new Account(uid, roles, stores, scopes);
                });
    }

    private static DataScope scopeRow(UUID roleId, String scopeType) {
        return DataScope.builder()
                .id(UUID.randomUUID())
                .roleId(roleId)
                .scopeType(scopeType)
                .build();
    }

    /**
     * A row representing the new Store_Group_Scope dimension, persisted with the
     * documented {@code assigned_store_group} scope_type. It carries a foreign
     * {@code store_ids} JSON payload that must never be honored for an
     * {@code assigned_store} account (whose stores come only from {@code user_stores}).
     */
    private static DataScope storeGroupScopeRow(UUID roleId) {
        return DataScope.builder()
                .id(UUID.randomUUID())
                .roleId(roleId)
                .scopeType("assigned_store_group")
                .storeIds(List.of(UUID.randomUUID().toString()))
                .build();
    }

    @Provide
    Arbitrary<UUID> uuid() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }
}
