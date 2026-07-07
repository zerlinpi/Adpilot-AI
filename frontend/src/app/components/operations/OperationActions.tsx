// AdPilot AI — Operation action controls
//
// Renders the action buttons available for an Operation in its current
// Sync_State. The set of rendered controls is decided entirely by the pure
// `actionsForSyncState` matrix (Requirement 56), so this component never offers
// an action the matrix forbids. The Cancel control uses copy that is distinct
// from "关闭AI托管" (turning off AI hosting) per Requirement 48.7.
//
// Validates: Requirements 56.1, 56.2, 8.3, 21.4, 48.7

import { Button } from '../ui/button';
import {
  actionsForSyncState,
  OPERATION_ACTION_ORDER,
  OPERATION_ACTION_LABELS,
  type OperationAction,
  type SyncState,
} from './operationActionMatrix';

/** Variant applied to each action's button, chosen to signal intent/severity. */
const ACTION_VARIANTS: Record<
  OperationAction,
  'default' | 'destructive' | 'outline' | 'secondary'
> = {
  approve: 'default',
  reject: 'destructive',
  cancel: 'outline',
  retry: 'secondary',
  reconcile: 'secondary',
  undo: 'outline',
};

export interface OperationActionsProps {
  /** The Operation's current Sync_State (machine value). */
  syncState: SyncState;
  /** The Operation's `reversible` flag — gates Undo on `effective` (Req 8.4). */
  reversible?: boolean;
  /**
   * Whether the before value is still valid (no newer `effective` Operation has
   * changed the field) — gates Undo on `effective` (Req 8.4).
   */
  beforeValueStillValid?: boolean;
  /** Disable every rendered control (e.g. while a request is in flight). */
  disabled?: boolean;
  /** Optional extra classes for the action container. */
  className?: string;

  // Per-action handlers. A handler is invoked only when its action is offered
  // by the matrix for the current Sync_State.
  onApprove?: () => void;
  onReject?: () => void;
  /** Cancel an in-flight platform Operation (NOT "关闭AI托管", per Req 48.7). */
  onCancel?: () => void;
  onRetry?: () => void;
  onReconcile?: () => void;
  onUndo?: () => void;
}

/**
 * Render the legal action controls for an Operation. Returns `null` when the
 * matrix offers no action for the current Sync_State (e.g. `local-only`,
 * `cancel_requested`, `cancelled`, `superseded`), so no illegal action is ever
 * presented (Requirement 56.2).
 */
export function OperationActions({
  syncState,
  reversible,
  beforeValueStillValid,
  disabled,
  className,
  onApprove,
  onReject,
  onCancel,
  onRetry,
  onReconcile,
  onUndo,
}: OperationActionsProps) {
  const allowed = new Set(
    actionsForSyncState(syncState, { reversible, beforeValueStillValid }),
  );

  if (allowed.size === 0) {
    return null;
  }

  const handlers: Record<OperationAction, (() => void) | undefined> = {
    approve: onApprove,
    reject: onReject,
    cancel: onCancel,
    retry: onRetry,
    reconcile: onReconcile,
    undo: onUndo,
  };

  // Render in a stable order; membership is decided by the matrix above.
  const rendered = OPERATION_ACTION_ORDER.filter((action) => allowed.has(action));

  return (
    <div className={['flex flex-wrap items-center gap-2', className].filter(Boolean).join(' ')}>
      {rendered.map((action) => (
        <Button
          key={action}
          type="button"
          size="sm"
          variant={ACTION_VARIANTS[action]}
          disabled={disabled}
          data-action={action}
          onClick={handlers[action]}
        >
          {OPERATION_ACTION_LABELS[action]}
        </Button>
      ))}
    </div>
  );
}
