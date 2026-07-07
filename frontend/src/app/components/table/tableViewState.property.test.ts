// Property-based test for the pure explicit UI-state resolver
// (advertising-workspace-rework Req 41.1–41.5).
//
// Feature: advertising-workspace-rework, Property 71
//
// Property 71 — Every view resolves to exactly one explicit UI state:
//   For ANY combination of inputs (permission / loading / error / stale / row
//   count), `resolveViewState` returns one — and only one — of the six explicit
//   states (loading | empty | partial-error | stale-cache | no-permission |
//   content), and the documented precedence holds:
//       no-permission > loading > partial-error > stale-cache > empty > content
//   so the advertising UI never shows an ambiguous blank or misleading screen.
//
// Validates: Requirements 41.1, 41.2, 41.3, 41.4, 41.5

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  resolveViewState,
  type ViewState,
  type ViewStateInputs,
} from './tableViewState';

const NUM_RUNS = 500;

// The exhaustive, mutually-exclusive set of states the resolver may return.
const ALL_STATES: readonly ViewState[] = [
  'loading',
  'empty',
  'partial-error',
  'stale-cache',
  'no-permission',
  'content',
];

// Generators deliberately cover the optional booleans as true/false/undefined
// (undefined exercises the documented defaults) and rowCount across negative,
// zero, positive, fractional, and non-finite values so the resolver's totality
// is stressed over the full input space.
const optionalBool: fc.Arbitrary<boolean | undefined> = fc.constantFrom(
  true,
  false,
  undefined,
);

const rowCountArb: fc.Arbitrary<number> = fc.oneof(
  fc.integer({ min: -10, max: 1000 }),
  fc.double({ min: -5, max: 5, noNaN: false }),
  fc.constantFrom(0, 1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY),
);

const inputsArb: fc.Arbitrary<ViewStateInputs> = fc.record({
  hasPermission: optionalBool,
  loading: optionalBool,
  hasError: optionalBool,
  stale: optionalBool,
  rowCount: rowCountArb,
});

/**
 * Reference model encoding the documented precedence independently of the
 * implementation: highest-priority signal wins.
 */
function expectedState(inputs: ViewStateInputs): ViewState {
  const hasPermission = inputs.hasPermission ?? true;
  const loading = inputs.loading ?? false;
  const hasError = inputs.hasError ?? false;
  const stale = inputs.stale ?? false;
  const rowCount =
    Number.isFinite(inputs.rowCount) && (inputs.rowCount as number) > 0
      ? Math.floor(inputs.rowCount as number)
      : 0;

  if (!hasPermission) return 'no-permission';
  if (loading) return 'loading';
  if (hasError) return 'partial-error';
  if (stale) return 'stale-cache';
  if (rowCount === 0) return 'empty';
  return 'content';
}

describe('Feature: advertising-workspace-rework, Property 71 — Every view resolves to exactly one explicit UI state', () => {
  it('returns exactly one of the six states and honors the documented precedence for any inputs', () => {
    fc.assert(
      fc.property(inputsArb, (inputs) => {
        const state = resolveViewState(inputs);

        // (1) Resolves to a valid state — and exactly one (the return type is a
        // single value, and that value is a member of the known set exactly
        // once).
        expect(ALL_STATES).toContain(state);
        expect(ALL_STATES.filter((s) => s === state)).toHaveLength(1);

        // (2) Deterministic: the same inputs always resolve identically.
        expect(resolveViewState(inputs)).toBe(state);

        // (3) The resolved state matches the documented precedence
        //     no-permission > loading > partial-error > stale-cache > empty >
        //     content.
        expect(state).toBe(expectedState(inputs));

        // (4) Spot-check the precedence ordering directly: a higher-priority
        //     signal must dominate every lower-priority one.
        const hasPermission = inputs.hasPermission ?? true;
        const loading = inputs.loading ?? false;
        const hasError = inputs.hasError ?? false;
        const stale = inputs.stale ?? false;
        if (!hasPermission) {
          expect(state).toBe('no-permission');
        } else if (loading) {
          expect(state).toBe('loading');
        } else if (hasError) {
          expect(state).toBe('partial-error');
        } else if (stale) {
          expect(state).toBe('stale-cache');
        }
      }),
      { numRuns: NUM_RUNS },
    );
  });
});
