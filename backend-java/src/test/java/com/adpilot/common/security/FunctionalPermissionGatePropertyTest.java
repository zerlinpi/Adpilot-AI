package com.adpilot.common.security;

import com.adpilot.common.enums.ResultCode;
import com.adpilot.common.exception.BusinessException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.aspectj.lang.ProceedingJoinPoint;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the functional-permission gate enforced by
 * {@link PermissionAspect} / {@link PermissionChecker} on every operation
 * annotated with {@link RequirePermission}.
 *
 * <p>Feature: platform-workspace-rbac, Property 6: Functional-permission gate.
 *
 * <p>Validates: Requirements 14.4, 14.5, 14.2, 7.5.
 *
 * <p>Property 6: For any operation requiring a {@code module:action} permission
 * and any account, the operation executes if and only if the account holds that
 * permission (or is a Super_Administrator); otherwise the system rejects it with
 * HTTP 403 and creates no Operation. This holds for the platform-specific
 * advertising permissions, so an account holding only one platform family's
 * advertising permission is rejected when operating on the other family's
 * advertising.
 *
 * <p>The aspect's {@code joinPoint.proceed()} models the guarded operation
 * executing (e.g. creating an advertising Operation). The gate is proven by
 * asserting {@code proceed()} runs exactly when authorized and never when denied,
 * so a rejected request creates no Operation (Req 7.5).
 *
 * <p>The real {@link PermissionChecker} is exercised against the live security
 * context; only the cross-cutting {@link AuditService} and the join point are
 * mocked, so no production authorization logic is stubbed away.
 */
class FunctionalPermissionGatePropertyTest {

    private static final String SUPER_ADMIN = "super_admin";

    /** Distinct platform-family advertising permissions (Req 14.2). */
    private static final String AMAZON_ADS = "advertising:amazon:operate";
    private static final String INDEPENDENT_ADS = "advertising:independent_site:operate";

    /**
     * The {@code module:action} permission universe (Req 14.1), including the two
     * distinct advertising-family permissions plus non-advertising functional
     * permissions that are assignable independently (Req 14.3).
     */
    private static final List<String> PERMISSION_UNIVERSE = List.of(
            AMAZON_ADS, INDEPENDENT_ADS,
            "finance:settle", "warehouse:manage", "product:write", "customer:reply");

    /** Non-admin role codes that carry no implicit bypass. */
    private static final List<String> PLAIN_ROLES = List.of(
            "operator", "manager", "viewer", "analyst");

    /**
     * Feature: platform-workspace-rbac, Property 6: Functional-permission gate.
     *
     * <p>Validates: Requirements 14.4, 14.5, 14.2, 7.5.
     *
     * <p>For any account (any roles + granted permissions) and any required
     * {@code module:action} permission, the guarded operation executes iff the
     * account holds the permission or is a Super_Administrator (Req 14.4); a
     * denied request is rejected with HTTP 403 and the operation never runs, so
     * no Operation is created (Req 14.5, 7.5).
     */
    @Property(tries = 200)
    void operationExecutesIffAccountHoldsPermissionOrIsSuperAdmin(
            @ForAll("roleSets") Set<String> roles,
            @ForAll("permissionSets") List<String> permissions,
            @ForAll("operations") String requiredPermission) throws Throwable {

        boolean expectedPermit = roles.contains(SUPER_ADMIN) || permissions.contains(requiredPermission);

        assertGateBehaviour(roles, permissions, requiredPermission, expectedPermit);
    }

    /**
     * Feature: platform-workspace-rbac, Property 6: Functional-permission gate.
     *
     * <p>Validates: Requirements 14.4, 14.5, 14.2, 7.5.
     *
     * <p>The platform-specific advertising permissions are distinct (Req 14.2):
     * a non-admin account holding exactly one advertising family's permission is
     * permitted to operate on that family's advertising but rejected with HTTP
     * 403 (creating no Operation) when operating on the other family's
     * advertising.
     */
    @Property(tries = 200)
    void advertisingPermissionIsScopedToItsPlatformFamily(
            @ForAll("roleSets") Set<String> baseRoles,
            @ForAll boolean grantAmazon) throws Throwable {

        // A non-admin account so the gate (not the super-admin bypass) decides.
        Set<String> roles = new HashSet<>(baseRoles);
        roles.remove(SUPER_ADMIN);

        String held = grantAmazon ? AMAZON_ADS : INDEPENDENT_ADS;
        String otherFamily = grantAmazon ? INDEPENDENT_ADS : AMAZON_ADS;
        List<String> permissions = List.of(held);

        // Operating on the held family's advertising is permitted.
        assertGateBehaviour(roles, permissions, held, true);

        // Operating on the other family's advertising is rejected (Req 14.2, 14.5).
        assertGateBehaviour(roles, permissions, otherFamily, false);
    }

    /**
     * Drive {@link PermissionAspect#enforce} once and assert the gate decision:
     * when {@code expectedPermit} the guarded operation runs and returns its
     * result; otherwise an HTTP 403 {@link BusinessException} is thrown and the
     * operation never runs (so no Operation is created).
     */
    private void assertGateBehaviour(Set<String> roles,
                                     List<String> permissions,
                                     String requiredPermission,
                                     boolean expectedPermit) throws Throwable {

        CurrentUser user = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .orgId(UUID.randomUUID().toString())
                .email("user@example.com")
                .roles(roles)
                .permissions(permissions)
                .build();

        AuditService auditService = Mockito.mock(AuditService.class);
        PermissionChecker checker = new PermissionChecker();
        PermissionAspect aspect = new PermissionAspect(checker, auditService);

        RequirePermission annotation = Mockito.mock(RequirePermission.class);
        when(annotation.value()).thenReturn(requiredPermission);

        Object proceedResult = new Object();
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(proceedResult);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        try {
            if (expectedPermit) {
                Object result = aspect.enforce(joinPoint, annotation);

                assertThat(result).isSameAs(proceedResult);
                // The guarded operation executed exactly once (Req 14.4).
                verify(joinPoint, times(1)).proceed();
            } else {
                assertThatThrownBy(() -> aspect.enforce(joinPoint, annotation))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                                .isEqualTo(ResultCode.FORBIDDEN.getCode()));

                // Denied: the operation never ran, so no Operation is created (Req 14.5, 7.5).
                verify(joinPoint, never()).proceed();
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // --- generators -----------------------------------------------------------

    /**
     * Role sets drawn from the plain roles plus an independent chance of holding
     * the super-administrator role, exercising both the bypass and gate branches
     * (Req 14.4). {@link PermissionChecker} requires a non-null role set.
     */
    @Provide
    Arbitrary<Set<String>> roleSets() {
        Arbitrary<Set<String>> plain = Arbitraries.of(PLAIN_ROLES).set().ofMaxSize(3);
        Arbitrary<Boolean> isSuperAdmin = Arbitraries.of(true, false);
        return Combinators.combine(plain, isSuperAdmin).as((base, admin) -> {
            Set<String> result = new HashSet<>(base);
            if (admin) {
                result.add(SUPER_ADMIN);
            }
            return result;
        });
    }

    /**
     * Permission grants drawn from the same universe as the required operation,
     * so a generated operation is sometimes held and sometimes not — covering
     * both permit (Req 14.4) and deny (Req 14.5) outcomes for non-admins.
     */
    @Provide
    Arbitrary<List<String>> permissionSets() {
        return Arbitraries.of(PERMISSION_UNIVERSE).list().ofMaxSize(4);
    }

    @Provide
    Arbitrary<String> operations() {
        return Arbitraries.of(PERMISSION_UNIVERSE);
    }
}
