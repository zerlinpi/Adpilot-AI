// AdPilot AI — Store switcher logic (search, grouping, default selection)
//
// Pure, render-independent functions that decide what the store switcher shows
// and which store is active. The header `StoreSwitcher` and `StoreProvider`
// delegate to these helpers so that "what gets rendered / selected" is governed
// by exactly this logic (Req 5.2.1–5.2.4).
//
// Keeping the decision logic here — mirroring the `navVisibility.ts` pattern —
// lets the property tests (store-name search, grouping partition) and the
// default-selection unit test validate the production behavior directly,
// without rendering React.

/** Minimal shape of a store needed for switcher decisions. The backend already
 *  scopes the store list to the stores the user may access (Req 5.2.1), so the
 *  switcher simply renders whatever it is given. */
export interface SwitcherStore {
  id: string;
  name: string;
  /** Optional group label; stores sharing a label are shown together
   *  (Req 5.2.4). A null/blank/undefined group means "ungrouped". */
  storeGroup?: string | null;
  /** First-class Store_Group identifier the store belongs to. Used to filter
   *  the switcher to the account's Store_Group_Scope (platform-workspace-rbac
   *  Req 13.5). Every store resolves to exactly one Store_Group server-side, so
   *  this is normally present; an absent id is treated as out of scope under a
   *  restricted scope. */
  storeGroupId?: string | null;
  /** The Nav_Block Platform_Family the store belongs to (`amazon` /
   *  `independent_site` / `tiktok`). Used to restrict switching to the current
   *  block's family so a selection never crosses platform families
   *  (multistore-ai-ads-operations Req 6.4). */
  platformFamily?: string | null;
}

/**
 * The account's Store_Group_Scope — the set of Store_Group identifiers whose
 * stores the account may see. A `null`/`undefined` scope means "unrestricted"
 * (for example a Super_Administrator, or before the scope has loaded) and does
 * not filter anything; an array (including the empty array) is a restriction to
 * exactly those groups.
 */
export type StoreGroupScope = string[] | null | undefined;

/** A group heading plus the stores that fall under it, used to render the
 *  switcher organized by group. */
export interface StoreGroup<S extends SwitcherStore = SwitcherStore> {
  /** The group label (or the ungrouped fallback label). */
  group: string;
  /** Stores assigned to this group, in their original list order. */
  stores: S[];
}

/** Fallback label for stores that have no assigned group (Req 5.2.4). */
export const UNGROUPED_LABEL = '未分组';

/** Normalize text for case-insensitive, whitespace-tolerant comparison. */
function normalize(text: string | null | undefined): string {
  return (text ?? '').trim().toLowerCase();
}

/**
 * Decide whether a single store matches the search text (Req 5.2.2). An empty
 * or whitespace-only search matches every store; otherwise the store matches
 * when its name contains the search text, compared case-insensitively.
 */
export function storeMatchesSearch(store: SwitcherStore, search: string): boolean {
  const needle = normalize(search);
  if (needle.length === 0) return true;
  return normalize(store.name).includes(needle);
}

/**
 * Filter the switcher list to the stores whose name matches `search`
 * (Req 5.2.2). The result preserves input order and contains exactly the
 * stores for which {@link storeMatchesSearch} holds — every matching store and
 * no non-matching store.
 */
export function filterStoresByName<S extends SwitcherStore>(stores: S[], search: string): S[] {
  return stores.filter((store) => storeMatchesSearch(store, search));
}

/**
 * Organize stores by their assigned group (Req 5.2.4). The result is a complete
 * partition of the input: every store appears under exactly its assigned group
 * (or {@link UNGROUPED_LABEL} when it has no group), each store appears exactly
 * once, and within a group stores keep their original order. Groups are emitted
 * in the order their first member appears in the input.
 */
export function groupStores<S extends SwitcherStore>(
  stores: S[],
  ungroupedLabel: string = UNGROUPED_LABEL,
): StoreGroup<S>[] {
  const groups: StoreGroup<S>[] = [];
  const byLabel = new Map<string, StoreGroup<S>>();

  for (const store of stores) {
    const label = normalize(store.storeGroup).length > 0 ? store.storeGroup!.trim() : ungroupedLabel;
    let group = byLabel.get(label);
    if (!group) {
      group = { group: label, stores: [] };
      byLabel.set(label, group);
      groups.push(group);
    }
    group.stores.push(store);
  }

  return groups;
}

/**
 * Restrict the switcher list to the stores whose Store_Group is within the
 * account's Store_Group_Scope (platform-workspace-rbac Req 13.5).
 *
 * The backend is the authoritative isolation boundary and already scopes the
 * store list to the account's permitted Store_Groups; this frontend filter is a
 * defense-in-depth/UX layer so the switcher never offers a store outside scope.
 *
 * Semantics:
 *  - An unrestricted scope (`null`/`undefined`, e.g. Super_Administrator or a
 *    scope that has not loaded yet) returns the list unchanged.
 *  - A restricted scope (any array, including `[]`) returns only the stores
 *    whose `storeGroupId` is one of the scoped group ids; a store with no
 *    resolvable group id is excluded because it cannot be proven in scope.
 *
 * The result preserves input order.
 */
export function filterStoresByGroupScope<S extends SwitcherStore>(
  stores: S[],
  storeGroupScope: StoreGroupScope,
): S[] {
  if (storeGroupScope == null) return stores;
  const allowed = new Set(storeGroupScope);
  return stores.filter((store) => !!store.storeGroupId && allowed.has(store.storeGroupId));
}

/**
 * Restrict the switcher list to the stores of a single Nav_Block Platform_Family
 * (multistore-ai-ads-operations Req 6.4). When the user is inside a Nav_Block of
 * a known family, the switcher offers only that family's stores, so a selection
 * never crosses platform families and the chosen store does not affect another
 * family's view.
 *
 * A `null`/`undefined` family means "no restriction" — used when the active
 * route is not owned by a single platform family — and returns the list
 * unchanged. The result preserves input order.
 */
export function filterStoresByPlatformFamily<S extends SwitcherStore>(
  stores: S[],
  family: string | null | undefined,
): S[] {
  if (family == null) return stores;
  return stores.filter((store) => store.platformFamily === family);
}

/** Inputs that influence which store is active when the switcher loads. */
export interface DefaultStoreSelection {
  /** The store id the user configured as their default (Req 5.2.3). */
  defaultStoreId?: string | null;
  /** The store id persisted from a previous selection (e.g. localStorage). */
  savedStoreId?: string | null;
}

/**
 * Choose which store id is active when the switcher loads (Req 5.2.3). The
 * user's configured default store wins when it is among the accessible stores,
 * so it is selected on the next login; otherwise a still-valid previously-saved
 * selection is used, then the first accessible store, and finally null when the
 * user has no accessible stores. A selection that no longer refers to an
 * accessible store is ignored rather than left dangling.
 */
export function selectDefaultStoreId<S extends SwitcherStore>(
  stores: S[],
  selection: DefaultStoreSelection = {},
): string | null {
  if (stores.length === 0) return null;
  const has = (id: string | null | undefined): id is string =>
    !!id && stores.some((s) => s.id === id);

  if (has(selection.defaultStoreId)) return selection.defaultStoreId;
  if (has(selection.savedStoreId)) return selection.savedStoreId;
  return stores[0].id;
}
