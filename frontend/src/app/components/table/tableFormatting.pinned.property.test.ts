// Property-based test for the pure pinned-column offset resolution helper
// (advertising-workspace-rework Req 38.1, 33.2).
//
// Feature: advertising-workspace-rework, Property 66
//
// Property 66 — Multi-pinned-column offsets are cumulative and non-overlapping:
//   For an arbitrary ordered list of pinned columns with arbitrary widths,
//   `resolvePinnedOffsets` lays the columns out contiguously left-to-right:
//     - the first pinned column sits at offset 0;
//     - each subsequent column's offset is the CUMULATIVE width of every column
//       preceding it (offset[i] = sum of widths[0..i-1]); and
//     - adjacent columns are non-overlapping with no gap, i.e.
//       offset[i] + width[i] === offset[i+1].
//   Widths are coerced to a safe, non-negative, finite value, so the resolved
//   offsets are always non-negative and non-decreasing.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  resolvePinnedOffsets,
  type PinnedColumnInput,
} from './tableFormatting';

const NUM_RUNS = 200;

/** Mirror of the helper's internal width coercion (non-negative, finite). */
function safeWidth(width: number): number {
  return Number.isFinite(width) && width > 0 ? width : 0;
}

// Widths deliberately span the messy real-world space: ordinary positive pixel
// widths, zero, negatives, fractional values, NaN and the infinities — every
// one of which the helper must coerce into a safe non-negative finite width.
const widthArb: fc.Arbitrary<number> = fc.oneof(
  fc.integer({ min: 0, max: 2_000 }),
  fc.double({ min: -1_000, max: 3_000, noDefaultInfinity: false, noNaN: false }),
  fc.constantFrom(0, -1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY),
);

// An ordered list of pinned columns with unique keys (the array order is the
// visible left-to-right pin order). Empty lists are allowed to exercise the
// base case.
const pinnedArb: fc.Arbitrary<PinnedColumnInput[]> = fc
  .uniqueArray(fc.string({ minLength: 1, maxLength: 8 }), { maxLength: 12 })
  .chain((keys) =>
    fc.tuple(...keys.map(() => widthArb)).map((widths) =>
      keys.map((key, i) => ({ key, width: widths[i] })),
    ),
  );

describe('Feature: advertising-workspace-rework, Property 66 — Multi-pinned-column offsets are cumulative and non-overlapping', () => {
  it('offsets are cumulative (sum of preceding widths), non-overlapping, and start at 0', () => {
    fc.assert(
      fc.property(pinnedArb, (pinned) => {
        const resolved = resolvePinnedOffsets(pinned);

        // Order- and length-preserving, keys untouched.
        expect(resolved).toHaveLength(pinned.length);
        resolved.forEach((r, i) => expect(r.key).toBe(pinned[i].key));

        // First pinned column always anchors at 0 (when present).
        if (resolved.length > 0) {
          expect(resolved[0].offset).toBe(0);
        }

        let cumulative = 0;
        for (let i = 0; i < resolved.length; i++) {
          const expectedWidth = safeWidth(pinned[i].width);

          // Widths are coerced to a safe, non-negative, finite value.
          expect(resolved[i].width).toBe(expectedWidth);
          expect(Number.isFinite(resolved[i].width)).toBe(true);
          expect(resolved[i].width).toBeGreaterThanOrEqual(0);

          // offset[i] === sum of preceding (coerced) widths; always >= 0.
          expect(resolved[i].offset).toBe(cumulative);
          expect(resolved[i].offset).toBeGreaterThanOrEqual(0);

          // Non-overlapping, no-gap layout: offset[i] + width[i] === offset[i+1].
          if (i + 1 < resolved.length) {
            expect(resolved[i].offset + resolved[i].width).toBe(
              resolved[i + 1].offset,
            );
          }

          cumulative += expectedWidth;
        }
      }),
      { numRuns: NUM_RUNS },
    );
  });
});
