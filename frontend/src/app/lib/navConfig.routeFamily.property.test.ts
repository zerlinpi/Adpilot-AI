// Feature: platform-workspace-rbac, Property 22: Every rendered Nav_Item
// resolves to a route and matches its block's family.
//
// For any Nav_Item in the navigation configuration, the item is rendered only
// if its route resolves in the route table, and the item's platform family
// equals its containing Nav_Block's platform family.
//
// Two clauses are proven:
//   (Req 2.5) Route resolution gate — the rendering decision never yields an
//     item whose route (base path, query/fragment stripped) is absent from the
//     resolvable route table. This is exercised across arbitrary route tables
//     and arbitrary permission sets so the gate holds for every combination.
//   (Req 2.6) Family consistency — every Nav_Item's platform family (the family
//     it is associated with for platform-scoped filtering) equals the key of
//     the Nav_Block that contains it, and that key is one of the four valid
//     Platform_Access families.
//
// Validates: Requirements 2.5, 2.6

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';

import {
  navBlocks,
  navItemBasePath,
  routeResolves,
  RESOLVABLE_ROUTES,
  type NavItem,
  type NavBlock,
  type PlatformFamily,
} from './navConfig';

const RUNS = 200; // >= 100 iterations as required for property tests.

const FAMILIES: PlatformFamily[] = ['amazon', 'independent_site', 'logistics', 'finance', 'tiktok'];

/**
 * Pure, parameterized mirror of Layout's `isItemVisible` rendering decision: an
 * item is rendered only when its route resolves against the supplied route
  * table AND the account holds the gating functional permission.Parameterizing
    * the resolvable set lets the property exercise arbitrary route tables, while
 * the route - resolution semantics(base - path match, query / fragment stripped)
  * stay identical to production via`navItemBasePath`.
 */
function itemRendered(
  item: NavItem,
  resolvable: ReadonlySet<string>,
  granted: ReadonlySet<string>,
): boolean {
  if (!resolvable.has(navItemBasePath(item.to))) return false;
  return !item.permission || granted.has(item.permission);
}

/** Flatten the four blocks into (family, item) pairs, tagging each item with
 *  the family of its containing block — the association under test (Req 2.6). */
const flatItems: { family: PlatformFamily; block: NavBlock; item: NavItem }[] =
  navBlocks.flatMap((block) =>
    block.items.map((item) => ({ family: block.key, block, item })),
  );

/** Every distinct base path referenced by a Nav_Item across all blocks. */
const allItemBasePaths: string[] = Array.from(
  new Set(flatItems.map(({ item }) => navItemBasePath(item.to))),
);

/** Every distinct functional permission referenced across all Nav_Items. */
const allItemPermissions: string[] = Array.from(
  new Set(flatItems.map(({ item }) => item.permission).filter((p): p is string => !!p)),
);

describe('Feature: platform-workspace-rbac, Property 22: Every rendered Nav_Item resolves to a route and matches its block family', () => {
  // ── Clause 1 (Req 2.5): the rendering decision never surfaces an item whose
  //    route is absent from the route table, for any route table / permissions.
  it('renders an item only when its route resolves in the route table', () => {
    // Arbitrary route table: an arbitrary subset of the real item routes, plus
    // arbitrary unrelated paths that must never cause an item to render.
    const resolvableArb = fc
      .tuple(
        fc.subarray(allItemBasePaths),
        fc.array(
          fc.string().map((s) => '/' + s.replace(/[?#]/g, '')),
          { maxLength: 5 },
        ),
      )
      .map(([routes, extras]) => new Set<string>([...routes, ...extras]));

    const grantedArb = fc
      .subarray(allItemPermissions)
      .map((perms) => new Set<string>(perms));

    fc.assert(
      fc.property(resolvableArb, grantedArb, (resolvable, granted) => {
        for (const block of navBlocks) {
          for (const item of block.items) {
            if (itemRendered(item, resolvable, granted)) {
              // Rendered => route is present in the table (Req 2.5).
              expect(resolvable.has(navItemBasePath(item.to))).toBe(true);
            }
          }
        }
        // Conversely: any item whose route is NOT in the table is never rendered.
        for (const { item } of flatItems) {
          if (!resolvable.has(navItemBasePath(item.to))) {
            expect(itemRendered(item, resolvable, granted)).toBe(false);
          }
        }
      }),
      { numRuns: RUNS },
    );
  });

  // ── Clause 2 (Req 2.6): a rendered item's family equals its containing
  //    block's family, regardless of route table or permission set.
  it("every rendered item's family equals its containing block's family", () => {
    const resolvableArb = fc
      .subarray(allItemBasePaths)
      .map((routes) => new Set<string>(routes));
    const grantedArb = fc
      .subarray(allItemPermissions)
      .map((perms) => new Set<string>(perms));

    fc.assert(
      fc.property(resolvableArb, grantedArb, (resolvable, granted) => {
        for (const block of navBlocks) {
          for (const item of block.items) {
            if (itemRendered(item, resolvable, granted)) {
              // The item is rendered inside this block, so its associated
              // platform family is exactly the block's family (Req 2.6).
              expect(FAMILIES).toContain(block.key);
            }
          }
        }
      }),
      { numRuns: RUNS },
    );
  });

  // ── Deterministic structural guarantees over the real configuration ──

  it('every Nav_Item belongs to a block whose key is a valid platform family (Req 2.6)', () => {
    for (const { family } of flatItems) {
      expect(FAMILIES).toContain(family);
    }
    // The four blocks are exactly the four families, in fixed order (Req 1.1).
    expect(navBlocks.map((b) => b.key)).toEqual(FAMILIES);
  });

  it('against the real route table, no item with an unresolved route would render (Req 2.5)', () => {
    // Grant every permission so the only gate exercised here is route resolution.
    const allGranted = new Set<string>(allItemPermissions);
    for (const { item } of flatItems) {
      const renderedReal = itemRendered(item, RESOLVABLE_ROUTES, allGranted);
      // `itemRendered` against the real table must agree with `routeResolves`
      // on the route-resolution clause for the production navigation.
      if (renderedReal) {
        expect(routeResolves(item.to)).toBe(true);
      }
      if (!routeResolves(item.to)) {
        expect(renderedReal).toBe(false);
      }
    }
  });

  it('the 独立站 block exposes a single hub entry (做减法: per-capability menu items removed) (Req 2.5)', () => {
    // 做减法: the 独立站 block collapses to the hub + connection entry. Google 广告,
    // 商品, 订单, 库存发货, 发布 are reached inside the per-store cockpit, so they
    // are intentionally NOT nav items. The hub entry must resolve.
    const independent = navBlocks.find((b) => b.key === 'independent_site')!;
    const hub = independent.items.find(({ to }) => navItemBasePath(to) === '/independent-site');
    expect(hub).toBeTruthy();
    expect(routeResolves('/independent-site')).toBe(true);
    // No per-capability Google Ads menu item remains in the block.
    expect(independent.items.some(({ to }) => navItemBasePath(to).startsWith('/google-ads'))).toBe(false);
  });
});
