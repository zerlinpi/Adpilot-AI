import { describe, expect, it } from 'vitest';

import {
  normalizeInventoryHealth,
  normalizeReplenishmentPlan,
} from './api';

describe('inventory and replenishment API contracts', () => {
  it('maps backend inventory field names to the UI contract', () => {
    const result = normalizeInventoryHealth({
      totalInventoryValue: 1250.5,
      lowStockCount: 1,
      overstockCount: 0,
      avgDaysOfSupply: 12,
      stockoutRisks: [{
        sku: 'SKU-1',
        productName: '真实产品',
        inventory: 8,
        inventoryValue: 80,
        recommendation: '立即补货',
      }],
      overstockRisks: [],
      notSafeToScale: [],
      clearanceCandidates: [],
    });

    expect(result.totalValue).toBe(1250.5);
    expect(result.stockoutRisks[0]).toMatchObject({
      name: '真实产品',
      value: 80,
      reason: '立即补货',
    });
  });

  it('maps backend replenishment product names and numeric costs', () => {
    expect(normalizeReplenishmentPlan({
      id: 'plan-1',
      productName: '真实产品',
      status: 'cancelled',
      recommendedQty: 20,
      purchaseCost: null,
      shippingCost: '15.5',
    })).toMatchObject({
      name: '真实产品',
      status: 'cancelled',
      recommendedQty: 20,
      purchaseCost: 0,
      shippingCost: 15.5,
    });
  });
});
