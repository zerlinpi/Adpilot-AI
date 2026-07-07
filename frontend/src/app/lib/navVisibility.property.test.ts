// Feature: core-platform-completion, Property 12: Navigation and action
// controls expose only permitted items.
//
// For any permission set, the set of rendered navigation items and enabled
// action controls equals exactly the set of items whose required permission is
// held by the user.
//
// Validates: Requirements 3.1.1, 3.1.2
//
// These tests exercise the exact functions the UI delegates to:
//   - `filterNavSections` / `isNavItemVisible` drive the sidebar in `Layout`.
//   - `isActionAllowed` drives the `<RequirePermission>` action-control wrapper.
// Testing them directly validates the production rendering decision without
// mocking React.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  filterNavSections,
  isNavItemVisible,
  isActionAllowed,
  type CanFn,
  type NavVisibilityItem,
} from './navVisibility';

const RUNS = 200; // >= 100 iterations as required for property tests.

// A pool of permission codes resembling the real "module:action" format. A
// small fixed pool guarantees meaningful overlap between the permissions a user
// holds and the permissions items require.
const PERMISSION_POOL = [
  'dashboard:view',
  'order:view',
  'advertising:view',
  'keyword:view',
  'product:view',
  'product:create',
  'finance:view',
  'warehouse:view',
  'role:view',
  'user:view',
  'audit:view',
  'import:manage',
];

const permissionArb = fc.constantFrom(...PERMISSION_POOL);

/** The set of permissions a user holds, modelled as a Set for O(1) lookup. */
const heldPermissionsArb = fc
  .uniqueArray(permissionArb, { maxLength: PERMISSION_POOL.length })
  .map((arr) => new Set(arr));

/** Build the production-style `can` predicate from a held-permission set. */
function makeCan(held: Set<string>): CanFn {
  return (permission: string) => held.has(permission);
}

/** A navigation item: roughly half the time it has no required permission
 *  (always-visible personal pages), otherwise it requires one permission. */
const navItemArb: fc.Arbitrary<NavVisibilityItem & { to: string }> = fc.record({
  to: fc.string({ minLength: 1, maxLength: 8 }),
  permission: fc.option(permissionArb, { nil: undefined }),
});

const navSectionArb = fc.record({
  label: fc.string({ minLength: 1, maxLength: 6 }),
  items: fc.array(navItemArb, { minLength: 0, maxLength: 6 }),
});

const navSectionsArb = fc.array(navSectionArb, { minLength: 0, maxLength: 8 });

describe('Property 12: navigation and action controls expose only permitted items', () => {
  it('navigation exposes exactly the items the user is permitted to see', () => {
    fc.assert(
      fc.property(navSectionsArb, heldPermissionsArb, (sections, held) => {
        const can = makeCan(held);

        // Expected: every item whose permission is undefined OR held.
        const expectedVisible = sections.flatMap((s) =>
          s.items.filter((i) => i.permission === undefined || held.has(i.permission)),
        );
        // Items that must NOT appear: a defined, unheld permission.
        const expectedHidden = sections.flatMap((s) =>
          s.items.filter((i) => i.permission !== undefined && !held.has(i.permission)),
        );

        const result = filterNavSections(sections, can);
        const renderedItems = result.flatMap((s) => s.items);

        // Exactly the permitted items are rendered (no more, no fewer).
        expect(renderedItems.length).toBe(expectedVisible.length);
        for (const item of expectedVisible) {
          expect(renderedItems).toContain(item);
        }
        // No forbidden item leaks through.
        for (const item of expectedHidden) {
          expect(renderedItems).not.toContain(item);
        }

        // Every rendered item individually satisfies the visibility predicate.
        for (const item of renderedItems) {
          expect(isNavItemVisible(item, can)).toBe(true);
        }

        // No empty section survives filtering (never show an empty heading).
        for (const section of result) {
          expect(section.items.length).toBeGreaterThan(0);
        }
      }),
      { numRuns: RUNS },
    );
  });

  it('a single-permission action control is exposed iff the permission is held', () => {
    fc.assert(
      fc.property(permissionArb, heldPermissionsArb, (required, held) => {
        const can = makeCan(held);
        const allowed = isActionAllowed({ permission: required }, can);
        expect(allowed).toBe(held.has(required));
      }),
      { numRuns: RUNS },
    );
  });

  it('an action control with no requirement is always exposed', () => {
    fc.assert(
      fc.property(heldPermissionsArb, (held) => {
        const can = makeCan(held);
        expect(isActionAllowed({}, can)).toBe(true);
      }),
      { numRuns: RUNS },
    );
  });

  it('an anyOf action control is exposed iff at least one required permission is held', () => {
    fc.assert(
      fc.property(
        fc.uniqueArray(permissionArb, { minLength: 1, maxLength: 4 }),
        heldPermissionsArb,
        (anyOf, held) => {
          const can = makeCan(held);
          const allowed = isActionAllowed({ anyOf }, can);
          expect(allowed).toBe(anyOf.some((p) => held.has(p)));
        },
      ),
      { numRuns: RUNS },
    );
  });

  it('an allOf action control is exposed iff every required permission is held', () => {
    fc.assert(
      fc.property(
        fc.uniqueArray(permissionArb, { minLength: 1, maxLength: 4 }),
        heldPermissionsArb,
        (allOf, held) => {
          const can = makeCan(held);
          const allowed = isActionAllowed({ allOf }, can);
          expect(allowed).toBe(allOf.every((p) => held.has(p)));
        },
      ),
      { numRuns: RUNS },
    );
  });
});

