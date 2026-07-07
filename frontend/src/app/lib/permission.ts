// AdPilot AI — Permission Utilities
// Permission format: "module:action" (e.g., "product:view", "advertising:manage")

interface RolePermission {
  code: string;
  permissions?: string[];
}

// ─── Permission Checking ──────────────────────────────────────────────────────

export function hasPermission(user: any, permission: string): boolean {
  if (!user) return false;

  // Check direct permissions on user
  if (Array.isArray(user.permissions) && user.permissions.includes(permission)) {
    return true;
  }

  // Check permissions from roles
  if (Array.isArray(user.roles)) {
    for (const role of user.roles) {
      if (Array.isArray(role.permissions) && role.permissions.includes(permission)) {
        return true;
      }
    }
  }

  return false;
}

export function hasAnyPermission(user: any, permissions: string[]): boolean {
  return permissions.some((p) => hasPermission(user, p));
}

export function hasAllPermissions(user: any, permissions: string[]): boolean {
  return permissions.every((p) => hasPermission(user, p));
}

export function hasRole(user: any, roleCode: string): boolean {
  if (!user || !Array.isArray(user.roles)) return false;
  return user.roles.some((r: any) => r.code === roleCode);
}

export function hasAnyRole(user: any, roleCodes: string[]): boolean {
  return roleCodes.some((code) => hasRole(user, code));
}

/**
 * True when the account is a Super_Administrator. The backend surfaces the
 * primary role as a single `role` string (which prefers `super_admin` when
 * present); some callers also carry a `roles` array. Check both so the
 * Super_Administrator bypass holds regardless of shape
 * (platform-workspace-rbac Req 15.3, 15.4).
 */
export function isSuperAdmin(user: any): boolean {
  if (!user) return false;
  if (user.role === 'super_admin') return true;
  return hasRole(user, 'super_admin');
}

// ─── Permission Aggregation ───────────────────────────────────────────────────

export function getMenuPermissions(user: any): string[] {
  if (!user) return [];

  const perms = new Set<string>();

  // Direct user permissions
  if (Array.isArray(user.permissions)) {
    user.permissions.forEach((p: string) => perms.add(p));
  }

  // Role-based permissions
  if (Array.isArray(user.roles)) {
    for (const role of user.roles) {
      if (Array.isArray(role.permissions)) {
        role.permissions.forEach((p: string) => perms.add(p));
      }
    }
  }

  return Array.from(perms);
}

export function getButtonPermissions(user: any): string[] {
  return getMenuPermissions(user);
}
