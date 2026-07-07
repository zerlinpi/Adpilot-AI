import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useMemo,
  useRef,
  ReactNode,
} from 'react';
import { useLocation } from 'react-router';
import { fetchCurrentUser, type CurrentUserInfo } from './api';
import { getCurrentUser, setCurrentUser } from './auth';
import { hasPermission, hasAnyPermission, hasAllPermissions } from './permission';
import { derivePlatformAccess } from './navConfig';
import type { PlatformAccess } from './navVisibility';

// `derivePlatformAccess` now lives alongside the navigation visibility logic in
// `navConfig` so the Platform_Access derivation and the `visibleNavBlocks`
// projection share one source of truth. Re-exported here for existing callers.
export { derivePlatformAccess };

interface PermissionContextValue {
  /** The logged-in user including the permission set, or null while loading / unauthenticated. */
  user: CurrentUserInfo | null;
  /** The aggregated permission set for the logged-in user. */
  permissions: string[];
  /** The account's Platform_Access dimension — which Nav_Blocks it may enter,
   *  plus the Super_Administrator bypass (platform-workspace-rbac Req 12.2). */
  platformAccess: PlatformAccess;
  /** The account's Store_Group_Scope — the Store_Group ids whose stores it may
   *  see, or null when unrestricted (super-admin / not yet surfaced; Req 13.5). */
  storeGroupScope: string[] | null;
  /** True until the first permission fetch resolves. */
  loading: boolean;
  /** Last fetch error message, if any. */
  error: string | null;
  /** Returns true when the logged-in user holds the given permission. */
  can: (permission: string) => boolean;
  /** Returns true when the user holds at least one of the given permissions. */
  canAny: (permissions: string[]) => boolean;
  /** Returns true when the user holds all of the given permissions. */
  canAll: (permissions: string[]) => boolean;
  /** Re-fetches the permission set from the server (e.g. to pick up a role change). */
  refresh: () => Promise<void>;
}

const PermissionContext = createContext<PermissionContextValue | undefined>(undefined);

/**
 * Loads the logged-in user's permission set from `GET /api/auth/me` after login
 * (Req 3.1.3) and exposes `can(permission)` for permission-driven UX. The set is
 * silently re-fetched on every navigation so a permission change takes effect on
 * the next navigation without requiring the user to log out and back in
 * (Req 3.1.5).
 *
 * Must be rendered inside the router (it relies on navigation) and behind the
 * auth guard so a token is present when it fetches.
 */
export function PermissionProvider({ children }: { children: ReactNode }) {
  // Seed from the cached user (written at login) so the UI has a permission set
  // to work with before the first /me fetch resolves.
  const [user, setUser] = useState<CurrentUserInfo | null>(() => getCurrentUser());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const location = useLocation();

  // Guards against overlapping fetches when navigation fires rapidly.
  const inFlight = useRef(false);

  const load = useCallback(async (silent: boolean) => {
    if (inFlight.current) return;
    inFlight.current = true;
    if (!silent) setLoading(true);
    try {
      const fresh = await fetchCurrentUser();
      setUser(fresh);
      // Keep the cached copy in sync so non-context consumers (e.g. PermissionGuard)
      // also see the refreshed permission set.
      setCurrentUser(fresh);
      setError(null);
    } catch (err: any) {
      // authFetch redirects to /login on 401 (auth expiry); 403 and other errors
      // surface here, so we keep the previously loaded permission set rather than
      // locking the user out.
      setError(err?.message || '加载权限失败');
    } finally {
      setLoading(false);
      inFlight.current = false;
    }
  }, []);

  // Initial load after login.
  useEffect(() => {
    void load(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Lightweight re-fetch on navigation to refresh permissions without logout.
  useEffect(() => {
    void load(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname]);

  const permissions = useMemo(() => user?.permissions ?? [], [user]);

  // Derive the Platform_Access and Store_Group_Scope dimensions from the latest
  // fetched user so the navigation re-renders on the next permission fetch when
  // a dimension changes, without requiring re-login (Req 12.5).
  const platformAccess = useMemo(() => derivePlatformAccess(user), [user]);
  const storeGroupScope = useMemo(() => user?.storeGroupScope ?? null, [user]);

  const can = useCallback((permission: string) => hasPermission(user, permission), [user]);
  const canAny = useCallback((perms: string[]) => hasAnyPermission(user, perms), [user]);
  const canAll = useCallback((perms: string[]) => hasAllPermissions(user, perms), [user]);
  const refresh = useCallback(() => load(false), [load]);

  const value = useMemo<PermissionContextValue>(
    () => ({
      user,
      permissions,
      platformAccess,
      storeGroupScope,
      loading,
      error,
      can,
      canAny,
      canAll,
      refresh,
    }),
    [user, permissions, platformAccess, storeGroupScope, loading, error, can, canAny, canAll, refresh],
  );

  return (
    <PermissionContext.Provider value={value}>
      {children}
    </PermissionContext.Provider>
  );
}

/**
 * Access the permission context. Falls back to the cached user when used outside
 * a provider so callers degrade gracefully instead of throwing.
 */
export function usePermissions(): PermissionContextValue {
  const ctx = useContext(PermissionContext);
  if (!ctx) {
    const cached = getCurrentUser();
    return {
      user: cached,
      permissions: cached?.permissions ?? [],
      platformAccess: derivePlatformAccess(cached),
      storeGroupScope: cached?.storeGroupScope ?? null,
      loading: false,
      error: null,
      can: (permission: string) => hasPermission(cached, permission),
      canAny: (perms: string[]) => hasAnyPermission(cached, perms),
      canAll: (perms: string[]) => hasAllPermissions(cached, perms),
      refresh: async () => { },
    };
  }
  return ctx;
}

/** Convenience hook for the common case of checking a single permission. */
export function useCan(permission: string): boolean {
  return usePermissions().can(permission);
}
