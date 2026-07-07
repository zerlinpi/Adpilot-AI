// SharedDataTable — a reusable, standalone table building block (Req 1.9).
//
// Renders a supplied set of records as rows and a supplied set of column
// definitions as columns (Req 2.1). It composes the other reusable building
// blocks (FilterToolbar, BulkActionBar, useColumnConfig) and resolves each view
// to EXACTLY ONE explicit host-driven UI state via the pure `resolveViewState`
// (Req 41.1–41.5):
//
//   - loading        -> skeleton placeholder, refresh disabled    (Req 1.5, 41.1)
//   - empty          -> empty-state view, NEVER an error           (Req 1.6, 2.15, 41.2)
//   - partial-error  -> error indicator with a retry control; shows any loaded
//                       content alongside it                       (Req 1.7, 41.3)
//   - stale-cache    -> staleness notice above the (cached) content (Req 41.4)
//   - no-permission  -> no-permission view rather than an empty table (Req 41.5)
//   - content        -> the table itself
//
// It also disables any control that has initiated an async action until that
// action completes, so the same Operation cannot be submitted again
// (double-submission prevention, Req 36.3).
//
// It also supports 1–5 pinned (frozen) columns held fixed at the start of the
// table while the remaining columns scroll horizontally (Req 2.4), row
// selection that is passed to bulk operations (Req 2.5), and rejecting a bulk
// operation activated with no selection (Req 2.6).
//
// Validates: Requirements 1.5, 1.6, 1.7, 1.9, 2.1, 2.4, 2.5, 2.6, 2.15, 36.3, 41.1, 41.2, 41.3, 41.4, 41.5

import * as React from 'react';
import {
  RefreshCw,
  Inbox,
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  ChevronsUpDown,
  ArrowUp,
  ArrowDown,
  Lock,
} from 'lucide-react';

import { cn } from '../../lib/utils';
import type { SortState } from '../../lib/advertisingQueryState';
import { Button } from '../ui/button';
import { Checkbox } from '../ui/checkbox';
import { Skeleton } from '../ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../ui/table';

import { BulkActionBar, type BulkOperation } from './BulkActionBar';
import {
  selectCurrentPageIds,
  currentPageSelectionState,
  toggleCurrentPage,
  toggleRow as toggleRowSelection,
  hasActiveSelection,
  canSelectAllFiltered,
  resolveCoveredCount,
  resolveBulkScope,
  type SelectionInput,
  type BulkSelectionScope,
} from './tableSelection';
import { useColumnConfig } from './useColumnConfig';
import { ColumnManagementMenu } from './ColumnManagementMenu';
import { ExportMenu } from './ExportMenu';
import { SavedViewControl } from './SavedViewControl';
import type { SavedViewConfig } from './useSavedViews';
import {
  resolveExportDataset,
  type ExportScope,
  type ExportDataset,
} from './tableExport';
import { FilterToolbar } from './FilterToolbar';
import {
  partitionFilterFields,
  unsupportedFilterMessage,
} from './filterValidation';
import {
  computeTotalPages,
  clampPage,
  pageRange,
  hasPrevPage,
  hasNextPage,
  nextSortState,
  DEFAULT_PAGE_SIZE_OPTIONS,
} from './tablePagination';
import {
  resolvePinnedOffsets,
  formatMonetaryValue,
  formatMoney,
  DEFAULT_PINNED_COLUMN_WIDTH,
} from './tableFormatting';
import {
  resolveViewState,
  beginAction,
  completeAction,
  isControlInProgress,
  EMPTY_IN_PROGRESS,
  type InProgressControls,
} from './tableViewState';
import type {
  ColumnDef,
  FilterFieldDef,
  FilterState,
  TypeSelectorDef,
} from './types';

/** Maximum number of columns that may be pinned at once (Req 2.4). */
export const MAX_PINNED_COLUMNS = 5;

// Re-export the pure pinned-offset + currency-formatting helpers so hosts and
// property tests (tasks 19.12 / 19.13) can import them alongside the table.
export {
  resolvePinnedOffsets,
  formatMonetaryValue,
  formatMoney,
  DEFAULT_PINNED_COLUMN_WIDTH,
};
export type {
  PinnedColumnInput,
  ResolvedPinnedOffset,
  MonetaryValue,
} from './tableFormatting';

// Re-export the pure export-scope + visible-column resolution helpers so hosts
// and the property test (task 19.15, design Property 70) can import them
// alongside the table.
export {
  resolveExportDataset,
  selectExportRows,
  exportCellValue,
  exportDatasetToCsv,
  EXPORT_SCOPE_LABELS,
} from './tableExport';
export type { ExportScope, ExportDataset, ExportColumn } from './tableExport';

// Re-export the pure explicit UI-state resolution + in-progress control model
// so hosts and the property tests (tasks 19.16 / 19.17, Req 41.x / 36.3) can
// import them alongside the table.
export {
  resolveViewState,
  beginAction,
  completeAction,
  isControlInProgress,
  EMPTY_IN_PROGRESS,
} from './tableViewState';
export type {
  ViewState,
  ViewStateInputs,
  InProgressControls,
  BeginActionResult,
} from './tableViewState';

