package com.adpilot.common.security;

import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for Store_Group read isolation enforced by the shared
 * data-scope query layer, {@link DataScopeServiceImpl} (task 5.1 / 5.2).
 *
 * <p>Feature: platform-workspace-rbac, Property 1: Store-group read isolation.
 *
 * <p>Validates: Requirements 16.1, 13.2, 9.7.
 *
 * <p>For any non-Super_Administrator account and any store-scoped list query, the
 * records returned by the Data_Scope_Service include only records whose store
 * belongs to a Store_Group within the account's Store_Group_Scope, and never any
 * record from a Store_Group outside that scope.
 *
 * <p>The RBAC mappers ({@link UserRoleMapper}, {@link DataScopeMapper},
 * {@link UserStoreMapper}, {@link StoreMapper}) are backed by in-memory stubs per
 * iteration so {@link DataScopeServiceImpl#resolve} runs end to end and produces an
 * {@code ASSIGNED_STORE_GROUP} effective scope from the account's roles. Because
 * {@code applyScope} contributes a {@code QueryWrapper} fragment that cannot be
 * executed without a database, the scoped result set is modelled exactly as
 * MyBatis-Plus would: the {@code IN (...)} filter the service binds onto the
 * wrapper is read back from the wrapper's bound parameter values, and the dataset is
 * filtered with that very predicate. The property asserts that the bound filter is
 * exactly the account's Store_Group_Scope (and never an out-of-scope group), and that
 * the modelled result set therefore contains every in-scope record and no
 * out-of-scope record.
 *
 * <p>Both expression paths of Requirement 13.6 are exercised: an entity that carries
 * a {@code store_group_id} column directly, and an entity that carries only a
 * {@code store_id} and is resolved store&rarr;group through the {@code stores} table.
 *
 * <p>This is an isolation property and runs a minimum of 200 tries.
 */
class StoreGroupReadIsolationPropertyTest {

    /**
     * Fixed pool of candidate Store_Group ids so generated scopes and records overlap
     * often, exercising both in-scope and out-of-scope outcomes within each iteration.
     */
    private static final List<UUID> GROUP_POOL = List.of(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    /** A store-scoped record: the store it belongs to and that store's Store_Group. */
    private record Record(UUID storeId, UUID groupId) {
    }

    /**
     * Feature: platform-workspace-rbac, Property 1: Store-group read isolation.
     *
     * <p>Validates: Requirements 16.1, 13.2, 9.7.
     *
     * <p>Direct-column path: the queried entity carries a {@code store_group_id}
     * column, so the shared layer filters on it with the account's Store_Group_Scope.
     */
    @Property(tries = 200)
    void listQueryViaStoreGroupColumnReturnsOnlyInScopeGroups(
            @ForAll("scopeGroups") Set<UUID> inScopeGroups,
            @ForAll("dataset") List<Record> dataset) {

        UUID userId = UUID.randomUUID();
        DataScopeServiceImpl service = buildService(userId, inScopeGroups, List.of());
        CurrentUser user = nonSuperAdmin(userId);

        // The shared layer binds the in-scope group ids onto the wrapper's IN predicate.
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        service.applyScope(wrapper, ScopeTarget.storeGroup("store_group_id"), user);
        Set<String> boundFilter = boundParamValues(wrapper);

        Set<String> inScopeStrings = asStrings(inScopeGroups);
        Set<String> outOfScopeStrings = asStrings(outOfScope(inScopeGroups));

        // The bound filter is EXACTLY the account's Store_Group_Scope: every in-scope
        // group, and never any out-of-scope group (the isolation boundary, Req 16.1).
        assertThat(boundFilter).containsExactlyInAnyOrderElementsOf(inScopeStrings);
        assertThat(Collections.disjoint(boundFilter, outOfScopeStrings)).isTrue();

        // Model the scoped result set with that very predicate (Req 13.2).
        List<Record> returned = dataset.stream()
                .filter(r -> boundFilter.contains(r.groupId().toString()))
                .toList();
        List<Record> expected = dataset.stream()
                .filter(r -> inScopeGroups.contains(r.groupId()))
                .toList();

        assertThat(returned).containsExactlyElementsOf(expected);
        // No record from a Store_Group outside the scope is ever returned.
        assertThat(returned).noneMatch(r -> !inScopeGroups.contains(r.groupId()));
    }

    /**
     * Feature: platform-workspace-rbac, Property 1: Store-group read isolation.
     *
     * <p>Validates: Requirements 16.1, 13.2, 9.7.
     *
     * <p>Store-resolution path (Req 13.6): the queried entity carries only a
     * {@code store_id}; the shared layer resolves the in-scope stores through the
     * {@code stores} table and filters on the store id.
     */
    @Property(tries = 200)
    void listQueryViaStoreResolutionReturnsOnlyInScopeGroups(
            @ForAll("scopeGroups") Set<UUID> inScopeGroups,
            @ForAll("dataset") List<Record> dataset) {

        UUID userId = UUID.randomUUID();

        // Every store referenced by the dataset, mapped to its Store_Group.
        List<StoreEntity> allStores = dataset.stream()
                .map(r -> StoreEntity.builder().id(r.storeId()).storeGroupId(r.groupId()).build())
                .collect(Collectors.toList());

        DataScopeServiceImpl service = buildService(userId, inScopeGroups, allStores);
        CurrentUser user = nonSuperAdmin(userId);

        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        service.applyScope(wrapper, ScopeTarget.store("store_id"), user);
        Set<String> boundFilter = boundParamValues(wrapper);

        // Stores whose Store_Group is within scope (what stores->group resolution yields).
        Set<String> inScopeStoreIds = dataset.stream()
                .filter(r -> inScopeGroups.contains(r.groupId()))
                .map(r -> r.storeId().toString())
                .collect(Collectors.toSet());
        Set<String> outOfScopeStoreIds = dataset.stream()
                .filter(r -> !inScopeGroups.contains(r.groupId()))
                .map(r -> r.storeId().toString())
                .collect(Collectors.toSet());

        // The bound store filter is exactly the in-scope stores; no out-of-scope store
        // (a store belonging to a group outside the scope) is ever admitted (Req 13.6).
        assertThat(boundFilter).containsExactlyInAnyOrderElementsOf(inScopeStoreIds);
        assertThat(Collections.disjoint(boundFilter, outOfScopeStoreIds)).isTrue();

        List<Record> returned = dataset.stream()
                .filter(r -> boundFilter.contains(r.storeId().toString()))
                .toList();
        List<Record> expected = dataset.stream()
                .filter(r -> inScopeGroups.contains(r.groupId()))
                .toList();

        assertThat(returned).containsExactlyElementsOf(expected);
        assertThat(returned).noneMatch(r -> !inScopeGroups.contains(r.groupId()));
    }

    // --- wiring ---------------------------------------------------------------

    /**
     * Build a service whose RBAC mappers describe a single non-super-admin role
     * holding an {@code assigned_store_group} scope over {@code inScopeGroups}. The
     * {@link StoreMapper} returns the subset of {@code allStores} whose group is in
     * scope, modelling the {@code stores} table for store&rarr;group resolution.
     */
    private DataScopeServiceImpl buildService(UUID userId, Set<UUID> inScopeGroups, List<StoreEntity> allStores) {
        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);

        UUID roleId = UUID.randomUUID();
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRole.builder().id(UUID.randomUUID()).userId(userId).roleId(roleId).build()));

        when(dataScopeMapper.selectList(any())).thenReturn(List.of(
                DataScope.builder()
                        .id(UUID.randomUUID())
                        .roleId(roleId)
                        .scopeType("assigned_store_group")
                        .storeGroupIds(inScopeGroups.stream().map(UUID::toString).collect(Collectors.toList()))
                        .build()));

        when(userStoreMapper.selectList(any())).thenReturn(List.of());

        // stores table: only stores whose Store_Group is in scope are resolved as in-scope.
        List<StoreEntity> inScopeStores = allStores.stream()
                .filter(st -> inScopeGroups.contains(st.getStoreGroupId()))
                .collect(Collectors.toList());
        when(storeMapper.selectList(any())).thenReturn(inScopeStores);

        return new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper, storeMapper);
    }

    private static CurrentUser nonSuperAdmin(UUID userId) {
        return CurrentUser.builder()
                .userId(userId.toString())
                .orgId(UUID.randomUUID().toString())
                .email("operator@example.com")
                .name("Operator")
                .roles(Set.of("operator"))
                .permissions(List.of())
                .build();
    }

    /**
     * Read back the parameter values MyBatis-Plus bound onto the wrapper. The SQL
     * segment is materialised first so the lazily-formatted IN values are present.
     */
    private static Set<String> boundParamValues(QueryWrapper<Object> wrapper) {
        wrapper.getTargetSql();
        return wrapper.getParamNameValuePairs().values().stream()
                .map(Object::toString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> asStrings(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<UUID> outOfScope(Set<UUID> inScopeGroups) {
        return GROUP_POOL.stream().filter(g -> !inScopeGroups.contains(g))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // --- generators -----------------------------------------------------------

    /** A non-empty Store_Group_Scope so the bound IN predicate is meaningful. */
    @Provide
    Arbitrary<Set<UUID>> scopeGroups() {
        return Arbitraries.of(GROUP_POOL).set().ofMinSize(1).ofMaxSize(4);
    }

    /**
     * A dataset of store-scoped records, each belonging to a store with a group drawn
     * from the shared pool so both in-scope and out-of-scope records appear. Each
     * record has a unique store id so store-level filtering is unambiguous.
     */
    @Provide
    Arbitrary<List<Record>> dataset() {
        Arbitrary<Record> record = Combinators.combine(
                Arbitraries.randomValue(r -> UUID.randomUUID()),
                Arbitraries.of(GROUP_POOL)
        ).as(Record::new);
        return record.list().ofMinSize(1).ofMaxSize(12);
    }
}
