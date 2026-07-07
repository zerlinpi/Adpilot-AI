// AdPilot AI — Advertising context preservation across tab switches
// (advertising-workspace-rework Req 45.1, 45.2, 45.3)
//
// When an operator switches from one advertising tab to another and returns,
// the originating tab's working context must be restored: its active filters
// (Req 45.1), its row selection for rows that are still present in the data
// (Req 45.2), and any unsaved in-progress edit must be preserved rather than
// discarded (Req 45.3).
//
// This module is the PURE, side-effect-free core of that behavior — no React,
// no router, no global state — so the capture→restore round-trip can be
// property-tested in isolation (task 20.7, Property 69) and reused by the
// `useAdvertisingContextPreservation` hook that drives the workspace shell.
//
// The model is a small immutable store keyed by tab. `captureTabContext` writes
// a tab's snapshot into the store on leaving it; `restoreTabContext` reads the
// snapshot back on return, intersecting the saved selection with the rows that
// are still present so a selection never resurrects a row that has since
// disappeared.

import type { AdvertisingQueryState } from './advertisingQueryState';
import { emptyQueryState } from './advertisingQueryState';

/**
 * An unsaved, in-progress edit for a tab. Modeled as an opaque field→value map
 * so any tab can stash whatever draft shape it owns; `null` means "no draft".
 * The preservation layer never inspects or mutates the contents (Req 45.3).
 */
export interface DraftState {
  readonly [field: string]: unknown;
}

/**
 * The preserved working context for a single advertising tab: its query state
 * (filters/date-range/sort/pagination — Req 45.1), its selected row ids
 * (Req 45.2), and its unsaved draft if any (Req 45.3).
 */
export interface TabContextSnapshot {
  readonly query: AdvertisingQueryState;
  readonly selectedRowIds: readonly string[];
  readonly draft: DraftState | null;
}

/**
 * The per-tab context store: tab key → its last preserved snapshot. Immutable;
 * every mutation returns a new object so the value can drive React state safely.
 */
export type AdvertisingContextStore = Readonly<Record<string, TabContextSnapshot>>;

/** The canonical empty store (no tab has preserved context yet). */
export function emptyContextStore(): AdvertisingContextStore {
  return {};
}

/** A blank snapshot: default query, no selection, no draft. */
export function emptyTabSnapshot(): TabContextSnapshot {
  return { query: emptyQueryState(), selectedRowIds: [], draft: null };
}

/**
 * Intersect a saved selection with the rows currently present in the data,
 * preserving the saved selection order and dropping duplicates. Rows that are
 * no longer present are dropped so the restored selection only ever references
 * rows the operator can actually see (Req 45.2).
 */
export function intersectSelection(
  selectedRowIds: readonly string[],
  presentRowIds: readonly string[],
): string[] {
  const present = new Set(presentRowIds);
  const seen = new Set<string>();
  const out: string[] = [];
  for (const id of selectedRowIds) {
    if (present.has(id) && !seen.has(id)) {
      seen.add(id);
      out.push(id);
    }
  }
  return out;
}

/**
 * Write a tab's working context into the store on leaving it, returning a new
 * store. The snapshot is stored verbatim (the selection is intersected with
 * present rows only on restore, not on capture, so a row that disappears and
 * later reappears can still be reselected).
 */
export function captureTabContext(
  store: AdvertisingContextStore,
  tabKey: string,
  snapshot: TabContextSnapshot,
): AdvertisingContextStore {
  return { ...store, [tabKey]: snapshot };
}

/**
 * Read a tab's preserved context back on return. The query is restored as it
 * was (Req 45.1); the selection is restored only for rows still present in
 * `presentRowIds` (Req 45.2); the unsaved draft is preserved untouched
 * (Req 45.3). A tab with no preserved snapshot resolves to a blank snapshot so
 * a never-visited tab starts clean.
 */
export function restoreTabContext(
  store: AdvertisingContextStore,
  tabKey: string,
  presentRowIds: readonly string[] = [],
): TabContextSnapshot {
  const saved = store[tabKey];
  if (!saved) return emptyTabSnapshot();
  return {
    query: saved.query,
    selectedRowIds: intersectSelection(saved.selectedRowIds, presentRowIds),
    draft: saved.draft,
  };
}

/**
 * Drop all preserved context — used when the Active_Store changes so context
 * from the previous store is never carried across (Req 40.1).
 */
export function clearContextStore(): AdvertisingContextStore {
  return emptyContextStore();
}
