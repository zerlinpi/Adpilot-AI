// Property-based test for the pure export scope + visible-column projection
// helpers (advertising-workspace-rework Req 37.2, 37.3, 37.4).
//
// Feature: advertising-workspace-rework, Property 70
//
// Property 70 — Export covers the chosen scope and visible columns only:
//   For any filtered result set, current page, and visible-column configuration,
//   `resolveExportDataset` produces a dataset where
//     1. scope `all_filtered` yields EXACTLY the full filtered set and scope
//        `current_page` yields EXACTLY the current page (row count + identity,
//        in order) — Req 37.2, 37.3;
//     2. the dataset's columns are EXACTLY the supplied visible columns, in the
//        same configured order, and any hidden column never appears — Req 37.4;
//     3. every produced row array aligns 1:1 with the visible columns (same
//        length, same per-column value), so there are no holes or misalignment.
//
// Validates: Requirements 37.2, 37.3, 37.4

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  resolveExportDataset,
  exportCellValue,
  type ExportColumn,
  type ExportScope,
} from './tableExport';

const NUM_RUNS = 200;

// A row carries a fixed set of candidate fields; the column config decides
// which of them are visible (and therefore exported).
interface Row {
  id: number;
  name: string;
  spend: number;
  hiddenNote: string;
  flag: boolean;
}

const rowArb: fc.Arbitrary<Row> = fc.record({
  id: fc.integer({ min: -1_000_000, max: 1_000_000 }),
  name: fc.string(),
  spend: fc.double({ min: -1_000, max: 1_000, noNaN: true }),
  hiddenNote: fc.string(),
  flag: fc.boolean(),
});

// Unique ids so row identity / ordering is unambiguous when we compare slices.
const rowsArb: fc.Arbitrary<Row[]> = fc.uniqueArray(rowArb, {
  selector: (r) => r.id,
  maxLength: 40,
});

// The full universe of selectable columns. A subset (in any order) becomes the
// "visible" config; the complement stays hidden and must never be exported.
const ALL_COLUMN_KEYS = ['id', 'name', 'spend', 'hiddenNote', 'flag'] as const;
type ColKey = (typeof ALL_COLUMN_KEYS)[number];

function makeColumn(key: ColKey): ExportColumn<Row> {
  // `spend` uses a custom exportValue to exercise that branch; the rest read row[key].
  if (key === 'spend') {
    return { key, header: `H:${key}`, exportValue: (r) => r.spend.toFixed(2) };
  }
  return { key, header: `H:${key}` };
}

// A visible-column configuration: a subset of the universe, no duplicate keys,
// in an arbitrary order (column order is operator-controlled).
const visibleColumnsArb: fc.Arbitrary<ExportColumn<Row>[]> = fc
  .uniqueArray(fc.constantFrom<ColKey>(...ALL_COLUMN_KEYS), {
    minLength: 0,
    maxLength: ALL_COLUMN_KEYS.length,
  })
  .chain((keys) =>
    // shuffle the chosen keys so order is not always the canonical order
    fc.shuffledSubarray(keys, { minLength: keys.length, maxLength: keys.length }),
  )
  .map((keys) => keys.map(makeColumn));

const scopeArb: fc.Arbitrary<ExportScope> = fc.constantFrom(
  'all_filtered',
  'current_page',
);

describe('Feature: advertising-workspace-rework, Property 70 — Export covers the chosen scope and visible columns only', () => {
  it('scope selects the exact row set and the dataset projects exactly the visible columns in order', () => {
    fc.assert(
      fc.property(
        scopeArb,
        rowsArb,
        rowsArb,
        visibleColumnsArb,
        (scope, allFilteredRows, currentPageRows, visibleColumns) => {
          const dataset = resolveExportDataset(
            scope,
            allFilteredRows,
            currentPageRows,
            visibleColumns,
          );

          // ── (1) scope selects the exact source row set (Req 37.2, 37.3) ──
          const expectedRows =
            scope === 'all_filtered' ? allFilteredRows : currentPageRows;
          expect(dataset.scope).toBe(scope);
          expect(dataset.rows).toHaveLength(expectedRows.length);

          // ── (2) columns are EXACTLY the visible columns, in order (Req 37.4) ──
          const expectedKeys = visibleColumns.map((c) => c.key);
          const expectedHeaders = visibleColumns.map((c) => c.header);
          expect(dataset.columnKeys).toEqual(expectedKeys);
          expect(dataset.headers).toEqual(expectedHeaders);

          // No hidden column ever leaks into the dataset.
          const hiddenKeys = ALL_COLUMN_KEYS.filter(
            (k) => !expectedKeys.includes(k),
          );
          for (const hidden of hiddenKeys) {
            expect(dataset.columnKeys).not.toContain(hidden);
          }

          // ── (3) every row aligns 1:1 with the visible columns ──
          dataset.rows.forEach((rowCells, rowIndex) => {
            expect(rowCells).toHaveLength(visibleColumns.length);
            const sourceRow = expectedRows[rowIndex];
            visibleColumns.forEach((col, colIndex) => {
              expect(rowCells[colIndex]).toEqual(
                exportCellValue(col, sourceRow),
              );
            });
          });
        },
      ),
      { numRuns: NUM_RUNS },
    );
  });
});
