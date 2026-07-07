// AdPilot AI — useAdvertisingQueryState
// (advertising-workspace-rework Req 34.4, 34.5, 39.1, 39.2, 39.3)
//
// The single source of truth for the advertising workspace's query conditions
// (filters, date-range, sort, pagination). The FilterToolbar, the KpiPanel, and
// the SharedDataTable all read `state` from THIS hook and mutate it through the
// returned setters, so the three surfaces never diverge (Req 39.1–39.3).
//
// State lives in the page URL: every change is encoded into the query string
// (Req 34.4) and a URL that already carries encoded filters/sort is applied on
// load (Req 34.5). The encode/decode is delegated to the pure helpers in
// `../advertisingQueryState`, which are re-exported here for convenience and
// for the round-trip property test (task 18.4).
//
// Foreign query params (e.g. a selected tab) are preserved: only the keys this
// module owns (MANAGED_QUERY_KEYS) are rewritten on each update.

import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';

import type {
  FilterCondition,
  FilterState,
} from '../../components/table/types';
import {
  decodeFilters,
  encodeFilters,
  MANAGED_QUERY_KEYS,
  type AdvertisingQueryState,
  type DateRangeState,
  type SortState,
} from '../advertisingQueryState';

export type {
  AdvertisingQueryState,
  DateRangeState,
  SortDirection,
  SortState,
} from '../advertisingQueryState';
export {
  decodeFilters,
  encodeFilters,
  emptyQueryState,
  normalizeQueryState,
  DEFAULT_PAGE,
  DEFAULT_PAGE_SIZE,
  MANAGED_QUERY_KEYS,
} from '../advertisingQueryState';

/** Options accepted by {@link useAdvertisingQueryState}. */
export interface UseAdvertisingQueryStateOptions {
  /**
   * Whether the navigation entry replaces the current history entry instead of
   * pushing a new one. Defaults to `true` so filter tweaks don't flood the back
   * stack; callers can opt into push navigation for shareable snapshots.
   */
  replace?: boolean;
}

/** The shape returned by {@link useAdvertisingQueryState}. */
export interface AdvertisingQueryStateApi {
  /** The current, normalized query state decoded from the URL. */
  state: AdvertisingQueryState;
  /** Replace the entire filter block (search + selectors + conditions); resets to page 1. */
  setFilters: (filters: FilterState) => void;
  /** Set the free-text search term; resets to page 1. */
  setSearch: (search: string) => void;
  /** Set one type-selector value (clearing it with an empty/undefined value); resets to page 1. */
  setTypeSelection: (key: string, value: string | undefined) => void;
  /** Replace the advanced-filter conditions; resets to page 1. */
  setConditions: (conditions: FilterCondition[]) => void;
  /** Set or clear the date range; resets to page 1. */
  setDateRange: (range: DateRangeState | null) => void;
  /** Set or clear the active sort; resets to page 1. */
  setSort: (sort: SortState | null) => void;
  /** Change the 1-based page (preserves filters/sort/pageSize). */
  setPage: (page: number) => void;
  /** Change the page size; resets to page 1. */
  setPageSize: (pageSize: number) => void;
  /** Apply an arbitrary partial patch to the query state. */
  patch: (partial: Partial<AdvertisingQueryState>) => void;
  /** Clear all managed query conditions back to defaults. */
  reset: () => void;
}

/**
 * Hook exposing the advertising query state and its setters, backed by the URL.
 *
 * @example
 * const { state, setSearch, setPage } = useAdvertisingQueryState();
 * // FilterToolbar, KpiPanel, and SharedDataTable all read `state`.
 */
export function useAdvertisingQueryState(
  options: UseAdvertisingQueryStateOptions = {},
): AdvertisingQueryStateApi {
  const { replace = true } = options;
  const [searchParams, setSearchParams] = useSearchParams();

  // Decode is pure and cheap; memoize on the serialized query string so `state`
  // is referentially stable while the URL is unchanged.
  const search = searchParams.toString();
  const state = useMemo<AdvertisingQueryState>(
    () => decodeFilters(search),
    [search],
  );

  // Write a new state into the URL, preserving any foreign params.
  const commit = useCallback(
    (next: AdvertisingQueryState) => {
      setSearchParams(
        (prev) => {
          const merged = new URLSearchParams(prev);
          for (const key of MANAGED_QUERY_KEYS) merged.delete(key);
          for (const [key, value] of encodeFilters(next)) {
            merged.set(key, value);
          }
          return merged;
        },
        { replace },
      );
    },
    [setSearchParams, replace],
  );

  const patch = useCallback(
    (partial: Partial<AdvertisingQueryState>) => {
      commit({ ...decodeFilters(search), ...partial });
    },
    [commit, search],
  );

  // Any change to a query condition resets pagination to the first page so the
  // operator is not stranded on an out-of-range page.
  const patchFilters = useCallback(
    (next: Partial<AdvertisingQueryState>) => {
      const current = decodeFilters(search);
      commit({ ...current, ...next, page: 1 });
    },
    [commit, search],
  );

  const setFilters = useCallback(
    (filters: FilterState) => patchFilters({ filters }),
    [patchFilters],
  );

  const setSearch = useCallback(
    (value: string) => {
      const current = decodeFilters(search);
      patchFilters({ filters: { ...current.filters, search: value } });
    },
    [patchFilters, search],
  );

  const setTypeSelection = useCallback(
    (key: string, value: string | undefined) => {
      const current = decodeFilters(search);
      const typeSelections = { ...current.filters.typeSelections };
      if (value === undefined || value === '') delete typeSelections[key];
      else typeSelections[key] = value;
      patchFilters({ filters: { ...current.filters, typeSelections } });
    },
    [patchFilters, search],
  );

  const setConditions = useCallback(
    (conditions: FilterCondition[]) => {
      const current = decodeFilters(search);
      patchFilters({ filters: { ...current.filters, conditions } });
    },
    [patchFilters, search],
  );

  const setDateRange = useCallback(
    (range: DateRangeState | null) => patchFilters({ dateRange: range }),
    [patchFilters],
  );

  const setSort = useCallback(
    (sort: SortState | null) => patchFilters({ sort }),
    [patchFilters],
  );

  const setPage = useCallback((page: number) => patch({ page }), [patch]);

  const setPageSize = useCallback(
    (pageSize: number) => patchFilters({ pageSize }),
    [patchFilters],
  );

  const reset = useCallback(() => {
    setSearchParams(
      (prev) => {
        const merged = new URLSearchParams(prev);
        for (const key of MANAGED_QUERY_KEYS) merged.delete(key);
        return merged;
      },
      { replace },
    );
  }, [setSearchParams, replace]);

  return {
    state,
    setFilters,
    setSearch,
    setTypeSelection,
    setConditions,
    setDateRange,
    setSort,
    setPage,
    setPageSize,
    patch,
    reset,
  };
}
