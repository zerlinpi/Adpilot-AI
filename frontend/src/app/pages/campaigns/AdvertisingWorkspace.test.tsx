// Unit/example tests for the four-group AdvertisingWorkspace shell (Task 20.1).
//
// Covers the four-group navigation presentation over the eleven existing tabs:
//   - Group taxonomy : exactly the four groups in order              (Req 28.1)
//   - Total partition: every existing tab assigned to exactly one    (Req 28.2)
//                      group, no tab removed
//   - Group select   : selecting a group reveals its tabs and        (Req 28.3)
//                      activates the group's first tab
//   - Tab isolation  : only the selected tab's content is rendered
//   - Controlled mode: active tab is driven by props and reported back
//
// The fast-check partition property test is a separate task (20.5); these are
// example-based behavior checks for the component shell.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { AdvertisingWorkspace } from './AdvertisingWorkspace';
import { CAMPAIGNS_TABS, type CampaignsTabKey } from './CampaignsWorkspace';
import {
  ADVERTISING_GROUPS,
  groupForTab,
  isTotalPartition,
} from './advertisingGroups';

afterEach(cleanup);

const GROUP_LABELS = ['广告管理', '搜索词管理', '智能优化', '记录与审计'] as const;

function stubRenderTab(key: CampaignsTabKey) {
  return <div data-testid={`panel-${key}`}>content::{key}</div>;
}

describe('AdvertisingWorkspace — four-group partition (Req 28.1, 28.2)', () => {
  it('declares exactly the four groups in canonical order (Req 28.1)', () => {
    expect(ADVERTISING_GROUPS.map((g) => g.label)).toEqual([...GROUP_LABELS]);
  });

  it('assigns every existing tab to exactly one group with no tab removed (Req 28.2)', () => {
    // Pure partition predicate must hold.
    expect(isTotalPartition()).toBe(true);

    // Cross-check: the union of all group tabs equals the eleven canonical tabs,
    // with no duplicates.
    const assigned = ADVERTISING_GROUPS.flatMap((g) => g.tabs);
    const canonical = CAMPAIGNS_TABS.map((t) => t.key);

    expect(assigned.length).toBe(canonical.length);
    expect(new Set(assigned).size).toBe(assigned.length); // no duplicates
    expect(new Set(assigned)).toEqual(new Set(canonical)); // same set

    // Every canonical tab resolves to a defined group.
    for (const tab of canonical) {
      expect(groupForTab(tab)).toBeDefined();
    }
  });

  it('renders the four group controls in order (Req 28.1)', () => {
    render(<AdvertisingWorkspace renderTab={stubRenderTab} />);
    const groupTabs = screen.getAllByRole('tab', { name: new RegExp(GROUP_LABELS.join('|')) });
    expect(groupTabs.map((t) => t.textContent)).toEqual([...GROUP_LABELS]);
  });
});

describe('AdvertisingWorkspace — navigation behavior (Req 28.3)', () => {
  it('initially shows the first group active and only its first tab content', () => {
    render(<AdvertisingWorkspace renderTab={stubRenderTab} />);

    // First group (广告管理) selected; first tab (campaigns) content shown.
    expect(screen.getByRole('tab', { name: '广告管理' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('panel-campaigns')).toBeInTheDocument();
    // Exactly one tab content panel is mounted.
    expect(screen.getAllByText(/^content::/)).toHaveLength(1);

    // A tab from another group is not shown.
    expect(screen.queryByRole('tab', { name: '搜索词' })).not.toBeInTheDocument();
  });

  it('selecting a group reveals its tabs and activates its first tab (Req 28.3)', async () => {
    const user = userEvent.setup();
    render(<AdvertisingWorkspace renderTab={stubRenderTab} />);

    await user.click(screen.getByRole('tab', { name: '搜索词管理' }));

    // The group's first tab (searchTerms) becomes active and its content shows.
    expect(screen.getByTestId('panel-searchTerms')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '搜索词' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '否定投放' })).toBeInTheDocument();
    // The previous group's tab content is gone.
    expect(screen.queryByTestId('panel-campaigns')).not.toBeInTheDocument();
    expect(screen.getAllByText(/^content::/)).toHaveLength(1);
  });

  it('switching tabs within a group shows only the selected tab content', async () => {
    const user = userEvent.setup();
    render(<AdvertisingWorkspace renderTab={stubRenderTab} />);

    await user.click(screen.getByRole('tab', { name: '广告组' }));
    expect(screen.getByTestId('panel-adGroups')).toBeInTheDocument();
    expect(screen.queryByTestId('panel-campaigns')).not.toBeInTheDocument();
  });
});

