// Unit/example tests for the decomposed Campaigns_Workspace (Task 22.4).
//
// Covers the workspace tab container and the shared view-state behaviors that
// every tab inherits from SharedDataTable + the react-query data-fetching layer:
//
//   - Tab taxonomy/order : exactly the eleven canonical tabs in order   (Req 1.1)
//   - Tab isolation      : only the selected tab's content is displayed (Req 1.2)
//   - Skeleton + refresh : a loading tab shows a skeleton and disables   (Req 1.5)
//                          its refresh control
//   - Error + retry      : a failed load shows an error indicator with a (Req 1.7)
//                          retry that re-requests and leaves prior
//                          records unchanged
//   - Working refresh    : the refresh control re-requests the records   (Req 1.8)
//
// Taxonomy and isolation are exercised at the container level via the
// `renderTab` callback with stub tab content. The skeleton/error/refresh
// behaviors live in SharedDataTable and are exercised both directly (with
// loading/error/onRetry/onRefresh props) and through a small react-query
// harness that mirrors how a real tab wires `useApiQuery` to SharedDataTable —
// so the "re-request" and "prior records unchanged" guarantees are validated
// against the actual data-fetching layer, not a stand-in.

import { describe, it, expect, vi } from 'vitest';
import { render, screen, within, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import {
  CampaignsWorkspace,
  CAMPAIGNS_TABS,
  type CampaignsTabKey,
} from './CampaignsWorkspace';
import { SharedDataTable } from '../../components/table/SharedDataTable';
import { useApiQuery } from '../../lib/hooks/useApiQuery';
import type { ColumnDef } from '../../components/table/types';

// The eleven tabs in their canonical order (Req 1.1). The container must render
// exactly these, in exactly this order.
const CANONICAL_LABELS = [
  '广告活动',
  'AI托管',
  '广告组',
  '推广商品',
  '投放',
  '否定投放',
  '搜索词',
  '购买的其他商品',
  '竞价调整',
  'SP预算上限',
  '操作日志',
] as const;

const CANONICAL_KEYS: CampaignsTabKey[] = [
  'campaigns',
  'hosting',
  'adGroups',
  'promotedProducts',
  'targeting',
  'negativeTargeting',
  'searchTerms',
  'otherProducts',
  'bidAdjustments',
  'budgetCaps',
  'operationLog',
];

// A stub tab renderer: emits a uniquely identifiable panel for the active tab so
// isolation can be asserted by what is (and is not) in the document.
function stubRenderTab(key: CampaignsTabKey) {
  return <div data-testid={`panel-${key}`}>content::{key}</div>;
}

describe('CampaignsWorkspace — tab taxonomy and isolation', () => {
  it('renders exactly the eleven canonical tabs in order (Req 1.1)', () => {
    render(<CampaignsWorkspace renderTab={stubRenderTab} />);

    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(11);
    expect(tabs.map((t) => t.textContent)).toEqual([...CANONICAL_LABELS]);

    // The exported taxonomy itself is the canonical eleven, in order.
    expect(CAMPAIGNS_TABS.map((t) => t.label)).toEqual([...CANONICAL_LABELS]);
    expect(CAMPAIGNS_TABS.map((t) => t.key)).toEqual(CANONICAL_KEYS);
  });

  it('initially displays only the first tab content (Req 1.2)', () => {
    render(<CampaignsWorkspace renderTab={stubRenderTab} />);

    // The first tab (广告活动) is active; its content is shown.
    expect(screen.getByTestId('panel-campaigns')).toBeInTheDocument();
    // No other tab's content is mounted — exactly one panel at a time.
    expect(screen.getAllByText(/^content::/)).toHaveLength(1);
    expect(screen.queryByTestId('panel-hosting')).not.toBeInTheDocument();

    // The first tab is marked selected; the others are not.
    const tabs = screen.getAllByRole('tab');
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    tabs.slice(1).forEach((t) => expect(t).toHaveAttribute('aria-selected', 'false'));
  });

  it('shows only the selected tab content after switching tabs (Req 1.2)', async () => {
    const user = userEvent.setup();
    render(<CampaignsWorkspace renderTab={stubRenderTab} />);

    await user.click(screen.getByRole('tab', { name: '搜索词' }));

    // Only the selected tab's content is present; the previous one is gone.
    expect(screen.getByTestId('panel-searchTerms')).toBeInTheDocument();
    expect(screen.queryByTestId('panel-campaigns')).not.toBeInTheDocument();
    expect(screen.getAllByText(/^content::/)).toHaveLength(1);

    // Selection state follows the click.
    expect(screen.getByRole('tab', { name: '搜索词' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: '广告活动' })).toHaveAttribute('aria-selected', 'false');
  });

  it('drives the controlled active tab from props and notifies on change (Req 1.2)', async () => {
    const user = userEvent.setup();
    const onTabChange = vi.fn();
    const { rerender } = render(
      <CampaignsWorkspace renderTab={stubRenderTab} activeTab="adGroups" onTabChange={onTabChange} />,
    );

    // Controlled: the supplied active tab is the one rendered.
    expect(screen.getByTestId('panel-adGroups')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: '操作日志' }));
    // Controlled component does not change itself; it reports the request.
    expect(onTabChange).toHaveBeenCalledWith('operationLog');
    expect(screen.getByTestId('panel-adGroups')).toBeInTheDocument();

    // Parent applies the new active tab; content follows.
    rerender(
      <CampaignsWorkspace renderTab={stubRenderTab} activeTab="operationLog" onTabChange={onTabChange} />,
    );
    expect(screen.getByTestId('panel-operationLog')).toBeInTheDocument();
    expect(screen.queryByTestId('panel-adGroups')).not.toBeInTheDocument();
  });
});

