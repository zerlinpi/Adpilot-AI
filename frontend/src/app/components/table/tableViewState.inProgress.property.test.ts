// Property-based test for the pure in-progress control model that prevents
// double submission of advertising operations (advertising-workspace-rework
// Req 36.3).
//
// Feature: advertising-workspace-rework, Property 72
//
// Property 72 — In-progress controls prevent double submission:
//   For ANY sequence of control ids, once a control begins an action it is
//   in-progress (and therefore disabled); a SECOND `beginAction` for that same
//   id while it is still in-progress is REJECTED (`allowed: false`) and leaves
//   the in-progress set unchanged — so the same operation can never be
//   submitted twice. `completeAction` removes the id, re-enabling the control
//   so it can begin again.
//
// Validates: Requirements 36.3

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  EMPTY_IN_PROGRESS,
  beginAction,
  completeAction,
  isControlInProgress,
  type InProgressControls,
} from './tableViewState';

const NUM_RUNS = 300;

// Stable control ids drawn from a small pool so the generated sequences
// frequently revisit the same id — that collision is exactly what exercises
// the double-submission guard.
const controlIdArb: fc.Arbitrary<string> = fc.constantFrom(
  'apply',
  'pause',
  'enable',
  'archive',
  'submit-budget',
  'control-x',
);

describe('Feature: advertising-workspace-rework, Property 72 — In-progress controls prevent double submission', () => {
  it('begins → disabled, second begin rejected with state unchanged, complete re-enables', () => {
    fc.assert(
      fc.property(controlIdArb, (id) => {
        // Starts not in progress (enabled).
        expect(isControlInProgress(EMPTY_IN_PROGRESS, id)).toBe(false);

        // First begin is allowed and marks the control in-progress (disabled).
        const first = beginAction(EMPTY_IN_PROGRESS, id);
        expect(first.allowed).toBe(true);
        expect(isControlInProgress(first.state, id)).toBe(true);

        // Second begin while in-progress is REJECTED and the state is unchanged
        // — this is the double-submission guard (Req 36.3).
        const second = beginAction(first.state, id);
        expect(second.allowed).toBe(false);
        expect(second.state).toBe(first.state);
        expect(isControlInProgress(second.state, id)).toBe(true);

        // Completing re-enables the control...
        const completed = completeAction(first.state, id);
        expect(isControlInProgress(completed, id)).toBe(false);

        // ...so it may begin again afterwards.
        const reBegin = beginAction(completed, id);
        expect(reBegin.allowed).toBe(true);
        expect(isControlInProgress(reBegin.state, id)).toBe(true);
      }),
      { numRuns: NUM_RUNS },
    );
  });

  it('over any sequence of begin/complete steps, a control is disabled iff it is in-progress and no id is ever submitted twice concurrently', () => {
    type Step = { kind: 'begin' | 'complete'; id: string };
    const stepArb: fc.Arbitrary<Step> = fc.record({
      kind: fc.constantFrom('begin' as const, 'complete' as const),
      id: controlIdArb,
    });

    fc.assert(
      fc.property(fc.array(stepArb, { maxLength: 40 }), (steps) => {
        let state: InProgressControls = EMPTY_IN_PROGRESS;
        // Reference model of which ids are currently running.
        const running = new Set<string>();

        for (const step of steps) {
          if (step.kind === 'begin') {
            const wasRunning = running.has(step.id);
            const result = beginAction(state, step.id);

            if (wasRunning) {
              // Duplicate activation: must be rejected and leave state intact.
              expect(result.allowed).toBe(false);
              expect(result.state).toBe(state);
            } else {
              // Fresh activation: allowed and now in-progress.
              expect(result.allowed).toBe(true);
              expect(isControlInProgress(result.state, step.id)).toBe(true);
              running.add(step.id);
            }
            state = result.state;
          } else {
            state = completeAction(state, step.id);
            running.delete(step.id);
            expect(isControlInProgress(state, step.id)).toBe(false);
          }

          // Invariant: implementation state agrees with the reference model —
          // a control is disabled (in-progress) iff it is genuinely running.
          for (const id of running) {
            expect(isControlInProgress(state, id)).toBe(true);
          }
        }
      }),
      { numRuns: NUM_RUNS },
    );
  });
});
