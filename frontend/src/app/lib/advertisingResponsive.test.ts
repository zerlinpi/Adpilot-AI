// Unit/example tests for the pure responsive-strategy decision (Task 20.4).
//
// Covers the 768px threshold behavior required by Req 43:
//   - desktop mode + full editing at/above 768px                    (Req 43.3)
//   - compact, view/pause/approve-only below 768px                  (Req 43.2)
//   - switching exactly at the threshold                            (Req 43.4)
// The fast-check property test (Property 73) is the separate task 20.8.

import { describe, it, expect } from 'vitest';

import {
  ADVERTISING_DESKTOP_MIN_WIDTH,
  COMPACT_ALLOWED_ACTIONS,
  allowedActionsForMode,
  isActionAllowed,
  isFullEditingEnabled,
  resolveResponsiveMode,
} from './advertisingResponsive';

describe('resolveResponsiveMode (Req 43.3, 43.4)', () => {
  it('is desktop at and above the 768px threshold', () => {
    expect(resolveResponsiveMode(ADVERTISING_DESKTOP_MIN_WIDTH)).toBe('desktop');
    expect(resolveResponsiveMode(769)).toBe('desktop');
    expect(resolveResponsiveMode(1440)).toBe('desktop');
  });

  it('is compact strictly below the threshold', () => {
    expect(resolveResponsiveMode(ADVERTISING_DESKTOP_MIN_WIDTH - 1)).toBe('compact');
    expect(resolveResponsiveMode(320)).toBe('compact');
    expect(resolveResponsiveMode(0)).toBe('compact');
  });

  it('falls back to desktop for a non-finite width', () => {
    expect(resolveResponsiveMode(Number.NaN)).toBe('desktop');
    expect(resolveResponsiveMode(Number.POSITIVE_INFINITY)).toBe('desktop');
  });
});

describe('action gating (Req 43.2)', () => {
  it('enables full editing only on desktop', () => {
    expect(isFullEditingEnabled(768)).toBe(true);
    expect(isFullEditingEnabled(767)).toBe(false);
  });

  it('allows every action on desktop', () => {
    expect(allowedActionsForMode('desktop')).toBe('all');
    for (const action of ['view', 'pause', 'approve', 'edit', 'delete', 'bulkEdit']) {
      expect(isActionAllowed(1024, action)).toBe(true);
    }
  });

  it('restricts compact to view/pause/approve only', () => {
    expect(allowedActionsForMode('compact')).toEqual(COMPACT_ALLOWED_ACTIONS);
    expect(isActionAllowed(500, 'view')).toBe(true);
    expect(isActionAllowed(500, 'pause')).toBe(true);
    expect(isActionAllowed(500, 'approve')).toBe(true);
    expect(isActionAllowed(500, 'edit')).toBe(false);
    expect(isActionAllowed(500, 'delete')).toBe(false);
    expect(isActionAllowed(500, 'bulkEdit')).toBe(false);
  });
});
