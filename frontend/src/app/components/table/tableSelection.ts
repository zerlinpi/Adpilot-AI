// AdPilot AI — Cross-page selection semantics for the Shared_Data_Table
// (advertising-workspace-rework Req 44.1–44.4).
//
// Everything here is PURE (no React, no DOM, no network) so the selection
// semantics can be unit-/property-tested in isolation (see task 19.14, design
// Property 68) and reused by any advertising surface.
//
// The model distinguishes the two operator choices Requirement 44 mandates:
//
//   - "select current page"        — includes ONLY the rows rendered on the
//                                     current page (Req 44.2). Modeled as an
//                                     explicit set of row ids.
//   - "select all filtered results"— defined by the ACTIVE FILTER CONDITIONS
//                                     across all pages, not by an enumerated id
//                                     set (Req 44.3). A Bulk_Operation built
//                                     from this selection therefore carries the
//                                     filters so the backend can apply it to
//                                     EVERY matching record, and the operator
//                                     sees the covered count (Req 44.4).
//
// Validates: Requirements 44.1, 44.2, 44.3, 44.4

import type { FilterState } from './types';

/**
 * The tri-state of the current page's selection, used to drive a header
 * "select page" checkbox (checked / indeterminate / unchecked).
 *
 *   - `none`    — no rendered row is selected
 *   - `partial` — some but not all rendered rows are selected (indeterminate)
 *   - `all`     — every rendered row is selected
 */
export type PageSelectionState = 'none' | 'partial' | 'all';

/**
 * The inputs that fully determine the cross-page selection. Held by the host
 * table as two pieces of state — an explicit id set plus the "all filtered"
 * flag — alongside the page's rendered ids and the server total.
 */
export interface SelectionInput {
  /** Explicitly selected row ids (meaningful for the "current page" choice). */
  selectedIds: readonly string[];
  /** Whether "select all filtered results" is active (Req 44.3). */
  allFilteredSelected: boolean;
  /** The ids of the rows rendered on the current page (Req 44.2). */
  currentPageRowIds: readonly string[];
  /** Total records matching the active filters across all pages (Req 44.4). */
  total?: number;
  /** The active filter conditions defining the "all filtered" selection (Req 44.3). */
  filters?: FilterState | null;
}

/**
 * A Bulk_Operation's resolved selection scope — the discriminated payload the
 * BulkActionBar hands to an operation so the host applies it correctly:
 *
 *   - `ids`          — apply to exactly these explicit row ids (the
 *                      "current page" / hand-picked selection).
 *   - `all_filtered` — apply to EVERY record matching `filters` across all
 *                      pages; `coveredCount` is how many that covers (Req 44.3,
 *                      44.4). The id set on the current page is NOT the
 *                      authoritative target — the filters are.
 */
export type BulkSelectionScope =
  | { kind: 'ids'; ids: string[] }
  | {
    kind: 'all_filtered';
    filters: FilterState | null;
    coveredCount: number;
  };

/** Deduplicate while preserving first-seen order. */
function dedupe(ids: readonly string[]): string[] {
  return [...new Set(ids)];
}

/** A non-negative integer total, or `undefined` when unknown. */
function normalizeTotal(total: number | undefined): number | undefined {
  if (typeof total !== 'number' || !Number.isFinite(total) || total < 0) {
    return undefined;
  }
  return Math.floor(total);
}

/**
 * The ids produced by choosing "select current page": EXACTLY the rendered
 * rows, deduped and order-preserving — never any row outside the current page
 * (Req 44.2). Pure and non-mutating.
 *
 * Validates: Requirements 44.2
 */
export function selectCurrentPageIds(
  currentPageRowIds: readonly string[],
): string[] {
  return dedupe(currentPageRowIds);
}

/**
 * The tri-state of the current page's selection given the explicit id set,
 * used to drive the header "select page" checkbox. An empty page is `none`.
 */
