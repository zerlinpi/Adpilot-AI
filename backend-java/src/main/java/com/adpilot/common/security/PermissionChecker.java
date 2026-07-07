package com.adpilot.common.security;

import com.adpilot.common.utils.SecurityUtils;
import org.springframework.stereotype.Component;

@Component
public class PermissionChecker {

    public boolean hasPermission(String permission) {
        CurrentUser user = SecurityUtils.getCurrentUser();
        if (user == null) return false;
        if (user.getRoles().contains("super_admin")) return true;
        return user.getPermissions() != null && user.getPermissions().contains(permission);
    }

    public boolean hasAnyPermission(String... permissions) {
        for (String p : permissions) {
            if (hasPermission(p)) return true;
        }
        return false;
    }

    public boolean hasRole(String role) {
        CurrentUser user = SecurityUtils.getCurrentUser();
        if (user == null) return false;
        return user.getRoles().contains(role);
    }

    public boolean isSuperAdmin() {
        return hasRole("super_admin");
    }
}
