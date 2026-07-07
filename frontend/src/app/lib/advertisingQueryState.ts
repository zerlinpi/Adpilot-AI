// AdPilot AI — Advertising query-state model + pure URL encode/decode
// (advertising-workspace-rework Req 34.4, 34.5, 39.1, 39.2, 39.3)
//
// This module owns the SINGLE SOURCE OF TRUTH shape for the advertising
// workspace's query conditions — filters, date-range, sort, and pagination —
// shared by the FilterToolbar, the KpiPanel, and the SharedDataTable (Req 39).
//
// The `encodeFilters` / `decodeFilters` pair is PURE (no React, no router, no
// global state) so that:
//   - applying a filter/sort can be encoded into the page URL (Req 34.4), and
//   - opening a URL that carries encoded filters/sort re-applies them on load
//     (Req 34.5),
// and so the round-trip `decodeFilters(encodeFilters(s))` can be property-tested
// in isolation (task 18.4).
//
// Only the keys this module manages (see MANAGED_QUERY_KEYS) participate in the
// encoding; foreign query parameters (e.g. a selected tab) are neither produced
// by `encodeFilters` nor read by `decodeFilters`, so callers may freely merge
// the encoded params over an existing query string without losing them.

import type { FilterCondition, FilterState } from '../components/table/types';
import { emptyFilterState } from '../components/table/types';

/** Sort direction for a single sorted column. */
export type SortDirection = 'asc' | 'desc';

/** Active sort: a column key plus a direction. */
export interface SortState {
  field: string;
  direction: SortDirection;
}

/** A closed date range (inclusive) expressed as `YYYY-MM-DD` strings. */
export interface DateRangeState {
  start: string;
  end: string;
}

/**
 * The complete advertising query state. This is the single set of query
 * conditions that drives the Filter controls, the KPI_Panel, and the table
 * together (Req 39.1, 39.2) — there is no separate, divergent state per surface
 * (Req 39.3).
 */
export interface AdvertisingQueryState {
  /** Search / type-selectors / advanced conditions (reuses the table model). */
  filters: FilterState;
  /** KPI/table date range; `null` when no explicit range is applied. */
  dateRange: DateRangeState | null;
  /** Active sort; `null` when the table uses its default order. */
  sort: SortState | null;
  /** 1-based page index. */
  page: number;
  /** Rows per page. */
  pageSize: number;
}

/** Default 1-based page index. */
export const DEFAULT_PAGE = 1;
/** Default rows-per-page when none is encoded. */
export const DEFAULT_PAGE_SIZE = 50;

/**
 * The query-string keys this module owns. Callers that merge the encoded params
 * over an existing query string should delete these keys first so a removed
 * condition does not linger.
 */
export const MANAGED_QUERY_KEYS = [
  'q', // search
  'ts', // type selections (JSON)
  'f', // advanced conditions (JSON)
  'from', // date range start
  'to', // date range end
  'sort', // sort field
  'dir', // sort direction
  'page', // 1-based page
  'size', // page size
] as const;

// ── internal helpers ────────────────────────────────────────────────────────

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** A finite, >= 1 integer or `fallback` (used for page / pageSize). */
function toPositiveInt(value: unknown, fallback: number): number {
  const n =
    typeof value === 'number' ? value : Number.parseInt(String(value ?? ''), 10);
  return Number.isInteger(n) && n >= 1 ? n : fallback;
}

/** Coerce an arbitrary parsed value into a `Record<string,string>`. */
function toStringRecord(value: unknown): Record<string, string> {
  if (!isPlainObject(value)) return {};
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(value)) {
    if (typeof v === 'string') out[k] = v;
    else if (typeof v === 'number' || typeof v === 'boolean') out[k] = String(v);
  }
  return out;
}

/** Coerce an arbitrary parsed value into a list of FilterConditions. */
function toConditions(value: unknown): FilterCondition[] {
  if (!Array.isArray(value)) return [];
  const out: FilterCondition[] = [];
  for (const raw of value) {
    if (
      isPlainObject(raw) &&
      typeof raw.field === 'string' &&
      typeof raw.op === 'string'
    ) {
      out.push({
        field: raw.field,
        op: raw.op as FilterCondition['op'],
        value: (raw as { value?: unknown }).value ?? null,
      });
    }
  }
  return out;
}

function safeJsonParse(raw: string | null): unknown {
  if (raw == null) return undefined;
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
}

