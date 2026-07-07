// AdPilot AI — Command palette data source
//
// Pure, framework-free projection of the navigation into a flat, searchable
// command list for the global ⌘K command palette. It reuses the EXACT same
// per-item visibility rule as the sidebar (`isNavItemVisible`, which also gates
// on route-resolvability), so the palette can never offer a page the operator
// cannot see or that does not resolve. Kept pure so it is unit-testable without
// rendering React.

import { navBlocks, accountAreaItems, isNavItemVisible } from './navConfig';

/** One navigable command: where it goes, its display label, and the block /
 *  section it belongs to (shown as the command group + search context). */
export interface NavCommand {
  to: string;
  label: string;
  group: string;
}

/**
 * Build the flat list of navigation commands the given account may run, in the
 * sidebar's defined order. Only items that are permission-visible AND
 * route-resolving are included (delegated to {@link isNavItemVisible}); the
 * Account_Area utilities are appended under a single 系统与管理 group.
 *
 * @param can permission predicate (from PermissionContext)
 */
export function buildNavCommands(can: (permission: string) => boolean): NavCommand[] {
  const list: NavCommand[] = [];
  for (const block of navBlocks) {
    for (const item of block.items) {
      if (isNavItemVisible(item, can)) {
        list.push({ to: item.to, label: item.label, group: block.label });
      }
    }
  }
  for (const item of accountAreaItems) {
    if (isNavItemVisible(item, can)) {
      list.push({ to: item.to, label: item.label, group: '系统与管理' });
    }
  }
  return list;
}

/** Group commands by their `group`, preserving first-seen group order — the
 *  shape the palette renders as cmdk groups. */
export function groupNavCommands(commands: NavCommand[]): { group: string; items: NavCommand[] }[] {
  const order: string[] = [];
  const byGroup = new Map<string, NavCommand[]>();
  for (const c of commands) {
    if (!byGroup.has(c.group)) {
      byGroup.set(c.group, []);
      order.push(c.group);
    }
    byGroup.get(c.group)!.push(c);
  }
  return order.map((group) => ({ group, items: byGroup.get(group)! }));
}

// ─── Recently-visited commands (most-recent-first quick access) ──────────────
// Top-tier command palettes surface where you just were. We persist the last
// few navigated routes and re-resolve them against the live, permission-filtered
// command list, so a recent entry that the account can no longer see (permission
// revoked / route removed) silently drops out.

const RECENT_KEY = 'adpilot.command.recent';
const RECENT_MAX = 6;

/**
 * Resolve the recent route list into commands, most-recent-first, keeping only
 * routes that still exist in `all` (visible + resolvable), de-duplicated, capped
 * at {@link RECENT_MAX}. Pure and unit-testable.
 */
export function pickRecentCommands(all: NavCommand[], recentRoutes: string[]): NavCommand[] {
  const byRoute = new Map(all.map((c) => [c.to, c]));
  const seen = new Set<string>();
  const out: NavCommand[] = [];
  for (const to of recentRoutes) {
    if (seen.has(to)) continue;
    const cmd = byRoute.get(to);
    if (cmd) {
      out.push(cmd);
      seen.add(to);
    }
    if (out.length >= RECENT_MAX) break;
  }
  return out;
}

/**
 * Fold a newly-visited route into a recent list, most-recent-first, de-duplicated
 * and capped. Pure: returns the next list without touching storage. Exported for
 * testing and reuse by the persistence helpers below.
 */
export function withRecentRoute(recentRoutes: string[], to: string): string[] {
  const next = [to, ...recentRoutes.filter((r) => r !== to)];
  return next.slice(0, RECENT_MAX);
}

/** Read the persisted recent-route list (safe on SSR / disabled storage). */
export function loadRecentRoutes(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

/** Persist a newly-visited route to the front of the recent list. */
export function pushRecentRoute(to: string): void {
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(withRecentRoute(loadRecentRoutes(), to)));
  } catch {
    /* storage unavailable — recent list is a non-critical convenience */
  }
}
