package com.adpilot.modules.rbac.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.entity.AccountPlatformAccessEntity;
import com.adpilot.modules.rbac.mapper.AccountPlatformAccessMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the Platform_Access cross-platform rejection invariant
 * enforced by {@link PlatformAccessServiceImpl}.
 *
 * <p>Feature: platform-workspace-rbac, Property 3: Cross-platform rejection.
 *
 * <p>Validates: Requirements 16.3, 12.3, 12.4.
 *
 * <p>Property 3 states: for any non-Super_Administrator account and any operation
 * or route scoped to a platform family outside the account's Platform_Access, the
 * system rejects the request with HTTP 403 before the operation executes. The
 * {@code PlatformAccessAspect} (task 4.1) makes that allow/deny decision purely
 * from {@link PlatformAccessService#hasAccess}, so proving the rejection invariant
 * reduces to proving {@code hasAccess} and {@code accessibleFamilies} agree exactly
 * with the persisted grants for non-super-admins, and that the super-admin role
 * bypasses the dimension entirely (Req 15.4).
 *
 * <p>The {@code account_platform_access} rows are modelled with a mocked
 * {@link AccountPlatformAccessMapper} that returns the generated grant rows for
 * the acting user, mirroring the mock-mapper pattern in
 * {@code TableViewIsolationPropertyTest}.
 */
class PlatformAccessRejectionPropertyTest {

    /**
     * Feature: platform-workspace-rbac, Property 3: Cross-platform rejection.
     *
     * <p>Validates: Requirements 16.3, 12.3, 12.4.
     *
     * <p>For any non-super-admin account holding an arbitrary subset of platform
     * families, every family outside that subset is denied
     * ({@code hasAccess == false} and excluded from {@code accessibleFamilies}),
     * while every family inside the subset is allowed. A denial is exactly what the
     * aspect turns into a 403 before the protected method body runs.
     */
    @Property(tries = 200)
    void nonSuperAdminIsDeniedEveryFamilyOutsideItsGrantedAccess(
            @ForAll("grantedFamilies") Set<PlatformFamily> granted,
            @ForAll("nonSuperAdminRoles") Set<String> roles) {

        UUID userId = UUID.randomUUID();
        CurrentUser user = CurrentUser.builder()
                .userId(userId.toString())
                .roles(roles)
                .build();

        AccountPlatformAccessMapper mapper = Mockito.mock(AccountPlatformAccessMapper.class);
        PlatformAccessServiceImpl service = new PlatformAccessServiceImpl(mapper);

        // Model the persisted account_platform_access rows: one row per granted
        // family for the acting user. The impl filters by user_id via the wrapper;
        // these are the only rows that exist, so returning them for the query is
        // faithful to a user-scoped read.
        List<AccountPlatformAccessEntity> rows = new ArrayList<>();
        for (PlatformFamily family : granted) {
            rows.add(grantRow(userId, family));
        }
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(rows);

        Set<PlatformFamily> accessible = service.accessibleFamilies(user);

        // accessibleFamilies reflects exactly the granted subset.
        assertThat(accessible).containsExactlyInAnyOrderElementsOf(granted);

        for (PlatformFamily family : PlatformFamily.values()) {
            boolean expectedAllowed = granted.contains(family);
            // hasAccess agrees with the grant: in-scope allowed, out-of-scope denied.
            assertThat(service.hasAccess(user, family))
                    .as("hasAccess for %s with grants %s", family, granted)
                    .isEqualTo(expectedAllowed);
            // The cross-platform family is never silently present in the scope set.
            assertThat(accessible.contains(family)).isEqualTo(expectedAllowed);
        }
    }

    /**
     * Feature: platform-workspace-rbac, Property 3: Cross-platform rejection.
     *
     * <p>Validates: Requirements 16.3, 12.3, 12.4.
     *
     * <p>A null target family is denied for any non-super-admin account, so the
     * aspect rejects an unresolved platform family rather than allowing it through.
     */
    @Property(tries = 200)
    void nonSuperAdminIsDeniedNullFamily(
            @ForAll("grantedFamilies") Set<PlatformFamily> granted,
            @ForAll("nonSuperAdminRoles") Set<String> roles) {

        UUID userId = UUID.randomUUID();
        CurrentUser user = CurrentUser.builder()
                .userId(userId.toString())
                .roles(roles)
                .build();

        AccountPlatformAccessMapper mapper = Mockito.mock(AccountPlatformAccessMapper.class);
        PlatformAccessServiceImpl service = new PlatformAccessServiceImpl(mapper);

        List<AccountPlatformAccessEntity> rows = new ArrayList<>();
        for (PlatformFamily family : granted) {
            rows.add(grantRow(userId, family));
        }
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(rows);

        assertThat(service.hasAccess(user, null)).isFalse();
    }

    /**
     * Feature: platform-workspace-rbac, Property 3: Cross-platform rejection.
     *
     * <p>Validates: Requirements 16.3, 12.3, 12.4 (super-admin bypass, Req 15.4).
     *
     * <p>An account holding the {@code super_admin} role is permitted across every
     * platform family with no persisted grants, so the cross-platform rejection
     * never fires for it. The mapper must not even be consulted.
     */
    @Property(tries = 200)
    void superAdministratorHasAccessToEveryFamily(
            @ForAll("otherRoles") Set<String> otherRoles) {

        Set<String> roles = mutableRoleSet(otherRoles);
        roles.add("super_admin");

        CurrentUser superAdmin = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .roles(roles)
                .build();

        AccountPlatformAccessMapper mapper = Mockito.mock(AccountPlatformAccessMapper.class);
        PlatformAccessServiceImpl service = new PlatformAccessServiceImpl(mapper);

        assertThat(service.accessibleFamilies(superAdmin))
                .containsExactlyInAnyOrder(PlatformFamily.values());

        for (PlatformFamily family : PlatformFamily.values()) {
            assertThat(service.hasAccess(superAdmin, family))
                    .as("super-admin access to %s", family)
                    .isTrue();
        }

        // Super-admin bypass must not depend on a persisted grant lookup.
        Mockito.verifyNoInteractions(mapper);
    }

    // --- helpers -----------------------------------------------------------

    private static AccountPlatformAccessEntity grantRow(UUID userId, PlatformFamily family) {
        return AccountPlatformAccessEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .platformFamily(family.getCode())
                .build();
    }

    private static Set<String> mutableRoleSet(Set<String> source) {
        Set<String> roles = new java.util.HashSet<>();
        roles.addAll(source);
        return roles;
    }

    // --- generators --------------------------------------------------------

    /** An arbitrary subset (possibly empty, up to all four) of the platform families. */
    @Provide
    Arbitrary<Set<PlatformFamily>> grantedFamilies() {
        return Arbitraries.of(PlatformFamily.class)
                .set()
                .ofMinSize(0)
                .ofMaxSize(PlatformFamily.values().length);
    }

    /** Role sets that never include the super-admin bypass role. */
    @Provide
    Arbitrary<Set<String>> nonSuperAdminRoles() {
        return Arbitraries.of("operator", "viewer", "ad_manager", "finance_clerk", "warehouse_staff")
                .set()
                .ofMinSize(0)
                .ofMaxSize(3);
    }

    /** Arbitrary non-super-admin roles to combine with the super_admin role. */
    @Provide
    Arbitrary<Set<String>> otherRoles() {
        return Arbitraries.of("operator", "viewer", "ad_manager", "finance_clerk")
                .set()
                .ofMinSize(0)
                .ofMaxSize(3);
    }
}
