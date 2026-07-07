// Unit/example tests for the pure context-preservation helpers (Task 20.4).
//
// These cover the capture→restore round-trip behavior required by Req 45:
//   - filters/query restored exactly                                 (Req 45.1)
//   - selection restored only for rows still present                 (Req 45.2)
//   - unsaved draft preserved untouched                              (Req 45.3)
// The fast-check property test (Property 69) is the separate task 20.7.

import { describe, it, expect } from 'vitest';

import {
  captureTabContext,
  clearContextStore,
  emptyContextStore,
  emptyTabSnapshot,
  intersectSelection,
  restoreTabContext,
  type TabContextSnapshot,
} from './advertisingContextPreservation';
import { emptyQueryState } from './advertisingQueryState';

const snapshot = (over: Partial<TabContextSnapshot> = {}): TabContextSnapshot => ({
  query: {
    ...emptyQueryState(),
    filters: { search: 'shoes', typeSelections: { type: 'SP' }, conditions: [] },
    page: 3,
  },
  selectedRowIds: ['a', 'b', 'c'],
  draft: { bid: 1.25 },
  ...over,
});

describe('intersectSelection (Req 45.2)', () => {
  it('keeps only present ids, preserving saved order and dropping duplicates', () => {
    expect(intersectSelection(['a', 'b', 'c', 'b'], ['c', 'a'])).toEqual(['a', 'c']);
  });

  it('returns empty when no saved id is present', () => {
    expect(intersectSelection(['x', 'y'], ['a', 'b'])).toEqual([]);
  });
});

describe('captureTabContext / restoreTabContext round-trip (Req 45.1–45.3)', () => {
  it('restores filters, present-row selection, and draft for a captured tab', () => {
    const store = captureTabContext(emptyContextStore(), 'campaigns', snapshot());

    const restored = restoreTabContext(store, 'campaigns', ['b', 'c', 'z']);

    // Filters/query restored exactly (Req 45.1).
    expect(restored.query).toEqual(snapshot().query);
    // Selection restored only for rows still present (Req 45.2).
    expect(restored.selectedRowIds).toEqual(['b', 'c']);
    // Unsaved draft preserved (Req 45.3).
    expect(restored.draft).toEqual({ bid: 1.25 });
  });

  it('resolves a never-visited tab to a blank snapshot', () => {
    const restored = restoreTabContext(emptyContextStore(), 'searchTerms', ['a']);
    expect(restored).toEqual(emptyTabSnapshot());
  });

  it('preserves the draft even when every selected row has disappeared', () => {
    const store = captureTabContext(emptyContextStore(), 'targeting', snapshot());
    const restored = restoreTabContext(store, 'targeting', []);
    expect(restored.selectedRowIds).toEqual([]);
    expect(restored.draft).toEqual({ bid: 1.25 });
    expect(restored.query).toEqual(snapshot().query);
  });

  it('does not mutate the original store (immutability)', () => {
    const store = emptyContextStore();
    const next = captureTabContext(store, 'campaigns', snapshot());
    expect(store).toEqual({});
    expect(next).not.toBe(store);
  });

  it('clearContextStore drops all preserved context (Req 40.1)', () => {
    const store = captureTabContext(emptyContextStore(), 'campaigns', snapshot());
    expect(clearContextStore()).toEqual({});
    expect(restoreTabContext(clearContextStore(), 'campaigns', ['a', 'b'])).toEqual(
      emptyTabSnapshot(),
    );
  });
});
