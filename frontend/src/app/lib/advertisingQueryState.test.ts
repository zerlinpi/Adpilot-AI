// Unit tests for the pure advertising query-state encode/decode helpers
// (advertising-workspace-rework Req 34.4, 34.5, 39).
//
// These are example-based checks of the round-trip and edge cases; the
// universal round-trip property test lives in task 18.4.

import { describe, it, expect } from 'vitest';
import {
  decodeFilters,
  encodeFilters,
  emptyQueryState,
  normalizeQueryState,
  DEFAULT_PAGE,
  DEFAULT_PAGE_SIZE,
  type AdvertisingQueryState,
} from './advertisingQueryState';

describe('encodeFilters / decodeFilters', () => {
  it('encodes the empty state to an empty query string', () => {
    expect(encodeFilters(emptyQueryState()).toString()).toBe('');
  });

  it('decodes an empty query string to the empty state', () => {
    expect(decodeFilters('')).toEqual(emptyQueryState());
  });

  it('round-trips a fully populated state', () => {
    const state: AdvertisingQueryState = {
      filters: {
        search: 'hello world',
        typeSelections: { status: 'enabled', matchType: 'exact' },
        conditions: [
          { field: 'acos', op: 'gte', value: 0.25 },
          { field: 'name', op: 'contains', value: 'promo' },
        ],
      },
      dateRange: { start: '2024-01-01', end: '2024-01-31' },
      sort: { field: 'spend', direction: 'desc' },
      page: 3,
      pageSize: 25,
    };
    expect(decodeFilters(encodeFilters(state))).toEqual(normalizeQueryState(state));
  });

  it('omits default page and pageSize from the encoding', () => {
    const params = encodeFilters({
      ...emptyQueryState(),
      filters: { search: 'x', typeSelections: {}, conditions: [] },
    });
    expect(params.get('page')).toBeNull();
    expect(params.get('size')).toBeNull();
    expect(params.get('q')).toBe('x');
  });

  it('normalizes an empty-field sort to null', () => {
    const decoded = decodeFilters(
      encodeFilters({
        ...emptyQueryState(),
        sort: { field: '', direction: 'asc' },
      }),
    );
    expect(decoded.sort).toBeNull();
  });

  it('drops a partial date range (only one side present)', () => {
    const decoded = decodeFilters('from=2024-01-01');
    expect(decoded.dateRange).toBeNull();
  });

  it('falls back to defaults for malformed page/size and JSON', () => {
    const decoded = decodeFilters('page=abc&size=-3&f=not-json&ts=%7Bbad');
    expect(decoded.page).toBe(DEFAULT_PAGE);
    expect(decoded.pageSize).toBe(DEFAULT_PAGE_SIZE);
    expect(decoded.filters.conditions).toEqual([]);
    expect(decoded.filters.typeSelections).toEqual({});
  });

  it('ignores foreign query params', () => {
    const decoded = decodeFilters('tab=hosting&q=abc');
    expect(decoded.filters.search).toBe('abc');
  });

  it('preserves special characters in search through a round-trip', () => {
    const state: AdvertisingQueryState = {
      ...emptyQueryState(),
      filters: { search: 'a&b=c d#e', typeSelections: {}, conditions: [] },
    };
    expect(decodeFilters(encodeFilters(state)).filters.search).toBe('a&b=c d#e');
  });
});
