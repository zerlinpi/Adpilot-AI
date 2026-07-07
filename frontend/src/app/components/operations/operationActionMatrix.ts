// AdPilot AI — Operation state-to-actions matrix (pure logic)
//
// Pure, render-independent logic that maps an Operation's current Sync_State to
// the exact set of action buttons the UI is allowed to offer. The
// `<OperationActions>` component delegates to `actionsForSyncState` so that
// "which actions get rendered" is governed by exactly this matrix and nothing
// outside it.
//
// This is the single authority for the Requirement 56 state-to-actions matrix
// and is exported so it can be property-tested directly (Property 79) without
// rendering React.
//
// Validates: Requirements 56.1, 56.2, 8.3, 21.4, 48.7

/**
 * The Sync_State of a platform Operation, expressed as the STABLE MACHINE
 * values the Backend returns (Requirement 48). The Frontend translates these to
 * display copy elsewhere; this module only reasons over the machine values.
 */
export type SyncState =
  | 'local-only'
  | 'pending'
  | 'awaiting_approval'
  | 'submitted'
  | 'amazon-processing'
  | 'cancel_requested'
  | 'effective'
  | 'failed'
  | 'expired'
  | 'reconciliation_required'
  | 'cancelled'
  | 'superseded';

/** The complete, closed set of Sync_States, useful for exhaustive iteration. */
export const SYNC_STATES: readonly SyncState[] = [
  'local-only',
  'pending',
  'awaiting_approval',
  'submitted',
  'amazon-processing',
  'cancel_requested',
  'effective',
  'failed',
  'expired',
  'reconciliation_required',
  'cancelled',
  'superseded',
];

/**
 * The closed set of remediation actions the matrix may offer. Drawn from
 * Requirement 56.1: Approve, Reject, Cancel, Retry, Reconcile, and Undo. A
 * `local-only` Operation's "submit to Amazon" / publish control is NOT one of
 * these — it is offered separately per Requirement 12 — so it is deliberately
 * absent from this enum.
 */
export type OperationAction =
  | 'approve'
  | 'reject'
  | 'cancel'
  | 'retry'
  | 'reconcile'
  | 'undo';

/**
 * A stable rendering order for the actions so the component never reorders
 * buttons between states. The matrix decides membership; this decides layout.
 */
export const OPERATION_ACTION_ORDER: readonly OperationAction[] = [
  'approve',
  'reject',
  'cancel',
  'retry',
  'reconcile',
  'undo',
];

/**
 * Conditions, beyond the Sync_State itself, that gate whether the `effective`
 * state offers Undo. Per Requirement 8 criterion 4, Undo is offered ONLY when
 * the Operation is reversible AND its before value is still valid (no newer
 * `effective` Operation has since changed the field).
 */
export interface OperationActionFlags {
  /** The Operation's `reversible` flag (Requirement 8.4). */
  reversible?: boolean;
  /**
   * Whether the before value is still valid — i.e. the field has NOT since been
   * changed by a newer `effective` Operation (Requirement 8.4).
   */
  beforeValueStillValid?: boolean;
}

/**
 * Return the EXACT set of remediation actions allowed for an Operation in the
 * given Sync_State, implementing the complete Requirement 56.1 matrix and
 * nothing outside it.
 *
 * The returned array is ordered per {@link OPERATION_ACTION_ORDER} and contains
 * no duplicates. States with no available action return an empty array; in
 * particular `local-only`, `cancel_requested`, `cancelled`, and `superseded`
 * never offer any of {Approve, Reject, Cancel, Retry, Reconcile, Undo}
 * (Requirement 56.2). For `effective`, Undo is included ONLY when both
 * `reversible` and `beforeValueStillValid` hold (Requirement 56.3 / 8.4).
 */
export function actionsForSyncState(
  syncState: SyncState,
  flags: OperationActionFlags = {},
): OperationAction[] {
  switch (syncState) {
    case 'pending':
      // Not yet submitted: a local cancel transitions directly to `cancelled`.
      return ['cancel'];
    case 'awaiting_approval':
      // Approval gate: approve -> submitted, reject -> cancelled, or cancel.
      return ['approve', 'reject', 'cancel'];
    case 'submitted':
    case 'amazon-processing':
      // In flight: Cancel routes to `cancel_requested` per Requirement 4 / 56.4.
      return ['cancel'];
    case 'failed':
      return ['retry'];
    case 'expired':
    case 'reconciliation_required':
      // Reconcile queries the platform's actual state per Requirement 56.5.
      return ['reconcile'];
    case 'effective':
      // Undo only when reversible AND the before value is still valid (8.4).
      return flags.reversible === true && flags.beforeValueStillValid === true
        ? ['undo']
        : [];
    case 'local-only':
    case 'cancel_requested':
    case 'cancelled':
    case 'superseded':
      // No remediation action; see Requirement 56.1 matrix.
      return [];
    default: {
      // Exhaustiveness guard: a new Sync_State must be added to the matrix
      // explicitly rather than silently offering no — or wrong — actions.
      const _exhaustive: never = syncState;
      return _exhaustive;
    }
  }
}

/**
 * DISTINCT display copy for the two easily-conflated actions called out by
 * Requirement 48.7. These MUST never share a label, button text, or
 * confirmation copy:
 *
 * - {@link CLOSE_AI_HOSTING_LABEL} — turning OFF AI hosting for a Campaign, a
 *   `local_configuration` change to the hosting policy. (Owned by the AI
 *   hosting UI; exported here so both surfaces draw from one source and stay
 *   distinct.)
 * - {@link CANCEL_PLATFORM_OPERATION_LABEL} — cancelling an in-flight platform
 *   Operation per Requirement 4. This is the label the Cancel action uses in
 *   {@link OPERATION_ACTION_LABELS}.
 *
 * Turning off AI hosting does NOT cancel any already-submitted platform
 * Operation, and cancelling a platform Operation does NOT turn off AI hosting.
 */
export const CLOSE_AI_HOSTING_LABEL = '关闭AI托管';
export const CANCEL_PLATFORM_OPERATION_LABEL = '取消平台操作';

/** Display copy for each remediation action button (Chinese UI). */
export const OPERATION_ACTION_LABELS: Record<OperationAction, string> = {
  approve: '批准',
  reject: '拒绝',
  // Distinct from CLOSE_AI_HOSTING_LABEL per Requirement 48.7.
  cancel: CANCEL_PLATFORM_OPERATION_LABEL,
  retry: '重试',
  reconcile: '核对平台状态',
  undo: '撤销',
};
