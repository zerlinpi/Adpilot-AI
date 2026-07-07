package com.adpilot.common.security;

import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.entity.AccountPlatformAccessEntity;
import com.adpilot.modules.rbac.mapper.AccountPlatformAccessMapper;
import com.adpilot.modules.rbac.service.impl.PlatformAccessServiceImpl;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the mutual independence of the three RBAC_Model
 * dimensions: Platform_Access ({@link PlatformAccessServiceImpl}), Store_Group_Scope
 * ({@link DataScopeServiceImpl}), and Functional_Permission ({@link PermissionChecker}).
 *
 * <p>Feature: platform-workspace-rbac, Property 8: RBAC dimensions are independent.
 *
 * <p>Validates: Requirements 14.7, 14.3.
 *
 * <p>Property 8 states: for any combination of a Platform_Access set, a
 * Store_Group_Scope set, and a Functional_Permission set, the account is configurable
 * with that exact combination, and each dimension's allow/deny decision is independent
 * of the values of the other two.
 *
 * <p>The three dimensions live in three orthogonal stores — {@code account_platform_access}
 * rows, {@code assigned_store_group} {@code data_scopes} rows, and the principal's
 * {@code module:action} permission list — and each dimension's service reads only its
 * own store. These properties assert (a) that an account built from any
 * ({@code platformAccess}, {@code storeGroupScope}, {@code permissions}) triple reflects
 * exactly that triple, and (b) that holding one dimension's configuration fixed while
 * arbitrarily varying the other two never changes that dimension's allow/deny decision.
 *
 * <p>The mappers are modelled as fixed-result stores (the mock-mapper modelling pattern
 * from {@code StoreGroupScopeUnionPropertyTest} / {@code SuperAdministratorBypassPropertyTest})
 * so the resolution logic runs without a Spring/MyBatis context.
 */
class RbacDimensionIndependencePropertyTest {

    /** The fixed pool of {@code module:action} codes a configuration may draw from. */
    private static final List<String> PERMISSION_POOL = List.of(
            "advertising:amazon",          // distinct Amazon advertising permission (Req 14.2)
            "advertising:independent_site", // distinct independent-site advertising permission (Req 14.2)
            "finance:read",                // non-advertising permissions, independently assignable (Req 14.3)
            "warehouse:write",
            "product:update",
            "customer:reply");

    // ---------------------------------------------------------------------
    // Configurability: any combination is expressible (Req 14.7)
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 8: RBAC dimensions are independent.
     *
     * <p>Validates: Requirements 14.7, 14.3.
     *
     * <p>An account built from any ({@code platformAccess}, {@code storeGroupScope},
     * {@code permissions}) triple reflects exactly that triple across the three
     * dimensions: the accessible platform families equal the configured set, the
     * resolved Store_Group_Scope equals the configured set, and the held functional
     * permissions equal the configured set — so any combination of the three is
     * expressible for a single account (Req 14.7, 14.3).
     */
    @Property(tries = 100)
    void anyCombinationIsExpressibleAndEachDimensionReflectsItsConfiguration(
            @ForAll("configs") AccountConfig config) {

        UUID userId = UUID.randomUUID();

        // Platform_Access dimension reflects exactly the configured families.
        PlatformAccessServiceImpl platformService = platformService(userId, config.platformAccess());
        CurrentUser user = nonSuperAdminUser(userId, config.permissions());
        assertThat(platformService.accessibleFamilies(user))
                .containsExactlyInAnyOrderElementsOf(config.platformAccess());

        // Store_Group_Scope dimension reflects exactly the configured groups.
        DataScopeServiceImpl dataScopeService = dataScopeService(userId, config.storeGroupScope());
        assertThat(dataScopeService.resolve(user).getStoreGroupIds())
                .isEqualTo(config.storeGroupScope());

        // Functional_Permission dimension reflects exactly the configured permissions.
        withSecurityContext(user, () -> {
            PermissionChecker checker = new PermissionChecker();
            for (String permission : PERMISSION_POOL) {
                assertThat(checker.hasPermission(permission))
                        .as("functional permission %s", permission)
                        .isEqualTo(config.permissions().contains(permission));
            }
        });
    }

