// Feature: platform-ux-logistics-enhancements, Property 15: At least one column always remains visible
//
// Validates: Requirements 2.2, 2.3
//
// For any sequence of column-configuration changes (hide, show, reorder), the
// set of rendered columns equals the configured visible columns in their
// configured order and is never empty; an attempt to hide the last remaining
// visible column is rejected, that column stays visible, and an indication is
// presented.
//
// This drives the real `useColumnConfig` hook (the engine behind
// SharedDataTable's Column_Configuration) through @testing-library/react's
// renderHook — no mocking — applying arbitrarily generated hide/show/toggle/
// reorder commands and asserting the invariant holds after every step.

import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import fc from 'fast-check';

import {
  useColumnConfig,
  AT_LEAST_ONE_VISIBLE_MESSAGE,
  type ColumnConfigItem,
  type UseColumnConfigResult,
} from './useColumnConfig';

const RUNS = 200; // comfortably above the required minimum of 100 iterations.

// A single column-configuration command. `keyIdx`/`toIdx` are unbounded naturals
// resolved against the actual column count inside the property so generation is
// independent of the (separately generated) number of columns.
const opArb = fc.record({
  kind: fc.constantFrom('hide', 'show', 'toggle', 'reorder') as fc.Arbitrary<
    'hide' | 'show' | 'toggle' | 'reorder'
  >,
  keyIdx: fc.nat(),
  toIdx: fc.nat(),
});

/**
 * Assert the standing invariants that must hold after every configuration step:
 * the rendered (visible) columns are exactly the configured visible columns in
 * the configured order, that order is the full order filtered to visible keys,
 * and at least one column is always visible.
 */
function assertInvariant(
  current: UseColumnConfigResult<ColumnConfigItem>,
  columnKeys: string[],
): void {
  // Never empty (Req 2.2/2.3 core guarantee).
  expect(current.visibleColumns.length).toBeGreaterThanOrEqual(1);

  // Rendered columns == configured visible columns, in the configured order.
  expect(current.visibleColumns.map((c) => c.key)).toEqual(current.visibleOrder);

  // visibleOrder is precisely the full order restricted to visible keys.
  expect(current.visibleOrder).toEqual(
    current.order.filter((k) => current.isVisible(k)),
  );

  // The order is a permutation of exactly the supplied column keys.
  expect([...current.order].sort()).toEqual([...columnKeys].sort());
}

describe('Feature: platform-ux-logistics-enhancements, Property 15: At least one column always remains visible', () => {
  it('keeps the rendered columns equal to the configured visible order and never empty across arbitrary changes', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 1, max: 6 }),
        fc.array(opArb, { maxLength: 30 }),
        (numColumns, rawOps) => {
          const columns: ColumnConfigItem[] = Array.from(
            { length: numColumns },
            (_, i) => ({ key: `col${i}` }),
          );
          const columnKeys = columns.map((c) => c.key);

          const { result } = renderHook(() => useColumnConfig(columns));

          // Invariant holds from the initial state.
          assertInvariant(result.current, columnKeys);

          for (const op of rawOps) {
            const key = `col${op.keyIdx % numColumns}`;

            // Snapshot the relevant pre-state to verify the last-visible guard.
            const wasVisible = result.current.isVisible(key);
            const visibleCountBefore = result.current.visibleOrder.length;
            const isSoleVisible = wasVisible && visibleCountBefore === 1;

            let returned = false;
            act(() => {
              switch (op.kind) {
                case 'hide':
                  returned = result.current.hideColumn(key);
                  break;
                case 'show':
                  returned = result.current.showColumn(key);
                  break;
                case 'toggle':
                  returned = result.current.toggleColumn(key);
                  break;
                case 'reorder':
                  returned = result.current.reorder(key, op.toIdx % numColumns);
                  break;
              }
            });

            // Attempting to hide (directly or via toggle) the last remaining
            // visible column must be rejected: no change, column stays visible,
            // and an indication is surfaced.
            if ((op.kind === 'hide' || op.kind === 'toggle') && isSoleVisible) {
              expect(returned).toBe(false);
              expect(result.current.isVisible(key)).toBe(true);
              expect(result.current.lastError).toBe(AT_LEAST_ONE_VISIBLE_MESSAGE);
            }

            // The standing invariant holds after every step regardless of op.
            assertInvariant(result.current, columnKeys);
          }
        },
      ),
      { numRuns: RUNS },
    );
  });
});
