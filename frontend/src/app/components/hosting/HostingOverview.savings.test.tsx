// Property-based tests for the estimated-savings DISPLAY logic of
// <HostingOverview> / <EstimateCard> (Req 27.3 / 11.3 / 50.12).
//
// The display logic must be honest: a real estimate is shown verbatim and
// always under an estimate label, while a missing baseline shows the fixed
// "暂不可估算：缺少有效基线" signal — never a fabricated number. These properties
// pin that contract across the whole input space, complementing the
// example-based checks in HostingOverview.test.tsx.
//
// Validates: Requirements 27.3

import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, within, cleanup } from '@testing-library/react';
import fc from 'fast-check';

import {
  HostingOverview,
  NO_BASELINE_SIGNAL,
  DEFAULT_SAVINGS_LABEL,
  selectEstimateDisplay,
  resolveSavingsLabel,
  type HostingOverviewData,
} from './HostingOverview';

afterEach(() => cleanup());

/** A non-empty estimate value that is itself distinguishable from the signal. */
const nonEmptyValue = fc
  .string({ minLength: 1 })
  .filter((s) => s !== NO_BASELINE_SIGNAL);

/** A formatted, human-visible monetary estimate (safe to assert in the DOM). */
const monetaryValue = fc
  .integer({ min: -1_000_000, max: 1_000_000 })
  .map((n) => `${n < 0 ? '-' : '+'}$${Math.abs(n).toLocaleString('en-US')}`);

/** The "no valid baseline" inputs that must collapse to the honest signal. */
const emptyValue = fc.constantFrom<string | null | undefined>(null, undefined, '');

/** Realistic savings-card labels (incl. the "no label" cases) for render tests. */
const renderLabel = fc.constantFrom<string | null | undefined>(
  '预计节省花费（估算·近7天）',
  '预计节省花费（估算·近30天）',
  'AI 节省花费（估算）',
  null,
  undefined,
  '',
);

function baseData(overrides: Partial<HostingOverviewData> = {}): HostingOverviewData {
  return {
    hostedCount: 5,
    todayDecisions: 12,
    awaitingApproval: 3,
    amazonEffective: 7,
    failed: 1,
    estimatedSpendSavings: '$1,200',
    estimatedSavingsLabel: '预计节省花费（估算·近7天）',
    learningPeriods: [],
    resolvedPersonalities: ['conservative', 'balanced', 'aggressive'],
    ...overrides,
  };
}

/** Locate the estimate card whose label text matches `label`. */
function savingsCard(label: string): HTMLElement {
  const card = screen.getByText(label).closest('div.rounded-xl') as HTMLElement;
  expect(card).not.toBeNull();
  return card;
}

describe('estimated-savings display logic (Req 27.3)', () => {
  // ── Pure-helper properties ────────────────────────────────────────────────

  it('Property: any non-empty string value is shown verbatim, never the signal', () => {
    fc.assert(
      fc.property(nonEmptyValue, (value) => {
        const result = selectEstimateDisplay(value);
        expect(result.hasBaseline).toBe(true);
        expect(result.text).toBe(value);
        expect(result.text).not.toBe(NO_BASELINE_SIGNAL);
      }),
      { numRuns: 200 },
    );
  });

  it('Property: null/undefined/empty always collapse to NO_BASELINE_SIGNAL (no fabricated number)', () => {
    fc.assert(
      fc.property(emptyValue, (value) => {
        const result = selectEstimateDisplay(value);
        expect(result.hasBaseline).toBe(false);
        expect(result.text).toBe(NO_BASELINE_SIGNAL);
        // The honest signal carries no fabricated digits.
        expect(/\d/.test(result.text)).toBe(false);
      }),
      { numRuns: 100 },
    );
  });

  it('Property: the savings label is always a non-empty estimate label', () => {
    // The default label is explicitly flagged as an estimate ("（估算）").
    expect(DEFAULT_SAVINGS_LABEL).toContain('估算');
    fc.assert(
      fc.property(fc.option(fc.string(), { nil: undefined }), (label) => {
        const resolved = resolveSavingsLabel(label);
        expect(resolved.length).toBeGreaterThan(0);
        if (label == null || label === '') {
          // No backend label → falls back to the estimate-flavoured default.
          expect(resolved).toBe(DEFAULT_SAVINGS_LABEL);
          expect(resolved).toContain('估算');
        } else {
          expect(resolved).toBe(label);
        }
      }),
      { numRuns: 200 },
    );
  });

  it('Property: the display selection is deterministic (pure function of its input)', () => {
    fc.assert(
      fc.property(fc.option(fc.string(), { nil: undefined }), (value) => {
        expect(selectEstimateDisplay(value)).toEqual(selectEstimateDisplay(value));
      }),
      { numRuns: 200 },
    );
  });

  // ── Render properties (the helper as wired into the component) ────────────

  it('Property: a non-empty savings value renders that value under its estimate label, never the signal', () => {
    fc.assert(
      fc.property(monetaryValue, (value) => {
        cleanup();
        const label = '预计节省花费（估算·近7天）';
        render(
          <HostingOverview
            data={baseData({ estimatedSpendSavings: value, estimatedSavingsLabel: label })}
          />,
        );
        const card = within(savingsCard(label));
        expect(card.getByText(value)).toBeInTheDocument();
        expect(card.queryByText(NO_BASELINE_SIGNAL)).toBeNull();
      }),
      { numRuns: 100 },
    );
  });

  it('Property: a missing savings baseline renders the honest signal under its estimate label', () => {
    fc.assert(
      fc.property(emptyValue, renderLabel, (value, label) => {
        cleanup();
        render(
          <HostingOverview
            data={baseData({ estimatedSpendSavings: value, estimatedSavingsLabel: label })}
          />,
        );
        const resolvedLabel = resolveSavingsLabel(label);
        const card = within(savingsCard(resolvedLabel));
        expect(card.getByText(NO_BASELINE_SIGNAL)).toBeInTheDocument();
        // The label is always present and always an estimate label.
        expect(resolvedLabel.length).toBeGreaterThan(0);
      }),
      { numRuns: 100 },
    );
  });
});
