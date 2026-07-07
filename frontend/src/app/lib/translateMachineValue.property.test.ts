// Feature: advertising-workspace-rework, Property 35: Frontend
// machine-value-to-display translation is total.
//
// The backend returns only STABLE MACHINE values for the four advertising
// enums and never display strings; the frontend alone translates those machine
// values into the fixed display copy (Req 48.5, 3.6). This property asserts
// that translation is TOTAL:
//   1. Over the enum: for every enum kind and every canonical machine value in
//      MACHINE_VALUE_SETS, `translateMachineValue` returns a defined, non-empty
//      display string (no canonical value maps to undefined/empty).
//   2. Over all inputs: for arbitrary enum kinds and arbitrary values
//      (including unknown strings, `null`, and `undefined`) the function never
//      throws and always returns a string.
//
// Validates: Requirements 48.5, 3.6

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  translateMachineValue,
  MACHINE_VALUE_SETS,
  type MachineEnumKind,
} from './translateMachineValue';

const RUNS = 200; // >= 100 iterations as required for property tests.

// The four enum kinds the backend can emit, derived from the canonical sets so
// the generator stays in lock-step with the production tables.
const ENUM_KINDS = Object.keys(MACHINE_VALUE_SETS) as MachineEnumKind[];

const enumKindArb = fc.constantFrom(...ENUM_KINDS);

// A canonical (kind, value) pair: pick an enum kind, then pick one of that
// enum's canonical machine values. This constrains generation to exactly the
// input space the backend emits for the "totality over the enum" check.
const canonicalPairArb: fc.Arbitrary<{ kind: MachineEnumKind; value: string }> = enumKindArb.chain(
  (kind) => fc.constantFrom(...MACHINE_VALUE_SETS[kind]).map((value) => ({ kind, value })),
);

// An arbitrary value the function might receive in the wild: a canonical value,
// an unknown/garbage string, the empty string, null, or undefined.
const allCanonicalValues = ENUM_KINDS.flatMap((k) => [...MACHINE_VALUE_SETS[k]]);
const arbitraryValueArb: fc.Arbitrary<string | null | undefined> = fc.oneof(
  fc.constantFrom(...allCanonicalValues),
  fc.string(),
  fc.constantFrom('', 'unknown', 'FUTURE_VALUE', 'hosted ', 'Enabled'),
  fc.constant(null),
  fc.constant(undefined),
);

describe('Feature: advertising-workspace-rework, Property 35: machine-value-to-display translation is total', () => {
  it('every canonical machine value of every enum maps to a defined, non-empty display string', () => {
    fc.assert(
      fc.property(canonicalPairArb, ({ kind, value }) => {
        const display = translateMachineValue(kind, value);
        // Totality over the enum: defined, a string, and non-empty.
        expect(display).toBeDefined();
        expect(typeof display).toBe('string');
        expect(display.length).toBeGreaterThan(0);
      }),
      { numRuns: RUNS },
    );
  });

  it('is total over arbitrary inputs: never throws and always returns a string', () => {
    fc.assert(
      fc.property(enumKindArb, arbitraryValueArb, (kind, value) => {
        let result: string;
        // The function must never throw for any input.
        expect(() => {
          result = translateMachineValue(kind, value);
        }).not.toThrow();
        // It must always return a string.
        expect(typeof result!).toBe('string');
      }),
      { numRuns: RUNS },
    );
  });

  // A non-property sanity check: enumerate the entire canonical space exactly
  // once to guarantee 100% coverage of the enum tables (the property test above
  // samples, this proves total coverage).
  it('exhaustively covers every canonical value across all enums', () => {
    for (const kind of ENUM_KINDS) {
      for (const value of MACHINE_VALUE_SETS[kind]) {
        const display = translateMachineValue(kind, value);
        expect(display.length).toBeGreaterThan(0);
      }
    }
  });
});