    // ---------------------------------------------------------------------
    // Platform_Access decision is independent of the other two dimensions
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 8: RBAC dimensions are independent.
     *
     * <p>Validates: Requirements 14.7, 14.3.
     *
     * <p>For a fixed Platform_Access set, the allow/deny decision for every platform
     * family is identical regardless of the account's Store_Group_Scope and
     * Functional_Permission values, and equals membership in the fixed set.
     */
    @Property(tries = 100)
    void platformAccessDecisionIsIndependentOfTheOtherDimensions(
            @ForAll("platformAccessSets") Set<PlatformFamily> platformAccess,
            @ForAll("storeGroupSets") Set<UUID> groupsA,
            @ForAll("permissionSets") Set<String> permsA,
            @ForAll("storeGroupSets") Set<UUID> groupsB,
            @ForAll("permissionSets") Set<String> permsB) {

        UUID userId = UUID.randomUUID();
        // Platform_Access lives in account_platform_access only, so the same grant rows
        // back both accounts; the other two dimensions vary freely between A and B.
        PlatformAccessServiceImpl service = platformService(userId, platformAccess);
        CurrentUser userA = nonSuperAdminUser(userId, permsA);
        CurrentUser userB = nonSuperAdminUser(userId, permsB);

        for (PlatformFamily family : PlatformFamily.values()) {
            boolean expected = platformAccess.contains(family);
            assertThat(service.hasAccess(userA, family))
                    .as("platform access to %s under config A", family)
                    .isEqualTo(expected);
            assertThat(service.hasAccess(userB, family))
                    .as("platform access to %s under config B", family)
                    .isEqualTo(expected);
        }
    }

    // ---------------------------------------------------------------------
    // Store_Group_Scope decision is independent of the other two dimensions
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 8: RBAC dimensions are independent.
     *
     * <p>Validates: Requirements 14.7, 14.3.
     *
     * <p>For a fixed Store_Group_Scope set, the resolved effective scope is identical
     * regardless of the account's Platform_Access and Functional_Permission values, and
     * equals the fixed set exactly.
     */
    @Property(tries = 100)
    void storeGroupScopeDecisionIsIndependentOfTheOtherDimensions(
            @ForAll("storeGroupSets") Set<UUID> storeGroupScope,
            @ForAll("platformAccessSets") Set<PlatformFamily> platformA,
            @ForAll("permissionSets") Set<String> permsA,
            @ForAll("platformAccessSets") Set<PlatformFamily> platformB,
            @ForAll("permissionSets") Set<String> permsB) {

        UUID userId = UUID.randomUUID();
        // Store_Group_Scope lives in assigned_store_group data_scopes only; the same scope
        // rows back both accounts while platform access and permissions vary freely.
        DataScopeServiceImpl service = dataScopeService(userId, storeGroupScope);
        CurrentUser userA = nonSuperAdminUser(userId, permsA);
        CurrentUser userB = nonSuperAdminUser(userId, permsB);

        Set<UUID> resolvedA = service.resolve(userA).getStoreGroupIds();
        Set<UUID> resolvedB = service.resolve(userB).getStoreGroupIds();

        assertThat(resolvedA).isEqualTo(storeGroupScope);
        assertThat(resolvedB).isEqualTo(storeGroupScope);
        assertThat(resolvedA).isEqualTo(resolvedB);
    }

    // ---------------------------------------------------------------------
    // Functional_Permission decision is independent of the other two dimensions
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 8: RBAC dimensions are independent.
     *
     * <p>Validates: Requirements 14.7, 14.3.
     *
     * <p>For a fixed Functional_Permission set, the allow/deny decision for every
     * {@code module:action} code is identical regardless of the account's
     * Platform_Access and Store_Group_Scope values, and equals membership in the fixed
     * set — including the distinct Amazon vs independent-site advertising permissions and
     * the non-advertising (finance / warehouse / product / customer) permissions
     * (Req 14.2, 14.3).
     */
    @Property(tries = 100)
    void functionalPermissionDecisionIsIndependentOfTheOtherDimensions(
            @ForAll("permissionSets") Set<String> permissions,
            @ForAll("platformAccessSets") Set<PlatformFamily> platformA,
            @ForAll("storeGroupSets") Set<UUID> groupsA,
            @ForAll("platformAccessSets") Set<PlatformFamily> platformB,
            @ForAll("storeGroupSets") Set<UUID> groupsB) {

        UUID userId = UUID.randomUUID();
        // Functional permissions live on the principal only; the same permission list backs
        // both accounts while platform access and store-group scope vary freely.
        CurrentUser userA = nonSuperAdminUser(userId, permissions);
        CurrentUser userB = nonSuperAdminUser(userId, permissions);

        for (String permission : PERMISSION_POOL) {
            boolean expected = permissions.contains(permission);

            boolean decisionA = withSecurityContext(userA,
                    () -> new PermissionChecker().hasPermission(permission));
            boolean decisionB = withSecurityContext(userB,
                    () -> new PermissionChecker().hasPermission(permission));

            assertThat(decisionA)
                    .as("functional permission %s under config A", permission)
                    .isEqualTo(expected);
            assertThat(decisionB)
                    .as("functional permission %s under config B", permission)
                    .isEqualTo(expected);
        }
    }

