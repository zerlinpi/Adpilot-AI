// Property-based test for the pure applied-filter → Filter_Chip derivation
// (advertising-workspace-rework Req 29.3, 29.4, 29.5).
//
// Feature: advertising-workspace-rework, Property 60
//
// Property 60 — Filter chips correspond exactly to applied filters:
//   For any AdvertisingQueryState, deriveFilterChips produces exactly one chip
//   per applied filter condition (a non-empty search, each type-selection, each
//   advanced condition, and an applied date range) and none when no filters are
//   applied; sort and pagination never contribute chips; and removeFilterChip
//   removes exactly the chip's filter (resetting page to 1) while leaving every
//   other applied filter intact.
//
// **Validates: Requirements 29.3, 29.4, 29.5**

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  deriveFilterChips,
  removeFilterChip,
} from './advertisingFilterChips';
import {
  normalizeQueryState,
  type AdvertisingQueryState,
} from './advertisingQueryState';
import type { FilterCondition, FilterOperator } from '../components/table/types';

const NUM_RUNS = 200;

const FILTER_OPERATORS: FilterOperator[] = [
  'eq',
  'ne',
  'gt',
  'gte',
  'lt',
  'lte',
  'contains',
  'in',
];

const conditionValueArb: fc.Arbitrary<unknown> = fc.oneof(
  fc.string(),
  fc.integer({ min: -1_000_000, max: 1_000_000 }),
  fc.boolean(),
  fc.constant(null),
);

const conditionArb: fc.Arbitrary<FilterCondition> = fc.record({
  field: fc.string(),
  op: fc.constantFrom(...FILTER_OPERATORS),
  value: conditionValueArb,
});

// Search deliberately includes the empty string and whitespace-only strings so
// the test exercises the "blank search is not an applied filter" path.
const searchArb: fc.Arbitrary<string> = fc.oneof(
  fc.constant(''),
  fc.constant('   '),
  fc.string(),
);

const datePartArb: fc.Arbitrary<string> = fc
  .date({ min: new Date('2000-01-01'), max: new Date('2035-12-31') })
  .map((d) => d.toISOString().slice(0, 10));

// page / pageSize generators include invalid values so derivation is exercised
// against the normalization path too. Neither must ever produce a chip.
const pageNumberArb: fc.Arbitrary<number> = fc.oneof(
  fc.integer({ min: 1, max: 10_000 }),
  fc.constantFrom(0, -1, 2.5, Number.NaN),
);

const queryStateArb: fc.Arbitrary<AdvertisingQueryState> = fc.record({
  filters: fc.record({
    search: searchArb,
    typeSelections: fc.dictionary(fc.string(), fc.string(), { maxKeys: 6 }),
    conditions: fc.array(conditionArb, { maxLength: 6 }),
  }),
  dateRange: fc.oneof(
    fc.constant(null),
    fc.record({ start: datePartArb, end: datePartArb }),
  ),
  sort: fc.oneof(
    fc.constant(null),
    fc.record({
      field: fc.string(),
      direction: fc.constantFrom('asc' as const, 'desc' as const),
    }),
  ),
  page: pageNumberArb,
  pageSize: pageNumberArb,
});

/** Count the applied filters in a (normalized) state — the expected chip count. */
function appliedFilterCount(state: AdvertisingQueryState): number {
  const n = normalizeQueryState(state);
  return (
    (n.filters.search.trim() !== '' ? 1 : 0) +
    Object.keys(n.filters.typeSelections).length +
    n.filters.conditions.length +
    (n.dateRange ? 1 : 0)
  );
}

const sortedLabels = (chips: { label: string }[]): string[] =>
  chips.map((c) => c.label).sort();

describe('Feature: advertising-workspace-rework, Property 60 — Filter chips correspond exactly to applied filters', () => {
  it('derives exactly one chip per applied filter, ignores sort/pagination, and removeFilterChip drops exactly that filter (page → 1)', () => {
    fc.assert(
      fc.property(queryStateArb, (state) => {
        const chips = deriveFilterChips(state);

        // (Req 29.3 / 29.5) Exactly one chip per applied filter; zero when none.
        const expectedCount = appliedFilterCount(state);
        expect(chips).toHaveLength(expectedCount);
        if (expectedCount === 0) {
          expect(chips).toEqual([]);
        }

        // Chips only ever represent the four kinds of filter — never sort/page.
        for (const chip of chips) {
          expect(['search', 'typeSelection', 'condition', 'dateRange']).toContain(
            chip.kind,
          );
        }

        // (Req 29.5) Sort and pagination never affect the chips: changing only
        // sort / page / pageSize yields an identical chip list.
        const variant: AdvertisingQueryState = {
          ...state,
          sort: state.sort
            ? null
            : { field: 'someColumn', direction: 'desc' },
          page: 999,
          pageSize: 7,
        };
        expect(sortedLabels(deriveFilterChips(variant))).toEqual(
          sortedLabels(chips),
        );

        // (Req 29.4) Removing each chip drops exactly that filter, leaves the
        // others, and resets pagination to page 1.
        chips.forEach((chip, idx) => {
          const next = removeFilterChip(state, chip);
          expect(next.page).toBe(1);

          const remaining = deriveFilterChips(next);
          // Exactly one fewer chip remains...
          expect(remaining).toHaveLength(chips.length - 1);
          // ...and the remaining chips are exactly the original set minus the
          // removed one (compared by label, which is index-independent).
          const expectedRemaining = sortedLabels(
            chips.filter((_, i) => i !== idx),
          );
          expect(sortedLabels(remaining)).toEqual(expectedRemaining);
        });
      }),
      { numRuns: NUM_RUNS },
    );
  });
});
