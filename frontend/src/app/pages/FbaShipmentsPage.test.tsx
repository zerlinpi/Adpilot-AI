import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { FbaShipmentsPage } from './FbaShipmentsPage';
import { useStoreContext } from '../lib/StoreContext';
import { usePermissions } from '../lib/PermissionContext';
import { useApiMutation, useApiQuery } from '../lib/hooks/useApiQuery';

vi.mock('../lib/StoreContext', () => ({ useStoreContext: vi.fn() }));
vi.mock('../lib/PermissionContext', () => ({ usePermissions: vi.fn() }));
vi.mock('../lib/hooks/useApiQuery', () => ({
  useApiQuery: vi.fn(),
  useApiMutation: vi.fn(),
}));

const shipment = {
  id: 'shipment-1',
  shipmentId: 'SHIP-001',
  shipmentType: 'small_parcel',
  status: 'in_transit',
  carrier: 'DHL',
  trackingNumber: 'TRACK-1',
  totalItems: 10,
  shippingCost: 120,
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <FbaShipmentsPage />
    </QueryClientProvider>,
  );
}

function setStore(platform: string) {
  vi.mocked(useStoreContext).mockReturnValue({
    stores: [{ id: 'store-1', name: 'Store', platform }],
    storeId: 'store-1',
    setStoreId: vi.fn(),
    loading: false,
    error: null,
    reload: vi.fn(),
  });
}

function setPermission(canManage: boolean) {
  vi.mocked(usePermissions).mockReturnValue({
    user: null,
    permissions: canManage ? ['warehouse:view', 'warehouse:manage'] : ['warehouse:view'],
    platformAccess: { families: ['amazon', 'independent_site', 'logistics', 'finance'], superAdmin: false },
    storeGroupScope: null,
    loading: false,
    error: null,
    can: (permission: string) => permission === 'warehouse:view' || canManage,
    canAny: () => canManage,
    canAll: () => canManage,
    refresh: vi.fn(),
  });
}

describe('FbaShipmentsPage channel and permission behavior', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setStore('amazon');
    setPermission(false);
    vi.mocked(useApiQuery).mockImplementation((key: readonly unknown[]) => ({
      data: key[0] === 'shipments' ? [shipment] : [],
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    }) as any);
    vi.mocked(useApiMutation).mockReturnValue({
      isPending: false,
      mutate: vi.fn(),
      mutateAsync: vi.fn(),
    } as any);
  });

  it('uses Amazon terminology but hides write actions from view-only users', () => {
    renderPage();

    expect(screen.getByText('FBA 货件')).toBeInTheDocument();
    expect(screen.getByTitle('查看')).toBeInTheDocument();
    expect(screen.queryByText('新增货件')).not.toBeInTheDocument();
    expect(screen.queryByTitle('编辑')).not.toBeInTheDocument();
  });

  it('shows write actions to warehouse managers', () => {
    setPermission(true);
    renderPage();

    expect(screen.getByText('新增货件')).toBeInTheDocument();
    expect(screen.getByTitle('编辑')).toBeInTheDocument();
  });

  it('uses generic logistics terminology for independent-site stores', () => {
    setStore('shopify');
    renderPage();

    expect(screen.getByText('物流货件')).toBeInTheDocument();
    expect(screen.queryByText('FBA 货件')).not.toBeInTheDocument();
  });
});
