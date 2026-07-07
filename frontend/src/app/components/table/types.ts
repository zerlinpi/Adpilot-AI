import type React from 'react';

/**
 * Shared types for the reusable table building blocks
 * (`SharedDataTable`, `FilterToolbar`, `BulkActionBar`) under
 * `app/components/table/`.
 *
 * These types are intentionally framework-light so any page can import them
 * without pulling in a specific table implementation.
 */

/** Supported field types for advanced filtering. */
export type FilterFieldType = 'text' | 'number' | 'enum' | 'date';

/**
 * Advanced-filter operators. Mirrors the backend `FilterCondition` contract
 * (`POST /api/{resource}/query`) so the frontend and backend agree on the
 * operator vocabulary.
 */
export type FilterOperator =
  | 'eq'
  | 'ne'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'
  | 'contains'
  | 'in';

/** A single advanced-filter condition: field + operator + value. */
export interface FilterCondition {
  field: string;
  op: FilterOperator;
  value: unknown;
}

/**
 * The complete, emitted filter state for a table. Produced by
 * `FilterToolbar` and consumed by a host page / `SharedDataTable`.
 *
 * Only valid advanced conditions ever reach `conditions`; invalid drafts are
 * surfaced inline by the toolbar and never committed (so the displayed rows
 * are left unchanged — Req 2.10).
 */
export interface FilterState {
  /** Free-text search term. */
  search: string;
  /** Selected value per type-selector, keyed by selector key. */
  typeSelections: Record<string, string>;
  /** Validated advanced-filter conditions, combined with AND server-side. */
  conditions: FilterCondition[];
}

/** Column definition shared with `SharedDataTable`. */
export interface ColumnDef<Row> {
  key: string;
  header: string;
  render?: (row: Row) => React.ReactNode;
  pinnable?: boolean;
  /**
   * Rendered width of the column in pixels. Used to compute cumulative,
   * non-overlapping horizontal offsets for pinned columns (Req 33.2, 38.1).
   * Falls back to a default width when omitted.
   */
  width?: number;
  filterType?: FilterFieldType;
  enumOptions?: string[];
  /**
   * When true, the column header is an activatable server-side sort control.
   * Activating it asks the host to re-request the globally sorted result set
   * (the table never re-sorts the rows it already holds) — Req 33.1, 33.4.
   */
  sortable?: boolean;
  /**
   * Sort key sent to the server when this column is sorted. Defaults to `key`.
   */
  sortField?: string;
  /**
   * Extracts the plain, serializable value used when this column is exported
   * (Req 37.4). Defaults to reading `row[key]` when omitted. Use this when the
   * cell `render` returns a React node that is not directly serializable.
   */
  exportValue?: (row: Row) => unknown;
}

/** A type-selector control rendered by the `FilterToolbar`. */
export interface TypeSelectorDef {
  /** Identity of the selector within `FilterState.typeSelections`. */
  key: string;
  /** Visible label. */
  label: string;
  /** Selectable options. */
  options: { label: string; value: string }[];
  /** Label for the "no filter" sentinel option. Defaults to `全部`. */
  allLabel?: string;
}

/** A field that may be used to build an advanced-filter condition. */
export interface FilterFieldDef {
  /** Field key sent to the backend. */
  key: string;
  /** Visible label. */
  label: string;
  /** Field type, which constrains valid operators and value parsing. */
  type: FilterFieldType;
  /** Allowed values when `type === 'enum'`. */
  enumOptions?: string[];
}

/** The sentinel selector value meaning "no type filter applied". */
export const ALL_SELECTION = '__all__';

/** Empty/default filter state, useful as an initializer for host pages. */
export function emptyFilterState(): FilterState {
  return { search: '', typeSelections: {}, conditions: [] };
}
