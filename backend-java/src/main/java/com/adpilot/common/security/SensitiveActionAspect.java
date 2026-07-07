package com.adpilot.common.security;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.auth.service.ReconfirmationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Enforces {@link RequiresReconfirmation} on annotated methods (Req 11.2.3).
 *
 * <p>Before a sensitive action runs, the aspect verifies that the current user
 * holds a live identity re-confirmation marker. When present, the action
 * proceeds; otherwise it is rejected with HTTP 428 (Precondition Required) so
 * the client can prompt the user to re-confirm. The marker is consumed on use,
 * so each sensitive action requires its own deliberate re-confirmation.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class SensitiveActionAspect {

    private static final int PRECONDITION_REQUIRED = 428;
    private static final String CODE_RECONFIRM_REQUIRED = "RECONFIRMATION_REQUIRED";

    private final ReconfirmationService reconfirmationService;

    @Around("@annotation(requiresReconfirmation)")
    public Object enforce(ProceedingJoinPoint joinPoint, RequiresReconfirmation requiresReconfirmation)
            throws Throwable {

        String userId = SecurityUtils.getCurrentUserId();
        String action = requiresReconfirmation.value();

        if (!reconfirmationService.isConfirmed(userId)) {
            log.warn("Sensitive action '{}' rejected: identity re-confirmation required", action);
            throw new BusinessException(
                    PRECONDITION_REQUIRED,
                    CODE_RECONFIRM_REQUIRED,
                    "Identity re-confirmation required before performing this action");
        }

        // Consume the marker so each sensitive action is individually confirmed.
        reconfirmationService.clear(userId);
        return joinPoint.proceed();
    }
}
