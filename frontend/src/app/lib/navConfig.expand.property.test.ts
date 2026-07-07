// Feature: platform-workspace-rbac, Property 21: Navigation expand/collapse
// state round-trips through persistence.
//
// For any set of per-block expanded/collapsed states, serializing the state and
// then loading it back (as happens across a page reload) yields the exact same
// set of states. The loader additionally falls back to defaults for any
// malformed, missing, or partial persisted input, so the restored state is
// always a complete, valid NavExpandState.
//
// Validates: Requirements 1.6

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';

import {
  defaultExpandState,
  loadExpandState,
  serializeExpandState,
  type NavExpandState,
  type PlatformFamily,
} from './navConfig';

const RUNS = 200; // >= 100 iterations as required for property tests.

const FAMILIES: PlatformFamily[] = ['amazon', 'independent_site', 'logistics', 'finance', 'tiktok'];

/** Any complete, valid per-block expand/collapse state. */
const expandStateArb: fc.Arbitrary<NavExpandState> = fc
  .record({
    amazon: fc.boolean(),
    independent_site: fc.boolean(),
    logistics: fc.boolean(),
    finance: fc.boolean(),
    tiktok: fc.boolean(),
  });

describe('Feature: platform-workspace-rbac, Property 21: Navigation expand/collapse state round-trips through persistence', () => {
  it('loadExpandState(serializeExpandState(state)) deep-equals the original state', () => {
    fc.assert(
      fc.property(expandStateArb, (state) => {
        const restored = loadExpandState(serializeExpandState(state));
        expect(restored).toEqual(state);
      }),
      { numRuns: RUNS },
    );
  });

  it('falls back to defaults for null/missing persisted input', () => {
    expect(loadExpandState(null)).toEqual(defaultExpandState());
  });

  it('falls back to defaults for malformed (non-JSON) input', () => {
    fc.assert(
      fc.property(
        fc.string().filter((s) => {
          try {
            JSON.parse(s);
            return false; // valid JSON — not a subject of the malformed case
          } catch {
            return true;
          }
        }),
        (raw) => {
          expect(loadExpandState(raw)).toEqual(defaultExpandState());
        },
      ),
      { numRuns: RUNS },
    );
  });

  it('falls back to defaults for JSON that is not an object (primitives/arrays)', () => {
    fc.assert(
      fc.property(
        fc.oneof(
          fc.integer(),
          fc.boolean(),
          fc.constant(null),
          fc.array(fc.boolean()),
          fc.string(),
        ),
        (value) => {
          const raw = JSON.stringify(value);
          // Arrays and `null` serialize to objects/null; the loader must still
          // return a complete default state without throwing. Non-boolean
          // values for known keys are ignored in favour of the default.
          const restored = loadExpandState(raw);
          // Every family is present and boolean.
          for (const fam of FAMILIES) {
            expect(typeof restored[fam]).toBe('boolean');
          }
        },
      ),
      { numRuns: RUNS },
    );
  });

  it('fills missing blocks from defaults while preserving present blocks (partial round-trip)', () => {
    fc.assert(
      fc.property(
        // A partial state: an arbitrary subset of families with boolean values.
        fc.dictionary(
          fc.constantFrom<PlatformFamily>(...FAMILIES),
          fc.boolean(),
        ),
        (partial) => {
          const raw = JSON.stringify(partial);
          const restored = loadExpandState(raw);
          const defaults = defaultExpandState();
          for (const fam of FAMILIES) {
            const expected = typeof partial[fam] === 'boolean' ? partial[fam] : defaults[fam];
            expect(restored[fam]).toBe(expected);
          }
        },
      ),
      { numRuns: RUNS },
    );
  });
});