// ── normalization ─────────────────────────────────────────────────────────────

/**
 * Produce the canonical form of a query state: empty/absent fields collapse to
 * their canonical "empty" representation so that
 * `decodeFilters(encodeFilters(s))` deep-equals `normalizeQueryState(s)` for
 * every state `s`. This is the contract the round-trip property (task 18.4)
 * relies on.
 */
export function normalizeQueryState(
  state: AdvertisingQueryState,
): AdvertisingQueryState {
  const search = state.filters?.search ?? '';
  const typeSelections = toStringRecord(state.filters?.typeSelections);
  const conditions = toConditions(state.filters?.conditions);

  let dateRange: DateRangeState | null = null;
  if (
    state.dateRange &&
    typeof state.dateRange.start === 'string' &&
    typeof state.dateRange.end === 'string' &&
    state.dateRange.start !== '' &&
    state.dateRange.end !== ''
  ) {
    dateRange = { start: state.dateRange.start, end: state.dateRange.end };
  }

  let sort: SortState | null = null;
  if (
    state.sort &&
    typeof state.sort.field === 'string' &&
    state.sort.field !== '' &&
    (state.sort.direction === 'asc' || state.sort.direction === 'desc')
  ) {
    sort = { field: state.sort.field, direction: state.sort.direction };
  }

  return {
    filters: { search, typeSelections, conditions },
    dateRange,
    sort,
    page: toPositiveInt(state.page, DEFAULT_PAGE),
    pageSize: toPositiveInt(state.pageSize, DEFAULT_PAGE_SIZE),
  };
}

/** The canonical empty query state (no filters, default pagination). */
export function emptyQueryState(): AdvertisingQueryState {
  return {
    filters: emptyFilterState(),
    dateRange: null,
    sort: null,
    page: DEFAULT_PAGE,
    pageSize: DEFAULT_PAGE_SIZE,
  };
}

// ── encode / decode ───────────────────────────────────────────────────────────

/**
 * Serialize a query state to `URLSearchParams` containing ONLY this module's
 * managed keys. Canonical/empty values are omitted so the encoding is minimal
 * and stable, which keeps the round-trip with {@link decodeFilters} exact.
 */
export function encodeFilters(state: AdvertisingQueryState): URLSearchParams {
  const n = normalizeQueryState(state);
  const params = new URLSearchParams();

  if (n.filters.search !== '') params.set('q', n.filters.search);
  if (Object.keys(n.filters.typeSelections).length > 0) {
    params.set('ts', JSON.stringify(n.filters.typeSelections));
  }
  if (n.filters.conditions.length > 0) {
    params.set('f', JSON.stringify(n.filters.conditions));
  }
  if (n.dateRange) {
    params.set('from', n.dateRange.start);
    params.set('to', n.dateRange.end);
  }
  if (n.sort) {
    params.set('sort', n.sort.field);
    params.set('dir', n.sort.direction);
  }
  if (n.page !== DEFAULT_PAGE) params.set('page', String(n.page));
  if (n.pageSize !== DEFAULT_PAGE_SIZE) params.set('size', String(n.pageSize));

  return params;
}

/**
 * Parse a query string (or `URLSearchParams`) back into a normalized query
 * state. Unknown/foreign params are ignored; malformed values fall back to
 * their canonical defaults so decoding never throws (Req 34.5).
 */
export function decodeFilters(
  input: string | URLSearchParams,
): AdvertisingQueryState {
  const params =
    typeof input === 'string' ? new URLSearchParams(input) : input;

  const filters: FilterState = {
    search: params.get('q') ?? '',
    typeSelections: toStringRecord(safeJsonParse(params.get('ts'))),
    conditions: toConditions(safeJsonParse(params.get('f'))),
  };

  const from = params.get('from');
  const to = params.get('to');
  const dateRange: DateRangeState | null =
    from && to ? { start: from, end: to } : null;

  const sortField = params.get('sort');
  const dirRaw = params.get('dir');
  const direction: SortDirection = dirRaw === 'desc' ? 'desc' : 'asc';
  const sort: SortState | null =
    sortField && sortField !== '' ? { field: sortField, direction } : null;

  return {
    filters,
    dateRange,
    sort,
    page: toPositiveInt(params.get('page'), DEFAULT_PAGE),
    pageSize: toPositiveInt(params.get('size'), DEFAULT_PAGE_SIZE),
  };
}
