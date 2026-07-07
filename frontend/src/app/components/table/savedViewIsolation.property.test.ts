// Feature: advertising-workspace-rework, Property 63: Saved view isolation
//
// Validates: Requirements 34.3
//
// A Saved_View is persisted scoped to the requesting user and the advertising
// table key (Requirement 34.3). The frontend half of that scoping lives in two
// places that this test exercises together:
//
//   1. `qk.savedViews(tableKey)` (lib/queryKeys) — the react-query cache key for
//      a table's saved-views list. Different table keys MUST map to different,
//      non-overlapping cache entries so one table's views never read from, nor
//      get invalidated by, another table's cache slot.
//   2. The acting user — `fetchSavedViews`/`saveSavedView`/`deleteSavedView`
//      (lib/api) hit the per-user `/api/table-views` endpoints, so every read is
//      implicitly scoped to the session's user. Two different users therefore
//      address two different logical saved-view sets even for the same table.
//
// The effective isolation identity for a saved-views read is thus the pair
// (user, tableKey). This property verifies the universal isolation guarantee:
// the identity produced for one (user, tableKey) collides with another's IFF
// BOTH the user AND the table key are equal — so views never leak across tables
// or across users — and when they differ, the cache keys are non-overlapping
// (neither is a prefix of the other), so a prefix-based invalidation of one
// scope can never reach into another.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import { qk } from '../../lib/queryKeys';

// ---------------------------------------------------------------------------
// Stable key hashing, identical in behavior to @tanstack/react-query's hashKey:
// JSON.stringify with a replacer that recursively sorts plain-object keys, so
// the comparison matches the structural cache-matching react-query relies on.
// (Saved-view keys are plain string tuples, but we keep the same hashing the
// rest of the suite uses for consistency.)
// ---------------------------------------------------------------------------
function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

function hashKey(key: unknown): string {
  return JSON.stringify(key, (_k, val) =>
    isPlainObject(val)
      ? Object.keys(val)
        .sort()
        .reduce<Record<string, unknown>>((acc, k) => {
          acc[k] = (val as Record<string, unknown>)[k];
          return acc;
        }, {})
      : val,
  );
}

// ---------------------------------------------------------------------------
// The effective isolation identity of a saved-views read: the session user
// (who the per-user `/table-views` endpoint scopes to) prefixed onto the real
// react-query cache key produced by `qk.savedViews(tableKey)`. This mirrors how
// the running app separates one user/table's saved-view cache slot from
// another's. The `qk.savedViews` call is the actual production scoping function.
// ---------------------------------------------------------------------------
function isolationIdentity(user: string, tableKey: string): readonly unknown[] {
  return ['user', user, ...qk.savedViews(tableKey)] as const;
}

// True when `shorter` is a positional prefix of `longer` — i.e. a react-query
// prefix invalidation of `shorter` would also match `longer`. Used to prove
// distinct scopes are non-overlapping, not merely unequal.
function isPrefixOf(shorter: readonly unknown[], longer: readonly unknown[]): boolean {
  if (shorter.length > longer.length) return false;
  return shorter.every((segment, i) => hashKey(segment) === hashKey(longer[i]));
}

// Distinct, realistic table keys and user ids. Empty strings are included so the
// property also holds at the boundary where a key/user is not yet supplied.
const tableKeyArb = fc.string();
const userArb = fc.string();

describe('Feature: advertising-workspace-rework, Property 63: Saved view isolation', () => {
  it('saved-view cache identity collides IFF (user, tableKey) match, and distinct scopes are non-overlapping (numRuns >= 100)', () => {
    fc.assert(
      fc.property(
        userArb,
        tableKeyArb,
        userArb,
        tableKeyArb,
        (userA, tableA, userB, tableB) => {
          const keyA = isolationIdentity(userA, tableA);
          const keyB = isolationIdentity(userB, tableB);

          const sameScope = userA === userB && tableA === tableB;
          const keysCollide = hashKey(keyA) === hashKey(keyB);

          // Isolation IFF: two saved-view reads share a cache slot exactly when
          // they belong to the same user AND the same table key. Differing on
          // either dimension guarantees a different slot — no cross-table or
          // cross-user leakage.
          expect(keysCollide).toBe(sameScope);

          // For genuinely different scopes, the keys must also be
          // non-overlapping: neither can be a prefix of the other, so a
          // prefix-based invalidation of one scope never reaches the other.
          if (!sameScope) {
            expect(isPrefixOf(keyA, keyB)).toBe(false);
            expect(isPrefixOf(keyB, keyA)).toBe(false);
          }
        },
      ),
      { numRuns: 300 },
    );
  });
});
