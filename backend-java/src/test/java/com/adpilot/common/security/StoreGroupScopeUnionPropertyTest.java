package com.adpilot.common.security;

import com.adpilot.modules.store.mapper.StoreMapper;
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
 * Property-based test for {@link DataScopeServiceImpl#resolve(CurrentUser)} (task 5.5).
 *
 * <p>Feature: platform-workspace-rbac, Property 7: Store-group scope is the union across roles.
 *
 * <p>Validates: Requirements 13.7.
 *
 * <p>For any account holding multiple roles whose Store_Group_Scopes differ, the
 * resolved effective Store_Group_Scope is exactly the union of the Store_Groups
 * permitted across those roles. Each role contributes an {@code assigned_store_group}
 * data-scope row carrying a (possibly overlapping, possibly empty) set of
 * store-group ids; {@code resolve()} must land on the assigned-store-group tier and
 * surface {@link EffectiveScope#getStoreGroupIds()} equal to the flattened union of
 * every role's permitted groups — no group dropped, none invented.
 *
 * <p>The RBAC mappers ({@code user_roles}, {@code data_scopes}) are backed by
 * in-memory stubs seeded with the generated account's roles and per-role
 * store-group scopes, mirroring the existing mock-mapper modeling pattern from
 * {@code TableViewIsolationPropertyTest} / {@code DataScopeResolutionPropertyTest}.
 */
class StoreGroupScopeUnionPropertyTest {

    /** Fixed pool so generated group ids overlap across roles, making the union meaningful. */
    private static final List<UUID> GROUP_POOL = List.of(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    // Feature: platform-workspace-rbac, Property 7: Store-group scope is the union across roles
    @Property(tries = 200)
    void effectiveStoreGroupScopeIsTheUnionAcrossRoles(
            @ForAll("roleGroupScopes") List<Set<UUID>> roleGroupScopes) {

        UUID userId = UUID.randomUUID();

        // --- seed the in-memory mappers for this single account ------------------
        List<UserRole> userRoles = new ArrayList<>();
        List<DataScope> dataScopes = new ArrayList<>();
        for (Set<UUID> groups : roleGroupScopes) {
            UUID roleId = UUID.randomUUID();
            userRoles.add(UserRole.builder().id(UUID.randomUUID()).userId(userId).roleId(roleId).build());
            dataScopes.add(DataScope.builder()
                    .id(UUID.randomUUID())
                    .roleId(roleId)
                    .scopeType("assigned_store_group")
                    .storeGroupIds(groups.stream().map(UUID::toString).collect(Collectors.toList()))
                    .build());
        }

        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        when(userRoleMapper.selectList(any())).thenReturn(userRoles);
        when(dataScopeMapper.selectList(any())).thenReturn(dataScopes);

        DataScopeServiceImpl service =
                new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper, storeMapper);

        CurrentUser user = CurrentUser.builder()
                .userId(userId.toString())
                .departmentId(UUID.randomUUID().toString())
                .orgId(UUID.randomUUID().toString())
                .email("user@example.com")
                .roles(Set.of()) // non-super-admin
                .permissions(List.of())
                .build();

        // --- expected resolution: the flattened union of every role's groups ------
        Set<UUID> expectedUnion = roleGroupScopes.stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(HashSet::new));

        EffectiveScope resolved = service.resolve(user);

        // Store-group rows occupy the assigned tier, so the resolved scope is the
        // store-group dimension (Req 13.7).
        assertThat(resolved.isSuperAdmin()).isFalse();
        assertThat(resolved.getType()).isEqualTo(ScopeType.ASSIGNED_STORE_GROUP);

        // The effective Store_Group_Scope is EXACTLY the union: every permitted group
        // is present and no extra group leaks in.
        assertThat(resolved.getStoreGroupIds()).isEqualTo(expectedUnion);
    }

    // --- generators -----------------------------------------------------------

    /**
     * Two-to-five roles, each with a (possibly empty, possibly overlapping) set of
     * store-group ids drawn from a shared pool. "Multiple roles whose
     * Store_Group_Scopes differ" is exercised because the per-role sets vary and
     * overlap, so the union semantics (Req 13.7) are non-trivially tested.
     */
    @Provide
    Arbitrary<List<Set<UUID>>> roleGroupScopes() {
        Arbitrary<Set<UUID>> groupSet = Arbitraries.of(GROUP_POOL).set().ofMaxSize(4);
        return groupSet.list().ofMinSize(2).ofMaxSize(5);
    }
}
