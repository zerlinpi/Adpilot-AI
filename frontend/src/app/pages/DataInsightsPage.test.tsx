// Unit tests for the Data Insights surfaces' empty-states (and the activation
// gating used as a non-error fallback on gated surfaces).
// Validates: Requirements 30.8, 30.7

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { DataInsightsPage } from './DataInsightsPage';
import type {
  ProductInsights,
  BrandMetrics,
  MarketInsights,
  SqpInsights,
} from '../lib/api';

vi.mock('../lib/api', () => ({
  fetchInsightsProductList: vi.fn(),
  fetchInsightsBrandMetrics: vi.fn(),
  fetchInsightsMarketInsights: vi.fn(),
  fetchInsightsSqp: vi.fn(),
}));

vi.mock('../lib/useStoreId', () => ({
  useStoreId: vi.fn(),
}));

import {
  fetchInsightsProductList,
  fetchInsightsBrandMetrics,
  fetchInsightsMarketInsights,
  fetchInsightsSqp,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';

const STORE_ID = 'store-1';

const emptyProducts: ProductInsights = {
  currency: 'USD',
  items: [],
  customReportConsumed: 0,
  customReportTotal: 0,
  customReports: [],
};

function setStoreReady() {
  vi.mocked(useStoreId).mockReturnValue({ storeId: STORE_ID, loading: false, error: null });
}

describe('DataInsightsPage empty-states (Req 30.8)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setStoreReady();
    // Default every surface to an empty (non-activation) payload; individual
    // tests override the surface they exercise.
    vi.mocked(fetchInsightsProductList).mockResolvedValue(emptyProducts);
    vi.mocked(fetchInsightsBrandMetrics).mockResolvedValue({ requiresActivation: false });
    vi.mocked(fetchInsightsMarketInsights).mockResolvedValue({ requiresActivation: false, reports: [] });
    vi.mocked(fetchInsightsSqp).mockResolvedValue({ requiresActivation: false, rows: [] });
  });

  it('renders the product-list empty-state when there are no items', async () => {
    render(<DataInsightsPage />);

    // Products is the default tab; an empty item list yields an empty-state,
    // not an error.
    expect(await screen.findByText('暂无商品数据')).toBeInTheDocument();
    expect(screen.queryByText('加载失败')).not.toBeInTheDocument();
  });

  it('renders the brand-metrics empty-state when no payload is available', async () => {
    vi.mocked(fetchInsightsBrandMetrics).mockResolvedValue(null as unknown as BrandMetrics);
    render(<DataInsightsPage />);

    await userEvent.click(screen.getByRole('button', { name: /品牌指标/ }));
    expect(await screen.findByText('暂无品牌指标')).toBeInTheDocument();
  });

  it('renders the market-insights empty-state when there are no reports', async () => {
    render(<DataInsightsPage />);

    await userEvent.click(screen.getByRole('button', { name: /市场洞察/ }));
    expect(await screen.findByText('暂无市场监控报告')).toBeInTheDocument();
    expect(screen.queryByText('加载失败')).not.toBeInTheDocument();
  });

  it('renders the SQP empty-state when there are no rows', async () => {
    render(<DataInsightsPage />);

    await userEvent.click(screen.getByRole('button', { name: /SQP分析/ }));
    expect(await screen.findByText('暂无 SQP 数据')).toBeInTheDocument();
    expect(screen.queryByText('加载失败')).not.toBeInTheDocument();
  });

  it('prompts to pick a store rather than erroring when none is selected', async () => {
    vi.mocked(useStoreId).mockReturnValue({ storeId: null, loading: false, error: null });
    render(<DataInsightsPage />);

    expect(await screen.findByText('请选择店铺')).toBeInTheDocument();
    expect(fetchInsightsProductList).not.toHaveBeenCalled();
  });
});

describe('DataInsightsPage activation gating fallback (Req 30.7)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setStoreReady();
    vi.mocked(fetchInsightsProductList).mockResolvedValue(emptyProducts);
  });

  it('shows a 需要激活 state on the brand surface when gated behind activation', async () => {
    const gated: BrandMetrics = {
      requiresActivation: true,
      message: '品牌指标需先激活品牌分析后方可使用。',
    };
    vi.mocked(fetchInsightsBrandMetrics).mockResolvedValue(gated);

    render(<DataInsightsPage />);
    await userEvent.click(screen.getByRole('button', { name: /品牌指标/ }));

    expect(await screen.findByText('需要激活品牌数据源')).toBeInTheDocument();
    expect(screen.getByText('品牌指标需先激活品牌分析后方可使用。')).toBeInTheDocument();
    expect(screen.queryByText('加载失败')).not.toBeInTheDocument();
  });
});
