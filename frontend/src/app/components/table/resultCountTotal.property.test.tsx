// Property-based test: the displayed result count equals the SERVER total.
//
// Feature: advertising-workspace-rework, Property 64
//
// Property 64 — Result count equals server total:
//   The count the SharedDataTable shows the operator ("共 N 条记录", and the
//   pagination footer "共 N 条") must equal the server-reported `total`, NOT the
//   number of rows currently rendered on the page. Because the advertising
//   tables paginate SERVER-side (task 19.1), the table holds only the current
//   page's slice yet must report the size of the full filtered result set. The
//   count is driven by the pure pagination helpers
//   (`pageRange`/`computeTotalPages` in tablePagination.ts): the rendered total
//   is `total`, and the footer window's `.total` is `pageRange(...).total`.
//
//   We drive the REAL <SharedDataTable /> through @testing-library/react with no
//   mocking and read the count straight from the DOM, deliberately decoupling
//   the number of rendered rows from the server `total` (the page slice is
//   generated independently and is usually far smaller than `total`). For ANY
//   such combination the displayed count must equal `total` — and when the two
//   differ, the displayed count must NOT be the rendered row count.
//
// Validates: Requirements 32.2

import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import fc from 'fast-check';

import { SharedDataTable } from './SharedDataTable';
import { pageRange } from './tablePagination';
import type { ColumnDef } from './types';

const NUM_RUNS = 200; // well above the required minimum of 100 iterations.

interface Row {
  id: string;
  value: number;
}

const columns: ColumnDef<Row>[] = [
  { key: 'id', header: 'ID' },
  { key: 'value', header: '值' },
];

// The server-reported total size of the full filtered result set across all
// pages. Independent of how many rows the current page actually holds.
const totalArb: fc.Arbitrary<number> = fc.integer({ min: 0, max: 5_000 });

// Rows-per-page offered by the pagination control.
const pageSizeArb: fc.Arbitrary<number> = fc.constantFrom(25, 50, 100, 200);

// Requested page (the table clamps it into range internally).
const pageArb: fc.Arbitrary<number> = fc.integer({ min: 1, max: 250 });

// The number of rows the page currently holds — generated INDEPENDENTLY of
// `total` (capped at one page) so the rendered row count is almost always
// different from the server total. This is the crux of the property: the count
// shown must track `total`, never this rendered count.
const renderedRowsArb: fc.Arbitrary<Row[]> = fc
  .integer({ min: 0, max: 200 })
  .map((n) =>
    Array.from({ length: n }, (_, i) => ({ id: `row-${i}`, value: i })),
  );

afterEach(() => {
  cleanup();
});

describe('Feature: advertising-workspace-rework, Property 64: Result count equals server total', () => {
  it('shows the server total (not the rendered row count) in the count + pagination labels', () => {
    fc.assert(
      fc.property(
        totalArb,
        pageSizeArb,
        pageArb,
        renderedRowsArb,
        (total, pageSize, page, rows) => {
          const { unmount } = render(
            <SharedDataTable<Row>
              rows={rows}
              columns={columns}
              rowId={(r) => r.id}
              tableKey="result-count-total"
              total={total}
              page={page}
              pageSize={pageSize}
              // Supplying onPageChange renders the server-side pagination footer.
              onPageChange={() => { }}
            />,
          );

          // 1) The "共 N 条记录" summary reflects the SERVER total exactly.
          const totalEl = document.querySelector('[data-slot="table-total"]');
          expect(totalEl).not.toBeNull();
          expect(totalEl?.textContent).toBe(`共 ${total} 条记录`);

          // 2) The pagination footer window reports the same server total. Its
          //    `.total` comes from the pure `pageRange` helper.
          const range = pageRange(page, pageSize, total);
          expect(range.total).toBe(total);

          const paginationEl = document.querySelector(
            '[data-slot="table-pagination"]',
          );
          expect(paginationEl).not.toBeNull();
          const paginationText = paginationEl?.textContent ?? '';
          if (total > 0) {
            expect(paginationText).toContain(`共 ${total} 条`);
          } else {
            expect(paginationText).toContain('共 0 条');
          }

          // 3) The crux: the displayed count is the server total, NOT the number
          //    of rendered rows. Whenever they differ, confirm the count did not
          //    track the rendered row count.
          if (rows.length !== total) {
            expect(totalEl?.textContent).not.toBe(`共 ${rows.length} 条记录`);
          }

          unmount();
        },
      ),
      { numRuns: NUM_RUNS },
    );
  });
});
