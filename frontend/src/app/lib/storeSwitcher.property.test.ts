// Feature: core-platform-completion, Property 17: Store grouping is a complete,
// correct partition.
//
// For any set of stores with assigned groups, each store appears under exactly
// its assigned group, and every accessible store appears exactly once across
// all groups (no store is dropped, duplicated, or misplaced).
//
// Validates: Requirements 5.2.4
//
// This test exercises `groupStores` directly — the same pure helper the store
// switcher delegates to when rendering stores organized by group — so it
// validates the production grouping decision without rendering React.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  groupStores,
  filterStoresByName,
  storeMatchesSearch,
  UNGROUPED_LABEL,
  type SwitcherStore,
} from './storeSwitcher';

const RUNS = 200; // >= 100 iterations as required for property tests.

// A small pool of group labels guarantees meaningful overlap (multiple stores
// landing in the same group) while also covering the "ungrouped" cases:
//   - a real label, possibly with surrounding whitespace (trimmed by grouping)
//   - null / undefined / blank → fall back to the ungrouped label
const groupArb = fc.oneof(
  fc.constantFrom('华东', '华南', '  华北  ', 'Overseas', 'VIP'),
  fc.constant(null),
  fc.constant(undefined),
  fc.constant(''),
  fc.constant('   '),
);

// Each store gets a distinct id so we can detect duplicates/drops unambiguously.
const storesArb: fc.Arbitrary<SwitcherStore[]> = fc
  .array(
    fc.record({
      name: fc.string({ minLength: 0, maxLength: 8 }),
      storeGroup: groupArb,
    }),
    { minLength: 0, maxLength: 30 },
  )
  .map((rows) =>
    rows.map((row, index) => ({
      id: `store-${index}`,
      name: row.name,
      storeGroup: row.storeGroup,
    })),
  );

/** The group label a store is expected to land under, mirroring the helper's
 *  contract: a non-blank group is trimmed, anything blank falls back to the
 *  ungrouped label. */
function expectedLabel(store: SwitcherStore, ungroupedLabel: string): string {
  const g = (store.storeGroup ?? '').trim();
  return g.length > 0 ? g : ungroupedLabel;
}

describe('Property 17: store grouping is a complete, correct partition', () => {
  it('places every store under exactly its assigned group, exactly once', () => {
    fc.assert(
      fc.property(storesArb, (stores) => {
        const groups = groupStores(stores);

        // 1) Completeness: the total number of stores across all groups equals
        //    the input size — nothing dropped, nothing duplicated by count.
        const flattened = groups.flatMap((g) => g.stores);
        expect(flattened.length).toBe(stores.length);

        // 2) Each input store appears exactly once across all groups (by id).
        const seen = new Map<string, number>();
        for (const s of flattened) {
          seen.set(s.id, (seen.get(s.id) ?? 0) + 1);
        }
        expect(seen.size).toBe(stores.length);
        for (const count of seen.values()) {
          expect(count).toBe(1);
        }

        // 3) Correctness: each store sits under exactly its assigned group, and
        //    no group label is repeated (one bucket per label).
        const labels = groups.map((g) => g.group);
        expect(new Set(labels).size).toBe(labels.length);

        for (const group of groups) {
          for (const store of group.stores) {
            expect(group.group).toBe(expectedLabel(store, UNGROUPED_LABEL));
          }
        }

        // 4) Cross-check from the store's perspective: locate each input store
        //    and confirm it lives in the group matching its assigned label.
        for (const store of stores) {
          const owning = groups.filter((g) => g.stores.some((s) => s.id === store.id));
          expect(owning.length).toBe(1);
          expect(owning[0].group).toBe(expectedLabel(store, UNGROUPED_LABEL));
        }
      }),
      { numRuns: RUNS },
    );
  });
});

// Feature: core-platform-completion, Property 16: Store search returns exactly
// the name-matching stores.
//
// For any set of store names and any search text, the filtered switcher list
// contains every accessible store whose name matches the text and no store
// whose name does not.
//
// Validates: Requirements 5.2.2
//
// These tests exercise the exact functions the store switcher delegates to:
//   - `storeMatchesSearch` decides whether one store matches the search text.
//   - `filterStoresByName` drives the filtered list rendered in the switcher.
// Testing them directly validates the production filtering decision without
// rendering React.

// A small pool of name fragments produces meaningful overlap between store
// names and the search text, so generated searches frequently match a subset
// (not always all or none) of the stores. Mixed casing exercises the
// case-insensitive comparison.
const NAME_FRAGMENTS = ['Alpha', 'beta', 'GAMMA', 'delta', 'Store', '店铺', '123', ' '];

const nameArb = fc
  .array(fc.constantFrom(...NAME_FRAGMENTS), { minLength: 0, maxLength: 4 })
  .map((parts) => parts.join(''));

const searchStoresArb: fc.Arbitrary<SwitcherStore[]> = fc
  .array(nameArb, { minLength: 0, maxLength: 12 })
  .map((names) => names.map((name, index) => ({ id: `s-${index}`, name })));

// Search text drawn from the same fragment pool (plus the empty string and
// arbitrary unicode) so it sometimes matches, sometimes does not.
const searchArb = fc.oneof(
  fc.constant(''),
  fc.constantFrom(...NAME_FRAGMENTS),
  fc.string({ maxLength: 6 }),
);

/** Reference predicate: case-insensitive, whitespace-trimmed substring match;
 *  an empty/whitespace-only search matches every store. */
function expectedMatch(store: SwitcherStore, search: string): boolean {
  const needle = search.trim().toLowerCase();
  if (needle.length === 0) return true;
  return store.name.trim().toLowerCase().includes(needle);
}

describe('Property 16: store search returns exactly the name-matching stores', () => {
  it('the filtered list contains every matching store and no non-matching store', () => {
    fc.assert(
      fc.property(searchStoresArb, searchArb, (stores, search) => {
        const expected = stores.filter((s) => expectedMatch(s, search));
        const expectedHidden = stores.filter((s) => !expectedMatch(s, search));

        const result = filterStoresByName(stores, search);

        // Exactly the matching stores are present (no more, no fewer).
        expect(result.length).toBe(expected.length);
        for (const store of expected) {
          expect(result).toContain(store);
        }
        // No non-matching store leaks through.
        for (const store of expectedHidden) {
          expect(result).not.toContain(store);
        }

        // Every store in the result individually satisfies the match predicate.
        for (const store of result) {
          expect(storeMatchesSearch(store, search)).toBe(true);
        }

        // Order is preserved relative to the input.
        expect(result).toStrictEqual(stores.filter((s) => result.includes(s)));
      }),
      { numRuns: RUNS },
    );
  });

  it('an empty or whitespace-only search returns every store', () => {
    fc.assert(
      fc.property(searchStoresArb, fc.constantFrom('', '   ', '\t'), (stores, search) => {
        const result = filterStoresByName(stores, search);
        expect(result).toStrictEqual(stores);
      }),
      { numRuns: RUNS },
    );
  });
});