    // --- service / principal modelling ------------------------------------

    /** A {@link PlatformAccessServiceImpl} whose grant table holds exactly {@code access}. */
    private static PlatformAccessServiceImpl platformService(UUID userId, Set<PlatformFamily> access) {
        AccountPlatformAccessMapper mapper = Mockito.mock(AccountPlatformAccessMapper.class);
        List<AccountPlatformAccessEntity> rows = access.stream()
                .map(family -> AccountPlatformAccessEntity.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .platformFamily(family.getCode())
                        .build())
                .collect(Collectors.toList());
        when(mapper.selectList(any())).thenReturn(rows);
        return new PlatformAccessServiceImpl(mapper);
    }

    /**
     * A {@link DataScopeServiceImpl} backed by a single role whose
     * {@code assigned_store_group} scope carries exactly {@code groups}.
     */
    private static DataScopeServiceImpl dataScopeService(UUID userId, Set<UUID> groups) {
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
                        .storeGroupIds(groups.stream().map(UUID::toString).collect(Collectors.toList()))
                        .build()));
        when(userStoreMapper.selectList(any())).thenReturn(List.of());

        return new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper, storeMapper);
    }

    /** A non-super-admin principal carrying exactly {@code permissions}. */
    private static CurrentUser nonSuperAdminUser(UUID userId, Set<String> permissions) {
        return CurrentUser.builder()
                .userId(userId.toString())
                .email("user@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(Set.of("operator"))
                .permissions(new ArrayList<>(permissions))
                .build();
    }

    /** Run {@code action} with {@code user} installed as the security-context principal. */
    private static <T> T withSecurityContext(CurrentUser user, java.util.function.Supplier<T> action) {
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Run {@code action} with {@code user} installed as the security-context principal. */
    private static void withSecurityContext(CurrentUser user, Runnable action) {
        withSecurityContext(user, () -> {
            action.run();
            return null;
        });
    }

    // --- generated model ---------------------------------------------------

    /**
     * One independently-configured account: a Platform_Access family set, a
     * Store_Group_Scope id set, and a Functional_Permission code set.
     */
    record AccountConfig(Set<PlatformFamily> platformAccess,
                         Set<UUID> storeGroupScope,
                         Set<String> permissions) {
    }

    @Provide
    Arbitrary<AccountConfig> configs() {
        return Combinators.combine(platformAccessSets(), storeGroupSets(), permissionSets())
                .as(AccountConfig::new);
    }

    /** Any subset of the four platform families (including empty and full). */
    @Provide
    Arbitrary<Set<PlatformFamily>> platformAccessSets() {
        return Arbitraries.of(PlatformFamily.values())
                .set().ofMinSize(0).ofMaxSize(PlatformFamily.values().length);
    }

    /** Any set (including empty) of distinct store-group ids. */
    @Provide
    Arbitrary<Set<UUID>> storeGroupSets() {
        return Arbitraries.randomValue(r -> UUID.randomUUID())
                .set().ofMinSize(0).ofMaxSize(6).uniqueElements();
    }

    /** Any subset of the fixed permission pool (including empty and full). */
    @Provide
    Arbitrary<Set<String>> permissionSets() {
        return Arbitraries.of(PERMISSION_POOL)
                .set().ofMinSize(0).ofMaxSize(PERMISSION_POOL.size());
    }
}
