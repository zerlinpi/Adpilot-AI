// Unit tests for the pure server-side pagination + sort helpers
// (advertising-workspace-rework task 19.1).

import { describe, it, expect } from 'vitest';

import {
  computeTotalPages,
  clampPage,
  pageRange,
  hasPrevPage,
  hasNextPage,
  nextSortState,
} from './tablePagination';

describe('computeTotalPages', () => {
  it('rounds up the page count', () => {
    expect(computeTotalPages(0, 50)).toBe(1);
    expect(computeTotalPages(1, 50)).toBe(1);
    expect(computeTotalPages(50, 50)).toBe(1);
    expect(computeTotalPages(51, 50)).toBe(2);
    expect(computeTotalPages(100, 25)).toBe(4);
    expect(computeTotalPages(101, 25)).toBe(5);
  });

  it('never returns less than 1 and tolerates bad sizes', () => {
    expect(computeTotalPages(10, 0)).toBe(1);
    expect(computeTotalPages(10, -5)).toBe(1);
    expect(computeTotalPages(-3, 10)).toBe(1);
    expect(computeTotalPages(Number.NaN, 10)).toBe(1);
  });
});

describe('clampPage', () => {
  it('clamps into [1, totalPages]', () => {
    expect(clampPage(0, 5)).toBe(1);
    expect(clampPage(3, 5)).toBe(3);
    expect(clampPage(99, 5)).toBe(5);
    expect(clampPage(-2, 5)).toBe(1);
  });
});

describe('pageRange', () => {
  it('computes the 1-based record window', () => {
    expect(pageRange(1, 50, 120)).toEqual({ start: 1, end: 50, total: 120 });
    expect(pageRange(2, 50, 120)).toEqual({ start: 51, end: 100, total: 120 });
    // final partial page never exceeds total
    expect(pageRange(3, 50, 120)).toEqual({ start: 101, end: 120, total: 120 });
  });

  it('handles an empty result set', () => {
    expect(pageRange(1, 50, 0)).toEqual({ start: 0, end: 0, total: 0 });
  });

  it('clamps an out-of-range page to the last page window', () => {
    expect(pageRange(99, 50, 120)).toEqual({ start: 101, end: 120, total: 120 });
  });
});

describe('hasPrevPage / hasNextPage', () => {
  it('reflects boundaries', () => {
    expect(hasPrevPage(1, 3)).toBe(false);
    expect(hasPrevPage(2, 3)).toBe(true);
    expect(hasNextPage(3, 3)).toBe(false);
    expect(hasNextPage(2, 3)).toBe(true);
  });
});

describe('nextSortState', () => {
  it('cycles a column none -> asc -> desc -> none', () => {
    expect(nextSortState(null, 'spend')).toEqual({ field: 'spend', direction: 'asc' });
    expect(nextSortState({ field: 'spend', direction: 'asc' }, 'spend')).toEqual({
      field: 'spend',
      direction: 'desc',
    });
    expect(nextSortState({ field: 'spend', direction: 'desc' }, 'spend')).toBeNull();
  });

  it('starts a different column at asc', () => {
    expect(nextSortState({ field: 'spend', direction: 'desc' }, 'acos')).toEqual({
      field: 'acos',
      direction: 'asc',
    });
  });
});
