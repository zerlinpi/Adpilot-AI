// Property-based test for the pure cross-page selection helpers
// (advertising-workspace-rework Req 44.1, 44.2, 44.3, 44.4).
//
// Feature: advertising-workspace-rework, Property 68
//
// Property 68 — Cross-page selection semantics:
//   For any selection, "select current page" includes ONLY the rows rendered on
//   the current page, while "select all filtered results" is defined by the
//   active filter conditions across all pages, applies the resulting
//   Bulk_Operation to every matching record, and displays the covered count
//   (= the server total of matching records).
//
//   This single property pins the three guarantees the task calls out together:
//     1. "select current page" === exactly the rendered rows (deduped,
//        order-preserving) and never an id outside the current page (Req 44.2).
//     2. "select all filtered" yields a filter-defined Bulk_Operation scope
//        across all pages whose coveredCount equals the server total (Req 44.3,
//        44.4).
//     3. resolveBulkScope distinguishes the explicit-ids choice from the
//        all_filtered choice correctly (Req 44.1, 44.2, 44.3).

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  selectCurrentPageIds,
  resolveBulkScope,
  resolveCoveredCount,
} from './tableSelection';
import type { SelectionInput } from './tableSelection';
import type { FilterState, FilterCondition, FilterOperator } from './types';

const NUM_RUNS = 200;

// A pool of distinct row ids the universe is drawn from. Keeping the pool small
// guarantees frequent overlap between the current page and the explicit
// selection (so both the on-page and off-page selection paths are exercised).
const idArb: fc.Arbitrary<string> = fc
  .integer({ min: 0, max: 40 })
  .map((n) => `row-${n}`);

const operatorArb: fc.Arbitrary<FilterOperator> = fc.constantFrom(
  'eq',
  'ne',
  'gt',
  'gte',
  'lt',
  'lte',
  'contains',
  'in',
);

const conditionArb: fc.Arbitrary<FilterCondition> = fc.record({
  field: fc.constantFrom('campaign', 'status', 'spend', 'name'),
  op: operatorArb,
  value: fc.oneof(fc.string(), fc.integer(), fc.boolean()),
});

const filtersArb: fc.Arbitrary<FilterState | null> = fc.oneof(
  fc.constant(null),
  fc.record({
    search: fc.string(),
    typeSelections: fc.dictionary(fc.string(), fc.string()),
    conditions: fc.array(conditionArb, { maxLength: 4 }),
  }),
);

/** Deduplicate while preserving first-seen order — the reference model. */
function dedupe(ids: readonly string[]): string[] {
  return [...new Set(ids)];
}

const selectionInputArb: fc.Arbitrary<SelectionInput> = fc
  .record({
    currentPageRowIds: fc.array(idArb, { maxLength: 25 }),
    selectedIds: fc.array(idArb, { maxLength: 40 }),
    allFilteredSelected: fc.boolean(),
    // `total` (the server-side count of all filtered matches) is usually known
    // and at least as large as the current page; `undefined` covers the
    // unknown-total path.
    total: fc.oneof(
      fc.constant<number | undefined>(undefined),
      fc.integer({ min: 0, max: 100_000 }),
    ),
    filters: filtersArb,
  })
  .map((r) => r as SelectionInput);

describe('Feature: advertising-workspace-rework, Property 68 — Cross-page selection semantics', () => {
  it('"current page" covers exactly the rendered rows; "all filtered" is filter-defined across all pages with coveredCount = server total', () => {
    fc.assert(
      fc.property(selectionInputArb, (input) => {
        const pageIds = dedupe(input.currentPageRowIds);
        const pageSet = new Set(pageIds);

        // ── (1) "select current page" === exactly the rendered rows (Req 44.2)
        const currentPage = selectCurrentPageIds(input.currentPageRowIds);
        // Exactly the deduped rendered rows, order-preserving.
        expect(currentPage).toEqual(pageIds);
        // Never an id outside the current page.
        for (const id of currentPage) {
          expect(pageSet.has(id)).toBe(true);
        }
        // Covers every rendered row — nothing on the page is dropped.
        expect(new Set(currentPage)).toEqual(pageSet);

        const scope = resolveBulkScope(input);

        if (input.allFilteredSelected) {
          // ── (2) "all filtered" → filter-defined scope across all pages
          //        (Req 44.3) carrying the covered count (Req 44.4).
          expect(scope.kind).toBe('all_filtered');
          if (scope.kind === 'all_filtered') {
            // The scope is defined by the active filter conditions, NOT by the
            // current page's enumerated ids.
            expect(scope.filters).toEqual(input.filters ?? null);

            // coveredCount equals the server total of matching records when the
            // total is known (Req 44.4); when unknown it falls back to the
            // rendered-page count so the operator still sees a coverage number.
            const expectedCovered =
              typeof input.total === 'number' &&
                Number.isFinite(input.total) &&
                input.total >= 0
                ? Math.floor(input.total)
                : pageIds.length;
            expect(scope.coveredCount).toBe(expectedCovered);
            expect(scope.coveredCount).toBe(resolveCoveredCount(input));
          }
        } else {
          // ── (3) otherwise → the explicit (hand-picked / current-page) id set
          //        (Req 44.2), and resolveBulkScope distinguishes the two
          //        choices (Req 44.1).
          expect(scope.kind).toBe('ids');
          if (scope.kind === 'ids') {
            expect(scope.ids).toEqual(dedupe(input.selectedIds));
            // Covered count for an explicit selection is its deduped size.
            expect(resolveCoveredCount(input)).toBe(
              dedupe(input.selectedIds).length,
            );
          }
        }
      }),
      { numRuns: NUM_RUNS },
    );
  });
});
