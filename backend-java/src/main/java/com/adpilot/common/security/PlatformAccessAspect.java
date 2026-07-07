package com.adpilot.common.security;

import com.adpilot.common.enums.ResultCode;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.service.PlatformAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Enforces {@link RequirePlatform} on annotated methods (platform-workspace-rbac
 * Requirement 12).
 *
 * <p>The around advice consults {@link PlatformAccessService}, which grants the
 * super administrator role every platform family without an explicit grant
 * (Req 15.4). When the caller's Platform_Access includes the required family the
 * method proceeds; otherwise the request is rejected with HTTP 403 before the
 * method body executes (Cross_Platform_Access, Req 12.3, 12.4, 16.3). The denial
 * raises the same 403 {@link BusinessException} used by {@link PermissionAspect},
 * and both decisions are recorded via {@link AuditService}.</p>
 *
 * <p>Missing or invalid authentication on a protected route is handled earlier by
 * {@code JwtAuthFilter} / {@code SecurityConfig}, which return HTTP 401 before
 * this aspect is reached.</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PlatformAccessAspect {

    private final PlatformAccessService platformAccessService;
    private final AuditService auditService;

    @Around("@annotation(requirePlatform)")
    public Object enforce(ProceedingJoinPoint joinPoint, RequirePlatform requirePlatform)
            throws Throwable {

        PlatformFamily family = requirePlatform.value();
        String operation = "platform:" + family.getCode();

        CurrentUser user = SecurityUtils.getCurrentUser();
        boolean permitted = platformAccessService.hasAccess(user, family);

        if (!permitted) {
            // Record the denied decision before rejecting.
            auditService.recordDeny(operation);
            log.warn("Cross-platform access denied for family '{}'", family.getCode());
            throw new BusinessException(
                    ResultCode.FORBIDDEN.getCode(),
                    String.valueOf(ResultCode.PERMISSION_DENIED.getCode()),
                    "Platform access denied: " + family.getCode());
        }

        // Record the permitted decision before proceeding.
        auditService.recordPermit(operation);
        return joinPoint.proceed();
    }
}
