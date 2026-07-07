// Feature: platform-ux-logistics-enhancements, Property 19: The bulk-action bar is visible exactly when rows are selected
//
// Validates: Requirements 1.4
//
// The Bulk_Action_Bar is a reusable, standalone table building block that must
// appear WHILE one or more rows are selected and be hidden when no rows are
// selected. This property exercises that visible-iff-non-empty invariant across
// arbitrarily generated selection sets: for any set of selected row identifiers,
// the rendered BulkActionBar is present (visible) if and only if the selection
// is non-empty, and produces no rendered output when the selection is empty.
//
// We drive the real <BulkActionBar /> component through @testing-library/react
// (matching the FilterToolbar / useApiQuery property-test conventions) and read
// visibility directly from the DOM — the bar exposes an ARIA toolbar
// (role="toolbar", aria-label="批量操作"); when hidden it renders nothing — so the
// assertion reflects exactly what an operator would see, with no mocking.

import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import fc from 'fast-check';

import { BulkActionBar, type BulkOperation } from './BulkActionBar';

const RUNS = 200; // well above the required minimum of 100 iterations.

// Always-applicable operation so the bar has at least one operation to expose
// when visible; its handler is irrelevant to the visibility property.
function makeOp(): BulkOperation {
  return { id: 'op', label: 'op', handler: vi.fn() };
}

// Selected row identifiers are opaque strings supplied by the host table's
// `rowId`. Exercise the full space: empty selection, single, and many ids,
// including duplicates and empty strings, so visibility depends only on the
// presence of *any* selection rather than on id content.
const selectionArb = fc.array(fc.string(), { maxLength: 25 });

// Read the bar's visibility from the DOM exactly as a user would perceive it:
// the rendered toolbar is the bar; its absence is a hidden bar.
function barIsVisible(): boolean {
  return screen.queryByRole('toolbar', { name: '批量操作' }) !== null;
}

afterEach(() => {
  cleanup();
});

describe('Feature: platform-ux-logistics-enhancements, Property 19: The bulk-action bar is visible exactly when rows are selected', () => {
  it('renders the bar if and only if one or more rows are selected', () => {
    fc.assert(
      fc.property(selectionArb, (selectedIds) => {
        const { container, unmount } = render(
          <BulkActionBar selectedIds={selectedIds} operations={[makeOp()]} />,
        );

        const visible = barIsVisible();
        const shouldBeVisible = selectedIds.length > 0;

        // The IFF: visible exactly when the selection is non-empty.
        expect(visible).toBe(shouldBeVisible);

        // When hidden, the component produces no rendered output at all;
        // when visible, it renders the toolbar element.
        if (shouldBeVisible) {
          expect(container.firstChild).not.toBeNull();
        } else {
          expect(container.firstChild).toBeNull();
        }

        unmount();
      }),
      { numRuns: RUNS },
    );
  });
});
