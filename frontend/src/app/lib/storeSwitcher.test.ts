// Feature: core-platform-completion — default-store selection (Req 5.2.3).
//
// Unit tests for `selectDefaultStoreId`, the helper the StoreProvider delegates
// to when deciding which store is active as the switcher loads. The precedence
// is: configured default → still-valid saved selection → first store → null.
// The headline case is that a user's configured default store is selected on
// the next login.
//
// Validates: Requirements 5.2.3

import { describe, it, expect } from 'vitest';
import {
  selectDefaultStoreId,
  filterStoresByGroupScope,
  type SwitcherStore,
} from './storeSwitcher';

const stores: SwitcherStore[] = [
  { id: 's1', name: 'Alpha Store' },
  { id: 's2', name: 'Beta Store' },
  { id: 's3', name: 'Gamma Store' },
];

describe('selectDefaultStoreId', () => {
  it('selects the user-configured default store (Req 5.2.3)', () => {
    expect(selectDefaultStoreId(stores, { defaultStoreId: 's2' })).toBe('s2');
  });

  it('prefers the configured default over a saved selection', () => {
    expect(
      selectDefaultStoreId(stores, { defaultStoreId: 's3', savedStoreId: 's1' }),
    ).toBe('s3');
  });

  it('falls back to a still-valid saved selection when no default is configured', () => {
    expect(selectDefaultStoreId(stores, { savedStoreId: 's2' })).toBe('s2');
  });

  it('ignores a configured default that is not among accessible stores', () => {
    expect(
      selectDefaultStoreId(stores, { defaultStoreId: 'gone', savedStoreId: 's2' }),
    ).toBe('s2');
  });

  it('ignores a saved selection that no longer refers to an accessible store', () => {
    expect(selectDefaultStoreId(stores, { savedStoreId: 'gone' })).toBe('s1');
  });

  it('falls back to the first store when neither default nor saved selection is usable', () => {
    expect(selectDefaultStoreId(stores)).toBe('s1');
    expect(
      selectDefaultStoreId(stores, { defaultStoreId: null, savedStoreId: null }),
    ).toBe('s1');
  });

  it('returns null when the user has no accessible stores', () => {
    expect(selectDefaultStoreId([])).toBeNull();
    expect(selectDefaultStoreId([], { defaultStoreId: 's1' })).toBeNull();
  });
});

// Feature: platform-workspace-rbac — store switcher Store_Group_Scope filtering
// (Req 13.5). The switcher shows only stores whose Store_Group is within the
// account's Store_Group_Scope. The backend is the authoritative isolation
// boundary; this frontend filter is the defense-in-depth/UX layer.
//
// Validates: Requirements 13.5
describe('filterStoresByGroupScope', () => {
  const scopedStores: SwitcherStore[] = [
    { id: 's1', name: 'Alpha', storeGroupId: 'g1' },
    { id: 's2', name: 'Beta', storeGroupId: 'g2' },
    { id: 's3', name: 'Gamma', storeGroupId: 'g3' },
  ];

  it('returns only stores whose group is within the scope (Req 13.5)', () => {
    const result = filterStoresByGroupScope(scopedStores, ['g1', 'g3']);
    expect(result.map((s) => s.id)).toEqual(['s1', 's3']);
  });

  it('excludes every store when the scope is empty', () => {
    expect(filterStoresByGroupScope(scopedStores, [])).toEqual([]);
  });

  it('returns all stores unchanged when the scope is unrestricted (null/undefined)', () => {
    expect(filterStoresByGroupScope(scopedStores, null)).toEqual(scopedStores);
    expect(filterStoresByGroupScope(scopedStores, undefined)).toEqual(scopedStores);
  });

  it('excludes stores with no resolvable group id under a restricted scope', () => {
    const stores: SwitcherStore[] = [
      { id: 's1', name: 'Alpha', storeGroupId: 'g1' },
      { id: 's2', name: 'NoGroup', storeGroupId: null },
      { id: 's3', name: 'Missing' },
    ];
    expect(filterStoresByGroupScope(stores, ['g1']).map((s) => s.id)).toEqual(['s1']);
  });

  it('preserves the input order of the surviving stores', () => {
    const result = filterStoresByGroupScope(scopedStores, ['g3', 'g1', 'g2']);
    expect(result.map((s) => s.id)).toEqual(['s1', 's2', 's3']);
  });
});
