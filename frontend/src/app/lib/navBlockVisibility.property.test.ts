// Feature: platform-workspace-rbac, Property 20: For any account, a Nav_Block
// is rendered if and only if its platform family is within the account's
// Platform_Access (or the account is a Super_Administrator) and the block
// contains at least one Nav_Item the account is permitted to see; likewise an
// Account_Area utility is shown if and only if the account holds its required
// permission.
//
// Validates: Requirements 1.7, 3.2, 3.3, 12.2, 15.3
//
// These tests exercise the exact pure functions the navigation shell delegates
// to:
//   - `isBlockVisible` (composed with the real `routeResolves` + permission
//     predicate) drives whether a Nav_Block renders in `Layout`.
//   - `isNavItemVisible` drives Account_Area utility visibility.
// They run against the production `navBlocks` / `accountAreaItems` configuration
// so the property is validated on the real navigation, plus over generated
// blocks to cover the full "for any account" input space. No React rendering is
// mocked — the rendering decision is exactly this logic.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';

import {
  isBlockVisible,
  isNavItemVisible,
  hasPlatformAccess,
  type CanFn,
  type PlatformAccess,
  type NavVisibilityItem,
} from './navVisibility';
import {
  navBlocks,
  accountAreaItems,
  routeResolves,
  type PlatformFamily,
} from './navConfig';

const RUNS = 200; // >= 100 iterations as required for property tests.

const FAMILIES: PlatformFamily[] = ['amazon', 'independent_site', 'logistics', 'finance', 'tiktok'];

// The full pool of permission codes referenced by the production navigation, so
// generated permission sets meaningfully overlap what blocks/items require.
const PERMISSION_POOL = Array.from(
  new Set(
    [...navBlocks.flatMap((b) => b.items), ...accountAreaItems]
      .map((i) => i.permission)
      .filter((p): p is string => typeof p === 'string'),
  ),
);

const permissionArb = fc.constantFrom(...PERMISSION_POOL);

/** A set of held functional permissions (an arbitrary subset of the pool). */
const heldPermissionsArb = fc
  .uniqueArray(permissionArb, { maxLength: PERMISSION_POOL.length })
  .map((arr) => new Set(arr));

/** An account's Platform_Access: an arbitrary subset of the four families plus
 *  an independent super-admin flag (super-admin bypasses the family gate). */
const platformAccessArb: fc.Arbitrary<PlatformAccess> = fc.record({
  families: fc.uniqueArray(fc.constantFrom<PlatformFamily>(...FAMILIES), { maxLength: FAMILIES.length }),
  superAdmin: fc.boolean(),
});

/** Build the production-style `can` predicate from a held-permission set. */
function makeCan(held: Set<string>): CanFn {
  return (permission: string) => held.has(permission);
}

/** The real per-item rendering predicate the sidebar applies: the route must
 *  resolve (Req 2.5) and the gating functional permission (if any) must be
 *  held (Req 12, 14). Mirrors `Layout.isItemVisible`. */
function makeItemVisible(can: CanFn) {
  return (item: NavVisibilityItem & { to: string }) =>
    routeResolves(item.to) && (!item.permission || can(item.permission));
}

describe('Feature: platform-workspace-rbac, Property 20: Navigation block visibility', () => {
  it('a production Nav_Block renders iff platform access AND at least one permitted item', () => {
    fc.assert(
      fc.property(platformAccessArb, heldPermissionsArb, (access, held) => {
        const can = makeCan(held);
        const itemVisible = makeItemVisible(can);

        for (const block of navBlocks) {
          // Independently recompute the property's two conjuncts.
          const accessOk = access.superAdmin || access.families.includes(block.key);
          const hasPermittedItem = block.items.some(
            (it) => routeResolves(it.to) && (!it.permission || held.has(it.permission)),
          );
          const expected = accessOk && hasPermittedItem;

          expect(isBlockVisible(block, access, itemVisible)).toBe(expected);
        }
      }),
      { numRuns: RUNS },
    );
  });

  it('no Nav_Block renders when its family is outside a non-super-admin Platform_Access', () => {
    fc.assert(
      fc.property(
        fc.constantFrom<PlatformFamily>(...FAMILIES),
        heldPermissionsArb,
        (excluded, held) => {
          const can = makeCan(held);
          const itemVisible = makeItemVisible(can);
          // Access grants every family except `excluded`; not a super-admin.
          const access: PlatformAccess = {
            families: FAMILIES.filter((f) => f !== excluded),
            superAdmin: false,
          };
          const block = navBlocks.find((b) => b.key === excluded)!;
          // Family gated out -> block hidden regardless of held permissions.
          expect(isBlockVisible(block, access, itemVisible)).toBe(false);
        },
      ),
      { numRuns: RUNS },
    );
  });

  it('a super-admin sees every block that has at least one route-resolving item', () => {
    fc.assert(
      fc.property(heldPermissionsArb, (held) => {
        const access: PlatformAccess = { families: [], superAdmin: true };
        // Super-admin holds every permission (bypasses functional gating too),
        // so block visibility reduces to "has at least one resolving route".
        const itemVisible = (item: any) => routeResolves(item.to);
        for (const block of navBlocks) {
          const hasResolvingItem = block.items.some((it) => routeResolves(it.to));
          expect(isBlockVisible(block, access, itemVisible)).toBe(hasResolvingItem);
        }
        // The held set is irrelevant to a super-admin's block access.
        void held;
      }),
      { numRuns: RUNS },
    );
  });

  it('an Account_Area utility is shown iff the account holds its required permission', () => {
    fc.assert(
      fc.property(heldPermissionsArb, (held) => {
        const can = makeCan(held);
        for (const item of accountAreaItems) {
          const expected = !item.permission || held.has(item.permission);
          expect(isNavItemVisible(item, can)).toBe(expected);
        }
      }),
      { numRuns: RUNS },
    );
  });

  // The same invariant must hold for any account/navigation shape, not only the
  // shipped configuration. Generated blocks pair an arbitrary family with items
  // that each carry an optional permission and an "always-resolving" flag.
  const genItemArb = fc.record({
    permission: fc.option(permissionArb, { nil: undefined }),
    resolves: fc.boolean(),
  });
  const genBlockArb = fc.record({
    key: fc.constantFrom<PlatformFamily>(...FAMILIES),
    items: fc.array(genItemArb, { minLength: 0, maxLength: 8 }),
  });

  it('holds for arbitrary blocks: visible iff platform access AND a permitted item', () => {
    fc.assert(
      fc.property(genBlockArb, platformAccessArb, heldPermissionsArb, (block, access, held) => {
        const can = makeCan(held);
        // Item visibility here is "marked resolving AND permission held".
        const itemVisible = (it: { permission?: string; resolves: boolean }) =>
          it.resolves && (!it.permission || can(it.permission));

        const accessOk = hasPlatformAccess(block.key, access);
        const hasPermittedItem = block.items.some(
          (it) => it.resolves && (!it.permission || held.has(it.permission)),
        );
        const expected = accessOk && hasPermittedItem;

        expect(isBlockVisible(block, access, itemVisible)).toBe(expected);
      }),
      { numRuns: RUNS },
    );
  });
});
