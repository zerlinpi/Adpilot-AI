// Feature: platform-ux-logistics-enhancements, Property 14: A bulk operation receives exactly the selected row identifiers
//
// Validates: Requirements 2.5
//
// Requirement 2.5: WHEN an operator selects rows and activates a Bulk_Operation
// exposed by the host page, THE Shared_Data_Table SHALL pass the set of selected
// row identifiers to that operation.
//
// The reusable table building blocks deliver a selection to a bulk operation
// through the standalone `BulkActionBar` module (and its pure
// `activateBulkOperation` contract). A `SharedDataTable` composes this same
// module: it accumulates selected row ids (resolved via its `rowId`) and hands
// them to the activated operation's handler. This property therefore exercises
// the exact selection-delivery contract a SharedDataTable relies on.
//
// "Exactly" is asserted strictly: when an operator activates an operation, its
// handler must be invoked with the selected row identifiers and nothing else —
// same elements, same order, no additions, no omissions — and other operations'
// handlers must not be invoked. We drive the real <BulkActionBar /> through
// @testing-library/react with no mocking of the component itself (only spy
// handlers, which stand in for host-page operations), matching the existing
// table property-test conventions.

import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import fc from 'fast-check';

import {
  BulkActionBar,
  activateBulkOperation,
  type BulkOperation,
} from './BulkActionBar';

const RUNS = 200; // well above the required minimum of 100 iterations.

afterEach(() => {
  cleanup();
});

// Row identifiers are opaque strings produced by a host table's `rowId`, which
// yields a distinct identifier per row. Model a real selection as a non-empty
// set of unique identifiers (a bulk operation is only meaningful with at least
// one selected row — the empty case is Req 2.6, covered elsewhere).
const selectionArb = fc
  .uniqueArray(fc.string({ minLength: 1 }), { minLength: 1, maxLength: 25 })
  .map((ids) => ids);

describe('Feature: platform-ux-logistics-enhancements, Property 14: A bulk operation receives exactly the selected row identifiers', () => {
  it('invokes the activated operation handler with exactly the selected row ids (component)', () => {
    fc.assert(
      fc.property(
        selectionArb,
        // Index of the operation the operator activates among several exposed.
        fc.nat(),
        (selectedIds, opSeed) => {
          // Expose several operations so we can confirm that only the activated
          // one receives the selection, and it receives exactly the selection.
          const handlers = [vi.fn(), vi.fn(), vi.fn()];
          const operations: BulkOperation[] = handlers.map((handler, i) => ({
            id: `op-${i}`,
            label: `op-${i}`,
            handler,
          }));
          const activeIdx = opSeed % operations.length;

          const { unmount } = render(
            <BulkActionBar selectedIds={selectedIds} operations={operations} />,
          );

          // Activate the operation exactly as an operator would: a click on its
          // button in the rendered bar.
          fireEvent.click(
            screen.getByRole('button', { name: `op-${activeIdx}` }),
          );

          // The activated operation received exactly the selected identifiers:
          // same elements, same order, nothing added or removed.
          expect(handlers[activeIdx]).toHaveBeenCalledTimes(1);
          expect(handlers[activeIdx]).toHaveBeenCalledWith(selectedIds);
          const received = handlers[activeIdx].mock.calls[0][0] as string[];
          expect(received).toEqual(selectedIds);
          expect([...received].sort()).toEqual([...selectedIds].sort());

          // No other operation's handler was invoked.
          handlers.forEach((h, i) => {
            if (i !== activeIdx) expect(h).not.toHaveBeenCalled();
          });

          unmount();
        },
      ),
      { numRuns: RUNS },
    );
  });

  it('passes exactly the selected row ids to the operation contract, decoupled from caller mutation (pure)', () => {
    fc.assert(
      fc.property(selectionArb, (selectedIds) => {
        const handler = vi.fn();
        const op: BulkOperation = { id: 'op', label: 'op', handler };

        const result = activateBulkOperation(op, selectedIds);

        // The operation was invoked with exactly the selected identifiers.
        expect(result.invoked).toBe(true);
        expect(handler).toHaveBeenCalledTimes(1);
        const received = handler.mock.calls[0][0] as string[];
        expect(received).toEqual(selectedIds);

        // The handler receives a defensive copy: mutating the original
        // selection afterwards must not change what the operation received,
        // so the operation truly got the set selected at activation time.
        const snapshot = [...received];
        selectedIds.push('__mutated__');
        expect(received).toEqual(snapshot);
      }),
      { numRuns: RUNS },
    );
  });
});
