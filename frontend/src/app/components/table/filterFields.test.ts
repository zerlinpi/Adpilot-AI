// Unit tests for partitioning advanced-filter conditions by persisted field
// (advertising-workspace-rework task 19.1, Req 15.5/15.6/15.7).

import { describe, it, expect } from 'vitest';

import {
  partitionFilterFields,
  unsupportedFilterMessage,
} from './filterValidation';
import type { FilterCondition } from './types';

const cond = (field: string): FilterCondition => ({ field, op: 'eq', value: 'x' });

describe('partitionFilterFields', () => {
  it('accepts persisted fields and rejects non-persisted ones', () => {
    const conditions = [cond('status'), cond('mystery'), cond('matchType')];
    const { accepted, rejected } = partitionFilterFields(conditions, [
      'status',
      'matchType',
    ]);
    expect(accepted.map((c) => c.field)).toEqual(['status', 'matchType']);
    expect(rejected.map((c) => c.field)).toEqual(['mystery']);
  });

  it('rejects nothing when the persisted set is undefined', () => {
    const conditions = [cond('a'), cond('b')];
    const { accepted, rejected } = partitionFilterFields(conditions, undefined);
    expect(accepted).toHaveLength(2);
    expect(rejected).toHaveLength(0);
  });

  it('rejects nothing when the persisted set is empty', () => {
    const conditions = [cond('a')];
    const { accepted, rejected } = partitionFilterFields(conditions, []);
    expect(accepted).toHaveLength(1);
    expect(rejected).toHaveLength(0);
  });
});

describe('unsupportedFilterMessage', () => {
  it('returns null when nothing is rejected', () => {
    expect(unsupportedFilterMessage([])).toBeNull();
  });

  it('names the unsupported fields (deduplicated)', () => {
    const msg = unsupportedFilterMessage([cond('x'), cond('x'), cond('y')]);
    expect(msg).toContain('x');
    expect(msg).toContain('y');
    // de-duplicated: 'x' appears once
    expect(msg!.match(/x/g)!.length).toBe(1);
  });
});
