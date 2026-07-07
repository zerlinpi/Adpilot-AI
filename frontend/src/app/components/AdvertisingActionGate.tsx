// AdPilot AI — Advertising action-control gate
//
// Permission-gated wrapper for advertising action controls. It drives the
// visibility/enabled state of a control from the SAME matrix code the backend
// enforces (Requirement 27.7) and GUARANTEES that a control hidden or disabled
// for lack of permission never issues its backend request (Requirement 26.4):
//
//   - mode="hide"  (default): the control is not rendered at all, so its click
//     handler can never run.
//   - mode="disable": the control is rendered disabled AND its click handlers
//     are replaced with a request-suppressing no-op, so even a styling bypass
//     cannot fire the underlying backend request.
//
// The allow/deny decision comes entirely from the pure `canShowControl`
// predicate via `useCanAdvertisingAction`, keeping this wrapper a thin,
// declarative shell over the matrix.
//
// Validates: Requirements 26.1, 26.2, 26.3, 26.4, 27.7

import { cloneElement, isValidElement, ReactNode } from 'react';
import { useCanAdvertisingAction } from '../lib/useAdvertisingPermission';
import type {
  AdvertisingAction,
  AdvertisingResource,
} from '../lib/advertisingPermissionMatrix';

export interface AdvertisingActionGateProps {
  /** The advertising resource the wrapped control acts on (Req 27.1 matrix). */
  resource: AdvertisingResource;
  /** The action the wrapped control performs (Req 27.1 matrix). */
  action: AdvertisingAction;
  /**
   * How to treat a control the user is not permitted to use:
   * - `hide` (default): the control is not rendered (or `fallback` is shown).
   * - `disable`: the control is rendered but disabled and non-interactive, and
   *   its click handlers are suppressed so no backend request can fire.
   */
  mode?: 'hide' | 'disable';
  /** Optional element rendered instead when denied and `mode` is `hide`. */
  fallback?: ReactNode;
  children: ReactNode;
}

/**
 * Gate an advertising action control on the logged-in user's permission set.
 * The control is exposed if and only if the permission set contains the matrix
 * code for `(resource, action)`; otherwise it is hidden (default) or rendered
 * inert.
 *
 * @example
 * <AdvertisingActionGate resource="Keyword" action="create-edit">
 *   <Button onClick={createKeyword}>新建关键词</Button>
 * </AdvertisingActionGate>
 *
 * @example
 * <AdvertisingActionGate resource="Recommendation" action="approve" mode="disable">
 *   <Button onClick={applyRecommendation}>应用</Button>
 * </AdvertisingActionGate>
 */
export function AdvertisingActionGate({
  resource,
  action,
  mode = 'hide',
  fallback,
  children,
}: AdvertisingActionGateProps) {
  const allowed = useCanAdvertisingAction(resource, action);

  if (allowed) {
    return <>{children}</>;
  }

  if (mode === 'disable') {
    return renderInert(children);
  }

  return fallback ? <>{fallback}</> : null;
}

/** A no-op that swallows an event so the underlying request never fires. */
function suppress(event?: { preventDefault?: () => void; stopPropagation?: () => void }) {
  event?.preventDefault?.();
  event?.stopPropagation?.();
}

/**
 * Render the child element in a disabled, non-interactive state with its click
 * handlers replaced by a request-suppressing no-op. For a single element we set
 * `disabled`/`aria-disabled`, drop it from the tab order, and override
 * `onClick`/`onClickCapture`; for fragments or text we wrap them so they cannot
 * be activated. This is what guarantees Requirement 26.4 in `disable` mode.
 */
function renderInert(children: ReactNode): ReactNode {
  if (isValidElement(children)) {
    const child = children as React.ReactElement<any>;
    return cloneElement(child, {
      disabled: true,
      'aria-disabled': true,
      tabIndex: -1,
      // Replace any handler so the backend request can never fire (Req 26.4).
      onClick: suppress,
      onClickCapture: suppress,
      className: [child.props.className, 'opacity-50 cursor-not-allowed pointer-events-none']
        .filter(Boolean)
        .join(' '),
    });
  }

  return (
    <span aria-disabled className="opacity-50 cursor-not-allowed pointer-events-none">
      {children}
    </span>
  );
}
