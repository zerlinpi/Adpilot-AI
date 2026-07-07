package com.adpilot.common.utils;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
        // Utility class
    }

    /**
     * Reserved non-interactive actor identifier (Req 12.5).
     *
     * <p>Used as the acting-user attribution on audit records for operations that
     * legitimately run without an interactive user (scheduled jobs, background
     * triggers). It is the nil UUID, which never matches any authenticated user
     * identifier. Never use this for an authenticated request — resolve the real
     * user via {@link #getCurrentUserId()} instead.
     */
    public static final String SYSTEM_ACTOR_ID = "00000000-0000-0000-0000-000000000000";

    public static CurrentUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser;
        }
        throw new BusinessException("AUTH_001", "No authenticated user found");
    }

    public static String getCurrentUserId() {
        return getCurrentUser().getUserId();
    }

    /**
     * Returns the current authenticated user's id, or {@code null} when there is
     * no authenticated user. Use this for audit/created-by fields on actions that
     * may run without a user context, so callers never pass a non-UUID literal
     * (e.g. "system") into {@code UUID.fromString}.
     */
    public static String getCurrentUserIdOrNull() {
        return isAuthenticated() ? getCurrentUser().getUserId() : null;
    }

    public static String getCurrentUserEmail() {
        return getCurrentUser().getEmail();
    }

    public static String getCurrentOrgId() {
        return getCurrentUser().getOrgId();
    }

    /** Role codes that can see/manage ALL stores (no store-level scoping). */
    private static final java.util.Set<String> STORE_ADMIN_ROLES = java.util.Set.of(
            "super_admin", "general_manager", "operations_manager");

    /** True when the current user may access every store (admin-level). */
    public static boolean isStoreAdmin() {
        if (!isAuthenticated()) return false;
        java.util.Set<String> roles = getCurrentUser().getRoles();
        if (roles == null) return false;
        return roles.stream().anyMatch(STORE_ADMIN_ROLES::contains);
    }

    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof CurrentUser;
    }
}
