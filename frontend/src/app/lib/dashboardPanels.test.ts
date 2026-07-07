// Unit tests for the AI advertising dashboard pure panel helpers.
// Validates: Requirements 18.1, 18.2, 18.6, 18.7

import { describe, it, expect } from 'vitest';

import {
  getDashboardDateRange,
  deltaTrend,
  buildSalesOverviewTiles,
  isSalesOverviewEmpty,
  isTrendEmpty,
  isAiActionsEmpty,
  isAiUsageEmpty,
  resolveNotificationCategories,
  NOTIFICATION_CATEGORY_FALLBACK,
} from './dashboardPanels';

const NOW = new Date('2024-03-15T12:00:00Z');

describe('getDashboardDateRange', () => {
  it('maps 今天 to a single-day inclusive range', () => {
    expect(getDashboardDateRange('今天', NOW)).toEqual({ startDate: '2024-03-15', endDate: '2024-03-15' });
  });

  it('maps 昨天 to the previous single day', () => {
    expect(getDashboardDateRange('昨天', NOW)).toEqual({ startDate: '2024-03-14', endDate: '2024-03-14' });
  });

  it('maps 近7天 to a 7-day window ending today', () => {
    expect(getDashboardDateRange('近7天', NOW)).toEqual({ startDate: '2024-03-08', endDate: '2024-03-15' });
  });

  it('maps 近30天 to a 30-day window ending today', () => {
    expect(getDashboardDateRange('近30天', NOW)).toEqual({ startDate: '2024-02-14', endDate: '2024-03-15' });
  });

  it('falls back to the 7-day window for an unknown label', () => {
    expect(getDashboardDateRange('unknown', NOW)).toEqual({ startDate: '2024-03-08', endDate: '2024-03-15' });
  });
});

describe('deltaTrend', () => {
  it('returns up for a positive delta', () => {
    expect(deltaTrend(12.5)).toBe('up');
  });
  it('returns down for a negative delta', () => {
    expect(deltaTrend(-3)).toBe('down');
  });
  it('returns flat for zero, null, or non-finite', () => {
    expect(deltaTrend(0)).toBe('flat');
    expect(deltaTrend(null)).toBe('flat');
    expect(deltaTrend(undefined)).toBe('flat');
    expect(deltaTrend(Number.NaN)).toBe('flat');
  });
});

describe('buildSalesOverviewTiles', () => {
  it('produces the six metrics in the Req 18.1 order with correct format and inverse flags', () => {
    const tiles = buildSalesOverviewTiles({
      currency: 'USD',
      totalSales: { value: 1000, deltaPct: 5 },
      adSpend: { value: 200, deltaPct: -2 },
      adSales: { value: 800, deltaPct: 10 },
      adOrders: { value: 42, deltaPct: 0 },
      tacos: { value: 20, deltaPct: 1 },
      acos: { value: 25, deltaPct: -4 },
    });

    expect(tiles.map((t) => t.key)).toEqual(['totalSales', 'adSpend', 'adSales', 'adOrders', 'tacos', 'acos']);
    expect(tiles.map((t) => t.label)).toEqual(['总销售额', '广告花费', '广告销售额', '广告订单数', 'TACoS', 'ACoS']);
    expect(tiles.map((t) => t.format)).toEqual(['currency', 'currency', 'currency', 'number', 'percent', 'percent']);
    // Lower-is-better metrics: spend, TACoS, ACoS.
    expect(tiles.map((t) => t.inverse)).toEqual([false, true, false, false, true, true]);
  });

  it('derives trend direction from each metric delta', () => {
    const tiles = buildSalesOverviewTiles({
      totalSales: { value: 1, deltaPct: 5 },
      adSpend: { value: 1, deltaPct: -2 },
      adOrders: { value: 1, deltaPct: 0 },
    });
    const byKey = Object.fromEntries(tiles.map((t) => [t.key, t.trend]));
    expect(byKey.totalSales).toBe('up');
    expect(byKey.adSpend).toBe('down');
    expect(byKey.adOrders).toBe('flat');
  });

  it('treats missing metrics as zero values', () => {
    const tiles = buildSalesOverviewTiles(null);
    expect(tiles).toHaveLength(6);
    expect(tiles.every((t) => t.rawValue === 0 && t.deltaPct === 0 && t.trend === 'flat')).toBe(true);
  });
});

describe('isSalesOverviewEmpty', () => {
  it('is empty for null or an all-zero overview', () => {
    expect(isSalesOverviewEmpty(null)).toBe(true);
    expect(isSalesOverviewEmpty({ totalSales: { value: 0, deltaPct: 0 } })).toBe(true);
  });
  it('is not empty when any metric has a non-zero value or delta', () => {
    expect(isSalesOverviewEmpty({ adSales: { value: 1 } })).toBe(false);
    expect(isSalesOverviewEmpty({ acos: { value: 0, deltaPct: -3 } })).toBe(false);
  });
});

describe('isTrendEmpty', () => {
  it('is empty for no points or all-zero points', () => {
    expect(isTrendEmpty(null)).toBe(true);
    expect(isTrendEmpty({ points: [] })).toBe(true);
    expect(isTrendEmpty({ points: [{ period: 'd1', spend: 0, sales: 0 }] })).toBe(true);
  });
  it('is not empty when any point carries spend or sales', () => {
    expect(isTrendEmpty({ points: [{ period: 'd1', spend: 0, sales: 5 }] })).toBe(false);
  });
});

describe('isAiActionsEmpty', () => {
  it('is empty when there are no actions or every count is 0', () => {
    expect(isAiActionsEmpty(null)).toBe(true);
    expect(isAiActionsEmpty({ actions: [] })).toBe(true);
    expect(isAiActionsEmpty({ actions: [{ key: 'k', label: 'l', count: 0 }] })).toBe(true);
  });
  it('is not empty when any action ran', () => {
    expect(isAiActionsEmpty({ actions: [{ key: 'k', label: 'l', count: 3 }] })).toBe(false);
  });
});

describe('isAiUsageEmpty', () => {
  it('is empty when coverage, spend, and sales are all zero', () => {
    expect(isAiUsageEmpty(null)).toBe(true);
    expect(isAiUsageEmpty({ coveragePercent: 0, aiAdSpend: 0, aiAdSales: 0 })).toBe(true);
  });
  it('is not empty when any usage metric is present', () => {
    expect(isAiUsageEmpty({ coveragePercent: 30 })).toBe(false);
  });
});

describe('resolveNotificationCategories', () => {
  it('returns the four fallback categories when none are provided (Req 18.5)', () => {
    expect(resolveNotificationCategories(null)).toEqual(NOTIFICATION_CATEGORY_FALLBACK);
    expect(resolveNotificationCategories({ categories: [] })).toEqual(NOTIFICATION_CATEGORY_FALLBACK);
    expect(resolveNotificationCategories(null)).toHaveLength(4);
  });
  it('returns the provided categories when present', () => {
    const cats = [{ key: 'core_ops_attention', label: '广告运营核心关注', pendingCount: 7 }];
    expect(resolveNotificationCategories({ available: true, categories: cats })).toEqual(cats);
  });
});
