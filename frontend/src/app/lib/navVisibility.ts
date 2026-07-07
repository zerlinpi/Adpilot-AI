// AdPilot AI — Navigation & action-control visibility logic
//
// Pure, render-independent functions that decide which navigation items and
// action controls a user is permitted to see. The navigation sidebar
// (`Layout`) and the `<RequirePermission>` action-control wrapper both delegate
// to these helpers so that "what gets rendered" is governed by exactly this
// logic (Req 3.1.1, 3.1.2).

/** Predicate that returns true when the logged-in user holds `permission`. */
export type CanFn = (permission: string) => boolean;

/** Minimal shape of a navigation item for visibility decisions. An item
 *  without a `permission` is always visible (e.g. personal pages). */
export interface NavVisibilityItem {
  permission?: string;
}

/** Minimal shape of a navigation section grouping items under a label. */
export interface NavVisibilitySection<I extends NavVisibilityItem = NavVisibilityItem> {
  items: I[];
}

/**
 * Decide whether a single navigation item is shown. An item with no required
 * permission is always visible; otherwise it is visible exactly when the user
 * holds the required permission (Req 3.1.1).
 */
export function isNavItemVisible(item: NavVisibilityItem, can: CanFn): boolean {
  return !item.permission || can(item.permission);
}

/**
 * Filter navigation sections down to the items the user may see. Items are
 * dropped unless {@link isNavItemVisible} allows them, and sections that become
 * empty after filtering are removed so the user never sees an empty group
 * heading (Req 3.1.1).
 */
export function filterNavSections<
  I extends NavVisibilityItem,
  S extends NavVisibilitySection<I>,
>(sections: S[], can: CanFn): S[] {
  return sections
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => isNavItemVisible(item, can)),
    }))
    .filter((section) => section.items.length > 0);
}

// ─── Nav_Block (platform-scoped) visibility ─────────────────────────────────
//
// A Nav_Block is gated by two dimensions at once (Req 12.2, 15.3, 1.7): the
// account's Platform_Access (which families it may enter — bypassed for a
// Super_Administrator) and whether the block still contains at least one
// Nav_Item the account is permitted to see. `Layout` delegates its block
// rendering decision to {@link isBlockVisible} so the sidebar and this logic
// can never diverge.

/**
 * The account's Platform_Access dimension: the platform families it may enter,
 * plus the Super_Administrator flag that bypasses platform gating entirely
 * (Req 12.1, 12.2, 15.3). Families are compared by their string key
 * (`amazon` / `independent_site` / `logistics` / `finance`).
 */
export interface PlatformAccess {
  /** Granted platform families. Ignored when {@link superAdmin} is true. */
  families: readonly string[];
  /** When true the account may enter every Nav_Block regardless of families. */
  superAdmin: boolean;
}

/**
 * Decide whether the account may enter a Nav_Block of the given platform
 * family: a Super_Administrator may enter any family, otherwise the family must
 * be within the account's granted Platform_Access (Req 12.2, 15.3).
 */
export function hasPlatformAccess(family: string, access: PlatformAccess): boolean {
  return access.superAdmin || access.families.includes(family);
}

/** Minimal shape of a Nav_Block for visibility decisions: a platform-family
 *  `key` gated by Platform_Access plus its second-level items. */
export interface NavVisibilityBlock<I extends NavVisibilityItem = NavVisibilityItem> {
  key: string;
  items: I[];
}

/**
 * Decide whether a Nav_Block is rendered (Property 20). The block is shown if
 * and only if BOTH hold:
 *   1. its platform family is within the account's Platform_Access, or the
 *      account is a Super_Administrator (Req 12.2, 15.3), AND
 *   2. it contains at least one Nav_Item the account is permitted to see
 *      (Req 1.7).
 * Per-item visibility (functional permission + route resolution) is supplied by
 * the `isItemVisible` predicate so this composition matches exactly what the
 * sidebar renders for each item.
 */
export function isBlockVisible<I extends NavVisibilityItem>(
  block: NavVisibilityBlock<I>,
  access: PlatformAccess,
  isItemVisible: (item: I) => boolean,
): boolean {
  if (!hasPlatformAccess(block.key, access)) return false;
  return block.items.some(isItemVisible);
}

/** A permission requirement attached to an action control. */
export interface ActionPermissionRequirement {
  /** A single required permission. */
  permission?: string;
  /** Allowed when the user holds at least one of these. */
  anyOf?: string[];
  /** Allowed when the user holds all of these. */
  allOf?: string[];
}

/**
 * Decide whether an action control is exposed (rendered/enabled) for the user
 * (Req 3.1.2). With no requirement the control is always allowed. Otherwise the
 * user must satisfy every specified requirement: hold `permission`, hold at
 * least one of `anyOf`, and hold all of `allOf`.
 */
export function isActionAllowed(req: ActionPermissionRequirement, can: CanFn): boolean {
  const { permission, anyOf, allOf } = req;

  // No requirement specified -> always allowed.
  if (!permission && !anyOf?.length && !allOf?.length) return true;

  if (permission && !can(permission)) return false;
  if (anyOf?.length && !anyOf.some((p) => can(p))) return false;
  if (allOf?.length && !allOf.every((p) => can(p))) return false;
  return true;
}
