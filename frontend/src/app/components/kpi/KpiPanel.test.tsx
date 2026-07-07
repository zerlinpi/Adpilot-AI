// Render tests for <KpiPanel>.
//
// Confirms the panel is collapsible (Req 30.1), defaults its date range to the
// last 7 days vs prior 7 days computed in the Marketplace_Timezone (Req 30.3,
// 30.4), renders supplied metrics with a period-over-period delta, and surfaces
// a configuration error (never the server timezone) when the timezone is unset.
//
// Validates: Requirements 30.1, 30.4

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { KpiPanel } from './KpiPanel';
import { resolveDefaultPeriodComparison } from '../../lib/advertisingKpiPeriod';

const NOW = new Date('2024-01-10T12:00:00Z');

describe('<KpiPanel>', () => {
  it('defaults to last-7 vs prior-7 in the marketplace timezone (Req 30.3/30.4)', () => {
    render(<KpiPanel timeZone="Asia/Tokyo" now={NOW} />);
    const expected = resolveDefaultPeriodComparison('Asia/Tokyo', { now: NOW });
    // 2024-01-10T12:00Z is 2024-01-10 in Tokyo as well → current ends today.
    expect(screen.getByTestId('kpi-panel-period').textContent).toContain(
      expected.current.start,
    );
    expect(screen.getByTestId('kpi-panel-period').textContent).toContain(
      expected.prior.start,
    );
  });

  it('resolves a DIFFERENT default for a different timezone (Req 30.4)', () => {
    // 2024-01-10T02:00Z is still 2024-01-09 in Los Angeles.
    const now = new Date('2024-01-10T02:00:00Z');
    render(<KpiPanel timeZone="America/Los_Angeles" now={now} />);
    expect(screen.getByTestId('kpi-panel-period').textContent).toContain('2024-01-09');
  });

  it('notifies the parent of the resolved comparison', () => {
    const onComparisonChange = vi.fn();
    render(
      <KpiPanel timeZone="UTC" now={NOW} onComparisonChange={onComparisonChange} />,
    );
    expect(onComparisonChange).toHaveBeenCalledWith(
      resolveDefaultPeriodComparison('UTC', { now: NOW }),
    );
  });

  it('is collapsible: toggling hides/shows the body (Req 30.1)', async () => {
    const user = userEvent.setup();
    render(
      <KpiPanel
        timeZone="UTC"
        now={NOW}
        metrics={[{ key: 'spend', label: '花费', value: '$1,000' }]}
      />,
    );
    const toggle = screen.getByTestId('kpi-panel-toggle');
    // Starts expanded.
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('kpi-metric-spend')).toBeInTheDocument();

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('honors defaultCollapsed', () => {
    render(<KpiPanel timeZone="UTC" now={NOW} defaultCollapsed />);
    expect(screen.getByTestId('kpi-panel-toggle')).toHaveAttribute(
      'aria-expanded',
      'false',
    );
  });

  it('renders metrics with a period-over-period delta', () => {
    render(
      <KpiPanel
        timeZone="UTC"
        now={NOW}
        metrics={[
          { key: 'spend', label: '花费', value: '$125', currentRaw: 125, priorRaw: 100 },
        ]}
      />,
    );
    const cell = screen.getByTestId('kpi-metric-spend');
    expect(cell.textContent).toContain('$125');
    expect(cell.textContent).toContain('+25.0%');
  });

  it('shows a configuration error and no server-tz fallback when timezone is unset (Req 30.4)', () => {
    render(<KpiPanel timeZone={null} now={NOW} />);
    expect(screen.getByTestId('kpi-panel-tz-error')).toBeInTheDocument();
    expect(screen.queryByTestId('kpi-panel-period')).not.toBeInTheDocument();
  });

  it('renders an empty-metrics placeholder', () => {
    render(<KpiPanel timeZone="UTC" now={NOW} metrics={[]} />);
    expect(screen.getByText('暂无指标数据')).toBeInTheDocument();
  });
});
