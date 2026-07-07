// Feature: advertising-workspace-rework, Property 79: State-to-actions matrix
// is exact.
//
// For any Sync_State, the set of action buttons offered (drawn from Approve,
// Reject, Cancel, Retry, Reconcile, Undo) equals exactly the Requirement 56.1
// matrix for that state, and no action outside that set is offered. For
// `effective`, Undo is offered iff the Operation is reversible AND its before
// value is still valid (Requirement 8 criterion 4 / 56.3).
//
// Validates: Requirements 56.1, 56.2, 8.3, 21.4
//
// This exercises the pure decision core (`actionsForSyncState`) that the
// <OperationActions> component delegates to, so the matrix is validated without
// mounting React.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  actionsForSyncState,
  SYNC_STATES,
  OPERATION_ACTION_ORDER,
  type SyncState,
  type OperationAction,
} from './operationActionMatrix';

const RUNS = 200; // >= 100 iterations as required for property tests.

// The complete, closed set of remediation actions the matrix may ever offer
// (Requirement 56.1). Anything returned outside this set is a violation.
const KNOWN_ACTIONS: readonly OperationAction[] = [
  'approve',
  'reject',
  'cancel',
  'retry',
  'reconcile',
  'undo',
];

// An INDEPENDENT re-encoding of the Requirement 56.1 matrix. Deliberately not
// imported from the module under test so the test pins the contract itself
// rather than mirroring the implementation. For `effective` the expectation is
// computed from the reversible/beforeValueStillValid flags below.
const EXPECTED_BASE: Record<SyncState, OperationAction[]> = {
  'local-only': [], // 56.1: none of the remediation actions (publish offered elsewhere)
  pending: ['cancel'],
  awaiting_approval: ['approve', 'reject', 'cancel'],
  submitted: ['cancel'], // routes to cancel_requested per 56.4
  'amazon-processing': ['cancel'], // routes to cancel_requested per 56.4
  cancel_requested: [], // 56.2: awaiting platform resolution
  effective: [], // overridden by undo gating below
  failed: ['retry'],
  expired: ['reconcile'],
  reconciliation_required: ['reconcile'],
  cancelled: [], // 56.2
  superseded: [], // 56.2
};

// Sort helper so set-equality comparisons are order-independent; the matrix's
// own ordering is asserted separately.
const sorted = (xs: readonly OperationAction[]): OperationAction[] =>
  [...xs].sort();

const syncStateArb: fc.Arbitrary<SyncState> = fc.constantFrom(...SYNC_STATES);

// Flags arbitrary: include undefined to prove the gate treats absence as false.
const triBool = fc.constantFrom<boolean | undefined>(true, false, undefined);
const flagsArb = fc.record({
  reversible: triBool,
  beforeValueStillValid: triBool,
});

describe('Feature: advertising-workspace-rework, Property 79: State-to-actions matrix is exact', () => {
  it('every Sync_State (and effective flag combinations) yields EXACTLY the Requirement 56.1 matrix actions and nothing outside it', () => {
    fc.assert(
      fc.property(syncStateArb, flagsArb, (state, flags) => {
        const actions = actionsForSyncState(state, flags);

        // Compute the exact expectation. `effective` offers Undo iff reversible
        // AND beforeValueStillValid both strictly hold (Requirement 56.3 / 8.4).
        let expected: OperationAction[];
        if (state === 'effective') {
          expected =
            flags.reversible === true && flags.beforeValueStillValid === true
              ? ['undo']
              : [];
        } else {
          expected = EXPECTED_BASE[state];
        }

        // EXACT membership: same set, no missing and no extra actions.
        expect(sorted(actions)).toEqual(sorted(expected));

        // Only known actions are ever returned (no action outside the closed
        // set of {approve, reject, cancel, retry, reconcile, undo}).
        for (const a of actions) {
          expect(KNOWN_ACTIONS).toContain(a);
        }

        // No duplicates are ever emitted.
        expect(new Set(actions).size).toBe(actions.length);

        // Returned actions are laid out per the stable rendering order.
        const order = OPERATION_ACTION_ORDER;
        const indices = actions.map((a) => order.indexOf(a));
        const ascending = [...indices].sort((x, y) => x - y);
        expect(indices).toEqual(ascending);

        // States the matrix marks as offering no remediation action return an
        // empty array (Requirement 56.2): local-only, cancel_requested,
        // cancelled, superseded — regardless of any flags.
        if (
          state === 'local-only' ||
          state === 'cancel_requested' ||
          state === 'cancelled' ||
          state === 'superseded'
        ) {
          expect(actions).toEqual([]);
        }

        // Undo appears iff the state is `effective` AND both gating flags hold.
        const hasUndo = actions.includes('undo');
        expect(hasUndo).toBe(
          state === 'effective' &&
          flags.reversible === true &&
          flags.beforeValueStillValid === true,
        );
      }),
      { numRuns: RUNS },
    );
  });
});