export type { SortState };

// Re-export the pure cross-page selection-semantics helpers so hosts and the
// property test (task 19.14, design Property 68) can import them alongside the
// table (Req 44.1–44.4).
export {
  selectCurrentPageIds,
  currentPageSelectionState,
  toggleCurrentPage,
  hasActiveSelection,
  canSelectAllFiltered,
  resolveCoveredCount,
  resolveBulkScope,
  selectionCountLabel,
} from './tableSelection';
export type {
  SelectionInput,
  BulkSelectionScope,
  PageSelectionState,
} from './tableSelection';

export interface SharedDataTableProps<Row> {
  /** The records to render as rows. */
  rows: Row[];
  /** The column definitions to render as columns. */
  columns: ColumnDef<Row>[];
  /** Resolves a stable identifier for a row (used for selection + React keys). */
  rowId: (row: Row) => string;
  /** Identity used for saved views / column configuration persistence. */
  tableKey: string;
  /** Total matching records across all pages (for pagination / select-all). */
  total?: number;

  // ── Server-side pagination (Req 31.1, 31.3, 33.1) ─────────────────────────
  /**
   * 1-based current page. When `onPageChange` is also supplied, a pagination
   * control is rendered that issues SERVER-side page requests via the callbacks
   * below (the table never re-pages the rows it already holds).
   */
  page?: number;
  /** Rows per page; drives the page-count math against `total`. */
  pageSize?: number;
  /** Notifies the host of a server-side page request (1-based). */
  onPageChange?: (page: number) => void;
  /** Notifies the host of a page-size change (resets to page 1, host-driven). */
  onPageSizeChange?: (pageSize: number) => void;
  /** Selectable rows-per-page options; defaults to {@link DEFAULT_PAGE_SIZE_OPTIONS}. */
  pageSizeOptions?: number[];

  // ── Server-side sort (Req 33.1, 33.4) ─────────────────────────────────────
  /** The active sort, or `null` for the server's default order. */
  sort?: SortState | null;
  /**
   * Notifies the host that a sortable column header was activated. The host
   * applies the new sort server-side so the correctly ordered global slice is
   * re-requested (the table does not re-sort its current rows).
   */
  onSortChange?: (sort: SortState | null) => void;

  /** While true, a skeleton placeholder is shown and refresh is disabled (Req 1.5). */
  loading?: boolean;
  /** When set, an error indicator with a retry control is shown (Req 1.7). */
  error?: string | null;
  /** Invoked when the retry control is activated (Req 1.7). */
  onRetry?: () => void;
  /** Invoked when the refresh control is activated (Req 1.8). */
  onRefresh?: () => void;

  // ── Explicit UI states (Req 41.3, 41.4, 41.5) ─────────────────────────────
  /**
   * When true, the logged-in user lacks permission to view this surface; a
   * no-permission state is shown rather than an empty table (Req 41.5).
   */
  noPermission?: boolean;
  /** Custom no-permission node; a default no-permission view is used when omitted. */
  noPermissionState?: React.ReactNode;
  /** Message shown in the default no-permission view. */
  noPermissionMessage?: string;
  /**
   * When true, the shown content is cached data known to be out of date; a
   * stale-cache indication is displayed above the content (Req 41.4).
   */
  stale?: boolean;
  /** Message shown in the stale-cache indication. */
  staleMessage?: string;

  /** Custom empty-state node; a default empty-state view is used when omitted (Req 1.6, 2.15). */
  emptyState?: React.ReactNode;

  /** Bulk operations exposed to the BulkActionBar (Req 2.5, 2.6). */
  bulkOperations?: BulkOperation<Row>[];
  /** Controlled selection; when omitted the table tracks selection internally. */
  selectedIds?: string[];
  /** Notifies the host when the selection changes. */
  onSelectionChange?: (selectedIds: string[]) => void;
  /**
   * Controlled "select all filtered results" flag (Req 44.3). When omitted the
   * table tracks it internally. When active, a bulk operation applies to EVERY
   * record matching the active filters across all pages, not just the rendered
   * rows.
   */
  allFilteredSelected?: boolean;
  /** Notifies the host when the "all filtered results" flag changes (Req 44.3). */
  onAllFilteredSelectedChange?: (allFilteredSelected: boolean) => void;

  /** Column keys to pin (frozen) at the start; clamped to {@link MAX_PINNED_COLUMNS} (Req 2.4). */
  pinnedColumnKeys?: string[];

  // ── Column management (Req 31.4, 32.1) ────────────────────────────────────
  /** Render the column-management entry + UI in the toolbar. */
  enableColumnManagement?: boolean;
  /** Column keys that should start hidden in the column configuration. */
  initialHiddenColumns?: string[];
  /** Explicit initial column order (by key); remaining columns keep definition order. */
  initialColumnOrder?: string[];
  /**
   * Notifies the host when the column configuration (order/visibility) changes,
   * so it can persist the configuration (e.g. via `saveColumnConfig`). Fires
   * only on an actual user-driven change, never on the initial mount. Optional
   * and backward-compatible: omitting it leaves behavior unchanged.
   */
  onColumnConfigChange?: (config: { order: string[]; hidden: string[] }) => void;

