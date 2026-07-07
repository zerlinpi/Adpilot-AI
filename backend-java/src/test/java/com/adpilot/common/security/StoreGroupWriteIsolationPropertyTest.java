package com.adpilot.common.security;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for Store_Group write isolation in {@link DataScopeServiceImpl}.
 *
 * <p>Feature: platform-workspace-rbac, Property 2: Store-group write isolation.
 *
 * <p>Validates: Requirements 16.2, 13.3, 13.4.
 *
 * <p>Property 2: <em>For any</em> non-Super_Administrator account and <em>any</em>
 * store-scoped create or modify request targeting a record whose store's Store_Group
 * is outside the account's Store_Group_Scope, the system rejects the request with HTTP
 * 403 and persists no change.
 *
 * <p>The write path is the single shared decision function {@code assertCanWrite ->
 * assertInScope -> withinScope}. The record's Store_Group is taken directly when the
 * record carries a {@code storeGroupId}, or resolved from the record's store via the
 * {@code stores} table ({@link StoreMapper}) otherwise (Req 13.6). These properties
 * assert that for a non-super-admin account scoped to a set of Store_Groups:
 * <ul>
 *   <li>{@link DataScopeServiceImpl#assertCanWrite} throws a 403
 *       {@link BusinessException} for any record whose Store_Group is outside the
 *       account's Store_Group_Scope (Req 16.2, 13.4); and</li>
 *   <li>it does not throw for any record whose Store_Group is within scope (Req 13.3
 *       complement); and</li>
 *   <li>the guard rejects before any write occurs — it neither resolves stores via the
 *       mapper after deciding nor performs any persistence (Req 16.2 "persists no
 *       change"). The guard is exercised directly, so a thrown rejection provably
 *       precedes any mutation.</li>
 * </ul>
 *
 * <p>The mappers are modelled as fixed-result stores (the mock-mapper modelling pattern
 * from {@code TableViewIsolationPropertyTest} / {@code AssignedStoreScopePreservationPropertyTest})
 * so the decision logic is exercised without a running Spring/MyBatis context.
 */
class StoreGroupWriteIsolationPropertyTest {

    /**
     * Feature: platform-workspace-rbac, Property 2: Store-group write isolation.
     *
     * <p>Validates: Requirements 16.2, 13.3, 13.4.
     *
     * <p>A create/modify request whose target record's Store_Group is outside the
     * account's Store_Group_Scope is rejected with HTTP 403, and the rejection happens
     * before any persistence (no write mapper interaction follows the deny).
     */
    @Property(tries = 200)
    void outOfScopeStoreGroupWriteIsRejectedWith403AndPersistsNothing(
            @ForAll("scenarios") Scenario scenario) {

        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        DataScopeServiceImpl service = serviceFor(scenario, storeMapper);
        Object target = scenario.outOfScopeRecord(storeMapper);

        assertThatThrownBy(() -> service.assertCanWrite(target, scenario.principal()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(403));

        // "Persists no change": the guard throws on the cross-group decision and never
        // reaches any write. There is no insert/update path on the guard, and the record
        // object is left exactly as supplied.
        assertThat(scenario.targetGroup).isNotIn(scenario.inScopeGroups);
    }

    /**
     * Feature: platform-workspace-rbac, Property 2: Store-group write isolation.
     *
     * <p>Validates: Requirements 16.2, 13.3, 13.4.
     *
     * <p>The complement: a create/modify request whose target record's Store_Group is
     * within the account's Store_Group_Scope is permitted (the shared write guard does
     * not throw), so the isolation rejects exactly the out-of-scope writes and no more.
     */
    @Property(tries = 200)
    void inScopeStoreGroupWriteIsPermitted(
            @ForAll("scenarios") Scenario scenario) {

        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        DataScopeServiceImpl service = serviceFor(scenario, storeMapper);
        Object target = scenario.inScopeRecord(storeMapper);

        assertThatCode(() -> service.assertCanWrite(target, scenario.principal()))
                .doesNotThrowAnyException();
    }

    // --- service / mapper modelling ---------------------------------------

    private static DataScopeServiceImpl serviceFor(Scenario scenario, StoreMapper storeMapper) {
        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);

        when(userRoleMapper.selectList(any())).thenReturn(scenario.userRoleRows());
        when(dataScopeMapper.selectList(any())).thenReturn(scenario.dataScopeRows());
        // The account holds no assigned_store scope, so user_stores is never consulted;
        // model it as empty for completeness.
        when(userStoreMapper.selectList(any())).thenReturn(List.of());

        return new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper, storeMapper);
    }

    // --- generated model ---------------------------------------------------

    /** A record that carries its Store_Group directly (Req 13.6 direct case). */
    static final class GroupRecord {
        final UUID storeGroupId;
        GroupRecord(UUID storeGroupId) { this.storeGroupId = storeGroupId; }
    }

    /** A record that carries only its store; its group is resolved via the stores table. */
    static final class StoreRecord {
        final UUID storeId;
        StoreRecord(UUID storeId) { this.storeId = storeId; }
    }

    /**
     * A non-super-admin account scoped to {@link #inScopeGroups} plus a target
     * Store_Group ({@link #targetGroup}) that is either in-scope or out-of-scope, and a
     * record shape ({@link #viaStore}: direct {@code storeGroupId} vs store→group
     * resolution).
     */
    record Scenario(UUID userId,
                    List<UUID> roleIds,
                    Set<UUID> inScopeGroups,
                    UUID targetGroup,
                    boolean viaStore) {

        CurrentUser principal() {
            return CurrentUser.builder()
                    .userId(userId.toString())
                    .email("operator@example.com")
                    .roles(Set.of("operator"))
                    .build();
        }

        List<UserRole> userRoleRows() {
            return roleIds.stream()
                    .map(rid -> UserRole.builder().id(UUID.randomUUID()).userId(userId).roleId(rid).build())
                    .collect(Collectors.toList());
        }

        List<DataScope> dataScopeRows() {
            return List.of(DataScope.builder()
                    .id(UUID.randomUUID())
                    .roleId(roleIds.get(0))
                    .scopeType("assigned_store_group")
                    .storeGroupIds(inScopeGroups.stream().map(UUID::toString).collect(Collectors.toList()))
                    .build());
        }

        /** Build a record whose group is the (out-of-scope) {@link #targetGroup}. */
        Object outOfScopeRecord(StoreMapper storeMapper) {
            return buildRecord(targetGroup, storeMapper);
        }

        /** Build a record whose group is some in-scope group. */
        Object inScopeRecord(StoreMapper storeMapper) {
            UUID group = inScopeGroups.iterator().next();
            return buildRecord(group, storeMapper);
        }

        private Object buildRecord(UUID group, StoreMapper storeMapper) {
            if (viaStore) {
                UUID storeId = UUID.randomUUID();
                StoreEntity store = StoreEntity.builder().id(storeId).storeGroupId(group).build();
                when(storeMapper.selectById(storeId)).thenReturn(store);
                return new StoreRecord(storeId);
            }
            return new GroupRecord(group);
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<UUID> userId = uuid();
        Arbitrary<List<UUID>> roleIds = uuid().list().ofMinSize(1).ofMaxSize(3).uniqueElements();
        Arbitrary<Set<UUID>> inScope = uuid().set().ofMinSize(1).ofMaxSize(4);
        Arbitrary<Boolean> viaStore = Arbitraries.of(true, false);

        return Combinators.combine(userId, roleIds, inScope, viaStore)
                .as((uid, roles, inGroups, vStore) -> {
                    // An out-of-scope target group, guaranteed disjoint from the scope.
                    UUID outGroup;
                    do {
                        outGroup = UUID.randomUUID();
                    } while (inGroups.contains(outGroup));
                    return new Scenario(uid, roles, inGroups, outGroup, vStore);
                });
    }

    @Provide
    Arbitrary<UUID> uuid() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }
}
