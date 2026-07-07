// Property-based test for the pure server-side pagination + sort helpers
// (advertising-workspace-rework Req 33.1, 33.4).
//
// Feature: advertising-workspace-rework, Property 37
//
// Property 37 — Server-side pagination and sort return the correct global slice:
//   For any dataset, page parameters, and sort key, the page the table renders
//   equals the corresponding slice of the GLOBALLY sorted, fully filtered result
//   set — not merely the current page re-sorted. The advertising tables paginate
//   and sort server-side, so the host:
//     1. derives the next sort from `nextSortState` (none→asc→desc→none; a new
//        column starts at asc), and
//     2. asks the server for the page window described by `pageRange` /
//        `computeTotalPages` / `clampPage`.
//   This test pins both pieces together: applying the cycled sort globally and
//   then taking the page-window slice must reproduce exactly the global slice
//   `globallySorted[(page-1)*size .. page*size]`.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  computeTotalPages,
  clampPage,
  pageRange,
  hasPrevPage,
  hasNextPage,
  nextSortState,
} from './tablePagination';
import type { SortDirection } from '../../lib/advertisingQueryState';

const NUM_RUNS = 200;

interface Row {
  id: number;
  value: number;
}

type SortKey = 'value' | 'id';

// A globally-sorted+filtered result set the server would hold. Unique `id`s keep
// the global ordering deterministic so slice equality is unambiguous.
const dataArb: fc.Arbitrary<Row[]> = fc.uniqueArray(
  fc.record({
    id: fc.integer({ min: -1_000_000, max: 1_000_000 }),
    value: fc.integer({ min: -1_000, max: 1_000 }),
  }),
  { selector: (r) => r.id, maxLength: 60 },
);

// Valid rows-per-page; the global-slice equality is only meaningful for a real
// page size (defaulting/clamping of invalid sizes is covered by unit tests).
const pageSizeArb: fc.Arbitrary<number> = fc.integer({ min: 1, max: 100 });

// Requested page deliberately ranges below 1 and far past the last page so the
// clamping path is exercised alongside in-range pages.
const pageArb: fc.Arbitrary<number> = fc.integer({ min: -5, max: 200 });

const sortFieldArb: fc.Arbitrary<SortKey> = fc.constantFrom('value', 'id');

const currentSortArb: fc.Arbitrary<{ field: SortKey; direction: SortDirection } | null> =
  fc.oneof(
    fc.constant(null),
    fc.record({
      field: sortFieldArb,
      direction: fc.constantFrom<SortDirection>('asc', 'desc'),
    }),
  );

/**
 * Reference model: sort the FULL result set globally. `null` sort = the table's
 * default order (by id asc). A real sort orders by the chosen field, with id as
 * a stable tiebreak.
 */
function sortGlobally(
  data: Row[],
  sort: { field: SortKey; direction: SortDirection } | null,
): Row[] {
  const arr = [...data];
  if (!sort) {
    arr.sort((a, b) => a.id - b.id);
    return arr;
  }
  const dir = sort.direction === 'asc' ? 1 : -1;
  arr.sort((a, b) => {
    const av = a[sort.field];
    const bv = b[sort.field];
    if (av < bv) return -1 * dir;
    if (av > bv) return 1 * dir;
    return a.id - b.id;
  });
  return arr;
}

describe('Feature: advertising-workspace-rework, Property 37 — Server-side pagination and sort return the correct global slice', () => {
  it('the cycled sort applied globally + the page window reproduce the exact global slice', () => {
    fc.assert(
      fc.property(
        dataArb,
        pageSizeArb,
        pageArb,
        currentSortArb,
        sortFieldArb,
        (data, pageSize, page, currentSort, clickedField) => {
          // ── sort cycle: none → asc → desc → none; new column starts at asc ──
          const next = nextSortState(currentSort, clickedField);
          if (!currentSort || currentSort.field !== clickedField) {
            expect(next).toEqual({ field: clickedField, direction: 'asc' });
          } else if (currentSort.direction === 'asc') {
            expect(next).toEqual({ field: clickedField, direction: 'desc' });
          } else {
            expect(next).toBeNull();
          }

          // The host applies `next` server-side, so the global ordering the
          // server pages over is exactly this sort (or default order when null).
          const effectiveSort = next as
            | { field: SortKey; direction: SortDirection }
            | null;
          const globallySorted = sortGlobally(data, effectiveSort);
          const total = globallySorted.length;

          // ── pagination math ──
          const totalPages = computeTotalPages(total, pageSize);
          const clamped = clampPage(page, totalPages);
          const range = pageRange(page, pageSize, total);

          // The slice the server should return for this page.
          const expectedSlice = globallySorted.slice(
            (clamped - 1) * pageSize,
            clamped * pageSize,
          );

          if (total === 0) {
            expect(range).toEqual({ start: 0, end: 0, total: 0 });
            expect(expectedSlice).toEqual([]);
          } else {
            // The displayed window [start, end] maps to exactly the global slice
            // — the page is a slice of the GLOBALLY sorted set, never the current
            // page re-sorted.
            const pageRows = globallySorted.slice(range.start - 1, range.end);
            expect(pageRows).toEqual(expectedSlice);

            // Window bounds are correct and never overrun the final page.
            expect(range.start).toBe((clamped - 1) * pageSize + 1);
            expect(range.end).toBe(Math.min(clamped * pageSize, total));
            expect(range.total).toBe(total);
            expect(range.end - range.start + 1).toBeLessThanOrEqual(pageSize);
          }

          // ── navigation flags follow the clamped page ──
          expect(hasPrevPage(page, totalPages)).toBe(clamped > 1);
          expect(hasNextPage(page, totalPages)).toBe(clamped < totalPages);
        },
      ),
      { numRuns: NUM_RUNS },
    );
  });
});
