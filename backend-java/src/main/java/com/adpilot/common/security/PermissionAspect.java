package com.adpilot.common.security;

import com.adpilot.common.enums.ResultCode;
import com.adpilot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Enforces {@link RequirePermission} on annotated methods (Req 2.1).
 *
 * <p>The around advice consults the existing {@link PermissionChecker}, which
 * grants the super administrator role without an explicit permission
 * (Req 2.1.4). When the caller holds the required permission the method
 * proceeds; otherwise the request is rejected with HTTP 403 (Req 2.1.2). In
 * both cases an audit entry recording the user identity and requested operation
 * is written via {@link AuditService} (Req 2.1.5).
 *
 * <p>Missing or invalid authentication on a protected route is handled earlier
 * by {@code JwtAuthFilter} / {@code SecurityConfig}, which return HTTP 401
 * (Req 2.1.3) before this aspect is reached.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final PermissionChecker permissionChecker;
    private final AuditService auditService;

    @Around("@annotation(requirePermission)")
    public Object enforce(ProceedingJoinPoint joinPoint, RequirePermission requirePermission)
            throws Throwable {

        String operation = requirePermission.value();

        boolean permitted = permissionChecker.hasPermission(operation);

        if (!permitted) {
            // Record the denied decision before rejecting (Req 2.1.5).
            auditService.recordDeny(operation);
            log.warn("Authorization denied for operation '{}'", operation);
            throw new BusinessException(
                    ResultCode.FORBIDDEN.getCode(),
                    String.valueOf(ResultCode.PERMISSION_DENIED.getCode()),
                    "Permission denied: " + operation);
        }

        // Record the permitted decision before proceeding (Req 2.1.5).
        auditService.recordPermit(operation);
        return joinPoint.proceed();
    }
}