// Feature: app-functionality-completion, Property 12: Navigation visibility
// structural invariant.
//
// For any nav config and permission set, each permitted item appears under
// exactly one section, and any section with no visible items is omitted.
//
// Validates: Requirements 16.2, 16.3
//
// This validates the structural guarantees of `filterNavSections` that back the
// SparkX-taxonomy sidebar (`Layout.navSections`): item-to-section uniqueness
// (Req 16.2 — "render each navigation item under exactly one Nav_Section") and
// empty-section omission (Req 16.3 — "omit any Nav_Section left with no visible
// items"). Each input item is tagged with a globally unique id so duplication
// across sections in the rendered output is detectable.
describe('Feature: app-functionality-completion, Property 12: Navigation visibility structural invariant', () => {
  it('each permitted item appears under exactly one section, and empty sections are omitted', () => {
    fc.assert(
      fc.property(navSectionsArb, heldPermissionsArb, (sections, held) => {
        const can = makeCan(held);

        // Tag every input item with a globally unique id. The id lets us detect
        // whether a permitted item appears under exactly one section (count 1),
        // is dropped (count 0), or is duplicated across sections (count > 1).
        let counter = 0;
        const taggedSections = sections.map((s) => ({
          ...s,
          items: s.items.map((item) => ({ ...item, uid: counter++ })),
        }));

        const result = filterNavSections(taggedSections, can);

        // Req 16.3: no rendered Nav_Section is left empty after filtering.
        for (const section of result) {
          expect(section.items.length).toBeGreaterThan(0);
        }

        // The permitted items from the input: those with no required permission
        // or whose required permission the user holds.
        const permittedUids = taggedSections.flatMap((s) =>
          s.items
            .filter((i) => i.permission === undefined || held.has(i.permission))
            .map((i) => i.uid),
        );

        // Count occurrences of each item id across all rendered sections.
        const renderedUids = result.flatMap((s) => s.items.map((i) => i.uid));
        const counts = new Map<number, number>();
        for (const uid of renderedUids) {
          counts.set(uid, (counts.get(uid) ?? 0) + 1);
        }

        // Req 16.2: every permitted item appears under exactly one section.
        for (const uid of permittedUids) {
          expect(counts.get(uid)).toBe(1);
        }

        // Nothing beyond the permitted items appears: rendered count equals the
        // permitted count, so no forbidden item leaks and none is duplicated.
        expect(renderedUids.length).toBe(permittedUids.length);
      }),
      { numRuns: RUNS },
    );
  });
});
