// AdPilot AI — Reusable table building blocks
//
// useColumnConfig — a standalone React hook that manages column visibility and
// ordering for the Shared_Data_Table. It tracks which columns are visible and
// the order in which they render, and exposes hide/show/reorder operations.
//
// Invariant (Req 2.2, 2.3): AT LEAST ONE column is always visible. Attempting to
// hide the last remaining visible column is rejected — the column stays visible,
// no state change occurs, and an indication is surfaced via `lastError`.

import { useCallback, useMemo, useRef, useState } from 'react';

/**
 * Minimal shape a column definition must satisfy to be managed by the hook.
 * The real {@link ColumnDef} used by `SharedDataTable` is a superset of this;
 * keeping the constraint minimal lets the hook be reused by any table.
 */
export interface ColumnConfigItem {
  /** Stable unique identity for the column. */
  key: string;
}

export interface UseColumnConfigOptions {
  /**
   * Column keys that should start hidden. Keys not present in the supplied
   * column set are ignored. If hiding the initial set would leave zero visible
   * columns, the initial state falls back to all columns visible.
   */
  initialHidden?: string[];
  /**
   * Explicit initial ordering by column key. Keys are applied in the given
   * order first; any remaining columns keep their definition order afterward.
   */
  initialOrder?: string[];
}

export interface UseColumnConfigResult<Col extends ColumnConfigItem> {
  /** All managed column definitions in their current configured order. */
  orderedColumns: Col[];
  /** Visible column definitions in their current configured order. */
  visibleColumns: Col[];
  /** Ordered list of all column keys (visible and hidden). */
  order: string[];
  /** Ordered list of the keys of currently visible columns. */
  visibleOrder: string[];
  /** True when `key` refers to a currently visible column. */
  isVisible: (key: string) => boolean;
  /**
   * Hide the column identified by `key`. Rejected (no state change) if it would
   * hide the last remaining visible column. Returns true when the column was
   * hidden, false when the request was rejected or `key` is unknown/already
   * hidden.
   */
  hideColumn: (key: string) => boolean;
  /** Show a previously hidden column. Returns true when a change was applied. */
  showColumn: (key: string) => boolean;
  /** Toggle a column's visibility, honoring the at-least-one-visible invariant. */
  toggleColumn: (key: string) => boolean;
  /**
   * Move the column identified by `key` to `toIndex` within the full column
   * order (0-based). Out-of-range indices are clamped. Returns true when the
   * order changed.
   */
  reorder: (key: string, toIndex: number) => boolean;
  /**
   * True when at least one OTHER column is visible, i.e. hiding the given key is
   * permitted. For a hidden or unknown key this reflects whether hiding would be
   * allowed if it were visible.
   */
  canHide: (key: string) => boolean;
  /**
   * The most recent rejection message (e.g. the last-visible-column guard), or
   * null when the last operation succeeded. Cleared by any successful change.
   */
  lastError: string | null;
}

export const AT_LEAST_ONE_VISIBLE_MESSAGE =
  'At least one column must remain visible.';

/**
 * Build the initial ordered list of column keys from the supplied columns,
 * honoring an optional explicit `initialOrder`.
 */
function buildInitialOrder(keys: string[], initialOrder?: string[]): string[] {
  if (!initialOrder || initialOrder.length === 0) return [...keys];
  const known = new Set(keys);
  const seen = new Set<string>();
  const result: string[] = [];
  for (const key of initialOrder) {
    if (known.has(key) && !seen.has(key)) {
      result.push(key);
      seen.add(key);
    }
  }
  for (const key of keys) {
    if (!seen.has(key)) result.push(key);
  }
  return result;
}

/**
 * Build the initial set of hidden keys, guaranteeing the at-least-one-visible
 * invariant even at construction time.
 */
function buildInitialHidden(keys: string[], initialHidden?: string[]): Set<string> {
  if (!initialHidden || initialHidden.length === 0) return new Set();
  const known = new Set(keys);
  const hidden = new Set(initialHidden.filter((k) => known.has(k)));
  // Never start with zero visible columns: if every column was requested
  // hidden, fall back to showing all of them.
  if (hidden.size >= keys.length) return new Set();
  return hidden;
}

/**
 * Manage visibility and ordering of a table's columns while enforcing the
 * invariant that at least one column remains visible at all times.
 */
