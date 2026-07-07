// AdPilot AI — Server-side pagination + sort helpers for the Shared_Data_Table
// (advertising-workspace-rework Req 31.1, 31.3, 33.1, 33.4)
//
// The advertising tables paginate and sort SERVER-SIDE: the table never re-pages
// or re-sorts the rows it already holds. It renders exactly the page the server
// returned, reports the server-supplied `total`, and emits page / page-size /
// sort change requests back to the host so the host can re-key its react-query
// read (and thus re-request the correct global slice — Req 33.1, 33.4).
//
// Everything here is PURE (no React, no DOM): it is display math + the sort-cycle
// transition, so the page-window labels and the "next sort direction" rule can be
// unit-/property-tested in isolation and reused by any table.

import type { SortDirection } from '../../lib/advertisingQueryState';

/** Selectable rows-per-page options offered by the pagination control. */
export const DEFAULT_PAGE_SIZE_OPTIONS = [25, 50, 100, 200] as const;

/**
 * Total number of pages for `total` records at `pageSize` per page. Always at
 * least 1 so an empty result still renders a single (empty) "page 1 of 1".
 * A non-positive / non-finite `pageSize` collapses to a single page.
 */
export function computeTotalPages(total: number, pageSize: number): number {
  const safeTotal = Number.isFinite(total) && total > 0 ? Math.floor(total) : 0;
  const safeSize = Number.isFinite(pageSize) && pageSize > 0 ? Math.floor(pageSize) : 0;
  if (safeSize === 0) return 1;
  return Math.max(1, Math.ceil(safeTotal / safeSize));
}

/** Clamp a 1-based page into `[1, totalPages]`. */
export function clampPage(page: number, totalPages: number): number {
  const pages = Number.isFinite(totalPages) && totalPages > 0 ? Math.floor(totalPages) : 1;
  const p = Number.isFinite(page) ? Math.floor(page) : 1;
  return Math.min(Math.max(1, p), pages);
}

/** The inclusive 1-based record indices shown on a given page. */
export interface PageRange {
  /** 1-based index of the first record on the page (0 when the result is empty). */
  start: number;
  /** 1-based index of the last record on the page (0 when the result is empty). */
  end: number;
  /** Total matching records across all pages. */
  total: number;
}

/**
 * Compute the `[start, end]` 1-based record window displayed on `page` given the
 * server `total`. With `total = 0` the window is `0..0`. The window never runs
 * past `total` on the final (possibly partial) page.
 */
export function pageRange(page: number, pageSize: number, total: number): PageRange {
  const safeTotal = Number.isFinite(total) && total > 0 ? Math.floor(total) : 0;
  const safeSize = Number.isFinite(pageSize) && pageSize > 0 ? Math.floor(pageSize) : 0;
  if (safeTotal === 0 || safeSize === 0) {
    return { start: safeTotal === 0 ? 0 : 1, end: safeTotal, total: safeTotal };
  }
  const totalPages = computeTotalPages(safeTotal, safeSize);
  const safePage = clampPage(page, totalPages);
  const start = (safePage - 1) * safeSize + 1;
  const end = Math.min(safePage * safeSize, safeTotal);
  return { start, end, total: safeTotal };
}

/** Whether a previous / next page exists relative to the (clamped) current page. */
export function hasPrevPage(page: number, totalPages: number): boolean {
  return clampPage(page, totalPages) > 1;
}
export function hasNextPage(page: number, totalPages: number): boolean {
  return clampPage(page, totalPages) < (Number.isFinite(totalPages) ? totalPages : 1);
}

/**
 * Next sort state when a sortable column header is activated. Cycling a column
 * goes `none → asc → desc → none`; activating a DIFFERENT column starts that
 * column at `asc`. Returns `null` to mean "no sort" (default order). The host
 * applies the returned sort server-side, so the table re-requests the correctly
 * ordered global slice rather than re-sorting the current page.
 */
export function nextSortState(
  current: { field: string; direction: SortDirection } | null,
  field: string,
): { field: string; direction: SortDirection } | null {
  if (!current || current.field !== field) {
    return { field, direction: 'asc' };
  }
  if (current.direction === 'asc') return { field, direction: 'desc' };
  // direction was 'desc' → cycle back to no sort
  return null;
}
