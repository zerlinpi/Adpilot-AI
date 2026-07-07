// Feature: multistore-ai-ads-operations, Property 10: 导航可见性是 Platform_Access 的纯函数
//
// For any account identity and its Platform_Access set, `visibleNavBlocks`
// returns the set of visible Nav_Blocks / Nav_Items such that a block is visible
// if and only if the account's Platform_Access contains the block's platform
// family (a Super_Administrator bypasses the family gate) AND the block holds at
// least one route-resolving Nav_Item the account is permitted to see. The same
// identity under a different Platform_Access yields correspondingly different
// visibility, computed purely from the supplied (freshly fetched) user with no
// login-state caching — i.e. visibility changes take effect without re-login.
//
// This exercises the production pure function `visibleNavBlocks(user)` directly,
// over arbitrary accounts (role / permission set / Platform_Access), and
// independently re-derives the expected projection from the acceptance
// criteria. The Google Ads block lives in the `independent_site` family and the
// `tiktok` block is its own family, so both are explicitly covered.
//
// Validates: Requirements 3.1, 3.2, 3.4, 5.4

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';

import {
  visibleNavBlocks,
  navBlocks,
  routeResolves,
  ALL_PLATFORM_FAMILIES,
  type PlatformFamily,
  type VisibleNavBlock,
} from './navConfig';
import type { CurrentUserInfo } from './api';

const RUNS = 200; // >= 100 iterations as required for property tests.

const FAMILIES: PlatformFamily[] = ['amazon', 'independent_site', 'logistics', 'finance', 'tiktok'];

// The full pool of functional-permission codes referenced by the navigation, so
// generated permission sets meaningfully overlap what Nav_Items require.
const PERMISSION_POOL: string[] = Array.from(
  new Set(
    navBlocks
      .flatMap((b) => b.items)
      .map((i) => i.permission)
      .filter((p): p is string => typeof p === 'string'),
  ),
);

const permissionArb = fc.constantFrom(...PERMISSION_POOL);

/** An arbitrary held functional-permission set (a subset of the real pool). */
const permissionsArb = fc
  .uniqueArray(permissionArb, { maxLength: PERMISSION_POOL.length })
  .map((arr) => arr as string[]);

/** An arbitrary Platform_Access value. `null` models the backend not yet
 *  surfacing the field (unrestricted fallback); an explicit (possibly empty)
 *  list gates the blocks. */
const platformAccessArb: fc.Arbitrary<string[] | null> = fc.option(
  fc.uniqueArray(fc.constantFrom<PlatformFamily>(...FAMILIES), { maxLength: FAMILIES.length }),
  { nil: null },
);

/** Role: a Super_Administrator bypasses the family gate; other roles do not. */
const roleArb = fc.constantFrom<string | undefined>('super_admin', 'admin', 'viewer', undefined);

/** A generated account in the `CurrentUserInfo` shape `visibleNavBlocks` reads:
 *  identity is irrelevant to visibility, so only role / permissions /
 *  platformAccess vary. */
const userArb: fc.Arbitrary<CurrentUserInfo> = fc
  .record({
    role: roleArb,
    permissions: permissionsArb,
    platformAccess: platformAccessArb,
  })
  .map(({ role, permissions, platformAccess }) => ({
    id: 'u',
    email: 'u@example.com',
    name: 'u',
    role,
    permissions,
    platformAccess,
  }));

/**
 * Independent re-derivation of the visibility projection straight from the
 * acceptance criteria (Req 3.1, 3.2, 5.4) — deliberately NOT calling the
 * production helper composition, so the property checks behaviour rather than
 * mirroring an implementation detail. Returns the visible (familyKey -> ordered
 * visible item routes) shape for comparison.
 */
function expectedVisibility(user: CurrentUserInfo): { key: PlatformFamily; routes: string[] }[] {
  const superAdmin = user.role === 'super_admin';
  const families: readonly string[] =
    user.platformAccess != null ? user.platformAccess : ALL_PLATFORM_FAMILIES;
  const held = new Set(user.permissions ?? []);
  const can = (perm?: string) => !perm || held.has(perm);

  const out: { key: PlatformFamily; routes: string[] }[] = [];
  for (const block of navBlocks) {
    const accessOk = superAdmin || families.includes(block.key);
    if (!accessOk) continue;
    const routes = block.items
      .filter((it) => routeResolves(it.to) && can(it.permission))
      .map((it) => it.to);
    if (routes.length === 0) continue;
    out.push({ key: block.key, routes });
  }
  return out;
}

