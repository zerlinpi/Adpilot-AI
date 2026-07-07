import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';

vi.mock('../lib/api', () => ({
  fetchSalesOverview: vi.fn(),
  fetchTasks: vi.fn(),
  fetchRecommendations: vi.fn(),
  fetchAiNotifications: vi.fn(),
  fetchHostingDashboardSummary: vi.fn(),
  fetchHostingHealth: vi.fn(),
  fetchPlatformConnections: vi.fn(),
  fetchStoreSyncJobs: vi.fn(),
  fetchProducts: vi.fn(),
  fetchUploadJobs: vi.fn(),
}));

vi.mock('../lib/StoreContext', () => ({
  useStoreContext: vi.fn(),
}));

import {
  fetchAiNotifications,
  fetchHostingDashboardSummary,
  fetchHostingHealth,
  fetchPlatformConnections,
  fetchProducts,
  fetchRecommendations,
  fetchSalesOverview,
  fetchStoreSyncJobs,
  fetchTasks,
  fetchUploadJobs,
} from '../lib/api';
import { useStoreContext } from '../lib/StoreContext';
import { CommandCenterPage } from './CommandCenterPage';

const storeId = '11111111-1111-1111-1111-111111111111';

function mockStore(platform: string) {
  vi.mocked(useStoreContext).mockReturnValue({
    stores: [{ id: storeId, name: '测试店铺', platform }],
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
      <CommandCenterPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchSalesOverview).mockResolvedValue({ currency: 'USD' });
  vi.mocked(fetchTasks).mockResolvedValue([]);
  vi.mocked(fetchRecommendations).mockResolvedValue([]);
  vi.mocked(fetchAiNotifications).mockResolvedValue({ categories: [] } as any);
  vi.mocked(fetchHostingDashboardSummary).mockResolvedValue({} as any);
  vi.mocked(fetchHostingHealth).mockResolvedValue(null as any);
  vi.mocked(fetchPlatformConnections).mockResolvedValue([
    { id: 'connection-1', storeId, platform: 'amazon_ads', status: 'connected' },
  ]);
  vi.mocked(fetchStoreSyncJobs).mockResolvedValue({ items: [] } as any);
  vi.mocked(fetchProducts).mockResolvedValue([
    { id: 'product-1', status: 'active' },
    { id: 'product-2', status: 'inactive' },
  ]);
  vi.mocked(fetchUploadJobs).mockResolvedValue([
    { id: 'upload-1', status: 'ready' },
    { id: 'upload-2', status: 'failed' },
  ]);
});

describe('Command Center store-scoped operations contract', () => {
  it('loads product, publishing and failed sync data for the selected store', async () => {
    mockStore('amazon');
    renderPage();

    expect(await screen.findByText('今日运营工作台')).toBeInTheDocument();
    expect(screen.getByText('商品与发布')).toBeInTheDocument();
    expect(screen.getByText('Amazon Ads 托管链路')).toBeInTheDocument();

    await waitFor(() => {
      expect(fetchProducts).toHaveBeenCalledWith(storeId);
      expect(fetchUploadJobs).toHaveBeenCalledWith(storeId);
      expect(fetchStoreSyncJobs).toHaveBeenCalledWith(storeId, { status: 'failed', pageSize: 100 });
    });
  });

  it('does not call Amazon hosting endpoints for a Shopify store', async () => {
    mockStore('shopify');
    vi.mocked(fetchPlatformConnections).mockResolvedValue([
      { id: 'connection-2', storeId, platform: 'google_ads', status: 'connected' },
    ]);

    renderPage();

    expect((await screen.findAllByText('Google Ads AI 监控')).length).toBeGreaterThan(0);
    expect(screen.queryByText('Amazon Ads 托管链路')).not.toBeInTheDocument();
    expect(fetchRecommendations).not.toHaveBeenCalled();
    expect(fetchAiNotifications).not.toHaveBeenCalled();
    expect(fetchHostingDashboardSummary).not.toHaveBeenCalled();
    expect(fetchHostingHealth).not.toHaveBeenCalled();
  });

  it('shows unavailable data without creating false missing-connection or missing-product work items', async () => {
    mockStore('shopify');
    vi.mocked(fetchPlatformConnections).mockRejectedValue(new Error('connection unavailable'));
    vi.mocked(fetchProducts).mockRejectedValue(new Error('product unavailable'));

    renderPage();

    expect(await screen.findByText('部分数据暂时不可用')).toBeInTheDocument();
    expect(screen.queryByText('还没有平台接口连接')).not.toBeInTheDocument();
    expect(screen.queryByText('当前店铺还没有商品')).not.toBeInTheDocument();
    expect(screen.getByText('连接状态暂不可用。')).toBeInTheDocument();
  });
  it('keeps product and publishing actions in the same operations workspace', async () => {
    mockStore('tiktok');
    renderPage();

    await screen.findByText('商品与发布');
    expect(screen.getByRole('link', { name: '管理商品' })).toHaveAttribute('href', '/products');
    expect(screen.getByRole('link', { name: /直发 TikTok 商品/ })).toHaveAttribute('href', '/product-upload');
  });
});