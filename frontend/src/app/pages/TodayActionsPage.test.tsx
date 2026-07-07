import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';

vi.mock('../lib/api', () => ({
  completeTask: vi.fn(),
  dismissTask: vi.fn(),
  fetchAiNotifications: vi.fn(),
  fetchHostingDashboardSummary: vi.fn(),
  fetchPlatformConnections: vi.fn(),
  fetchProducts: vi.fn(),
  fetchRecommendations: vi.fn(),
  fetchStoreSyncJobs: vi.fn(),
  fetchTasks: vi.fn(),
  fetchUploadJobs: vi.fn(),
}));

vi.mock('../lib/StoreContext', () => ({
  useStoreContext: vi.fn(),
}));

import {
  completeTask,
  fetchAiNotifications,
  fetchHostingDashboardSummary,
  fetchPlatformConnections,
  fetchProducts,
  fetchRecommendations,
  fetchStoreSyncJobs,
  fetchTasks,
  fetchUploadJobs,
} from '../lib/api';
import { useStoreContext } from '../lib/StoreContext';
import { TodayActionsPage } from './TodayActionsPage';

const storeId = '11111111-1111-1111-1111-111111111111';

function mockStore(platform: string) {
  vi.mocked(useStoreContext).mockReturnValue({
    stores: [{ id: storeId, name: '运营测试店', platform }],
    storeId,
    setStoreId: vi.fn(),
    loading: false,
    error: null,
    reload: vi.fn(),
  } as any);
}

function renderPage() {
  return render(
    <MemoryRouter>
      <TodayActionsPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchTasks).mockResolvedValue([
    {
      id: 'task-1',
      status: 'open',
      priority: 'high',
      taskType: 'ads',
      title: '检查广告预算',
      description: '预算接近上限',
    },
  ]);
  vi.mocked(fetchPlatformConnections).mockResolvedValue([
    { id: 'connection-1', storeId, platform: 'google_ads', status: 'connected' },
  ]);
  vi.mocked(fetchProducts).mockResolvedValue([{ id: 'product-1', status: 'active' }]);
  vi.mocked(fetchUploadJobs).mockResolvedValue([
    { id: 'upload-1', status: 'failed' },
    { id: 'upload-2', status: 'ready' },
  ]);
  vi.mocked(fetchStoreSyncJobs).mockResolvedValue({
    items: [{ id: 'sync-1', status: 'failed', entityType: 'product' }],
  } as any);
  vi.mocked(fetchRecommendations).mockResolvedValue([]);
  vi.mocked(fetchAiNotifications).mockResolvedValue({ categories: [] } as any);
  vi.mocked(fetchHostingDashboardSummary).mockResolvedValue({} as any);
  vi.mocked(completeTask).mockResolvedValue({ id: 'task-1', status: 'completed' } as any);
});

describe('TodayActionsPage unified operations queue', () => {
  it('combines tasks, publishing failures and store-scoped sync failures', async () => {
    mockStore('shopify');
    renderPage();

    expect(await screen.findByText('今日运营待办')).toBeInTheDocument();
    expect(screen.getByText('检查广告预算')).toBeInTheDocument();
    expect(screen.getByText('1 个同步任务失败')).toBeInTheDocument();
    expect(screen.getByText('1 个商品发布任务失败')).toBeInTheDocument();
    expect(screen.getByText('1 个商品任务等待处理')).toBeInTheDocument();

    expect(fetchStoreSyncJobs).toHaveBeenCalledWith(storeId, { status: 'failed', pageSize: 100 });
    expect(fetchRecommendations).not.toHaveBeenCalled();
    expect(fetchAiNotifications).not.toHaveBeenCalled();
    expect(fetchHostingDashboardSummary).not.toHaveBeenCalled();
  });

  it('only exposes direct completion controls for real operation tasks', async () => {
    mockStore('shopify');
    const user = userEvent.setup();
    renderPage();

    const taskTitle = await screen.findByText('检查广告预算');
    const taskRow = taskTitle.closest('.grid');
    expect(taskRow).not.toBeNull();
    expect(within(taskRow as HTMLElement).getByRole('button', { name: '完成' })).toBeInTheDocument();

    const publishTitle = screen.getByText('1 个商品发布任务失败');
    const publishRow = publishTitle.closest('.grid');
    expect(publishRow).not.toBeNull();
    expect(within(publishRow as HTMLElement).queryByRole('button', { name: '完成' })).not.toBeInTheDocument();
    expect(within(publishRow as HTMLElement).getByRole('link', { name: '处理' })).toHaveAttribute('href', '/product-upload');

    await user.click(within(taskRow as HTMLElement).getByRole('button', { name: '完成' }));
    await waitFor(() => expect(completeTask).toHaveBeenCalledWith('task-1'));
    expect(screen.queryByText('检查广告预算')).not.toBeInTheDocument();
  });

  it('does not turn API read failures into false empty-state warnings', async () => {
    mockStore('shopify');
    vi.mocked(fetchPlatformConnections).mockRejectedValue(new Error('connection unavailable'));
    vi.mocked(fetchProducts).mockRejectedValue(new Error('product unavailable'));

    renderPage();

    expect(await screen.findByText('部分待办数据暂时不可用')).toBeInTheDocument();
    expect(screen.queryByText('当前店铺还没有可用的平台连接')).not.toBeInTheDocument();
    expect(screen.queryByText('当前店铺还没有商品数据')).not.toBeInTheDocument();
  });
  it('loads Amazon-only recommendations and approvals for Amazon stores', async () => {
    mockStore('amazon');
    vi.mocked(fetchPlatformConnections).mockResolvedValue([
      { id: 'connection-2', storeId, platform: 'amazon_ads', status: 'connected' },
    ]);
    vi.mocked(fetchHostingDashboardSummary).mockResolvedValue({ awaiting_approval_count: 2 } as any);

    renderPage();

    expect(await screen.findByText('2 条广告决策等待审批')).toBeInTheDocument();
    expect(fetchRecommendations).toHaveBeenCalledWith({ storeId, status: 'pending' });
    expect(fetchAiNotifications).toHaveBeenCalledWith(storeId);
    expect(fetchHostingDashboardSummary).toHaveBeenCalledWith(storeId);
  });
});