// Feature: platform-ux-logistics-enhancements, Property 18: Empty result sets render an empty state, never an error
//
// Validates: Requirements 1.6, 2.15
//
// When a host page supplies zero rows for the current filters, the
// SharedDataTable must render an EMPTY-STATE view rather than an ERROR state
// (Req 1.6 for a Campaigns_Workspace tab; Req 2.15 for the reusable table).
// This property exercises that invariant across arbitrarily generated column
// sets, table keys, optional bulk operations, and an optional custom
// empty-state node: for ANY empty row set (with no error and not loading), the
// rendered table shows an empty-state region and produces NO error indicator.
//
// We drive the real <SharedDataTable /> through @testing-library/react with no
// mocking (matching the BulkActionBar property-test conventions) and read the
// outcome directly from the DOM — the empty state renders a `status` region
// (data-slot="table-empty", or the host's custom node) while the error state
// renders an `alert` (data-slot="table-error"). The assertion therefore
// reflects exactly what an operator would see.

import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import fc from 'fast-check';

import { SharedDataTable } from './SharedDataTable';
import type { BulkOperation } from './BulkActionBar';
import type { ColumnDef } from './types';

const RUNS = 200; // well above the required minimum of 100 iterations.

interface Row {
  id: string;
  [key: string]: unknown;
}

// Arbitrary, non-empty set of column definitions with distinct keys. The empty
// state must hold regardless of how many columns the host configured.
const columnsArb = fc
  .uniqueArray(
    fc.string({ minLength: 1, maxLength: 8 }).filter((s) => s.trim().length > 0),
    { minLength: 1, maxLength: 6 },
  )
  .map<ColumnDef<Row>[]>((keys) =>
    keys.map((key) => ({ key, header: `列-${key}` })),
  );

// A bulk operation makes the table render a selection column + bulk bar; its
// presence must not affect the empty-state behavior.
function makeOp(): BulkOperation<Row> {
  return { id: 'op', label: 'op', handler: vi.fn() };
}

const bulkArb = fc.option(fc.constant([makeOp()]), { nil: undefined });

// Optionally supply a custom empty-state node. When present it must be the
// rendered empty view; when absent the default empty view is used. Either way,
// no error must appear.
const customEmptyArb = fc.option(
  fc.string({ minLength: 1, maxLength: 20 }).map((text) => ({ text })),
  { nil: undefined },
);

const tableKeyArb = fc.string({ minLength: 1, maxLength: 16 });

const errorIsPresent = () =>
  screen.queryByRole('alert') !== null ||
  document.querySelector('[data-slot="table-error"]') !== null;

afterEach(() => {
  cleanup();
});

describe('Feature: platform-ux-logistics-enhancements, Property 18: Empty result sets render an empty state, never an error', () => {
  it('renders an empty-state view (never an error) for any zero-row result set', () => {
    fc.assert(
      fc.property(
        columnsArb,
        tableKeyArb,
        bulkArb,
        customEmptyArb,
        (columns, tableKey, bulkOperations, custom) => {
          const emptyMarker = '__EMPTY_STATE_MARKER__';
          const emptyState = custom ? (
            <div data-slot="table-empty">{`${emptyMarker}:${custom.text}`}</div>
          ) : undefined;

          const { unmount } = render(
            <SharedDataTable<Row>
              rows={[]}
              columns={columns}
              rowId={(r) => r.id}
              tableKey={tableKey}
              // No error and not loading: this is a genuine zero-result set.
              loading={false}
              error={null}
              bulkOperations={bulkOperations}
              emptyState={emptyState}
            />,
          );

          // 1) An empty-state view IS rendered.
          const emptyEl = document.querySelector('[data-slot="table-empty"]');
          expect(emptyEl).not.toBeNull();

          // 2) NO error indicator is rendered for an empty result set.
          expect(errorIsPresent()).toBe(false);

          // 3) The data table itself is not rendered when empty.
          expect(document.querySelector('[data-slot="table"]')).toBeNull();

          // 4) A supplied custom empty node is the one shown.
          if (custom) {
            expect(emptyEl?.textContent).toContain(emptyMarker);
          }

          unmount();
        },
      ),
      { numRuns: RUNS },
    );
  });
});
