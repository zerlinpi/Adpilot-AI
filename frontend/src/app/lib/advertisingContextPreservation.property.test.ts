// Property-based round-trip test for the pure advertising context-preservation
// core (advertising-workspace-rework Req 45.1, 45.2, 45.3).
//
// Feature: advertising-workspace-rework, Property 69
//
// Property 69 — Context preservation round-trip across tab switches:
//   For every tab context snapshot `s`, set of currently-present row ids `P`,
//   and arbitrary pre-existing store contents, after
//       store' = captureTabContext(store, tab, s)
//       r      = restoreTabContext(store', tab, P)
//   the following all hold:
//     (45.1) r.query is the captured query, exactly (filters/date-range/sort/
//            pagination restored verbatim);
//     (45.2) r.selectedRowIds === intersectSelection(s.selectedRowIds, P):
//            the selection is restored only for rows still present, with the
//            saved order preserved and duplicates dropped, and never references
//            a row that is not present;
//     (45.3) r.draft is the captured draft, untouched (same reference, not
//            inspected or rebuilt by the preservation layer).

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  captureTabContext,
  restoreTabContext,
  intersectSelection,
  type AdvertisingContextStore,
  type DraftState,
  type TabContextSnapshot,
} from './advertisingContextPreservation';
import type { AdvertisingQueryState } from './advertisingQueryState';
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

const conditionArb: fc.Arbitrary<FilterCondition> = fc.record({
  field: fc.string(),
  op: fc.constantFrom(...FILTER_OPERATORS),
  value: fc.oneof(
    fc.string(),
    fc.integer({ min: -1_000_000, max: 1_000_000 }),
    fc.boolean(),
    fc.constant(null),
  ),
});

const datePartArb: fc.Arbitrary<string> = fc
  .date({ min: new Date('2000-01-01'), max: new Date('2035-12-31') })
  .map((d) => d.toISOString().slice(0, 10));

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
  page: fc.integer({ min: 1, max: 10_000 }),
  pageSize: fc.integer({ min: 1, max: 500 }),
});

const draftArb: fc.Arbitrary<DraftState | null> = fc.oneof(
  fc.constant(null),
  fc.dictionary(
    fc.string(),
    fc.oneof(fc.string(), fc.integer(), fc.boolean(), fc.constant(null)),
  ),
);

const snapshotArb: fc.Arbitrary<TabContextSnapshot> = fc.record({
  query: queryStateArb,
  // selectedRowIds may contain duplicates and rows that are not present, so the
  // intersection/dedup behavior of restore (45.2) is exercised.
  selectedRowIds: fc.array(fc.string({ minLength: 1, maxLength: 6 }), {
    maxLength: 12,
  }),
  draft: draftArb,
});

// A pre-existing store of OTHER tabs, plus the target tab key. The target key
// is drawn from a small alphabet that overlaps the other-tab keys so we also
// cover the case where capture overwrites an existing snapshot for the tab.
const tabKeyArb: fc.Arbitrary<string> = fc.constantFrom(
  'campaigns',
  'adGroups',
  'ads',
  'keywords',
  'targets',
);

const otherStoreArb: fc.Arbitrary<AdvertisingContextStore> = fc.dictionary(
  tabKeyArb,
  snapshotArb,
);

describe('Feature: advertising-workspace-rework, Property 69 — context preservation round-trip', () => {
  it('capture then restore returns the query exactly (45.1), the present-row selection (45.2), and the untouched draft (45.3)', () => {
    fc.assert(
      fc.property(
        otherStoreArb,
        tabKeyArb,
        snapshotArb,
        // presentRowIds: a mix drawn from the same id space as selections plus
        // ids that may never have been selected, so intersection is non-trivial.
        fc.array(fc.string({ minLength: 1, maxLength: 6 }), { maxLength: 16 }),
        (existingStore, tabKey, snapshot, presentRowIds) => {
          const store = captureTabContext(existingStore, tabKey, snapshot);
          const restored = restoreTabContext(store, tabKey, presentRowIds);

          // (45.1) The query is restored verbatim — same value the tab left with.
          expect(restored.query).toEqual(snapshot.query);

          // (45.2) Selection is restored only for still-present rows, in saved
          // order, with duplicates dropped, and never references an absent row.
          const expectedSelection = intersectSelection(
            snapshot.selectedRowIds,
            presentRowIds,
          );
          expect(restored.selectedRowIds).toEqual(expectedSelection);

          const present = new Set(presentRowIds);
          for (const id of restored.selectedRowIds) {
            expect(present.has(id)).toBe(true);
          }
          // No duplicates in the restored selection.
          expect(new Set(restored.selectedRowIds).size).toBe(
            restored.selectedRowIds.length,
          );

          // (45.3) The unsaved draft is preserved untouched — the preservation
          // layer never inspects or rebuilds it (identity is preserved).
          expect(restored.draft).toBe(snapshot.draft);
        },
      ),
      { numRuns: NUM_RUNS },
    );
  });
});