  // ── Export (Req 31.5, 32.1, 37.x) ─────────────────────────────────────────
  /**
   * When provided, an export entry is rendered that discloses scope
   * ("current page" vs "all filtered rows") before producing the file
   * (Req 37.1). The host receives the resolved {@link ExportDataset} — already
   * scoped and projected to ONLY the currently visible columns in order
   * (Req 37.2, 37.3, 37.4) — and performs the actual file production.
   */
  onExport?: (scope: ExportScope, dataset: ExportDataset) => void;
  /**
   * The full filtered result set across all pages, used when the operator
   * exports "all filtered rows" (Req 37.2). Defaults to the current `rows` when
   * omitted (e.g. when the table already holds every filtered row).
   */
  allFilteredRows?: Row[];

  // ── Saved views (Req 31.6, 34.3) ──────────────────────────────────────────
  /** Render the saved-view entry; saved views are scoped to `(user_id, tableKey)`. */
  enableSavedViews?: boolean;
  /**
   * Applies a decoded saved view's filters and sort to the host (the table
   * applies the saved column configuration itself). The host re-requests data
   * with the restored filters/sort.
   */
  onApplySavedView?: (config: SavedViewConfig) => void;

  /** Optional filter toolbar wiring (Req 1.3). */
  filterState?: FilterState;
  onFilterChange?: (next: FilterState) => void;
  typeSelectors?: TypeSelectorDef[];
  filterableFields?: FilterFieldDef[];
  searchPlaceholder?: string;

  className?: string;
}

const DEFAULT_EMPTY_TITLE = '暂无数据';
const DEFAULT_EMPTY_DESCRIPTION = '当前筛选条件下没有匹配的记录';
const DEFAULT_NO_PERMISSION_TITLE = '无访问权限';
const DEFAULT_NO_PERMISSION_DESCRIPTION = '您没有查看该内容的权限';
const DEFAULT_STALE_MESSAGE = '数据可能已过期，显示的是缓存内容';

/**
 * Default no-permission view. Shown instead of an empty table when the user
 * lacks permission to view the surface (Req 41.5). Exposed as a `status`
 * region (not an `alert`).
 */
function DefaultNoPermissionState({ message }: { message?: string }) {
  return (
    <div
      role="status"
      data-slot="table-no-permission"
      className="flex flex-col items-center justify-center py-16 text-center"
    >
      <div className="mb-4 flex size-12 items-center justify-center rounded-xl bg-slate-100">
        <Lock size={24} className="text-slate-400" />
      </div>
      <h3 className="mb-1 text-base font-medium text-slate-900">
        {DEFAULT_NO_PERMISSION_TITLE}
      </h3>
      <p className="max-w-sm text-sm text-slate-500">
        {message ?? DEFAULT_NO_PERMISSION_DESCRIPTION}
      </p>
    </div>
  );
}

/**
 * React hook wrapping the pure in-progress control model (Req 36.3). A control
 * that has initiated an async action is disabled until it completes, so the
 * same Operation cannot be submitted again (double-submission prevention). The
 * in-progress set is held in a ref for a synchronous guard (so a rapid second
 * activation is rejected before React re-renders) and mirrored to state to
 * drive the disabled UI.
 */
function useInProgressControls() {
  const ref = React.useRef<InProgressControls>(EMPTY_IN_PROGRESS);
  const [inProgress, setInProgress] =
    React.useState<InProgressControls>(EMPTY_IN_PROGRESS);

  const isRunning = React.useCallback(
    (id: string) => isControlInProgress(inProgress, id),
    [inProgress],
  );

  const run = React.useCallback(
    async (id: string, action?: () => void | Promise<void>) => {
      const begun = beginAction(ref.current, id);
      // Reject a duplicate activation while the control is still in progress.
      if (!begun.allowed) return;
      ref.current = begun.state;
      setInProgress(begun.state);
      try {
        await action?.();
      } finally {
        ref.current = completeAction(ref.current, id);
        setInProgress(ref.current);
      }
    },
    [],
  );

  return { isRunning, run };
}

/**
 * Default empty-state view. Distinct from the error state: it never conveys an
 * error (Req 1.6, 2.15). Exposed as a `status` region (not an `alert`).
 */
function DefaultEmptyState() {
  return (
    <div
      role="status"
      data-slot="table-empty"
      className="flex flex-col items-center justify-center py-16 text-center"
    >
      <div className="mb-4 flex size-12 items-center justify-center rounded-xl bg-slate-100">
        <Inbox size={24} className="text-slate-400" />
      </div>
      <h3 className="mb-1 text-base font-medium text-slate-900">
        {DEFAULT_EMPTY_TITLE}
      </h3>
      <p className="max-w-sm text-sm text-slate-500">{DEFAULT_EMPTY_DESCRIPTION}</p>
    </div>
  );
}

