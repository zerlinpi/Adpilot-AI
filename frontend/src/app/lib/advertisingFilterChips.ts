// AdPilot AI — Applied-filter → Filter_Chip derivation (PURE)
// (advertising-workspace-rework Req 29.3, 29.4, 29.5)
//
// This module owns the SINGLE, PURE mapping from the advertising query state
// (the single source of truth, see `./advertisingQueryState`) to the list of
// Filter_Chips the workspace renders, plus the pure reducer that removes one
// chip and yields the remaining query state.
//
// Keeping the derivation pure (no React, no router, no global state) lets the
// FilterChips component stay a thin presentation layer AND lets task 20.6
// property-test "chips correspond exactly to applied filters" (Property 60) in
// isolation:
//   - exactly one chip per applied filter condition       (Req 29.3)
//   - removing a chip yields the state without that filter (Req 29.4)
//   - no applied filters ⇒ no chips                        (Req 29.5)
//
// What counts as an "applied filter" (each contributes exactly one chip):
//   - a non-empty free-text search term
//   - each type-selector selection (one per selected key)
//   - each advanced-filter condition (one per condition)
//   - an applied date range
// Sort and pagination are NOT filters and never produce chips.

import type {
  AdvertisingQueryState,
  DateRangeState,
} from './advertisingQueryState';
import { normalizeQueryState } from './advertisingQueryState';
import type { FilterCondition } from '../components/table/types';

/** Which part of the query state a chip represents. */
export type FilterChipKind = 'search' | 'typeSelection' | 'condition' | 'dateRange';

/**
 * A removable visual indicator of one currently applied filter condition
 * (Filter_Chip, Req 29). Each chip carries enough identity to be removed purely
 * via {@link removeFilterChip} without consulting the DOM.
 */
export interface FilterChip {
  /** Stable identity, unique within a single derivation of a given state. */
  id: string;
  /** Which kind of filter this chip represents. */
  kind: FilterChipKind;
  /** Human-readable label, e.g. `搜索: shoes` or `状态 等于 enabled`. */
  label: string;
  /** For `typeSelection` chips: the selector key being filtered. */
  selectionKey?: string;
  /** For `condition` chips: the index of the condition within the state. */
  conditionIndex?: number;
}

/**
 * Optional human-readable labels used only to render chip text. Pure, so it is
 * passed in by the caller rather than read from any context. When a label is
 * absent the raw key/field is used so derivation never throws.
 */
export interface FilterChipLabels {
  /** Display label for the free-text search chip prefix. Defaults to `搜索`. */
  searchLabel?: string;
  /** Display label for the date-range chip prefix. Defaults to `日期`. */
  dateRangeLabel?: string;
  /** Map of type-selector key → display label. */
  selectionLabels?: Record<string, string>;
  /** Map of condition field key → display label. */
  fieldLabels?: Record<string, string>;
  /** Map of condition operator → display label. */
  operatorLabels?: Record<string, string>;
}

/** Render an arbitrary condition value into a short, stable string. */
function formatConditionValue(value: unknown): string {
  if (value == null) return '';
  if (Array.isArray(value)) return value.map((v) => String(v)).join(', ');
  return String(value);
}

/** Format a date-range chip body. */
function formatDateRange(range: DateRangeState): string {
  return range.start === range.end
    ? range.start
    : `${range.start} ~ ${range.end}`;
}

/**
 * Derive the Filter_Chips for a query state — exactly one chip per applied
 * filter condition, in a stable order, and an empty list when no filters are
 * applied (Req 29.3, 29.5).
 *
 * The state is normalized first so empty/blank values never yield a chip
 * (e.g. an all-whitespace search is not "applied").
 */
export function deriveFilterChips(
  state: AdvertisingQueryState,
  labels: FilterChipLabels = {},
): FilterChip[] {
  const n = normalizeQueryState(state);
  const {
    searchLabel = '搜索',
    dateRangeLabel = '日期',
    selectionLabels = {},
    fieldLabels = {},
    operatorLabels = {},
  } = labels;

  const chips: FilterChip[] = [];

  // 1) Free-text search — at most one chip.
  if (n.filters.search.trim() !== '') {
    chips.push({
      id: 'search',
      kind: 'search',
      label: `${searchLabel}: ${n.filters.search}`,
    });
  }

  // 2) Type selections — one chip per selected key, in a stable key order.
  for (const key of Object.keys(n.filters.typeSelections).sort()) {
    const value = n.filters.typeSelections[key];
    chips.push({
      id: `ts:${key}`,
      kind: 'typeSelection',
      selectionKey: key,
      label: `${selectionLabels[key] ?? key}: ${value}`,
    });
  }

  // 3) Advanced conditions — one chip per condition, preserving order.
  n.filters.conditions.forEach((cond: FilterCondition, index: number) => {
    const field = fieldLabels[cond.field] ?? cond.field;
    const op = operatorLabels[cond.op] ?? cond.op;
    const value = formatConditionValue(cond.value);
    chips.push({
      id: `cond:${index}`,
      kind: 'condition',
      conditionIndex: index,
      label: value === '' ? `${field} ${op}` : `${field} ${op} ${value}`,
    });
  });

  // 4) Date range — at most one chip.
  if (n.dateRange) {
    chips.push({
      id: 'dateRange',
      kind: 'dateRange',
      label: `${dateRangeLabel}: ${formatDateRange(n.dateRange)}`,
    });
  }

  return chips;
}

/**
 * Remove the filter a chip represents and return the remaining query state
 * (Req 29.4). Pure: it derives the next state solely from the chip identity, so
 * a caller can feed the result straight into the query-state setters to
 * re-request with the remaining filters. Pagination is reset to page 1 because
 * the result set changes when a filter is removed.
 */
export function removeFilterChip(
  state: AdvertisingQueryState,
  chip: FilterChip,
): AdvertisingQueryState {
  const n = normalizeQueryState(state);
  const next: AdvertisingQueryState = {
    ...n,
    filters: {
      search: n.filters.search,
      typeSelections: { ...n.filters.typeSelections },
      conditions: [...n.filters.conditions],
    },
    page: 1,
  };

  switch (chip.kind) {
    case 'search':
      next.filters.search = '';
      break;
    case 'typeSelection':
      if (chip.selectionKey !== undefined) {
        delete next.filters.typeSelections[chip.selectionKey];
      }
      break;
    case 'condition':
      if (chip.conditionIndex !== undefined) {
        next.filters.conditions = next.filters.conditions.filter(
          (_, i) => i !== chip.conditionIndex,
        );
      }
      break;
    case 'dateRange':
      next.dateRange = null;
      break;
  }

  return next;
}
