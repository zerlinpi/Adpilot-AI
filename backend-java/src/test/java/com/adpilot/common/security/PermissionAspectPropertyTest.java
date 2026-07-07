package com.adpilot.common.security;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.enums.ResultCode;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link PermissionAspect}, the around advice that
 * enforces {@link RequirePermission} using the existing {@link PermissionChecker}
 * and audits every decision via {@link AuditService} (tasks 7.2, 8.1).
 *
 * Feature: core-platform-completion, Property 9: Authorization permits exactly
 * authorized callers and audits every decision.
 *
 * <p>For any authenticated user (any combination of roles and granted
 * permissions) and any required operation, the aspect proceeds iff the user
 * holds the required permission OR holds the super-administrator role
 * (Req 2.1.1, 2.1.4); otherwise it rejects the request with HTTP 403
 * (Req 2.1.2). In every case — permit or deny — exactly one audit entry
 * recording the user identity and the requested operation is written
 * (Req 2.1.5).
 *
 * <p>The real {@link PermissionChecker} is exercised against the live security
 * context; the {@link AuditService} is mocked so the test can assert exactly one
 * audit write per authorization decision.
 *
 * Validates: Requirements 2.1.1, 2.1.2, 2.1.4, 2.1.5
 */
class PermissionAspectPropertyTest {

    private static final String SUPER_ADMIN = "super_admin";

    /** The non-admin permission codes a user may be granted / may be required. */
    private static final List<String> PERMISSION_UNIVERSE = List.of(
            "campaign:update", "campaign:delete", "order:read",
            "product:write", "report:view", "store:manage");

    /** Non-admin role codes that carry no implicit bypass. */
    private static final List<String> PLAIN_ROLES = List.of(
            "operator", "manager", "viewer", "analyst");

    // Feature: core-platform-completion, Property 9: Authorization permits exactly authorized callers and audits every decision
    @Property(tries = 200)
    void permitsAuthorizedCallersDeniesOthersAndAuditsEveryDecision(
            @ForAll("roleSets") Set<String> roles,
            @ForAll("permissionSets") List<String> permissions,
            @ForAll("operations") String operation) throws Throwable {

        String userId = UUID.randomUUID().toString();
        CurrentUser user = CurrentUser.builder()
                .userId(userId)
                .orgId(UUID.randomUUID().toString())
                .email("user@example.com")
                .roles(roles)
                .permissions(permissions)
                .build();

        // Expected decision per Req 2.1.1 / 2.1.4: explicit grant OR super-admin bypass.
        boolean expectedPermit = roles.contains(SUPER_ADMIN) || permissions.contains(operation);

        AuditService auditService = Mockito.mock(AuditService.class);
        PermissionChecker checker = new PermissionChecker();
        PermissionAspect aspect = new PermissionAspect(checker, auditService);

        RequirePermission annotation = Mockito.mock(RequirePermission.class);
        when(annotation.value()).thenReturn(operation);

        Object proceedResult = new Object();
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(proceedResult);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        try {
            if (expectedPermit) {
                // Authorized: the guarded method runs and its result is returned (Req 2.1.1, 2.1.4).
                Object result = aspect.enforce(joinPoint, annotation);

                assertThat(result).isSameAs(proceedResult);
                verify(joinPoint, times(1)).proceed();
                // Exactly the permit decision is audited with the requested operation (Req 2.1.5).
                verify(auditService, times(1)).recordPermit(operation);
                verify(auditService, never()).recordDeny(anyString());
            } else {
                // Unauthorized: rejected with HTTP 403 and the method never runs (Req 2.1.2).
                assertThatThrownBy(() -> aspect.enforce(joinPoint, annotation))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                                .isEqualTo(ResultCode.FORBIDDEN.getCode()));

                verify(joinPoint, never()).proceed();
                // Exactly the deny decision is audited with the requested operation (Req 2.1.5).
                verify(auditService, times(1)).recordDeny(operation);
                verify(auditService, never()).recordPermit(anyString());
            }

            // In all cases exactly one audit entry is written for the decision (Req 2.1.5).
            long auditWrites = Mockito.mockingDetails(auditService).getInvocations().size();
            assertThat(auditWrites).isEqualTo(1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // --- generators -----------------------------------------------------------

    /**
     * Role sets drawn from the plain roles plus an independent chance of holding
     * the super-administrator role, so both the bypass and non-bypass branches
     * (Req 2.1.4) are exercised. Always non-empty-safe: {@link PermissionChecker}
     * requires a non-null role set.
     */
    @Provide
    Arbitrary<Set<String>> roleSets() {
        Arbitrary<Set<String>> plain = Arbitraries.of(PLAIN_ROLES).set().ofMaxSize(3);
        Arbitrary<Boolean> isSuperAdmin = Arbitraries.of(true, false);
        return Combinators.combine(plain, isSuperAdmin).as((base, admin) -> {
            java.util.Set<String> result = new java.util.HashSet<>(base);
            if (admin) {
                result.add(SUPER_ADMIN);
            }
            return result;
        });
    }

    /**
     * Permission grants drawn from the same universe as the required operation,
     * so a generated operation is sometimes held and sometimes not — covering
     * both permit (Req 2.1.1) and deny (Req 2.1.2) outcomes for non-admins.
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