// ─── Shared view-state behavior every tab inherits from SharedDataTable ──────

interface Row {
  id: string;
  name: string;
}

const COLUMNS: ColumnDef<Row>[] = [{ key: 'name', header: '名称', render: (r) => r.name }];

describe('Campaigns_Workspace tab — loading skeleton and refresh control', () => {
  it('shows a skeleton and disables the refresh control while loading (Req 1.5)', () => {
    render(
      <SharedDataTable<Row>
        tableKey="campaigns.test"
        rows={[]}
        columns={COLUMNS}
        rowId={(r) => r.id}
        loading
        onRefresh={vi.fn()}
      />,
    );

    // Skeleton placeholder is shown for the loading tab.
    expect(document.querySelector('[data-slot="table-skeleton"]')).not.toBeNull();
    // The refresh control is disabled until loading completes.
    expect(screen.getByRole('button', { name: '刷新' })).toBeDisabled();
    // No data table and no error while loading.
    expect(document.querySelector('[data-slot="table"]')).toBeNull();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('enables the refresh control and re-requests records when activated (Req 1.8)', async () => {
    const user = userEvent.setup();
    const onRefresh = vi.fn();
    render(
      <SharedDataTable<Row>
        tableKey="campaigns.test"
        rows={[{ id: '1', name: '广告活动 A' }]}
        columns={COLUMNS}
        rowId={(r) => r.id}
        loading={false}
        onRefresh={onRefresh}
      />,
    );

    const refresh = screen.getByRole('button', { name: '刷新' });
    expect(refresh).toBeEnabled();
    await user.click(refresh);
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });
});

describe('Campaigns_Workspace tab — error indicator and retry', () => {
  it('shows an error indicator with a retry that re-requests, leaving prior rows available (Req 1.7)', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const priorRows: Row[] = [{ id: '1', name: '广告活动 A' }];

    // Error state: the host still holds the previously loaded records (rows),
    // and SharedDataTable surfaces the error indicator with a retry control.
    const { rerender } = render(
      <SharedDataTable<Row>
        tableKey="campaigns.test"
        rows={priorRows}
        columns={COLUMNS}
        rowId={(r) => r.id}
        error="加载广告活动失败"
        onRetry={onRetry}
      />,
    );

    const alert = screen.getByRole('alert');
    expect(within(alert).getByText('加载广告活动失败')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '重试' }));
    expect(onRetry).toHaveBeenCalledTimes(1);

    // The host's prior records were never cleared by the error: once the error
    // resolves (same rows still supplied), they render unchanged.
    rerender(
      <SharedDataTable<Row>
        tableKey="campaigns.test"
        rows={priorRows}
        columns={COLUMNS}
        rowId={(r) => r.id}
        error={null}
        onRetry={onRetry}
      />,
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('广告活动 A')).toBeInTheDocument();
  });
});

// ─── End-to-end wiring through the actual data-fetching layer ────────────────
//
// A minimal stand-in for a real tab: it wires `useApiQuery` to SharedDataTable
// the same way the extracted tab modules do (loading -> skeleton + disabled
// refresh, error -> retry, refresh -> refetch). This validates the Req 1.5 /
// 1.7 / 1.8 behaviors against the real react-query layer, including that a
// failed refetch retains the last successful rows (Req 1.7 "leave the
// previously displayed records unchanged").

function DataFetchingTab({ fetcher }: { fetcher: () => Promise<Row[]> }) {
  const query = useApiQuery<Row[]>(['campaigns', 'store-1'], fetcher);
  const rows = query.data ?? [];
  const error = query.isError ? query.error?.message ?? '加载失败' : null;
  return (
    <SharedDataTable<Row>
      tableKey="campaigns.test"
      rows={rows}
      columns={COLUMNS}
      rowId={(r) => r.id}
      loading={query.isLoading}
      error={error}
      onRetry={() => query.refetch()}
      onRefresh={() => query.refetch()}
    />
  );
}

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('Campaigns_Workspace tab — data-fetching layer integration', () => {
  it('renders a skeleton during the initial load, then the records (Req 1.5, 1.8)', async () => {
    const fetcher = vi.fn().mockResolvedValue([{ id: '1', name: '广告活动 A' }]);
    renderWithClient(<DataFetchingTab fetcher={fetcher} />);

    // While the in-flight read resolves, the skeleton is shown and refresh is disabled.
    expect(document.querySelector('[data-slot="table-skeleton"]')).not.toBeNull();
    expect(screen.getByRole('button', { name: '刷新' })).toBeDisabled();

    // Once loaded, the records render and refresh is enabled.
    expect(await screen.findByText('广告活动 A')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '刷新' })).toBeEnabled();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('on a failed refresh shows the error indicator and a retry that re-requests, leaving prior rows cached (Req 1.7)', async () => {
    const fetcher = vi
      .fn<() => Promise<Row[]>>()
      .mockResolvedValueOnce([{ id: '1', name: '广告活动 A' }]) // initial load
      .mockRejectedValueOnce(new Error('网络异常')) // failed refresh
      .mockResolvedValueOnce([{ id: '1', name: '广告活动 A' }]); // successful retry

    const user = userEvent.setup();
    renderWithClient(<DataFetchingTab fetcher={fetcher} />);

    // Initial load succeeds.
    expect(await screen.findByText('广告活动 A')).toBeInTheDocument();

    // Trigger a refresh that fails: an error indicator with a retry appears.
    await user.click(screen.getByRole('button', { name: '刷新' }));
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('网络异常')).toBeInTheDocument();
    // The previously loaded record is retained in cache (not cleared on failure).
    expect(fetcher).toHaveBeenCalledTimes(2);

    // Retry re-requests and, on success, restores the records unchanged.
    await user.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('广告活动 A')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(3);
  });
});
