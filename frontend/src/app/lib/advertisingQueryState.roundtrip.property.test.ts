// Property-based round-trip test for the pure advertising query-state codec
// (advertising-workspace-rework Req 34.4, 34.5).
//
// Feature: advertising-workspace-rework, Property 62
//
// Property 62 — URL filter/sort round-trip:
//   For every AdvertisingQueryState `s`,
//       decodeFilters(encodeFilters(s))  deep-equals  normalizeQueryState(s)
//
// i.e. encoding a state into the URL and decoding it back yields the canonical
// (normalized) form of that state — applying filters/sort persists to the URL
// (Req 34.4) and re-opening that URL re-applies them exactly (Req 34.5).

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  decodeFilters,
  encodeFilters,
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

// Condition values are restricted to JSON-stable primitives (string / integer /
// boolean / null). This is intentional: arbitrary floats (NaN/Infinity) and
// nested `undefined` do not survive a JSON.stringify→parse round-trip, which is
// a property of JSON, not of the codec under test.
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

// `page` / `pageSize` generators deliberately include invalid inputs (0,
// negatives, non-integers) so the test exercises the codec's defaulting path as
// well as valid values.
const pageNumberArb: fc.Arbitrary<number> = fc.oneof(
  fc.integer({ min: 1, max: 10_000 }),
  fc.constantFrom(0, -1, -50, 2.5, Number.NaN),
);

// Date parts may be empty (which normalizes the whole range to null) or a
// realistic-looking date string; both must round-trip.
const datePartArb: fc.Arbitrary<string> = fc.oneof(
  fc.constant(''),
  fc
    .date({ min: new Date('2000-01-01'), max: new Date('2035-12-31') })
    .map((d) => d.toISOString().slice(0, 10)),
);

const queryStateArb: fc.Arbitrary<AdvertisingQueryState> = fc.record({
  filters: fc.record({
    search: fc.string(),
    typeSelections: fc.dictionary(fc.string(), fc.string()),
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

describe('Feature: advertising-workspace-rework, Property 62 — URL filter/sort round-trip', () => {
  it('decodeFilters(encodeFilters(s)) deep-equals normalizeQueryState(s) for any query state', () => {
    fc.assert(
      fc.property(queryStateArb, (state) => {
        const roundTripped = decodeFilters(encodeFilters(state));
        expect(roundTripped).toEqual(normalizeQueryState(state));
      }),
      { numRuns: NUM_RUNS },
    );
  });
});
