// Feature: advertising-workspace-rework, Property 73: Responsive threshold
// switching at 768px.
//
// The advertising workspace renders the full table + full editing experience at
// or above 768px (desktop) and a read-oriented alternative offering only
// view/pause/approve below it (compact); the presentation switches exactly at
// the 768px threshold, and a non-finite width falls back to the desktop
// experience.
//
// Validates: Requirements 43.2, 43.3, 43.4
//
// These tests exercise the exact pure decision core the `useResponsiveMode`
// hook and workspace shell delegate to (`resolveResponsiveMode`,
// `isFullEditingEnabled`, `isActionAllowed`), so the threshold behavior is
// validated without mounting React.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  ADVERTISING_DESKTOP_MIN_WIDTH,
  COMPACT_ALLOWED_ACTIONS,
  resolveResponsiveMode,
  isFullEditingEnabled,
  isActionAllowed,
  type AdvertisingRowAction,
} from './advertisingResponsive';

const RUNS = 200; // >= 100 iterations as required for property tests.

const THRESHOLD = ADVERTISING_DESKTOP_MIN_WIDTH; // 768

// The universe of row actions the gating must reason about: the compact-allowed
// trio plus full-editing actions that compact must reject.
const ALL_ACTIONS: AdvertisingRowAction[] = [
  'view',
  'pause',
  'approve',
  'edit',
  'delete',
  'bulkEdit',
];
const actionArb = fc.constantFrom(...ALL_ACTIONS);

// Finite widths spanning realistic-and-beyond viewport sizes, including 0 and
// the negative/huge extremes so the boundary is stressed from both sides.
const finiteWidthArb = fc.integer({ min: -2000, max: 8000 });

describe('Feature: advertising-workspace-rework, Property 73: Responsive threshold switching at 768px', () => {
  it('width >= 768 resolves to desktop with full editing; width < 768 resolves to compact', () => {
    fc.assert(
      fc.property(finiteWidthArb, (width) => {
        const mode = resolveResponsiveMode(width);
        if (width >= THRESHOLD) {
          // Req 43.3: at or above threshold the full desktop experience renders.
          expect(mode).toBe('desktop');
          expect(isFullEditingEnabled(width)).toBe(true);
        } else {
          // Req 43.2: below threshold the read-oriented compact alternative
          // renders and full editing is disabled.
          expect(mode).toBe('compact');
          expect(isFullEditingEnabled(width)).toBe(false);
        }
      }),
      { numRuns: RUNS },
    );
  });

  it('the threshold switches exactly at 768px (768 desktop, 767 compact)', () => {
    // Req 43.4: the UI switches between the two presentations as the threshold
    // is crossed. Pin the exact boundary so an off-by-one regression fails.
    expect(resolveResponsiveMode(THRESHOLD)).toBe('desktop');
    expect(resolveResponsiveMode(THRESHOLD - 1)).toBe('compact');
    expect(isFullEditingEnabled(THRESHOLD)).toBe(true);
    expect(isFullEditingEnabled(THRESHOLD - 1)).toBe(false);

    // And property-wise: there is a single switch point — desktop iff the width
    // is >= threshold for every finite width on both sides of the boundary.
    fc.assert(
      fc.property(finiteWidthArb, (width) => {
        const isDesktop = resolveResponsiveMode(width) === 'desktop';
        expect(isDesktop).toBe(width >= THRESHOLD);
      }),
      { numRuns: RUNS },
    );
  });

  it('compact (width < 768) allows only view/pause/approve; desktop allows every action', () => {
    fc.assert(
      fc.property(finiteWidthArb, actionArb, (width, action) => {
        const allowed = isActionAllowed(width, action);
        if (width >= THRESHOLD) {
          // Desktop: full editing — every action permitted (Req 43.3).
          expect(allowed).toBe(true);
        } else {
          // Compact: only the read-oriented trio permitted (Req 43.2).
          const expected = (COMPACT_ALLOWED_ACTIONS as readonly string[]).includes(
            action,
          );
          expect(allowed).toBe(expected);
          if (['edit', 'delete', 'bulkEdit'].includes(action)) {
            // Full bulk editing is never offered below the threshold.
            expect(allowed).toBe(false);
          }
        }
      }),
      { numRuns: RUNS },
    );
  });

  it('non-finite width falls back to the desktop experience with full editing', () => {
    // Req 43.3: when a width cannot be measured the safe fallback is the full
    // desktop experience, never the restricted compact one.
    const nonFiniteArb = fc.constantFrom(
      Number.NaN,
      Number.POSITIVE_INFINITY,
      Number.NEGATIVE_INFINITY,
    );
    fc.assert(
      fc.property(nonFiniteArb, actionArb, (width, action) => {
        expect(resolveResponsiveMode(width)).toBe('desktop');
        expect(isFullEditingEnabled(width)).toBe(true);
        // Every action is permitted under the desktop fallback.
        expect(isActionAllowed(width, action)).toBe(true);
      }),
      { numRuns: RUNS },
    );
  });
});
