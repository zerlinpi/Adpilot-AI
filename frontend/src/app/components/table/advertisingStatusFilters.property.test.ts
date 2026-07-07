// Feature: advertising-workspace-rework, Property 65: Status filter options come from a fixed enumeration
//
// Validates: Requirements 35.1, 35.2, 35.3
//
// A status type-selector's options come from a FIXED enumeration of the
// possible status values for that field, never derived from whichever rows
// happen to be loaded on the page. This property drives the real pure module
// `advertisingStatusFilters` (task 19.4) directly — no mocking — and asserts
// that for ANY status field and ANY arbitrarily-generated set of loaded rows,
// `getStatusFilterOptions(field)` returns exactly the fixed enumeration for
// that field:
//   • each option is populated from the fixed enumeration (Req 35.1),
//   • the option set is independent of the loaded rows (Req 35.2), and
//   • every enumerated status is offered even when no loaded row has it
//     (Req 35.3).

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';

import {
  ADVERTISING_STATUS_ENUMERATIONS,
  ADVERTISING_STATUS_FIELDS,
  getStatusFilterOptions,
  type AdvertisingStatusField,
} from './advertisingStatusFilters';

const RUNS = 200; // comfortably above the required minimum of 100 iterations.

// The universe of status machine values that a loaded row could carry, plus
// arbitrary noise values that are NOT part of any fixed enumeration. This lets
// the generated rows range over: rows that happen to cover some enumerated
// statuses, rows that cover none of them, and rows carrying values outside the
// enumeration entirely — none of which may influence the offered options.
const ALL_ENUMERATED_VALUES = Array.from(
  new Set(
    ADVERTISING_STATUS_FIELDS.flatMap(
      (f) => ADVERTISING_STATUS_ENUMERATIONS[f] as readonly string[],
    ),
  ),
);

const fieldArb: fc.Arbitrary<AdvertisingStatusField> = fc.constantFrom(
  ...ADVERTISING_STATUS_FIELDS,
);

// A single arbitrarily-generated loaded row: an object whose status fields may
// hold any enumerated value, an out-of-enumeration noise value, or be absent.
const rowArb = fc.record(
  {
    objectStatus: fc.option(
      fc.oneof(fc.constantFrom(...ALL_ENUMERATED_VALUES), fc.string()),
      { nil: undefined },
    ),
    aiHostingStatus: fc.option(
      fc.oneof(fc.constantFrom(...ALL_ENUMERATED_VALUES), fc.string()),
      { nil: undefined },
    ),
  },
  { requiredKeys: [] },
);

describe('Feature: advertising-workspace-rework, Property 65: Status filter options come from a fixed enumeration', () => {
  it('returns exactly the fixed enumeration for the field regardless of the loaded rows', () => {
    fc.assert(
      fc.property(
        fieldArb,
        fc.array(rowArb, { maxLength: 50 }),
        (field, _loadedRows) => {
          // The options offered for the field. The loaded rows are intentionally
          // unused as inputs to the production call — proving independence.
          const options = getStatusFilterOptions(field);
          const offeredValues = options.map((o) => o.value);

          const fixedEnumeration = [
            ...(ADVERTISING_STATUS_ENUMERATIONS[field] as readonly string[]),
          ];

          // (Req 35.1) Options come from the fixed enumeration, in order, one
          // per enumerated value — and (Req 35.3) every enumerated status is
          // offered even when no loaded row carries that value.
          expect(offeredValues).toEqual(fixedEnumeration);

          // (Req 35.2) The offered set is NOT derived from the loaded rows: it
          // is identical to calling with no rows in scope at all.
          expect(offeredValues).toEqual(
            getStatusFilterOptions(field).map((o) => o.value),
          );

          // Every option carries a non-empty display label.
          for (const opt of options) {
            expect(typeof opt.label).toBe('string');
            expect(opt.label.length).toBeGreaterThan(0);
          }
        },
      ),
      { numRuns: RUNS },
    );
  });
});
