package com.adpilot.modules.rbac.service;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.rbac.PlatformFamily;

import java.util.Set;

/**
 * The Platform_Access RBAC dimension service: which of the four Nav_Blocks
 * ({@code amazon} / {@code independent_site} / {@code logistics} / {@code finance})
 * an account may enter (platform-workspace-rbac Requirement 12).
 *
 * <p>Resolves an account's accessible platform families from the
 * {@code account_platform_access} rows persisted in task 2.1, with the existing
 * super-administrator role bypassing all restrictions and gaining every family
 * (Req 15.4). Consulted by {@code PlatformAccessAspect} to reject
 * Cross_Platform_Access with HTTP 403 before the protected method body executes
 * (Req 12.3, 12.4, 16.3).</p>
 */
public interface PlatformAccessService {

    /** The platform families this account may enter. Super-admin =&gt; all four. */
    Set<PlatformFamily> accessibleFamilies(CurrentUser user);

    /**
     * Whether the account may enter the given platform family.
     *
     * @param user   the requesting account; super-admin always passes (Req 15.4)
     * @param family the platform family being entered or operated on
     * @return {@code true} when the account's Platform_Access includes the family
     */
    boolean hasAccess(CurrentUser user, PlatformFamily family);
}
