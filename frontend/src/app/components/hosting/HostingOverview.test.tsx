// Render tests for <HostingOverview> wired to real dashboard data.
//
// Confirms the first-screen summary figures reflect the supplied counts, the
// estimated-savings card is always labelled as an estimate (Req 11.3 / 27.3),
// and the learning-period section lists campaigns with their days remaining
// (Req 19.5). Also confirms the honest "no baseline" signal renders when an
// estimate is absent.
//
// Validates: Requirements 11.1, 11.3, 19.5, 27.2

import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { HostingOverview, NO_BASELINE_SIGNAL, type HostingOverviewData } from './HostingOverview';

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
    resolvedPersonalities: ['conservative', 'balanced', 'aggressive', 'balanced', 'conservative'],
    ...overrides,
  };
}

describe('<HostingOverview>', () => {
  it('renders the five summary figures from real data (Req 11.1)', () => {
    render(<HostingOverview data={baseData()} />);
    // Scope each figure to its card so the value is unambiguous (figure values
    // can otherwise collide with the personality-distribution counts).
    const figure = (label: string) => {
      const card = screen.getByText(label).closest('div.rounded-xl') as HTMLElement;
      expect(card).not.toBeNull();
      return within(card);
    };
    expect(figure('托管活动').getByText('5')).toBeInTheDocument();
    expect(figure('今日决策').getByText('12')).toBeInTheDocument();
    expect(figure('等待审批').getByText('3')).toBeInTheDocument();
    expect(figure('Amazon生效').getByText('7')).toBeInTheDocument();
    expect(figure('失败').getByText('1')).toBeInTheDocument();
  });

  it('labels the estimated-savings figure as an estimate using the backend label (Req 11.3/27.3)', () => {
    render(<HostingOverview data={baseData()} />);
    expect(screen.getByText('预计节省花费（估算·近7天）')).toBeInTheDocument();
    expect(screen.getByText('$1,200')).toBeInTheDocument();
  });

  it('falls back to the no-baseline signal when no savings estimate exists (Req 11.3)', () => {
    render(<HostingOverview data={baseData({ estimatedSpendSavings: null })} />);
    // The estimate card renders the honest no-baseline signal, never a fabricated number.
    expect(screen.getAllByText(NO_BASELINE_SIGNAL).length).toBeGreaterThan(0);
  });

  it('renders learning-period status with days remaining per campaign (Req 19.5)', () => {
    render(
      <HostingOverview
        data={baseData({
          learningPeriods: [
            { campaignId: 'c-1', campaignName: '夏季促销', daysRemaining: 2, totalDays: 3 },
            { campaignId: 'c-2', campaignName: null, daysRemaining: 1, totalDays: 3 },
          ],
        })}
      />,
    );
    expect(screen.getByText('学习期')).toBeInTheDocument();
    expect(screen.getByText('（2 个广告活动学习中）')).toBeInTheDocument();
    expect(screen.getByText('夏季促销')).toBeInTheDocument();
    expect(screen.getByText('剩余 2 / 3 天')).toBeInTheDocument();
    // Missing campaign name falls back to the id.
    expect(screen.getByText('c-2')).toBeInTheDocument();
    expect(screen.getByText('剩余 1 / 3 天')).toBeInTheDocument();
  });

  it('hides the learning-period section when no campaigns are learning (Req 19.5)', () => {
    render(<HostingOverview data={baseData({ learningPeriods: [] })} />);
    expect(screen.queryByText('学习期')).not.toBeInTheDocument();
  });
});
