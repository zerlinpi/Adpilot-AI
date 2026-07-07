// Feature: advertising-workspace-rework, Property 58: Frontend control
// visibility follows the permission matrix.
//
// For any permission set, an advertising action control is shown/enabled if and
// only if the set contains the matrix code for that control's action, and a
// hidden or disabled control (an N/A cell with no code) never issues its backend
// request regardless of the permissions held.
//
// Validates: Requirements 26.1, 26.2, 26.3, 26.4, 27.7
//
// This exercises the exact pure predicate the UI delegates to: the
// `AdvertisingActionGate` wrapper and the `useAdvertisingPermission` hook both
// call `canShowControl`, so testing it directly validates the production
// visibility/enablement decision (and therefore whether a backend request may
// fire) without rendering React.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  ADVERTISING_PERMISSION_MATRIX,
  canShowControl,
  permissionCodeForAction,
  type AdvertisingResource,
  type AdvertisingAction,
} from './advertisingPermissionMatrix';

const RUNS = 200; // >= 100 iterations as required for property tests.

const RESOURCES = Object.keys(
  ADVERTISING_PERMISSION_MATRIX,
) as AdvertisingResource[];

const ACTIONS: AdvertisingAction[] = [
  'view',
  'create-edit',
  'delete',
  'approve',
  'platform-execute',
];

// Every distinct permission code referenced anywhere in the matrix. This is the
// universe a real user's aggregated permission set is drawn from. Generating
// held sets from this pool guarantees meaningful overlap with the codes the
// matrix actually gates on (rather than near-always-empty random strings).
const MATRIX_CODES = Array.from(
  new Set(
    RESOURCES.flatMap((resource) =>
      ACTIONS.map((action) => permissionCodeForAction(resource, action)).filter(
        (code): code is string => code !== null,
      ),
    ),
  ),
);

// Include some codes the matrix never uses, so the "iff" is also exercised
// against irrelevant permissions a user might hold.
const PERMISSION_POOL = [...MATRIX_CODES, 'unrelated:view', 'finance:manage'];

const permissionArb = fc.constantFrom(...PERMISSION_POOL);

/** The user's aggregated permission set, modelled as a Set for O(1) lookup. */
const heldPermissionsArb = fc
  .uniqueArray(permissionArb, { maxLength: PERMISSION_POOL.length })
  .map((arr) => new Set(arr));

const resourceArb = fc.constantFrom(...RESOURCES);
const actionArb = fc.constantFrom(...ACTIONS);

describe('Feature: advertising-workspace-rework, Property 58: Frontend control visibility follows the permission matrix', () => {
  it('a control is shown iff the permission set contains the matrix code for its action', () => {
    fc.assert(
      fc.property(
        resourceArb,
        actionArb,
        heldPermissionsArb,
        (resource, action, held) => {
          const code = permissionCodeForAction(resource, action);
          const shown = canShowControl(held, resource, action);

          if (code === null) {
            // N/A cell: never shown, no matter what is held.
            expect(shown).toBe(false);
          } else {
            // Shown if and only if the matrix code is held.
            expect(shown).toBe(held.has(code));
          }
        },
      ),
      { numRuns: RUNS },
    );
  });

  it('an N/A cell (null code, e.g. audit-resource delete) is never shown regardless of permissions held', () => {
    // Sanity-anchor the property: the audit resources' delete cell is N/A.
    for (const resource of ['Operation', 'SyncLog', 'BidChange'] as const) {
      expect(permissionCodeForAction(resource, 'delete')).toBeNull();
    }

    // The N/A cells exist; otherwise this property would be vacuous.
    const naCells = RESOURCES.flatMap((resource) =>
      ACTIONS.filter(
        (action) => permissionCodeForAction(resource, action) === null,
      ).map((action) => ({ resource, action })),
    );
    expect(naCells.length).toBeGreaterThan(0);

    fc.assert(
      fc.property(heldPermissionsArb, (held) => {
        for (const { resource, action } of naCells) {
          expect(canShowControl(held, resource, action)).toBe(false);
        }
      }),
      { numRuns: RUNS },
    );
  });

  it('holding the exact matrix code shows the control, and holding everything-but shows nothing for that cell', () => {
    fc.assert(
      fc.property(resourceArb, actionArb, (resource, action) => {
        const code = permissionCodeForAction(resource, action);

        // Holding the entire universe: shown iff the cell has a code.
        const all = new Set(PERMISSION_POOL);
        expect(canShowControl(all, resource, action)).toBe(code !== null);

        // Holding everything except this cell's code: never shown.
        if (code !== null) {
          const allButCode = new Set(PERMISSION_POOL);
          allButCode.delete(code);
          expect(canShowControl(allButCode, resource, action)).toBe(false);

          // Holding only this cell's code: always shown.
          expect(canShowControl(new Set([code]), resource, action)).toBe(true);
        }

        // The empty permission set never shows any control.
        expect(canShowControl(new Set<string>(), resource, action)).toBe(false);
      }),
      { numRuns: RUNS },
    );
  });
});
