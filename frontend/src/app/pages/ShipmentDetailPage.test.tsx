// Unit/example tests for ShipmentDetailPage (Task 23.3).
//
// Covers detail rendering and empty/not-started states for each section:
//   - Transport legs, including the empty-leg state          (Req 4.8)
//   - Carton specs + computed totals, including the zero case (Req 5.4)
//   - Customs clearance not-started state when absent         (Req 7.6)
//   - Tracking trajectory, including the empty-trajectory state (Req 8.8)
//   - Exception indicator                                     (Req 9.6)
//   - Cost chain itemized lines + total                       (Req 10.3)
//   - FBA core fields + line items                            (Req 16.4)
//
// The page reads through react-query (`useApiQuery`) so the network seam
// (`lib/api.ts` fetchers) is mocked and the component runs against a real
// QueryClient + Router. Each section manages its own loading/empty state, so
// every fetcher is given a default resolution and individual tests override
// only the surface under exercise.

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { ShipmentDetailPage } from './ShipmentDetailPage';
import { useStoreContext } from '../lib/StoreContext';
import {
  fetchShipment,
  fetchShipmentLegs,
  fetchCartonSpecs,
  fetchCustomsClearance,
  fetchTrackingEvents,
  fetchShipmentExceptions,
  fetchCostChain,
  fetchFbaFields,
  type Shipment,
  type ShipmentLeg,
  type CartonSpec,
  type CustomsClearance,
  type TrackingEvent,
  type ShipmentException,
  type CostChain,
  type FbaFields,
} from '../lib/api';

vi.mock('../lib/api', () => ({
  fetchShipment: vi.fn(),
  fetchShipmentLegs: vi.fn(),
  fetchCartonSpecs: vi.fn(),
  fetchCustomsClearance: vi.fn(),
  fetchTrackingEvents: vi.fn(),
  fetchShipmentExceptions: vi.fn(),
  fetchCostChain: vi.fn(),
  fetchFbaFields: vi.fn(),
}));

vi.mock('../lib/StoreContext', () => ({
  useStoreContext: vi.fn(),
}));

const SHIPMENT_ID = 'ship-1';

// ─── Fixture builders ───────────────────────────────────────────────

function makeShipment(overrides: Partial<Shipment> = {}): Shipment {
  return {
    id: SHIPMENT_ID,
    storeId: 'amazon-store',
    shipmentId: 'FBA-001',
    status: 'in_transit',
    carrier: 'DHL',
    trackingNumber: 'TRK-999',
    shipDate: '2024-01-01',
    estimatedDeliveryDate: '2024-01-20',
    currency: 'USD',
    ...overrides,
  };
}

function makeLeg(overrides: Partial<ShipmentLeg> = {}): ShipmentLeg {
  return {
    id: 'leg-1',
    shipmentId: SHIPMENT_ID,
    legType: 'first_leg',
    sequenceNo: 1,
    carrierName: '头程货代',
    departureDate: '2024-01-01',
    arrivalDate: '2024-01-05',
    legCost: 1200,
    ...overrides,
  };
}

function makeCarton(overrides: Partial<CartonSpec> = {}): CartonSpec {
  return {
    id: 'carton-1',
    shipmentId: SHIPMENT_ID,
    boxLengthCm: 40,
    boxWidthCm: 30,
    boxHeightCm: 20,
    boxWeightKg: 5,
    unitsPerBox: 4,
    boxCount: 10,
    ...overrides,
  };
}

// ─── Default-resolution wiring ──────────────────────────────────────