describe('AdvertisingWorkspace — controlled mode', () => {
  it('drives the active tab and its group from props and reports changes', async () => {
    const user = userEvent.setup();
    const onTabChange = vi.fn();
    const { rerender } = render(
      <AdvertisingWorkspace renderTab={stubRenderTab} activeTab="bidAdjustments" onTabChange={onTabChange} />,
    );

    // Controlled: bidAdjustments belongs to 智能优化, which is the active group.
    expect(screen.getByRole('tab', { name: '智能优化' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('panel-bidAdjustments')).toBeInTheDocument();

    // Clicking another group reports the request but does not self-update.
    await user.click(screen.getByRole('tab', { name: '记录与审计' }));
    expect(onTabChange).toHaveBeenCalledWith('operationLog');
    expect(screen.getByTestId('panel-bidAdjustments')).toBeInTheDocument();

    // Parent applies the new active tab; content and group follow.
    rerender(
      <AdvertisingWorkspace renderTab={stubRenderTab} activeTab="operationLog" onTabChange={onTabChange} />,
    );
    expect(screen.getByTestId('panel-operationLog')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '记录与审计' })).toHaveAttribute('aria-selected', 'true');
  });
});

describe('AdvertisingWorkspace — KPI slot integration (Task 20.4, Req 30.1)', () => {
  it('renders the supplied KPI panel in the header area', () => {
    render(
      <AdvertisingWorkspace
        renderTab={stubRenderTab}
        kpiPanel={<div data-testid="kpi-slot">kpi</div>}
      />,
    );
    expect(screen.getByTestId('kpi-slot')).toBeInTheDocument();
  });
});

describe('AdvertisingWorkspace — responsive strategy (Task 20.4, Req 43)', () => {
  it('renders the full desktop content at/above the threshold', () => {
    render(
      <AdvertisingWorkspace
        renderTab={stubRenderTab}
        renderCompact={(key) => <div data-testid={`compact-${key}`}>compact</div>}
        responsiveMode="desktop"
      />,
    );
    expect(screen.getByTestId('panel-campaigns')).toBeInTheDocument();
    expect(screen.queryByTestId('compact-campaigns')).not.toBeInTheDocument();
    expect(screen.getByRole('tabpanel')).toHaveAttribute('data-responsive-mode', 'desktop');
  });

  it('renders the read-oriented alternative below the threshold', () => {
    render(
      <AdvertisingWorkspace
        renderTab={stubRenderTab}
        renderCompact={(key) => <div data-testid={`compact-${key}`}>compact</div>}
        responsiveMode="compact"
      />,
    );
    expect(screen.getByTestId('compact-campaigns')).toBeInTheDocument();
    expect(screen.queryByTestId('panel-campaigns')).not.toBeInTheDocument();
    expect(screen.getByRole('tabpanel')).toHaveAttribute('data-responsive-mode', 'compact');
  });

  it('falls back to full content when no compact renderer is provided', () => {
    render(
      <AdvertisingWorkspace renderTab={stubRenderTab} responsiveMode="compact" />,
    );
    expect(screen.getByTestId('panel-campaigns')).toBeInTheDocument();
  });
});
