import { getCurrentUser } from '../lib/auth';
import { hasPermission } from '../lib/permission';

interface PermissionGuardProps {
  permission: string;
  children: React.ReactNode;
  fallback?: React.ReactNode;
}

export function PermissionGuard({ permission, children, fallback }: PermissionGuardProps) {
  const user = getCurrentUser();

  if (!hasPermission(user, permission)) {
    return fallback ? <>{fallback}</> : null;
  }

  return <>{children}</>;
}
