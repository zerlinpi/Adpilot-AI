// Unit tests for the Operation state-to-actions matrix (pure logic).
//
// Example-based coverage of the complete Requirement 56.1 matrix, the Undo
// gating of Requirement 8.4 / 56.3, and the distinct-copy rule of Requirement
// 48.7. (The exhaustive property test lives in operationActions.property.test.)
//
// Validates: Requirements 56.1, 56.2, 8.3, 21.4, 48.7

import { describe, it, expect } from 'vitest';
import {
  actionsForSyncState,
  SYNC_STATES,
  OPERATION_ACTION_LABELS,
  CLOSE_AI_HOSTING_LABEL,
  CANCEL_PLATFORM_OPERATION_LABEL,
  type SyncState,
  type OperationAction,
} from './operationActionMatrix';

// The exact matrix from Requirement 56.1. `effective` is parameterised by the
// reversible/before-value flags and handled separately below.
const MATRIX: Record<Exclude<SyncState, 'effective'>, OperationAction[]> = {
  'local-only': [],
  pending: ['cancel'],
  awaiting_approval: ['approve', 'reject', 'cancel'],
  submitted: ['cancel'],
  'amazon-processing': ['cancel'],
  cancel_requested: [],
  failed: ['retry'],
  expired: ['reconcile'],
  reconciliation_required: ['reconcile'],
  cancelled: [],
  superseded: [],
};

describe('actionsForSyncState — Requirement 56.1 matrix', () => {
  for (const [state, expected] of Object.entries(MATRIX) as [
    Exclude<SyncState, 'effective'>,
    OperationAction[],
  ][]) {
    it(`offers exactly [${expected.join(', ') || 'none'}] for "${state}"`, () => {
      expect(actionsForSyncState(state)).toEqual(expected);
    });
  }

  it('offers Undo for "effective" only when reversible AND before value still valid', () => {
    expect(
      actionsForSyncState('effective', { reversible: true, beforeValueStillValid: true }),
    ).toEqual(['undo']);

    expect(
      actionsForSyncState('effective', { reversible: false, beforeValueStillValid: true }),
    ).toEqual([]);
    expect(
      actionsForSyncState('effective', { reversible: true, beforeValueStillValid: false }),
    ).toEqual([]);
    // Missing flags default to no Undo.
    expect(actionsForSyncState('effective')).toEqual([]);
  });

  it('never offers any action for states the matrix leaves empty (Req 56.2)', () => {
    for (const state of ['local-only', 'cancel_requested', 'cancelled', 'superseded'] as const) {
      expect(actionsForSyncState(state)).toEqual([]);
    }
  });

  it('returns no duplicates and only known actions for every Sync_State', () => {
    const known: OperationAction[] = ['approve', 'reject', 'cancel', 'retry', 'reconcile', 'undo'];
    for (const state of SYNC_STATES) {
      const result = actionsForSyncState(state, { reversible: true, beforeValueStillValid: true });
      expect(new Set(result).size).toBe(result.length);
      for (const action of result) {
        expect(known).toContain(action);
      }
    }
  });
});

describe('distinct copy for hosting-off vs platform-operation cancel (Req 48.7)', () => {
  it('uses different copy for "关闭AI托管" and "取消平台操作"', () => {
    expect(CLOSE_AI_HOSTING_LABEL).toBe('关闭AI托管');
    expect(CANCEL_PLATFORM_OPERATION_LABEL).toBe('取消平台操作');
    expect(CLOSE_AI_HOSTING_LABEL).not.toBe(CANCEL_PLATFORM_OPERATION_LABEL);
  });

  it('labels the Cancel action with the platform-operation copy, never the hosting copy', () => {
    expect(OPERATION_ACTION_LABELS.cancel).toBe(CANCEL_PLATFORM_OPERATION_LABEL);
    expect(OPERATION_ACTION_LABELS.cancel).not.toBe(CLOSE_AI_HOSTING_LABEL);
  });
});
