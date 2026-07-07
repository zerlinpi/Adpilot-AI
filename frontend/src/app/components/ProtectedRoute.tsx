import { ReactNode } from 'react';
import { Navigate } from 'react-router';
import { usePermissions } from '../lib/PermissionContext';
import { Zap } from 'lucide-react';

interface ProtectedRouteProps {
  /** The permission code (e.g. "order:view") the user must hold to access the route. */
  permission: string;
  children: ReactNode;
}

/**
 * Route guard that blocks direct navigation to a route the logged-in user is not
 * permitted to access and shows an access-denied indication (Req 3.1.4).
 *
 * It relies on the permission set exposed by {@link PermissionProvider}, so it must
 * be rendered inside the provider (i.e. within the authenticated route tree). While
 * the first permission fetch is still in flight and no cached permission set is
 * available yet, it shows a lightweight loader so it does not redirect before the
 * user's permissions are known. When the user lacks the required permission it
 * redirects to the `/403` access-denied page.
 */
export function ProtectedRoute({ permission, children }: ProtectedRouteProps) {
  const { can, loading, user } = usePermissions();

  // Avoid a premature redirect: if we have no permission set yet and are still
  // loading the first /me response, wait rather than deny access.
  if (loading && !user) {
    return (
      <div className="flex h-full min-h-[60vh] items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-10 h-10 bg-gradient-to-br from-blue-600 to-indigo-600 rounded-lg flex items-center justify-center shadow-sm animate-pulse">
            <Zap size={20} className="text-white" />
          </div>
          <p className="text-sm text-slate-400">加载中...</p>
        </div>
      </div>
    );
  }

  if (!can(permission)) {
    return <Navigate to="/403" replace />;
  }

  return <>{children}</>;
}
