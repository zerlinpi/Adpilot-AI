// Feature: platform-ux-logistics-enhancements, Property 16: Pinned columns are held at the start of the table
//
// Validates: Requirements 2.4
//
// For any selection of 1 to 5 pinnable columns designated as pinned, those
// columns are rendered HELD FIXED AT THE START of the table while the remaining
// columns scroll horizontally. The SharedDataTable freezes a pinned column by
// anchoring it to the table start with sticky positioning (the `sticky left-0`
// utility) and marking it `data-pinned="true"`; a non-pinned column carries
// neither marker, so it participates in the horizontal scroll region.
//
// This property drives the real <SharedDataTable /> through
// @testing-library/react (no mocking) and reads the rendered header (<th>) and
// body (<td>) cells directly from the DOM — exactly what an operator sees —
// asserting, across an arbitrarily generated column set and pinned selection:
//
//   1. PRESERVED LAYOUT: every supplied column is rendered, in its configured
//      order (no column is dropped or reordered away).
//   2. HELD AT THE START (iff): a cell is anchored to the start of the table
//      (data-pinned + sticky + left-0) if and only if its column was pinned —
//      pinned columns are held fixed at the start, the rest scroll.
//   3. BOUND: at most 5 columns are ever pinned (Req 2.4).
//
// Both the header row and the first body row are checked so the freeze applies
// to the whole column, not just its header.

import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import fc from 'fast-check';

import { SharedDataTable, MAX_PINNED_COLUMNS } from './SharedDataTable';
import type { ColumnDef } from './types';

const RUNS = 200; // well above the required minimum of 100 iterations.

interface DemoRow {
  id: string;
  [key: string]: string;
}

afterEach(() => {
  cleanup();
});

// Whether a rendered cell is "held fixed at the start of the table": it is
// marked pinned AND anchored to the start via sticky positioning (`sticky` +
// `left-0`). A non-pinned, scrolling cell carries none of these.
function isHeldAtStart(el: Element): boolean {
  const dataPinned = el.getAttribute('data-pinned') === 'true';
  const cls = el.getAttribute('class') ?? '';
  const startAnchored = /(?:^|\s)sticky(?:\s|$)/.test(cls) && /(?:^|\s)left-0(?:\s|$)/.test(cls);
  return dataPinned && startAnchored;
}

// Whether a cell carries ANY freeze marker — used to confirm non-pinned cells
// are entirely free to scroll (no data-pinned, no sticky anchoring).
function hasAnyFreezeMarker(el: Element): boolean {
  const dataPinned = el.getAttribute('data-pinned') === 'true';
  const cls = el.getAttribute('class') ?? '';
  return dataPinned || /(?:^|\s)sticky(?:\s|$)/.test(cls) || /(?:^|\s)left-0(?:\s|$)/.test(cls);
}

describe('Feature: platform-ux-logistics-enhancements, Property 16: Pinned columns are held at the start of the table', () => {
  it('holds exactly the pinned columns fixed at the start while the rest scroll, capped at five', () => {
    fc.assert(
      fc.property(
        fc
          // 2..12 distinct columns; every column is pinnable so any subset is a
          // legal pin selection.
          .integer({ min: 2, max: 12 })
          .chain((n) => {
            const columns: ColumnDef<DemoRow>[] = Array.from({ length: n }, (_, i) => ({
              key: `c${i}`,
              header: `H${i}`,
              pinnable: true,
            }));
            const allKeys = columns.map((c) => c.key);
            const maxPick = Math.min(MAX_PINNED_COLUMNS, n);
            // Pick 1..5 keys to pin, then shuffle so the SELECTION order differs
            // from the configured order — the freeze must depend on identity,
            // not on the order the keys were supplied in.
            return fc
              .subarray(allKeys, { minLength: 1, maxLength: maxPick })
              .chain((picked) =>
                fc.shuffledSubarray(picked, {
                  minLength: picked.length,
                  maxLength: picked.length,
                }),
              )
              .map((pinnedColumnKeys) => ({ columns, pinnedColumnKeys }));
          }),
        ({ columns, pinnedColumnKeys }) => {
          const pinnedSet = new Set(pinnedColumnKeys);
          // header text -> column key, so DOM cells can be mapped back to columns.
          const headerToKey = new Map(columns.map((c) => [c.header, c.key] as const));

          const rows: DemoRow[] = [{ id: 'r1' }, { id: 'r2' }];

          const { container, unmount } = render(
            <SharedDataTable
              rows={rows}
              columns={columns}
              rowId={(r) => r.id}
              tableKey="pinned-prop"
              pinnedColumnKeys={pinnedColumnKeys}
            />,
          );

          // (1) PRESERVED LAYOUT: all columns rendered, in configured order.
          const headerCells = Array.from(
            container.querySelectorAll('thead th'),
          ) as HTMLElement[];
          const renderedHeaders = headerCells.map((th) => th.textContent ?? '');
          expect(renderedHeaders).toEqual(columns.map((c) => c.header));

          // (2) HELD-AT-START iff pinned — checked on the header row.
          headerCells.forEach((th) => {
            const key = headerToKey.get(th.textContent ?? '');
            const shouldBePinned = key !== undefined && pinnedSet.has(key);
            expect(isHeldAtStart(th)).toBe(shouldBePinned);
            if (!shouldBePinned) {
              // A scrolling column must carry no freeze marker at all.
              expect(hasAnyFreezeMarker(th)).toBe(false);
            }
          });

          // (2) HELD-AT-START iff pinned — also on the first body row, so the
          // freeze covers the whole column, not just its header.
          const firstBodyRow = container.querySelector('tbody tr') as HTMLElement;
          const bodyCells = Array.from(
            firstBodyRow.querySelectorAll('td'),
          ) as HTMLElement[];
          // Body cells align 1:1 with the configured columns (no selection col).
          expect(bodyCells.length).toBe(columns.length);
          bodyCells.forEach((td, idx) => {
            const shouldBePinned = pinnedSet.has(columns[idx].key);
            expect(isHeldAtStart(td)).toBe(shouldBePinned);
            if (!shouldBePinned) {
              expect(hasAnyFreezeMarker(td)).toBe(false);
            }
          });

          // (3) BOUND: never more than five columns are pinned (Req 2.4).
          const pinnedRendered = headerCells.filter((th) => isHeldAtStart(th));
          expect(pinnedRendered.length).toBeLessThanOrEqual(MAX_PINNED_COLUMNS);
          // And exactly the selected, in-range pinned columns are frozen.
          expect(pinnedRendered.length).toBe(pinnedSet.size);

          unmount();
        },
      ),
      { numRuns: RUNS },
    );
  });
});
