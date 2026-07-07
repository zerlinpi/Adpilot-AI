// Feature: advertising-workspace-rework, Property 59: Tab-to-group assignment
// is a total partition.
//
// For any advertising tab, it is assigned to exactly one of the four groups,
// and the union of the four groups covers every existing Campaigns_Workspace
// tab with none removed (Req 28.2). No tab is duplicated across groups
// (Req 28.4), and `groupForTab` is consistent with `ADVERTISING_GROUPS`
// (Req 28.1).
//
// Validates: Requirements 28.1, 28.2, 28.4
//
// The partition is fixed data, so the universal claim is exercised by
// generating over the tab/group universe — every canonical tab key, plus
// arbitrary strings that include invalid (non-tab) keys — and asserting the
// membership and uniqueness properties hold for each generated key. This proves
// the property "for any tab" rather than merely checking the four hard-coded
// lists once.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  ADVERTISING_GROUPS,
  groupForTab,
  tabsForGroup,
  isTotalPartition,
  type AdvertisingGroupKey,
} from './advertisingGroups';
import { CAMPAIGNS_TABS, type CampaignsTabKey } from './CampaignsWorkspace';

const RUNS = 200; // >= 100 iterations as required for property tests.

/** The canonical eleven tab keys (the partition universe). */
const CANONICAL_TAB_KEYS: readonly CampaignsTabKey[] = CAMPAIGNS_TABS.map((t) => t.key);
const CANONICAL_SET = new Set<string>(CANONICAL_TAB_KEYS);

const GROUP_KEYS: readonly AdvertisingGroupKey[] = ADVERTISING_GROUPS.map((g) => g.key);

/** All tabs assigned across every group, flattened in declaration order. */
const ASSIGNED_TABS: readonly CampaignsTabKey[] = ADVERTISING_GROUPS.flatMap((g) => g.tabs);

/**
 * A key drawn from the tab/group universe: roughly half the time a real tab
 * key, otherwise an arbitrary string (which is almost surely NOT a tab key).
 * This lets the membership properties be asserted for both valid and invalid
 * inputs.
 */
const tabKeyOrInvalidArb: fc.Arbitrary<string> = fc.oneof(
  fc.constantFrom<string>(...CANONICAL_TAB_KEYS),
  fc.string({ minLength: 0, maxLength: 12 }),
);

describe('Feature: advertising-workspace-rework, Property 59: tab-to-group assignment is a total partition', () => {
  it('is a total partition by the pure predicate', () => {
    // The module exposes the partition guarantee directly; assert it holds.
    expect(isTotalPartition()).toBe(true);
  });

  it('every existing tab is assigned to exactly one group, and unknown keys to none', () => {
    fc.assert(
      fc.property(tabKeyOrInvalidArb, (key) => {
        // Count how many groups list this key among their tabs.
        const owningGroups = ADVERTISING_GROUPS.filter((g) =>
          (g.tabs as readonly string[]).includes(key),
        );

        if (CANONICAL_SET.has(key)) {
          // A real tab: assigned to exactly one group (existence + uniqueness).
          expect(owningGroups.length).toBe(1);

          // groupForTab is consistent with ADVERTISING_GROUPS: it resolves to
          // the single owning group, and that group actually lists the tab.
          const resolved = groupForTab(key as CampaignsTabKey);
          expect(resolved).toBe(owningGroups[0].key);
          expect(tabsForGroup(resolved as AdvertisingGroupKey)).toContain(key as CampaignsTabKey);
        } else {
          // A non-tab key belongs to no group and resolves to undefined.
          expect(owningGroups.length).toBe(0);
          expect(groupForTab(key as CampaignsTabKey)).toBeUndefined();
        }
      }),
      { numRuns: RUNS },
    );
  });

  it('no tab is duplicated across groups and the assignment loses no tab', () => {
    // No duplicates anywhere in the flattened assignment.
    expect(new Set(ASSIGNED_TABS).size).toBe(ASSIGNED_TABS.length);

    // Same cardinality as the canonical tab set: none added, none removed.
    expect(ASSIGNED_TABS.length).toBe(CANONICAL_TAB_KEYS.length);

    // Set-equality both directions: coverage (every canonical tab is assigned)
    // and soundness (every assigned tab is canonical — no foreign tab).
    expect(new Set(ASSIGNED_TABS)).toEqual(CANONICAL_SET);
  });

  it('round-trips: each canonical tab is listed by exactly the group groupForTab names', () => {
    fc.assert(
      fc.property(fc.constantFrom<CampaignsTabKey>(...CANONICAL_TAB_KEYS), (tab) => {
        const group = groupForTab(tab);
        expect(group).toBeDefined();
        // The tab appears in its own group's list and in no other group's list.
        for (const g of GROUP_KEYS) {
          const listed = tabsForGroup(g).includes(tab);
          expect(listed).toBe(g === group);
        }
      }),
      { numRuns: RUNS },
    );
  });
});
