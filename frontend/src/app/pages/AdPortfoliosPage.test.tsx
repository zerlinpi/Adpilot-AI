// Unit tests for the Ad Portfolios page budget-type labelling, focused on the
// 无预算上限 (no budget cap) label and budget cell rendering.
// Validates: Requirements 20.3

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { AdPortfoliosPage, budgetTypeLabel } from './AdPortfoliosPage';
import type { AdPortfolio } from '../lib/api';

// Drive the page from mocked data/store hooks so the tests observe only the
// rendering behaviour for the budget label.
vi.mock('../lib/api', () => ({
  fetchAdPortfolios: vi.fn(),
  createAdPortfolio: vi.fn(),
}));

vi.mock('../lib/useStoreId', () => ({
  useStoreId: vi.fn(),
}));

vi.mock('../lib/StoreContext', () => ({
  useStoreContext: vi.fn(),
}));

import { fetchAdPortfolios } from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { useStoreContext } from '../lib/StoreContext';

const STORE_ID = 'store-1';

function makePortfolio(overrides: Partial<AdPortfolio> = {}): AdPortfolio {
  return {
    id: 'p1',
    storeId: STORE_ID,
    name: '核心品类组合',
    state: 'enabled',
    budgetType: 'none',
    budget: null,
    startDate: null,
    endDate: null,
    externalId: null,
    campaignCount: 0,
    impressions: 0,
    clicks: 0,
    ctr: 0,
    spend: 0,
    cpc: 0,
    orders: 0,
    sales: 0,
    ...overrides,
  };
}

describe('budgetTypeLabel (Req 20.3)', () => {
  it('renders 无预算上限 when the portfolio has no budget cap', () => {
    expect(budgetTypeLabel('none')).toBe('无预算上限');
  });

  it('renders readable labels for the other budget types', () => {
    expect(budgetTypeLabel('recurring')).toBe('循环预算');
    expect(budgetTypeLabel('date_range')).toBe('日期范围预算');
  });

  it('falls back to the raw value for an unrecognized budget type', () => {
    expect(budgetTypeLabel('weekly')).toBe('weekly');
  });
});

describe('AdPortfoliosPage budget cell (Req 20.3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useStoreId).mockReturnValue({ storeId: STORE_ID, loading: false, error: null });
    vi.mocked(useStoreContext).mockReturnValue({
      stores: [{ id: STORE_ID, name: '美国站' }],
      storeId: STORE_ID,
      setStoreId: () => { },
      loading: false,
      error: null,
      reload: () => { },
    } as any);
  });

  it('shows the 无预算上限 budget type and budget value for a portfolio with no cap', async () => {
    vi.mocked(fetchAdPortfolios).mockResolvedValue([makePortfolio()]);

    render(<AdPortfoliosPage />);

    // The portfolio row renders once data resolves.
    expect(await screen.findByText('核心品类组合')).toBeInTheDocument();
    // Both the 预算类型 cell and the 预算 cell render 无预算上限 for a none-type
    // portfolio with a null budget.
    expect(screen.getAllByText('无预算上限').length).toBeGreaterThanOrEqual(2);
  });

  it('shows a formatted budget amount for a portfolio with a recurring cap', async () => {
    vi.mocked(fetchAdPortfolios).mockResolvedValue([
      makePortfolio({ id: 'p2', name: '促销组合', budgetType: 'recurring', budget: 100 }),
    ]);

    render(<AdPortfoliosPage />);

    expect(await screen.findByText('促销组合')).toBeInTheDocument();
    // The budget type cell shows the readable label, not 无预算上限.
    expect(screen.getByText('循环预算')).toBeInTheDocument();
    expect(screen.queryByText('无预算上限')).not.toBeInTheDocument();
  });
});
