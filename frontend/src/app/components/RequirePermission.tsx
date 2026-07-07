import { cloneElement, isValidElement, ReactNode } from 'react';
import { usePermissions } from '../lib/PermissionContext';
import { isActionAllowed } from '../lib/navVisibility';

interface RequirePermissionProps {
  /** A single required permission (e.g. "campaign:update"). */
  permission?: string;
  /** Render the control when the user holds at least one of these permissions. */
  anyOf?: string[];
  /** Render the control when the user holds all of these permissions. */
  allOf?: string[];
  /**
   * How to treat a control the user is not permitted to use:
   * - `hide` (default): the control is not rendered at all (or `fallback` is shown).
   * - `disable`: the control is rendered but disabled and non-interactive.
   */
  mode?: 'hide' | 'disable';
  /** Optional element to render instead when access is denied and `mode` is `hide`. */
  fallback?: ReactNode;
  children: ReactNode;
}

/**
 * Gates an action control (button, menu item, link, etc.) on the logged-in
 * user's permission set (Req 3.1.2). When the user lacks the required
 * permission the control is hidden by default, or disabled when
 * `mode="disable"` is set, so a control is never both visible and usable
 * without the backing permission.
 *
 * Provide exactly one of `permission`, `anyOf`, or `allOf`. When none is
 * provided the control is always rendered (no requirement).
 *
 * @example
 * <RequirePermission permission="campaign:update">
 *   <button onClick={save}>保存</button>
 * </RequirePermission>
 *
 * @example
 * <RequirePermission permission="campaign:update" mode="disable">
 *   <button onClick={save}>保存</button>
 * </RequirePermission>
 */
export function RequirePermission({
  permission,
  anyOf,
  allOf,
  mode = 'hide',
  fallback,
  children,
}: RequirePermissionProps) {
  const { can } = usePermissions();

  const allowed = isActionAllowed({ permission, anyOf, allOf }, can);

  if (allowed) {
    return <>{children}</>;
  }

  if (mode === 'disable') {
    return disableChildren(children);
  }

  return fallback ? <>{fallback}</> : null;
}

/**
 * Renders the child element(s) in a disabled, non-interactive state. For a
 * single element we set `disabled`/`aria-disabled` and suppress pointer events;
 * for fragments or multiple children we wrap them so they can't be activated.
 */
function disableChildren(children: ReactNode): ReactNode {
  if (isValidElement(children)) {
    const child = children as React.ReactElement<any>;
    return cloneElement(child, {
      disabled: true,
      'aria-disabled': true,
      tabIndex: -1,
      className: [child.props.className, 'opacity-50 cursor-not-allowed pointer-events-none']
        .filter(Boolean)
        .join(' '),
    });
  }

  return (
    <span
      aria-disabled
      className="opacity-50 cursor-not-allowed pointer-events-none"
    >
      {children}
    </span>
  );
}
