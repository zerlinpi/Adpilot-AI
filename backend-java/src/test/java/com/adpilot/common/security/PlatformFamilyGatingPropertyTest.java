package com.adpilot.common.security;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.entity.AccountPlatformAccessEntity;
import com.adpilot.modules.rbac.mapper.AccountPlatformAccessMapper;
import com.adpilot.modules.rbac.service.impl.PlatformAccessServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.aspectj.lang.ProceedingJoinPoint;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the backend platform-family gate enforced by
 * {@link PlatformAccessAspect} via the {@link RequirePlatform} annotation.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 9: 后端平台族门控.
 *
 * <p>Validates: Requirements 3.3, 5.5.
 *
 * <p>Property 9 states: for any backend operation protected by
 * {@code @RequirePlatform(family)} and any account, if the account's
 * {@code Platform_Access} does not contain that {@code family} the request is
 * rejected with HTTP 403; if it contains the family (or the account is a super
 * administrator) the operation is allowed. The decision is made server-side by
 * the aspect, so it holds independently of whether the frontend hides controls.
 *
 * <p>The test drives the real {@link PlatformAccessAspect} backed by the real
 * {@link PlatformAccessServiceImpl} (with a mocked
 * {@link AccountPlatformAccessMapper} standing in for the persisted
 * {@code account_platform_access} rows, mirroring the mock-mapper pattern in
 * {@code TableViewIsolationPropertyTest}). It generates an arbitrary
 * (granted Platform_Access set, required family, super-admin?) triple and asserts
 * that allow/deny matches family membership, with super-admin always allowed.
 */
class PlatformFamilyGatingPropertyTest {

    private static final Object PROCEED_SENTINEL = new Object();

    /**
     * Feature: multistore-ai-ads-operations, Property 9: 后端平台族门控.
     *
     * <p>Validates: Requirements 3.3, 5.5.
     *
     * <p>For an arbitrary account and required family: the aspect proceeds with the
     * protected method exactly when the account is a super administrator or its
     * granted {@code Platform_Access} contains the required family; otherwise it
     * rejects the request with HTTP 403 before the method body runs.
     */
    @Property(tries = 200)
    void requirePlatformAllowsOnMembershipOrSuperAdminAndRejectsOtherwiseWith403(
            @ForAll("grantedFamilies") Set<PlatformFamily> granted,
            @ForAll PlatformFamily required,
            @ForAll boolean superAdmin,
            @ForAll("nonSuperAdminRoles") Set<String> otherRoles) throws Throwable {

        UUID userId = UUID.randomUUID();
        Set<String> roles = new HashSet<>(otherRoles);
        if (superAdmin) {
            roles.add("super_admin");
        }
        CurrentUser user = CurrentUser.builder()
                .userId(userId.toString())
                .roles(roles)
                .build();

        // Real access service backed by a mocked mapper: the persisted grants are
        // exactly one row per granted family for the acting user.
        AccountPlatformAccessMapper mapper = Mockito.mock(AccountPlatformAccessMapper.class);
        List<AccountPlatformAccessEntity> rows = new ArrayList<>();
        for (PlatformFamily family : granted) {
            rows.add(grantRow(userId, family));
        }
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(rows);
        PlatformAccessServiceImpl accessService = new PlatformAccessServiceImpl(mapper);

        AuditService auditService = Mockito.mock(AuditService.class);
        PlatformAccessAspect aspect = new PlatformAccessAspect(accessService, auditService);

        RequirePlatform annotation = Mockito.mock(RequirePlatform.class);
        when(annotation.value()).thenReturn(required);

        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(PROCEED_SENTINEL);

        boolean expectedAllowed = superAdmin || granted.contains(required);

        Throwable thrown;
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(user);
            thrown = catchThrowable(() -> {
                Object result = aspect.enforce(joinPoint, annotation);
                // On allow the aspect returns whatever the protected method returned.
                assertThat(result).isSameAs(PROCEED_SENTINEL);
            });
        }

        if (expectedAllowed) {
            assertThat(thrown)
                    .as("granted=%s required=%s superAdmin=%s should be allowed", granted, required, superAdmin)
                    .isNull();
            // The protected method body actually executed.
            verify(joinPoint, times(1)).proceed();
        } else {
            // Out-of-scope family is rejected with HTTP 403 before the body runs.
            assertThat(thrown)
                    .as("granted=%s required=%s superAdmin=%s should be denied", granted, required, superAdmin)
                    .isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(403);
            verify(joinPoint, never()).proceed();
        }
    }

    // --- helpers -----------------------------------------------------------

    private static AccountPlatformAccessEntity grantRow(UUID userId, PlatformFamily family) {
        return AccountPlatformAccessEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .platformFamily(family.getCode())
                .build();
    }

    // --- generators --------------------------------------------------------

    /** An arbitrary subset (possibly empty, up to all families) of Platform_Access. */
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
}