/** Point every fetcher at a benign default so unrelated sections render. */
function setDefaults() {
  vi.mocked(fetchShipment).mockResolvedValue(makeShipment());
  vi.mocked(fetchShipmentLegs).mockResolvedValue([]);
  vi.mocked(fetchCartonSpecs).mockResolvedValue([]);
  vi.mocked(fetchCustomsClearance).mockResolvedValue({
    shipmentId: SHIPMENT_ID,
    clearanceStatus: 'not-started',
  } as CustomsClearance);
  vi.mocked(fetchTrackingEvents).mockResolvedValue([]);
  vi.mocked(fetchShipmentExceptions).mockResolvedValue([]);
  vi.mocked(fetchCostChain).mockResolvedValue({
    shipmentId: SHIPMENT_ID,
    reportingCurrency: 'USD',
    legCosts: [],
    handlingCosts: [],
    customsDutiesTaxes: 0,
    totalLandedCost: 0,
  } as CostChain);
  vi.mocked(fetchFbaFields).mockResolvedValue({ shipmentId: SHIPMENT_ID } as FbaFields);
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/fba-shipments/${SHIPMENT_ID}`]}>
        <Routes>
          <Route path="/fba-shipments/:id" element={<ShipmentDetailPage />} />
          <Route path="/fba-shipments" element={<div>货件列表</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ShipmentDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useStoreContext).mockReturnValue({
      stores: [{ id: 'amazon-store', name: 'Amazon US', platform: 'amazon' }],
      storeId: 'amazon-store',
      setStoreId: vi.fn(),
      loading: false,
      error: null,
      reload: vi.fn(),
    });
    setDefaults();
  });

  it('renders the shipment header with id and tracking number', async () => {
    renderPage();
    expect(await screen.findByText('FBA-001')).toBeInTheDocument();
    expect(screen.getByText('追踪号 TRK-999')).toBeInTheDocument();
    expect(screen.getByText('DHL')).toBeInTheDocument();
  });

  // ─── Transport legs (Req 4.4, 4.8) ────────────────────────────────

  it('renders transport legs ordered by sequence number', async () => {
    vi.mocked(fetchShipmentLegs).mockResolvedValue([
      makeLeg({ id: 'leg-2', sequenceNo: 2, legType: 'last_leg', carrierName: '尾程派送' }),
      makeLeg({ id: 'leg-1', sequenceNo: 1, legType: 'first_leg', carrierName: '头程货代' }),
    ]);

    renderPage();

    const first = await screen.findByText('头程货代');
    const second = screen.getByText('尾程派送');
    expect(first).toBeInTheDocument();
    expect(second).toBeInTheDocument();
    // Sequence 1 leg renders before sequence 2 leg in document order.
    expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // The leg type label is translated.
    expect(screen.getByText('头程')).toBeInTheDocument();
    expect(screen.getByText('尾程')).toBeInTheDocument();
  });

  it('shows the empty-leg state when there are no legs (Req 4.8)', async () => {
    vi.mocked(fetchShipmentLegs).mockResolvedValue([]);
    renderPage();
    expect(
      await screen.findByText('暂无运输段，添加头程/尾程以记录完整运输路径。'),
    ).toBeInTheDocument();
  });

  // ─── Carton specs + totals (Req 5.3, 5.4) ──────────────────────────

  it('renders carton specs with computed totals', async () => {
    vi.mocked(fetchCartonSpecs).mockResolvedValue([
      makeCarton({ id: 'c1', unitsPerBox: 4, boxCount: 10 }),
      makeCarton({ id: 'c2', unitsPerBox: 2, boxCount: 5 }),
    ]);

    renderPage();

    // Totals: boxes = 10 + 5 = 15; units = 4*10 + 2*5 = 50.
    expect(await screen.findByText('15')).toBeInTheDocument();
    expect(screen.getByText('50')).toBeInTheDocument();
  });

  it('shows zero totals and an empty message when there are no cartons (Req 5.4)', async () => {
    vi.mocked(fetchCartonSpecs).mockResolvedValue([]);
    renderPage();
    expect(
      await screen.findByText('暂无箱规记录。总箱数 0，总数量 0。'),
    ).toBeInTheDocument();
    // The header summary still reports the zero totals.
    expect(screen.getByText('总箱数')).toBeInTheDocument();
    expect(screen.getByText('总数量')).toBeInTheDocument();
  });

  // ─── Customs clearance (Req 7.5, 7.6) ──────────────────────────────

  it('shows the not-started customs state when clearance is absent (Req 7.6)', async () => {
    vi.mocked(fetchCustomsClearance).mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      clearanceStatus: 'not-started',
    } as CustomsClearance);

    renderPage();

    expect(await screen.findByText('未开始')).toBeInTheDocument();
  });

  it('renders customs details when clearance data is present', async () => {
    vi.mocked(fetchCustomsClearance).mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      clearanceStatus: 'cleared',
      declarationRef: 'DECL-123',
      dutiesTaxes: 300,
    } as CustomsClearance);

    renderPage();

    expect(await screen.findByText('已清关')).toBeInTheDocument();
    expect(screen.getByText('DECL-123')).toBeInTheDocument();
    expect(screen.getByText('$300')).toBeInTheDocument();
  });

  // ─── Tracking trajectory (Req 8.7, 8.8) ────────────────────────────

  it('renders tracking trajectory entries in supplied (most-recent-first) order', async () => {
    const events: TrackingEvent[] = [
      { id: 'ev-2', shipmentId: SHIPMENT_ID, eventTime: '2024-01-05T10:00:00Z', description: '已到达目的港' },
      { id: 'ev-1', shipmentId: SHIPMENT_ID, eventTime: '2024-01-01T08:00:00Z', description: '已揽收' },
    ];
    vi.mocked(fetchTrackingEvents).mockResolvedValue(events);

    renderPage();

    const newest = await screen.findByText('已到达目的港');
    const oldest = screen.getByText('已揽收');
    // Backend supplies newest-first; the page preserves that order.
    expect(newest.compareDocumentPosition(oldest) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('shows the empty-trajectory state when there are no events (Req 8.8)', async () => {
    vi.mocked(fetchTrackingEvents).mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('暂无物流轨迹记录。')).toBeInTheDocument();
  });

  // ─── Exceptions (Req 9.6) ──────────────────────────────────────────

  it('renders an exception indicator and counts only unresolved exceptions (Req 9.6)', async () => {
    const exceptions: ShipmentException[] = [
      { id: 'ex-1', shipmentId: SHIPMENT_ID, exceptionType: 'delay', description: '航班延误', resolutionState: 'open' },
      { id: 'ex-2', shipmentId: SHIPMENT_ID, exceptionType: 'damage', description: '已修复', resolutionState: 'resolved' },
    ];
    vi.mocked(fetchShipmentExceptions).mockResolvedValue(exceptions);

    renderPage();

    // One open exception drives the header indicator (resolved ones excluded).
    expect(await screen.findByText('1 个未处理异常')).toBeInTheDocument();
    // Exception rows render with translated type labels.
    expect(screen.getByText('延误')).toBeInTheDocument();
    expect(screen.getByText('货损')).toBeInTheDocument();
    expect(screen.getByText('航班延误')).toBeInTheDocument();
  });

  it('shows the empty-exception state when there are none', async () => {
    vi.mocked(fetchShipmentExceptions).mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('暂无异常记录。')).toBeInTheDocument();
    // No exception indicator in the header.
    expect(screen.queryByText(/个未处理异常/)).not.toBeInTheDocument();
  });

  // ─── Cost chain (Req 10.3) ─────────────────────────────────────────

  it('renders the itemized cost chain with leg costs, duties, handling, and total (Req 10.3)', async () => {
    vi.mocked(fetchShipmentLegs).mockResolvedValue([makeLeg({ id: 'leg-1', sequenceNo: 1 })]);
    vi.mocked(fetchCostChain).mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      reportingCurrency: 'USD',
      legCosts: [{ legId: 'leg-1', amount: 1200 }],
      handlingCosts: [{ id: 'h1', amount: 500 }],
      customsDutiesTaxes: 300,
      totalLandedCost: 2000,
    } as CostChain);

    renderPage();

    // Itemized lines. "关税及税费" appears both in the customs section and as a
    // cost-chain row, so assert at least one occurrence.
    expect((await screen.findAllByText('关税及税费')).length).toBeGreaterThan(0);
    expect(screen.getByText('操作费用')).toBeInTheDocument();
    expect(screen.getByText('合计落地物流成本')).toBeInTheDocument();
    // Total appears (header extra + footer row both show the formatted total).
    expect(screen.getAllByText('$2,000').length).toBeGreaterThan(0);
  });

  // ─── FBA fields + line items (Req 16.4) ────────────────────────────

  it('renders FBA core fields and line items (Req 16.4)', async () => {
    vi.mocked(fetchFbaFields).mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      fbaShipmentId: 'FBA15ABCDE',
      amazonShipmentStatus: 'WORKING',
      destinationFcCode: 'LAX9',
      lineItems: [
        { id: 'li-1', sku: 'SKU-001', msku: 'MSKU-001', asin: 'B000000001', quantity: 40 },
      ],
    } as FbaFields);

    renderPage();

    expect(await screen.findByText('FBA15ABCDE')).toBeInTheDocument();
    expect(screen.getByText('WORKING')).toBeInTheDocument();
    expect(screen.getByText('LAX9')).toBeInTheDocument();
    // Line item fields render.
    expect(screen.getByText('SKU-001')).toBeInTheDocument();
    expect(screen.getByText('MSKU-001')).toBeInTheDocument();
    expect(screen.getByText('B000000001')).toBeInTheDocument();
  });

  it('shows the empty FBA line-items state when there are none', async () => {
    vi.mocked(fetchFbaFields).mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      fbaShipmentId: 'FBA15ABCDE',
    } as FbaFields);

    renderPage();

    expect(await screen.findByText('暂无 FBA 行项目。')).toBeInTheDocument();
  });

  it('does not request or render FBA data for an independent-site shipment', async () => {
    vi.mocked(useStoreContext).mockReturnValue({
      stores: [{ id: 'shopify-store', name: 'Shopify', platform: 'shopify' }],
      storeId: 'shopify-store',
      setStoreId: vi.fn(),
      loading: false,
      error: null,
      reload: vi.fn(),
    });
    vi.mocked(fetchShipment).mockResolvedValue(makeShipment({ storeId: 'shopify-store' }));

    renderPage();

    expect(await screen.findByText('FBA-001')).toBeInTheDocument();
    expect(fetchFbaFields).not.toHaveBeenCalled();
    expect(screen.queryByText('FBA 信息')).not.toBeInTheDocument();
  });
});