/** Normalize the production result to the comparable (key -> routes) shape. */
function actualVisibility(result: VisibleNavBlock[]): { key: PlatformFamily; routes: string[] }[] {
  return result.map((vb) => ({ key: vb.block.key, routes: vb.items.map((it) => it.to) }));
}

describe('Feature: multistore-ai-ads-operations, Property 10: 导航可见性是 Platform_Access 的纯函数', () => {
  it('matches the Platform_Access × permission projection for any account', () => {
    fc.assert(
      fc.property(userArb, (user) => {
        expect(actualVisibility(visibleNavBlocks(user))).toEqual(expectedVisibility(user));
      }),
      { numRuns: RUNS },
    );
  });

  it('is a pure function: identical inputs yield identical visibility', () => {
    fc.assert(
      fc.property(userArb, (user) => {
        const first = actualVisibility(visibleNavBlocks(user));
        const second = actualVisibility(visibleNavBlocks({ ...user }));
        expect(second).toEqual(first);
      }),
      { numRuns: RUNS },
    );
  });

  it('a non-super-admin never sees a block whose family is outside its Platform_Access', () => {
    fc.assert(
      fc.property(
        fc.constantFrom<PlatformFamily>(...FAMILIES),
        permissionsArb,
        (excluded, permissions) => {
          const user: CurrentUserInfo = {
            id: 'u',
            email: 'u@example.com',
            name: 'u',
            role: 'admin',
            permissions,
            // Grant every family except the excluded one.
            platformAccess: FAMILIES.filter((f) => f !== excluded),
          };
          const keys = visibleNavBlocks(user).map((vb) => vb.block.key);
          expect(keys).not.toContain(excluded);
        },
      ),
      { numRuns: RUNS },
    );
  });

  it('the same identity under different Platform_Access flips a block on/off without re-login', () => {
    fc.assert(
      fc.property(
        fc.constantFrom<PlatformFamily>(...FAMILIES),
        (family) => {
          // Hold every permission so visibility is governed solely by access,
          // and the block has at least one route-resolving permitted item.
          const allPermissions = [...PERMISSION_POOL];
          const block = navBlocks.find((b) => b.key === family)!;
          const blockHasRenderableItem = block.items.some((it) => routeResolves(it.to));

          const withAccess: CurrentUserInfo = {
            id: 'u', email: 'u@example.com', name: 'u', role: 'admin',
            permissions: allPermissions,
            platformAccess: [family],
          };
          const withoutAccess: CurrentUserInfo = {
            ...withAccess,
            platformAccess: FAMILIES.filter((f) => f !== family),
          };

          const visibleWith = visibleNavBlocks(withAccess).some((vb) => vb.block.key === family);
          const visibleWithout = visibleNavBlocks(withoutAccess).some((vb) => vb.block.key === family);

          // Granting the family reveals the block (when it has a renderable
          // item); revoking it always hides the block. The decision is recomputed
          // purely from the passed-in Platform_Access (Req 3.4, 5.4).
          expect(visibleWith).toBe(blockHasRenderableItem);
          expect(visibleWithout).toBe(false);
        },
      ),
      { numRuns: RUNS },
    );
  });

  it('covers the 独立站 hub block and the tiktok block', () => {
    fc.assert(
      fc.property(fc.boolean(), fc.boolean(), (grantIndependent, grantTiktok) => {
        const families: PlatformFamily[] = [];
        if (grantIndependent) families.push('independent_site');
        if (grantTiktok) families.push('tiktok');

        const user: CurrentUserInfo = {
          id: 'u', email: 'u@example.com', name: 'u', role: 'admin',
          permissions: [...PERMISSION_POOL], // hold everything: access is the only gate
          platformAccess: families,
        };

        const keys = visibleNavBlocks(user).map((vb) => vb.block.key);

        // 做减法: the 独立站 block collapses to the hub entry; Google 广告 / 商品 /
        // 订单 / 库存发货 / 发布 live inside the per-store cockpit, not the nav. The
        // block appears iff independent_site access is granted (Req 3.2), and its
        // hub entry is present.
        expect(keys.includes('independent_site')).toBe(grantIndependent);
        if (grantIndependent) {
          const independent = visibleNavBlocks(user).find((vb) => vb.block.key === 'independent_site')!;
          expect(independent.items.some((it) => it.to === '/independent-site')).toBe(true);
        }

        // The tiktok block is its own family, gated by tiktok access (Req 5.4).
        expect(keys.includes('tiktok')).toBe(grantTiktok);
      }),
      { numRuns: RUNS },
    );
  });
});
