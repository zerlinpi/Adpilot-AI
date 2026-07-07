// Unit/example tests for the pure applied-filter → Filter_Chip derivation
// (Task 20.2; Req 29.3, 29.4, 29.5). The fast-check property test for the
// exact correspondence is a separate task (20.6).

import { describe, it, expect } from 'vitest';

import {
  deriveFilterChips,
  removeFilterChip,
  type FilterChip,
} from './advertisingFilterChips';
import {
  emptyQueryState,
  type AdvertisingQueryState,
} from './advertisingQueryState';

function stateWith(
  partial: Partial<AdvertisingQueryState>,
): AdvertisingQueryState {
  return { ...emptyQueryState(), ...partial };
}

describe('deriveFilterChips — applied filters → chips (Req 29.3, 29.5)', () => {
  it('returns no chips when no filters are applied (Req 29.5)', () => {
    expect(deriveFilterChips(emptyQueryState())).toEqual([]);
  });

  it('treats an all-whitespace search as not applied (Req 29.5)', () => {
    const state = stateWith({
      filters: { search: '   ', typeSelections: {}, conditions: [] },
    });
    expect(deriveFilterChips(state)).toEqual([]);
  });

  it('emits one chip for a non-empty search', () => {
    const state = stateWith({
      filters: { search: 'shoes', typeSelections: {}, conditions: [] },
    });
    const chips = deriveFilterChips(state);
    expect(chips).toHaveLength(1);
    expect(chips[0]).toMatchObject({ kind: 'search' });
    expect(chips[0].label).toContain('shoes');
  });

  it('emits one chip per type selection and per condition and the date range', () => {
    const state = stateWith({
      filters: {
        search: 'shoes',
        typeSelections: { status: 'enabled', type: 'sp' },
        conditions: [
          { field: 'acos', op: 'gt', value: 0.25 },
          { field: 'clicks', op: 'gte', value: 100 },
        ],
      },
      dateRange: { start: '2024-06-01', end: '2024-06-07' },
    });
    const chips = deriveFilterChips(state);
    // 1 search + 2 selections + 2 conditions + 1 date range = 6.
    expect(chips).toHaveLength(6);
    expect(chips.filter((c) => c.kind === 'typeSelection')).toHaveLength(2);
    expect(chips.filter((c) => c.kind === 'condition')).toHaveLength(2);
    expect(chips.filter((c) => c.kind === 'dateRange')).toHaveLength(1);
    // Chip ids are unique.
    expect(new Set(chips.map((c) => c.id)).size).toBe(chips.length);
  });

  it('uses provided labels for selection/field/operator text', () => {
    const state = stateWith({
      filters: {
        search: '',
        typeSelections: { status: 'enabled' },
        conditions: [{ field: 'acos', op: 'gt', value: 0.25 }],
      },
    });
    const chips = deriveFilterChips(state, {
      selectionLabels: { status: '状态' },
      fieldLabels: { acos: 'ACoS' },
      operatorLabels: { gt: '大于' },
    });
    expect(chips.find((c) => c.kind === 'typeSelection')?.label).toBe(
      '状态: enabled',
    );
    expect(chips.find((c) => c.kind === 'condition')?.label).toBe(
      'ACoS 大于 0.25',
    );
  });
});

describe('removeFilterChip — removing yields the remaining filters (Req 29.4)', () => {
  it('removes the search filter', () => {
    const state = stateWith({
      filters: { search: 'shoes', typeSelections: { a: '1' }, conditions: [] },
    });
    const chip = deriveFilterChips(state).find((c) => c.kind === 'search')!;
    const next = removeFilterChip(state, chip);
    expect(next.filters.search).toBe('');
    expect(next.filters.typeSelections).toEqual({ a: '1' }); // others kept
  });

  it('removes a single type selection, keeping the rest', () => {
    const state = stateWith({
      filters: { search: '', typeSelections: { a: '1', b: '2' }, conditions: [] },
    });
    const chip = deriveFilterChips(state).find(
      (c): c is FilterChip => c.selectionKey === 'a',
    )!;
    const next = removeFilterChip(state, chip);
    expect(next.filters.typeSelections).toEqual({ b: '2' });
  });

  it('removes the condition at the chip index, keeping the others', () => {
    const state = stateWith({
      filters: {
        search: '',
        typeSelections: {},
        conditions: [
          { field: 'acos', op: 'gt', value: 0.25 },
          { field: 'clicks', op: 'gte', value: 100 },
        ],
      },
    });
    const chip = deriveFilterChips(state).filter(
      (c) => c.kind === 'condition',
    )[0];
    const next = removeFilterChip(state, chip);
    expect(next.filters.conditions).toEqual([
      { field: 'clicks', op: 'gte', value: 100 },
    ]);
  });

  it('removes the date range', () => {
    const state = stateWith({
      dateRange: { start: '2024-06-01', end: '2024-06-07' },
    });
    const chip = deriveFilterChips(state).find((c) => c.kind === 'dateRange')!;
    const next = removeFilterChip(state, chip);
    expect(next.dateRange).toBeNull();
  });

  it('resets to page 1 because the result set changes when a filter is removed', () => {
    const state = stateWith({
      filters: { search: 'shoes', typeSelections: {}, conditions: [] },
      page: 5,
    });
    const chip = deriveFilterChips(state)[0];
    expect(removeFilterChip(state, chip).page).toBe(1);
  });

  it('removing every chip one by one ends with no chips (Req 29.5)', () => {
    let state = stateWith({
      filters: {
        search: 'shoes',
        typeSelections: { a: '1', b: '2' },
        conditions: [{ field: 'acos', op: 'gt', value: 0.25 }],
      },
      dateRange: { start: '2024-06-01', end: '2024-06-07' },
    });
    // Re-derive after each removal since indices/keys shift.
    let chips = deriveFilterChips(state);
    while (chips.length > 0) {
      state = removeFilterChip(state, chips[0]);
      chips = deriveFilterChips(state);
    }
    expect(deriveFilterChips(state)).toEqual([]);
  });
});