export function currentPageSelectionState(
  selectedIds: readonly string[],
  currentPageRowIds: readonly string[],
): PageSelectionState {
  const page = dedupe(currentPageRowIds);
  if (page.length === 0) return 'none';
  const selected = new Set(selectedIds);
  const selectedOnPage = page.filter((id) => selected.has(id)).length;
  if (selectedOnPage === 0) return 'none';
  if (selectedOnPage === page.length) return 'all';
  return 'partial';
}

/**
 * Toggle the whole current page in/out of the explicit selection. Selecting
 * adds exactly the rendered rows (Req 44.2); deselecting removes them while
 * leaving any off-page selection untouched. Pure; returns a fresh array.
 */
export function toggleCurrentPage(
  selectedIds: readonly string[],
  currentPageRowIds: readonly string[],
  select: boolean,
): string[] {
  const page = new Set(currentPageRowIds);
  if (select) {
    return dedupe([...selectedIds, ...currentPageRowIds]);
  }
  return dedupe(selectedIds.filter((id) => !page.has(id)));
}

/**
 * Toggle a single row in/out of the explicit selection. Pure; returns a fresh
 * array. (Hand-picking rows is always an explicit-id selection.)
 */
export function toggleRow(
  selectedIds: readonly string[],
  id: string,
  checked: boolean,
): string[] {
  const set = new Set(selectedIds);
  if (checked) set.add(id);
  else set.delete(id);
  return [...set];
}

/**
 * Whether there is any active selection at all — either explicit ids are
 * selected or "all filtered results" is active. Drives BulkActionBar
 * visibility.
 */
export function hasActiveSelection(input: SelectionInput): boolean {
  return input.allFilteredSelected || input.selectedIds.length > 0;
}

/**
 * Whether the "select all filtered results" affordance is meaningful — i.e. the
 * filtered result set spans MORE records than the current page renders, so the
 * operator can broaden the current-page selection to cover every matching
 * record (Req 44.1, 44.3). When the page already holds every matching record,
 * the two choices coincide and the affordance is unnecessary.
 */
export function canSelectAllFiltered(input: SelectionInput): boolean {
  if (input.allFilteredSelected) return false;
  const total = normalizeTotal(input.total);
  const pageCount = dedupe(input.currentPageRowIds).length;
  if (total === undefined) return false;
  return total > pageCount;
}

/**
 * The number of records the current selection COVERS (Req 44.4):
 *
 *   - "all filtered results" → the server total of matching records (falling
 *     back to the rendered-page count when the total is unknown), because the
 *     selection spans every matching record across all pages;
 *   - otherwise               → the number of explicitly selected ids.
 *
 * Pure and total.
 *
 * Validates: Requirements 44.4
 */
export function resolveCoveredCount(input: SelectionInput): number {
  if (input.allFilteredSelected) {
    const total = normalizeTotal(input.total);
    return total ?? dedupe(input.currentPageRowIds).length;
  }
  return dedupe(input.selectedIds).length;
}

/**
 * Resolve the {@link BulkSelectionScope} a Bulk_Operation should act on:
 *
 *   - when "all filtered results" is active, the scope is filter-defined and
 *     carries the covered count so the backend applies the operation to EVERY
 *     matching record across all pages (Req 44.3, 44.4) — the page's id set is
 *     deliberately NOT used as the target;
 *   - otherwise the scope is the explicit set of selected row ids (the
 *     hand-picked / current-page selection, Req 44.2).
 *
 * Pure and non-mutating.
 *
 * Validates: Requirements 44.2, 44.3, 44.4
 */
export function resolveBulkScope(input: SelectionInput): BulkSelectionScope {
  if (input.allFilteredSelected) {
    return {
      kind: 'all_filtered',
      filters: input.filters ?? null,
      coveredCount: resolveCoveredCount(input),
    };
  }
  return { kind: 'ids', ids: dedupe(input.selectedIds) };
}

/**
 * A human-facing label for the current selection count, distinguishing the two
 * choices so the operator always knows what a bulk action will hit (Req 44.4).
 */
export function selectionCountLabel(input: SelectionInput): string {
  const count = resolveCoveredCount(input);
  return input.allFilteredSelected
    ? `已选择全部筛选结果（共 ${count} 项）`
    : `已选择 ${count} 项`;
}
