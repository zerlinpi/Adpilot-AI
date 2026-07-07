// AdPilot AI — Advertising permission hook
//
// Bridges the pure advertising permission matrix (`canShowControl`) to the
// live permission set exposed by `PermissionProvider`. Components call this hook
// to decide whether an advertising action control is shown/enabled, driving
// visibility from the same matrix code the backend enforces (Requirement 27.7).
//
// Because the permission set is silently re-fetched on every navigation by the
// provider, the value returned here re-renders controls according to the
// updated permission set on the next fetch/navigation (Requirement 26.3).
//
// Validates: Requirements 26.1, 26.2, 26.3, 27.7

import { useCallback } from 'react';
import { usePermissions } from './PermissionContext';
import {
  canShowControl,
  permissionCodeForAction,
  type AdvertisingAction,
  type AdvertisingResource,
} from './advertisingPermissionMatrix';

export interface AdvertisingPermission {
  /**
   * Returns true when the logged-in user may see/use the control for the given
   * advertising (resource, action) per the Requirement 27.1 matrix. Backed by
   * the pure {@link canShowControl} predicate.
   */
  canAction: (resource: AdvertisingResource, action: AdvertisingAction) => boolean;
  /** The matrix permission code for a (resource, action), or null for N/A cells. */
  codeFor: (resource: AdvertisingResource, action: AdvertisingAction) => string | null;
  /** The user's aggregated permission set (for passing to pure helpers). */
  permissions: string[];
}

/**
 * Hook returning advertising permission predicates derived from the live
 * permission set. `canAction(resource, action)` is the single check components
 * use to gate advertising controls; a control whose `canAction` is false is
 * hidden or disabled and therefore never issues its backend request
 * (Requirement 26.4).
 */
export function useAdvertisingPermission(): AdvertisingPermission {
  const { permissions } = usePermissions();

  const canAction = useCallback(
    (resource: AdvertisingResource, action: AdvertisingAction) =>
      canShowControl(permissions, resource, action),
    [permissions],
  );

  const codeFor = useCallback(
    (resource: AdvertisingResource, action: AdvertisingAction) =>
      permissionCodeForAction(resource, action),
    [],
  );

  return { canAction, codeFor, permissions };
}

/**
 * Convenience hook for the common case of gating a single advertising control.
 * Returns true when the user may show/use the control for the given
 * (resource, action).
 */
export function useCanAdvertisingAction(
  resource: AdvertisingResource,
  action: AdvertisingAction,
): boolean {
  return useAdvertisingPermission().canAction(resource, action);
}
