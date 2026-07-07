// AdPilot AI — Export scope + visible-column resolution for the
// Shared_Data_Table (advertising-workspace-rework Req 37.2, 37.3, 37.4).
//
// Everything here is PURE (no React, no DOM, no network) so the export-scope
// row selection and the visible-column projection can be unit-/property-tested
// in isolation (see task 19.15, design Property 70) and reused by any table.
//
// Two concerns live here:
//
//   1. Scope selection — choosing "all filtered rows" yields the full filtered
//      result set, choosing "current page" yields only the rows on the current
//      page (Req 37.2, 37.3).
//
//   2. Visible-column projection — the produced dataset contains ONLY the
//      columns currently visible in the operator's column configuration, in
//      their configured order; hidden columns never appear (Req 37.4).

/** Which rows an export covers (Req 37.1, 37.2, 37.3). */
export type ExportScope = 'all_filtered' | 'current_page';

/** Human-facing labels for the two export scopes, surfaced before producing the file (Req 37.1). */
export const EXPORT_SCOPE_LABELS: Record<ExportScope, string> = {
  all_filtered: '全部筛选结果',
  current_page: '当前页',
};

/**
 * Minimal column shape needed to project a row into an exportable cell. The
 * real {@link ColumnDef} used by `SharedDataTable` is a superset of this; the
 * optional `exportValue` lets a column emit a plain serializable value distinct
 * from its (possibly React-node) cell renderer.
 */
export interface ExportColumn<Row> {
  /** Stable column key; also the default property read from the row. */
  key: string;
  /** Column header used as the exported column title. */
  header: string;
  /**
   * Extracts the plain, serializable value to export for this column.
   * Defaults to reading `row[key]` when omitted.
   */
  exportValue?: (row: Row) => unknown;
}

/** The resolved, serialization-ready export dataset. */
export interface ExportDataset {
  /** The scope that produced this dataset. */
  scope: ExportScope;
  /** Visible column keys, in their configured order. */
  columnKeys: string[];
  /** Visible column headers, aligned 1:1 with {@link columnKeys}. */
  headers: string[];
  /**
   * One row per exported record. Each inner array is aligned 1:1 with
   * {@link columnKeys} / {@link headers}, holding ONLY the visible columns'
   * values in their configured order.
   */
  rows: unknown[][];
}

/**
 * Select the rows an export covers for the chosen {@link ExportScope}:
 * `all_filtered` → the full filtered result set; `current_page` → only the rows
 * on the current page. Pure and non-mutating (returns a fresh array).
 *
 * Validates: Requirements 37.2, 37.3
 */
export function selectExportRows<Row>(
  scope: ExportScope,
  allFilteredRows: ReadonlyArray<Row>,
  currentPageRows: ReadonlyArray<Row>,
): Row[] {
  return scope === 'all_filtered' ? [...allFilteredRows] : [...currentPageRows];
}

/**
 * Extract the exported cell value for a column, using its `exportValue` when
 * supplied and otherwise reading `row[key]`. A missing/undefined value is
 * normalized to an empty string so the projection never produces holes.
 */
export function exportCellValue<Row>(column: ExportColumn<Row>, row: Row): unknown {
  if (column.exportValue) return column.exportValue(row);
  const raw = (row as Record<string, unknown>)[column.key];
  return raw ?? '';
}

/**
 * Resolve a complete, serialization-ready {@link ExportDataset} for the chosen
 * scope and the operator's currently visible columns.
 *
 * Guarantees (design Property 70):
 *   - `all_filtered` produces exactly the full filtered result set and
 *     `current_page` produces exactly the current page (row count + identity);
 *   - the dataset's columns are EXACTLY the supplied visible columns, in the
 *     same order — no hidden column ever appears;
 *   - every produced row array is aligned 1:1 with the visible columns.
 *
 * Pure and order-preserving.
 *
 * Validates: Requirements 37.2, 37.3, 37.4
 */
export function resolveExportDataset<Row>(
  scope: ExportScope,
  allFilteredRows: ReadonlyArray<Row>,
  currentPageRows: ReadonlyArray<Row>,
  visibleColumns: ReadonlyArray<ExportColumn<Row>>,
): ExportDataset {
  const selectedRows = selectExportRows(scope, allFilteredRows, currentPageRows);
  const columnKeys = visibleColumns.map((c) => c.key);
  const headers = visibleColumns.map((c) => c.header);
  const rows = selectedRows.map((row) =>
    visibleColumns.map((col) => exportCellValue(col, row)),
  );
  return { scope, columnKeys, headers, rows };
}

/** Escape a single CSV field per RFC 4180 (quote when it contains `,"`\n` or `\r`). */
function escapeCsvField(value: unknown): string {
  const text = value == null ? '' : String(value);
  if (/[",\r\n]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`;
  }
  return text;
}

/**
 * Serialize a resolved {@link ExportDataset} into CSV text (headers first, then
 * one line per row). Pure; emits only the dataset's visible columns in order,
 * so the produced file contains only the columns currently visible (Req 37.4).
 */
export function exportDatasetToCsv(dataset: ExportDataset): string {
  const lines = [dataset.headers, ...dataset.rows].map((cells) =>
    cells.map(escapeCsvField).join(','),
  );
  return lines.join('\r\n');
}
