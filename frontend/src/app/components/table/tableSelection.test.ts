// Unit tests for the pure cross-page selection-semantics helpers.
//
// Validates: Requirements 44.1, 44.2, 44.3, 44.4

import { describe, it, expect } from 'vitest';

import {
  selectCurrentPageIds,
  currentPageSelectionState,
  toggleCurrentPage,
  toggleRow,
  hasActiveSelection,
  canSelectAllFiltered,
  resolveCoveredCount,
  resolveBulkScope,
  selectionCountLabel,
  type SelectionInput,
} from './tableSelection';
import { emptyFilterState } from './types';

function input(overrides: Partial<SelectionInput> = {}): SelectionInput {
  return {
    selectedIds: [],
    allFilteredSelected: false,
    currentPageRowIds: ['a', 'b', 'c'],
    total: 100,
    filters: emptyFilterState(),
    ...overrides,
  };
}

describe('selectCurrentPageIds (Req 44.2)', () => {
  it('returns exactly the rendered rows, deduped and order-preserving', () => {
    expect(selectCurrentPageIds(['a', 'b', 'a', 'c'])).toEqual(['a', 'b', 'c']);
  });

  it('never includes rows outside the current page', () => {
    const page = ['p1', 'p2'];
    const result = selectCurrentPageIds(page);
    expect(result.every((id) => page.includes(id))).toBe(true);
  });
});

describe('currentPageSelectionState', () => {
  it('is none when no rendered row is selected', () => {
    expect(currentPageSelectionState(['x'], ['a', 'b'])).toBe('none');
  });
  it('is partial when some rendered rows are selected', () => {
    expect(currentPageSelectionState(['a'], ['a', 'b'])).toBe('partial');
  });
  it('is all when every rendered row is selected', () => {
    expect(currentPageSelectionState(['a', 'b'], ['a', 'b'])).toBe('all');
  });
  it('is none for an empty page', () => {
    expect(currentPageSelectionState([], [])).toBe('none');
  });
});

describe('toggleCurrentPage (Req 44.2)', () => {
  it('adds exactly the rendered rows when selecting', () => {
    expect(toggleCurrentPage(['z'], ['a', 'b'], true)).toEqual(['z', 'a', 'b']);
  });
  it('removes the rendered rows while keeping off-page selection when deselecting', () => {
    expect(toggleCurrentPage(['z', 'a', 'b'], ['a', 'b'], false)).toEqual(['z']);
  });
});

describe('toggleRow', () => {
  it('adds and removes a single id', () => {
    expect(toggleRow(['a'], 'b', true)).toEqual(['a', 'b']);
    expect(toggleRow(['a', 'b'], 'b', false)).toEqual(['a']);
  });
});

describe('resolveCoveredCount (Req 44.4)', () => {
  it('uses the explicit id count for a page selection', () => {
    expect(resolveCoveredCount(input({ selectedIds: ['a', 'b'] }))).toBe(2);
  });
  it('uses the server total for an all-filtered selection', () => {
    expect(
      resolveCoveredCount(input({ allFilteredSelected: true, total: 100 })),
    ).toBe(100);
  });
  it('falls back to the rendered count when the total is unknown', () => {
    expect(
      resolveCoveredCount(
        input({ allFilteredSelected: true, total: undefined }),
      ),
    ).toBe(3);
  });
});

describe('resolveBulkScope (Req 44.2, 44.3, 44.4)', () => {
  it('produces an ids scope for a hand-picked / current-page selection', () => {
    const scope = resolveBulkScope(input({ selectedIds: ['a', 'b'] }));
    expect(scope).toEqual({ kind: 'ids', ids: ['a', 'b'] });
  });

  it('produces a filter-defined scope spanning all matching records', () => {
    const filters = emptyFilterState();
    const scope = resolveBulkScope(
      input({ allFilteredSelected: true, total: 100, filters }),
    );
    expect(scope.kind).toBe('all_filtered');
    if (scope.kind === 'all_filtered') {
      expect(scope.filters).toBe(filters);
      expect(scope.coveredCount).toBe(100);
    }
  });

  it('does not use the current page ids as the all-filtered target', () => {
    const scope = resolveBulkScope(
      input({
        allFilteredSelected: true,
        selectedIds: ['a', 'b', 'c'],
        currentPageRowIds: ['a', 'b', 'c'],
        total: 100,
      }),
    );
    // The authoritative target is the filters + covered count, not the 3 ids.
    expect(scope.kind).toBe('all_filtered');
    if (scope.kind === 'all_filtered') expect(scope.coveredCount).toBe(100);
  });
});

describe('hasActiveSelection / canSelectAllFiltered (Req 44.1)', () => {
  it('reports an active selection for explicit ids or all-filtered', () => {
    expect(hasActiveSelection(input({ selectedIds: [] }))).toBe(false);
    expect(hasActiveSelection(input({ selectedIds: ['a'] }))).toBe(true);
    expect(
      hasActiveSelection(input({ selectedIds: [], allFilteredSelected: true })),
    ).toBe(true);
  });

  it('offers all-filtered only when more records exist beyond the page', () => {
    expect(canSelectAllFiltered(input({ total: 100 }))).toBe(true); // page 3 < 100
    expect(canSelectAllFiltered(input({ total: 3 }))).toBe(false); // page holds all
    expect(canSelectAllFiltered(input({ total: undefined }))).toBe(false);
    expect(
      canSelectAllFiltered(input({ allFilteredSelected: true, total: 100 })),
    ).toBe(false); // already all-filtered
  });
});

describe('selectionCountLabel (Req 44.4)', () => {
  it('distinguishes the two choices', () => {
    expect(selectionCountLabel(input({ selectedIds: ['a', 'b'] }))).toBe(
      '已选择 2 项',
    );
    expect(
      selectionCountLabel(input({ allFilteredSelected: true, total: 100 })),
    ).toBe('已选择全部筛选结果（共 100 项）');
  });
});
