// Render tests for <DecisionExplanationCard> (Req 13.1–13.6, 8.6).
//
// Confirms the card renders the immutable Decision_Snapshot data — triggering
// metrics, personality + source level, safety boundaries, proposed action and
// predicted risk — and that effect attribution shows the honest estimate label,
// the no-baseline signal for null estimates, and a pending state while the
// measurement window is still open.
//
// Validates: Requirements 13.1, 13.2, 13.3, 13.4, 13.6, 8.6

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DecisionExplanationCard, NO_BASELINE_SIGNAL } from './DecisionExplanationCard';
import type { HostingDecisionDetail } from '../../lib/api';

function baseDetail(overrides: Partial<HostingDecisionDetail> = {}): HostingDecisionDetail {
  return {
    decision: {
      id: 'dec-1',
      store_id: 'store-1',
      campaign_id: 'camp-1',
      campaign_name: '夏季促销活动',
      engine: 'v1_bid',
      decision_type: 'bid_adjustment',
      execution_mode: 'auto_execute',
      routing_outcome: 'pending',
      risk_score: 0.42,
      field: 'bid',
      before_value: 1.5,
      after_value: 1.35,
      sync_state: 'effective',
      created_at: '2024-06-01T08:00:00Z',
      expires_at: '2024-06-02T08:00:00Z',
    },
    snapshot: {
      dataCutoff: '2024-05-31T23:59:59Z',
      lookbackDays: 7,
      metricInputs: { currentAcos: 0.32, targetAcos: 0.25, spend: 120.5, clicks: 340 },
      dqGateResult: { passed: true },
      personality: 'conservative',
      inheritanceChain: ['goal', 'store', 'system'],
      effectiveBoundaries: [
        { limitName: 'maxBidDecreaseRatio', value: '0.20', sourceLevel: 'store' },
        { limitName: 'minBid', value: '0.10', sourceLevel: 'system' },
      ],
      riskFormulaVersion: 'v3',
      riskScore: 0.42,
      ruleVersion: 'rule-2024.5',
      executionMode: 'auto_execute',
    },
    attributions: null,
    ...overrides,
  };
}

describe('<DecisionExplanationCard>', () => {
  it('renders triggering metric inputs from the snapshot (Req 13.1)', () => {
    render(<DecisionExplanationCard detail={baseDetail()} />);
    expect(screen.getByText('当前 ACoS')).toBeInTheDocument();
    expect(screen.getByText('目标 ACoS')).toBeInTheDocument();
    expect(screen.getByText('花费')).toBeInTheDocument();
    expect(screen.getByText('7 天')).toBeInTheDocument();
  });

  it('renders the personality, rule version and source level (Req 13.4)', () => {
    render(<DecisionExplanationCard detail={baseDetail()} />);
    expect(screen.getByText('常规型')).toBeInTheDocument();
    expect(screen.getByText('rule-2024.5')).toBeInTheDocument();
    // First hop of the inheritance chain is the determining source level.
    expect(screen.getByText('目标级')).toBeInTheDocument();
  });

  it('renders safety boundaries with their source levels (Req 13.1)', () => {
    render(<DecisionExplanationCard detail={baseDetail()} />);
    expect(screen.getByText('maxBidDecreaseRatio')).toBeInTheDocument();
    expect(screen.getByText('minBid')).toBeInTheDocument();
    expect(screen.getByText('系统默认')).toBeInTheDocument();
  });

  it('renders the proposed action and predicted risk score (Req 13.2)', () => {
    render(<DecisionExplanationCard detail={baseDetail()} />);
    expect(screen.getByText('1.5')).toBeInTheDocument();
    expect(screen.getByText('1.35')).toBeInTheDocument();
    expect(screen.getByText('0.42')).toBeInTheDocument();
    expect(screen.getByText(/公式 v3/)).toBeInTheDocument();
  });

  it('shows a pending state when the measurement window is still open (Req 8.6)', () => {
    render(<DecisionExplanationCard detail={baseDetail({ attributions: null })} />);
    expect(screen.getByText(/测量窗口进行中/)).toBeInTheDocument();
  });

  it('renders attribution results once the window completes, labelled as estimate (Req 8.6)', () => {
    render(
      <DecisionExplanationCard
        detail={baseDetail({
          attributions: [
            {
              metric_type: 'acos',
              observed_change: -0.05,
              estimated_incremental_impact: -0.03,
              attribution_confidence: 0.7,
            },
          ],
        })}
      />,
    );
    expect(screen.getByText('增量影响（估算）')).toBeInTheDocument();
    expect(screen.getByText('-0.05')).toBeInTheDocument();
    expect(screen.getByText('-0.03')).toBeInTheDocument();
    expect(screen.getByText('70%')).toBeInTheDocument();
  });

  it('shows the no-baseline signal instead of a fabricated number when estimate is null (Req 8.2)', () => {
    render(
      <DecisionExplanationCard
        detail={baseDetail({
          attributions: [
            {
              metric_type: 'sales',
              observed_change: 12,
              estimated_incremental_impact: null,
              attribution_confidence: 0.2,
            },
          ],
        })}
      />,
    );
    expect(screen.getByText(NO_BASELINE_SIGNAL)).toBeInTheDocument();
  });

  it('degrades gracefully when no snapshot is present (Req 13.6)', () => {
    render(<DecisionExplanationCard detail={baseDetail({ snapshot: null })} />);
    expect(screen.getByText(/没有可用的不可变快照数据/)).toBeInTheDocument();
    // Audit + attribution sections still render from the decision itself.
    expect(screen.getByText('审计轨迹')).toBeInTheDocument();
  });
});