export function SharedDataTable<Row>({
  rows,
  columns,
  rowId,
  tableKey,
  total,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
  pageSizeOptions = [...DEFAULT_PAGE_SIZE_OPTIONS],
  sort = null,
  onSortChange,
  loading = false,
  error = null,
  onRetry,
  onRefresh,
  noPermission = false,
  noPermissionState,
  noPermissionMessage,
  stale = false,
  staleMessage,
  emptyState,
  bulkOperations,
  selectedIds: controlledSelectedIds,
  onSelectionChange,
  allFilteredSelected: controlledAllFilteredSelected,
  onAllFilteredSelectedChange,
  pinnedColumnKeys = [],
  enableColumnManagement = false,
  initialHiddenColumns,
  initialColumnOrder,
  onColumnConfigChange,
  onExport,
  allFilteredRows,
  enableSavedViews = false,
  onApplySavedView,
  filterState,
  onFilterChange,
  typeSelectors,
  filterableFields,
  searchPlaceholder,
  className,
}: SharedDataTableProps<Row>) {
  const columnConfig = useColumnConfig(columns, {
    initialHidden: initialHiddenColumns,
    initialOrder: initialColumnOrder,
  });
  const {
    visibleColumns,
    order,
    visibleOrder,
    isVisible,
    canHide,
    toggleColumn,
    showColumn,
    hideColumn,
    reorder,
    lastError: columnConfigError,
  } = columnConfig;

  // Notify the host of user-driven column configuration changes (order /
  // visibility) so it can persist them (Req 2.12). Skips the initial mount so
  // restoring a persisted configuration does not immediately re-persist it.
  const columnConfigChangeRef = React.useRef(onColumnConfigChange);
  columnConfigChangeRef.current = onColumnConfigChange;
  const lastConfigSignatureRef = React.useRef<string | null>(null);
  React.useEffect(() => {
    const visibleSet = new Set(visibleOrder);
    const hidden = order.filter((k) => !visibleSet.has(k));
    const signature = `${order.join('\u0000')}|${hidden.join('\u0000')}`;
    // Record the signature on first run without notifying (mount baseline).
    if (lastConfigSignatureRef.current === null) {
      lastConfigSignatureRef.current = signature;
      return;
    }
    if (lastConfigSignatureRef.current === signature) return;
    lastConfigSignatureRef.current = signature;
    columnConfigChangeRef.current?.({ order: [...order], hidden });
  }, [order, visibleOrder]);

  // --- Selection (controlled or internal) --------------------------------
  const [internalSelected, setInternalSelected] = React.useState<string[]>([]);
  const selectedIds = controlledSelectedIds ?? internalSelected;

  // --- Cross-page "all filtered results" selection (Req 44.3) -------------
  const [internalAllFiltered, setInternalAllFiltered] = React.useState(false);
  const allFilteredSelected =
    controlledAllFilteredSelected ?? internalAllFiltered;

  // --- In-progress controls (double-submission prevention, Req 36.3) ------
  const { isRunning, run } = useInProgressControls();

  const setSelected = React.useCallback(
    (next: string[]) => {
      if (controlledSelectedIds === undefined) setInternalSelected(next);
      onSelectionChange?.(next);
    },
    [controlledSelectedIds, onSelectionChange],
  );

  const setAllFilteredSelected = React.useCallback(
    (next: boolean) => {
      if (controlledAllFilteredSelected === undefined)
        setInternalAllFiltered(next);
      onAllFilteredSelectedChange?.(next);
    },
    [controlledAllFilteredSelected, onAllFilteredSelectedChange],
  );

  // Hand-picking a single row is always an explicit-id selection, so it clears
  // any active "all filtered results" selection (Req 44.2 vs 44.3).
  const toggleRow = React.useCallback(
    (id: string, checked: boolean) => {
      setSelected(toggleRowSelection(selectedIds, id, checked));
      if (allFilteredSelected) setAllFilteredSelected(false);
    },
    [selectedIds, setSelected, allFilteredSelected, setAllFilteredSelected],
  );

  // Clear EVERYTHING — explicit ids and the all-filtered flag.
  const clearSelection = React.useCallback(() => {
    setSelected([]);
    setAllFilteredSelected(false);
  }, [setSelected, setAllFilteredSelected]);

  // --- Pinned columns (clamped to 1–5) -----------------------------------
  const pinnedSet = React.useMemo(
    () => new Set(pinnedColumnKeys.slice(0, MAX_PINNED_COLUMNS)),
    [pinnedColumnKeys],
  );

  // --- Pinned-column cumulative offsets (Req 33.2, 38.1) ------------------
  // Multiple pinned columns must NOT all sit at left-0 (which overlaps them).
  // We resolve each pinned column's horizontal offset as the cumulative width
  // of the pinned columns preceding it, in their visible (left-to-right) order,
  // via the pure `resolvePinnedOffsets`. The resulting `left` px value is then
  // applied inline per pinned cell so the columns lay out contiguously.
  const pinnedLeftByKey = React.useMemo(() => {
    const pinnedVisible = visibleColumns.filter((c) => pinnedSet.has(c.key));
    const offsets = resolvePinnedOffsets(
      pinnedVisible.map((c) => ({
        key: c.key,
        width: c.width ?? DEFAULT_PINNED_COLUMN_WIDTH,
      })),
    );
    const map = new Map<string, number>();
    for (const o of offsets) map.set(o.key, o.offset);
    return map;
  }, [visibleColumns, pinnedSet]);

  const showSelection = Boolean(bulkOperations && bulkOperations.length > 0);

  // --- Cross-page selection semantics (Req 44.1–44.4) ---------------------
  // The current page's rendered row ids define the "select current page" choice
  // (Req 44.2); `total` defines how many records an "all filtered results"
  // selection covers across all pages (Req 44.4); `filterState` defines that
  // selection so the backend can apply a Bulk_Operation to every matching
  // record (Req 44.3). All semantics are delegated to the pure helpers.
  const currentPageRowIds = React.useMemo(
    () => rows.map((row) => rowId(row)),
    [rows, rowId],
  );

  const selectionInput = React.useMemo<SelectionInput>(
    () => ({
      selectedIds,
      allFilteredSelected,
      currentPageRowIds,
      total,
      filters: filterState ?? null,
    }),
    [selectedIds, allFilteredSelected, currentPageRowIds, total, filterState],
  );

  const pageSelectionState = React.useMemo(
    () => currentPageSelectionState(selectedIds, currentPageRowIds),
    [selectedIds, currentPageRowIds],
  );

  const selectionScope = React.useMemo<BulkSelectionScope>(
    () => resolveBulkScope(selectionInput),
    [selectionInput],
  );

  const coveredCount = resolveCoveredCount(selectionInput);
  const offerSelectAllFiltered = canSelectAllFiltered(selectionInput);
  const selectionActive = hasActiveSelection(selectionInput);

  // "Select current page" = exactly the rendered rows (Req 44.2). Toggling the
  // header checkbox selects/clears the whole page and clears any all-filtered
  // selection (it is being narrowed back to explicit ids).
  const toggleCurrentPageSelection = React.useCallback(
    (select: boolean) => {
      if (select) {
        setSelected(toggleCurrentPage(selectedIds, currentPageRowIds, true));
      } else {
        setSelected(toggleCurrentPage(selectedIds, currentPageRowIds, false));
      }
      if (allFilteredSelected) setAllFilteredSelected(false);
    },
    [
      selectedIds,
      currentPageRowIds,
      setSelected,
      allFilteredSelected,
      setAllFilteredSelected,
    ],
  );

  // "Select all filtered results" (Req 44.3): keep the current page selected
  // (so the rendered checkboxes stay checked) and flip the all-filtered flag so
  // a Bulk_Operation targets every matching record across all pages.
  const selectAllFiltered = React.useCallback(() => {
    setSelected(selectCurrentPageIds(currentPageRowIds));
    setAllFilteredSelected(true);
  }, [currentPageRowIds, setSelected, setAllFilteredSelected]);

  // --- Server-side filter rejection (Req 15.5, 15.6, 15.7) ----------------
  // A filter referencing a field that is NOT persisted/server-filterable must
  // never be silently applied (that would return unfiltered rows). We partition
  // the committed conditions against the declared `filterableFields`, forward
  // ONLY the persisted conditions to the host (so the server request constrains
  // the full result set), and surface a validation message for the rest.
  const persistedFieldKeys = React.useMemo(
    () => filterableFields?.map((f) => f.key),
    [filterableFields],
  );

  const filterRejectionMessage = React.useMemo(() => {
    if (!filterState) return null;
    const { rejected } = partitionFilterFields(
      filterState.conditions,
      persistedFieldKeys,
    );
    return unsupportedFilterMessage(rejected);
  }, [filterState, persistedFieldKeys]);

  const handleFilterChange = React.useCallback(
    (next: FilterState) => {
      if (!onFilterChange) return;
      const { accepted } = partitionFilterFields(
        next.conditions,
        persistedFieldKeys,
      );
      // Forward only persisted conditions; non-persisted ones are rejected and
      // reported, never sent to the server.
      onFilterChange({ ...next, conditions: accepted });
    },
    [onFilterChange, persistedFieldKeys],
  );

  // --- Server-side pagination (Req 31.1, 31.3, 33.1) ----------------------
  const showPagination = Boolean(onPageChange);
  const effectivePageSize =
    pageSize && pageSize > 0 ? pageSize : pageSizeOptions[0] ?? 50;
  const totalRecords = typeof total === 'number' ? total : rows.length;
  const totalPages = computeTotalPages(totalRecords, effectivePageSize);
  const currentPage = clampPage(page ?? 1, totalPages);
  const range = pageRange(currentPage, effectivePageSize, totalRecords);

  // --- Server-side sort (Req 33.1, 33.4) ----------------------------------
  const handleSort = React.useCallback(
    (column: ColumnDef<Row>) => {
      if (!onSortChange || !column.sortable) return;
      const field = column.sortField ?? column.key;
      onSortChange(nextSortState(sort, field));
    },
    [onSortChange, sort],
  );

  const sortIndicator = React.useCallback(
    (column: ColumnDef<Row>) => {
      const field = column.sortField ?? column.key;
      if (!sort || sort.field !== field) {
        return <ChevronsUpDown className="size-3.5 text-slate-400" aria-hidden />;
      }
      return sort.direction === 'asc' ? (
        <ArrowUp className="size-3.5 text-slate-700" aria-hidden />
      ) : (
        <ArrowDown className="size-3.5 text-slate-700" aria-hidden />
      );
    },
    [sort],
  );

  // --- Export (Req 37.1–37.4) ---------------------------------------------
  // Build the export dataset with the PURE `resolveExportDataset` helper so the
  // chosen scope and the visible-column projection are testable in isolation
  // (task 19.15). The host produces the actual file from the resolved dataset.
  const handleExport = React.useCallback(
    (scope: ExportScope) => {
      if (!onExport) return;
      const exportColumns = visibleColumns.map((col) => ({
        key: col.key,
        header: col.header,
        exportValue: col.exportValue,
      }));
      const dataset = resolveExportDataset(
        scope,
        allFilteredRows ?? rows,
        rows,
        exportColumns,
      );
      onExport(scope, dataset);
    },
    [onExport, visibleColumns, allFilteredRows, rows],
  );

  // --- Saved views (Req 34.3) ---------------------------------------------
  // The current column configuration, filters, and sort serialized into a
  // Saved_View. `hidden` is the full order minus the visible order.
  const buildCurrentViewConfig = React.useCallback((): SavedViewConfig => {
    const visibleSet = new Set(visibleOrder);
    return {
      columns: {
        order: [...order],
        hidden: order.filter((k) => !visibleSet.has(k)),
      },
      filters: filterState,
      sort: sort ? { field: sort.field, direction: sort.direction } : null,
    };
  }, [order, visibleOrder, filterState, sort]);

  // Apply a decoded Saved_View: restore the column configuration here (the hook
  // owns it) and delegate filters/sort restoration to the host.
  const handleApplySavedView = React.useCallback(
    (config: SavedViewConfig) => {
      const cols = config.columns;
      if (cols?.order) {
        cols.order.forEach((key, idx) => reorder(key, idx));
      }
      if (cols) {
        const hiddenSet = new Set(cols.hidden ?? []);
        for (const col of columns) {
          if (hiddenSet.has(col.key)) hideColumn(col.key);
          else showColumn(col.key);
        }
      }
      onApplySavedView?.(config);
    },
    [columns, reorder, hideColumn, showColumn, onApplySavedView],
  );

  const showToolbarControls =
    enableColumnManagement || Boolean(onExport) || enableSavedViews;

  // --- View-state resolution (Req 41.1–41.5) ------------------------------
  // Resolve the surface to EXACTLY ONE explicit UI state via the pure
  // `resolveViewState`, then derive the render flags from it. This replaces the
  // old ad-hoc loading>error>empty>data precedence so the table never shows an
  // ambiguous blank or misleading screen.
  const hasError = error != null && error !== '';
  const viewState = resolveViewState({
    hasPermission: !noPermission,
    loading,
    hasError,
    stale,
    rowCount: rows.length,
  });

  const showSkeleton = viewState === 'loading';
  const showNoPermission = viewState === 'no-permission';
  // partial-error (Req 41.3): surface what failed while still showing whatever
  // content loaded — the error banner renders, and the table renders too when
  // rows are present.
  const showError = viewState === 'partial-error';
  // stale-cache (Req 41.4): show the (cached) content with a staleness notice.
  const showStale = viewState === 'stale-cache';
  const showEmpty = viewState === 'empty';
  // The data table renders for content/stale/partial-error states whenever
  // there are rows to show.
  const showTable =
    rows.length > 0 &&
    (viewState === 'content' ||
      viewState === 'stale-cache' ||
      viewState === 'partial-error');

  return (
    <div
      className={cn('flex flex-col gap-3', className)}
      data-slot="shared-data-table"
      data-table-key={tableKey}
    >
      {filterState && onFilterChange && (
        <FilterToolbar
          filterState={filterState}
          onFilterChange={handleFilterChange}
          typeSelectors={typeSelectors}
          filterableFields={filterableFields}
          searchPlaceholder={searchPlaceholder}
        />
      )}

      {filterRejectionMessage && (
        <div
          role="alert"
          data-slot="table-filter-rejection"
          className="flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-700"
        >
          <AlertTriangle size={16} aria-hidden />
          <span>{filterRejectionMessage}</span>
        </div>
      )}

      {(typeof total === 'number' || onRefresh || showToolbarControls) && (
        <div className="flex items-center justify-between gap-2">
          {typeof total === 'number' ? (
            <p data-slot="table-total" className="text-sm text-slate-500">
              共 {totalRecords} 条记录
            </p>
          ) : (
            <span />
          )}
          <div
            className="flex items-center gap-2"
            data-slot="table-toolbar-actions"
          >
            {enableColumnManagement && (
              <ColumnManagementMenu
                columns={columns.map((c) => ({ key: c.key, header: c.header }))}
                isVisible={isVisible}
                canHide={canHide}
                onToggle={toggleColumn}
                lastError={columnConfigError}
              />
            )}
            {onExport && (
              <ExportMenu
                onExport={handleExport}
                disabled={loading || showNoPermission}
              />
            )}
            {enableSavedViews && (
              <SavedViewControl
                tableKey={tableKey}
                buildCurrentConfig={buildCurrentViewConfig}
                onApply={handleApplySavedView}
              />
            )}
            {onRefresh && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                aria-label="刷新"
                disabled={loading || isRunning('refresh')}
                onClick={() => run('refresh', onRefresh)}
              >
                <RefreshCw
                  className={cn(
                    'size-4',
                    (loading || isRunning('refresh')) && 'animate-spin',
                  )}
                />
                刷新
              </Button>
            )}
          </div>
        </div>
      )}

      {showSelection && (
        <BulkActionBar
          selectedIds={selectedIds}
          operations={bulkOperations!}
          selectionScope={selectionScope}
          onClearSelection={clearSelection}
        />
      )}

      {/* Cross-page selection affordance (Req 44.1, 44.3, 44.4): once rows are
          selected, offer broadening the selection to every matching record
          across all pages, and surface the covered count. */}
      {showSelection && selectionActive && (offerSelectAllFiltered || allFilteredSelected) && (
        <div
          role="status"
          data-slot="table-select-all-filtered"
          className="flex flex-wrap items-center justify-center gap-2 rounded-md border border-sky-200 bg-sky-50 px-3 py-2 text-sm text-sky-800"
        >
          {allFilteredSelected ? (
            <>
              <span data-slot="covered-count">
                已选择全部 {coveredCount} 条筛选结果
              </span>
              <Button
                type="button"
                variant="link"
                size="sm"
                className="h-auto p-0 text-sky-700"
                onClick={() => {
                  setSelected(selectCurrentPageIds(currentPageRowIds));
                  setAllFilteredSelected(false);
                }}
              >
                仅选择当前页
              </Button>
            </>
          ) : (
            <>
              <span>已选择当前页 {selectedIds.length} 项。</span>
              <Button
                type="button"
                variant="link"
                size="sm"
                className="h-auto p-0 text-sky-700"
                onClick={selectAllFiltered}
              >
                选择全部 {typeof total === 'number' ? total : currentPageRowIds.length} 条筛选结果
              </Button>
            </>
          )}
        </div>
      )}

      {showSkeleton && (
        <div
          data-slot="table-skeleton"
          aria-busy="true"
          aria-label="加载中"
          className="flex flex-col gap-2 py-4"
        >
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-9 w-full" />
          ))}
        </div>
      )}

      {showNoPermission &&
        (noPermissionState ?? (
          <DefaultNoPermissionState message={noPermissionMessage} />
        ))}

      {showError && rows.length === 0 && (
        <div
          role="alert"
          data-slot="table-error"
          className="flex flex-col items-center justify-center py-16 text-center"
        >
          <div className="mb-4 flex size-12 items-center justify-center rounded-xl bg-red-50">
            <AlertTriangle size={24} className="text-red-500" />
          </div>
          <h3 className="mb-1 text-base font-medium text-slate-900">出错了</h3>
          <p className="mb-4 text-sm text-slate-500">{error}</p>
          {onRetry && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={isRunning('retry')}
              onClick={() => run('retry', onRetry)}
            >
              <RefreshCw
                className={cn('size-4', isRunning('retry') && 'animate-spin')}
              />
              重试
            </Button>
          )}
        </div>
      )}

      {/* partial-error with content (Req 41.3): identify what failed while still
          showing the successfully loaded rows below. */}
      {showError && rows.length > 0 && (
        <div
          role="alert"
          data-slot="table-partial-error"
          className="flex items-center justify-between gap-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          <span className="flex items-center gap-2">
            <AlertTriangle size={16} aria-hidden />
            <span>{error}</span>
          </span>
          {onRetry && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={isRunning('retry')}
              onClick={() => run('retry', onRetry)}
            >
              <RefreshCw
                className={cn('size-4', isRunning('retry') && 'animate-spin')}
              />
              重试
            </Button>
          )}
        </div>
      )}

      {/* stale-cache (Req 41.4): indicate the shown content may be out of date. */}
      {showStale && (
        <div
          role="status"
          data-slot="table-stale"
          className="flex items-center justify-between gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-700"
        >
          <span className="flex items-center gap-2">
            <AlertTriangle size={16} aria-hidden />
            <span>{staleMessage ?? DEFAULT_STALE_MESSAGE}</span>
          </span>
          {onRefresh && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={loading || isRunning('refresh')}
              onClick={() => run('refresh', onRefresh)}
            >
              <RefreshCw
                className={cn(
                  'size-4',
                  (loading || isRunning('refresh')) && 'animate-spin',
                )}
              />
              刷新
            </Button>
          )}
        </div>
      )}

      {showEmpty && (emptyState ?? <DefaultEmptyState />)}

      {showTable && (
        <Table className={className}>
          <TableHeader>
            <TableRow>
              {showSelection && (
                <TableHead className="w-10">
                  <Checkbox
                    aria-label="选择当前页"
                    checked={
                      pageSelectionState === 'all'
                        ? true
                        : pageSelectionState === 'partial'
                          ? 'indeterminate'
                          : false
                    }
                    onCheckedChange={() =>
                      toggleCurrentPageSelection(pageSelectionState !== 'all')
                    }
                  />
                </TableHead>
              )}
              {visibleColumns.map((col) => {
                const isSortable = Boolean(onSortChange && col.sortable);
                const field = col.sortField ?? col.key;
                const isSorted = sort?.field === field;
                return (
                  <TableHead
                    key={col.key}
                    data-pinned={pinnedSet.has(col.key) ? 'true' : undefined}
                    style={
                      pinnedSet.has(col.key)
                        ? { left: pinnedLeftByKey.get(col.key) ?? 0 }
                        : undefined
                    }
                    aria-sort={
                      isSorted
                        ? sort!.direction === 'asc'
                          ? 'ascending'
                          : 'descending'
                        : isSortable
                          ? 'none'
                          : undefined
                    }
                    className={cn(pinnedSet.has(col.key) && 'sticky left-0 z-10 bg-background')}
                  >
                    {isSortable ? (
                      <button
                        type="button"
                        onClick={() => handleSort(col)}
                        className="inline-flex items-center gap-1 font-medium hover:text-slate-900"
                      >
                        <span>{col.header}</span>
                        {sortIndicator(col)}
                      </button>
                    ) : (
                      col.header
                    )}
                  </TableHead>
                );
              })}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row) => {
              const id = rowId(row);
              const checked = selectedIds.includes(id);
              return (
                <TableRow key={id} data-state={checked ? 'selected' : undefined}>
                  {showSelection && (
                    <TableCell className="w-10">
                      <Checkbox
                        aria-label={`选择 ${id}`}
                        checked={checked}
                        onCheckedChange={(value) => toggleRow(id, value === true)}
                      />
                    </TableCell>
                  )}
                  {visibleColumns.map((col) => (
                    <TableCell
                      key={col.key}
                      data-pinned={pinnedSet.has(col.key) ? 'true' : undefined}
                      style={
                        pinnedSet.has(col.key)
                          ? { left: pinnedLeftByKey.get(col.key) ?? 0 }
                          : undefined
                      }
                      className={cn(pinnedSet.has(col.key) && 'sticky left-0 z-10 bg-background')}
                    >
                      {col.render
                        ? col.render(row)
                        : String((row as Record<string, unknown>)[col.key] ?? '')}
                    </TableCell>
                  ))}
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}

      {showPagination && !showSkeleton && !showNoPermission &&
        !(showError && rows.length === 0) && (
          <div
            data-slot="table-pagination"
            className="flex flex-wrap items-center justify-between gap-3 pt-1"
          >
            <div className="flex items-center gap-2 text-sm text-slate-500">
              <span>
                {range.total > 0
                  ? `第 ${range.start}–${range.end} 条，共 ${range.total} 条`
                  : '共 0 条'}
              </span>
              {onPageSizeChange && (
                <label className="flex items-center gap-1">
                  <span>每页</span>
                  <select
                    aria-label="每页条数"
                    className="rounded-md border border-slate-200 bg-white px-2 py-1 text-sm"
                    value={effectivePageSize}
                    disabled={loading}
                    onChange={(e) => onPageSizeChange(Number(e.target.value))}
                  >
                    {pageSizeOptions.map((size) => (
                      <option key={size} value={size}>
                        {size}
                      </option>
                    ))}
                  </select>
                  <span>条</span>
                </label>
              )}
            </div>
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                aria-label="上一页"
                disabled={loading || !hasPrevPage(currentPage, totalPages)}
                onClick={() => onPageChange?.(currentPage - 1)}
              >
                <ChevronLeft className="size-4" />
              </Button>
              <span data-slot="table-page-indicator" className="text-sm text-slate-600">
                第 {currentPage} / {totalPages} 页
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                aria-label="下一页"
                disabled={loading || !hasNextPage(currentPage, totalPages)}
                onClick={() => onPageChange?.(currentPage + 1)}
              >
                <ChevronRight className="size-4" />
              </Button>
            </div>
          </div>
        )}
    </div>
  );
}

export default SharedDataTable;
