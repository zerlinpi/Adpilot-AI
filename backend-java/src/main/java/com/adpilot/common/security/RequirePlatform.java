package com.adpilot.common.security;

import com.adpilot.modules.rbac.PlatformFamily;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the Platform_Access family required to invoke the annotated operation
 * (platform-workspace-rbac Requirement 12).
 *
 * <p>Read by {@link PlatformAccessAspect}, which performs a platform-access check
 * before the method executes. The check reuses
 * {@code PlatformAccessService.hasAccess}, so a user holding the super
 * administrator role is authorized without an explicit Platform_Access grant
 * (Req 15.4). When the requesting account's Platform_Access does not include the
 * required family (Cross_Platform_Access), the request is rejected with HTTP 403
 * before the method body executes (Req 12.3, 12.4, 16.3).</p>
 *
 * <p>Mirrors {@link RequirePermission}. Apply to controller or service methods,
 * for example:
 * <pre>{@code
 * @RequirePlatform(PlatformFamily.AMAZON)
 * public CampaignVo update(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePlatform {

    /**
     * The platform family the caller must be able to enter
     * (for example, {@link PlatformFamily#AMAZON}).
     *
     * @return the required platform family
     */
    PlatformFamily value();
}
