package com.adpilot.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission required to invoke the annotated operation.
 *
 * <p>Read by {@link PermissionAspect}, which performs an authorization check
 * before the method executes (Req 2.1.1). The check reuses the existing
 * {@link PermissionChecker}, so a user holding the super administrator role is
 * authorized without an explicit permission grant (Req 2.1.4). When the
 * requesting user does not hold the required permission, the request is rejected
 * with HTTP 403 (Req 2.1.2). Every decision — permit or deny — is recorded for
 * audit (Req 2.1.5).
 *
 * <p>Apply to controller or service methods, for example:
 * <pre>{@code
 * @RequirePermission("campaign:update")
 * public CampaignVo update(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * The permission code required to invoke the annotated operation
     * (for example, {@code "campaign:update"}).
     *
     * @return the required permission code
     */
    String value();
}
