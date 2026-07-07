package com.adpilot.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller/service method as a sensitive action that requires the
 * caller to have recently re-confirmed their identity before it executes
 * (Req 11.2.3).
 *
 * <p>Enforced by {@link SensitiveActionAspect}: if the current user has no live
 * re-confirmation marker, the action is rejected with HTTP 428 (Precondition
 * Required) and the client must prompt the user to re-confirm via
 * {@code POST /api/auth/reconfirm}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresReconfirmation {

    /** Optional human-readable label of the action, used in the audit/log message. */
    String value() default "";
}