export function useColumnConfig<Col extends ColumnConfigItem>(
  columns: Col[],
  options: UseColumnConfigOptions = {},
): UseColumnConfigResult<Col> {
  // A map from key -> column definition, recomputed when the column set changes.
  const columnKeys = columns.map((c) => c.key);
  const columnKeySignature = columnKeys.join('\u0000');

  const columnMap = useMemo(() => {
    const map = new Map<string, Col>();
    for (const col of columns) map.set(col.key, col);
    return map;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [columnKeySignature]);

  const [order, setOrder] = useState<string[]>(() =>
    buildInitialOrder(columnKeys, options.initialOrder),
  );
  const [hidden, setHidden] = useState<Set<string>>(() =>
    buildInitialHidden(columnKeys, options.initialHidden),
  );
  const [lastError, setLastError] = useState<string | null>(null);

  // Reconcile internal state when the supplied column set changes (columns
  // added or removed). Done during render via a ref-guarded signature compare to
  // avoid an extra effect/render cycle.
  const lastSignature = useRef(columnKeySignature);
  if (lastSignature.current !== columnKeySignature) {
    lastSignature.current = columnKeySignature;
    setOrder((prev) => {
      const known = new Set(columnKeys);
      const kept = prev.filter((k) => known.has(k));
      const keptSet = new Set(kept);
      for (const key of columnKeys) if (!keptSet.has(key)) kept.push(key);
      return kept;
    });
    setHidden((prev) => {
      const known = new Set(columnKeys);
      const next = new Set([...prev].filter((k) => known.has(k)));
      // Preserve the invariant if column removal left nothing visible.
      if (next.size >= columnKeys.length && columnKeys.length > 0) {
        return new Set();
      }
      return next;
    });
  }

  const visibleOrder = useMemo(
    () => order.filter((key) => !hidden.has(key)),
    [order, hidden],
  );

  const orderedColumns = useMemo(
    () => order.map((key) => columnMap.get(key)).filter((c): c is Col => Boolean(c)),
    [order, columnMap],
  );

  const visibleColumns = useMemo(
    () =>
      visibleOrder
        .map((key) => columnMap.get(key))
        .filter((c): c is Col => Boolean(c)),
    [visibleOrder, columnMap],
  );

  const isVisible = useCallback(
    (key: string) => columnMap.has(key) && !hidden.has(key),
    [columnMap, hidden],
  );

  const visibleCount = visibleOrder.length;

  const canHide = useCallback(
    (key: string) => {
      if (!columnMap.has(key)) return false;
      // Hiding is allowed as long as at least one OTHER column stays visible.
      if (hidden.has(key)) return visibleCount >= 1;
      return visibleCount > 1;
    },
    [columnMap, hidden, visibleCount],
  );

  const hideColumn = useCallback(
    (key: string): boolean => {
      if (!columnMap.has(key) || hidden.has(key)) {
        // Unknown or already hidden — no change, not a rejection to surface.
        return false;
      }
      if (visibleCount <= 1) {
        // Last visible column: reject with an indication, no state change.
        setLastError(AT_LEAST_ONE_VISIBLE_MESSAGE);
        return false;
      }
      setHidden((prev) => {
        const next = new Set(prev);
        next.add(key);
        return next;
      });
      setLastError(null);
      return true;
    },
    [columnMap, hidden, visibleCount],
  );

  const showColumn = useCallback(
    (key: string): boolean => {
      if (!columnMap.has(key) || !hidden.has(key)) return false;
      setHidden((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
      setLastError(null);
      return true;
    },
    [columnMap, hidden],
  );

  const toggleColumn = useCallback(
    (key: string): boolean => (hidden.has(key) ? showColumn(key) : hideColumn(key)),
    [hidden, showColumn, hideColumn],
  );

  const reorder = useCallback(
    (key: string, toIndex: number): boolean => {
      if (!columnMap.has(key)) return false;
      let changed = false;
      setOrder((prev) => {
        const from = prev.indexOf(key);
        if (from === -1) return prev;
        const clamped = Math.max(0, Math.min(toIndex, prev.length - 1));
        if (from === clamped) return prev;
        const next = [...prev];
        next.splice(from, 1);
        next.splice(clamped, 0, key);
        changed = true;
        return next;
      });
      if (changed) setLastError(null);
      return changed;
    },
    [columnMap],
  );

  return {
    orderedColumns,
    visibleColumns,
    order,
    visibleOrder,
    isVisible,
    hideColumn,
    showColumn,
    toggleColumn,
    reorder,
    canHide,
    lastError,
  };
}

export default useColumnConfig;
