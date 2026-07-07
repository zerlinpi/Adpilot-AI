// 推广商品 (Promoted Products) tab and 购买的其他商品 (Other Products) tab share
// the same product-ad table shape; both are recomposed on SharedDataTable
// (Req 1.2, 1.9). Read-only lists with loading / empty / error+retry / refresh.

import type { ReactNode } from 'react';
import { Package, ShoppingCart } from 'lucide-react';

import {
  fetchOtherProducts,
  fetchProductAds,
  type ProductAdVo,
} from '../../../lib/api';
import { useApiQuery } from '../../../lib/hooks/useApiQuery';
import { qk } from '../../../lib/queryKeys';
import { cn, formatCurrency, formatNumber, formatPercent } from '../../../lib/utils';
import { SharedDataTable } from '../../../components/table/SharedDataTable';
import type { ColumnDef } from '../../../components/table/types';

import { acosColor } from './tabHelpers';

const columns: ColumnDef<ProductAdVo>[] = [
  { key: 'asin', header: 'ASIN', render: (p) => <span className="font-medium text-slate-800">{p.asin ?? '—'}</span> },
  { key: 'sku', header: 'SKU', render: (p) => p.sku ?? '—' },
  { key: 'campaignName', header: '广告活动', render: (p) => p.campaignName ?? '—' },
  { key: 'adGroupName', header: '广告组', render: (p) => p.adGroupName ?? '—' },
  { key: 'impressions', header: '曝光量', render: (p) => formatNumber(p.impressions) },
  { key: 'clicks', header: '点击量', render: (p) => formatNumber(p.clicks) },
  { key: 'spend', header: '花费', render: (p) => formatCurrency(p.spend) },
  { key: 'sales', header: '销售额', render: (p) => formatCurrency(p.sales) },
  { key: 'orders', header: '订单数', render: (p) => formatNumber(p.orders) },
  {
    key: 'acos',
    header: 'ACoS',
    render: (p) => <span className={cn('font-medium', acosColor(p.acos))}>{formatPercent(p.acos)}</span>,
  },
];

/** Stable row id for an aggregated product-ad row (no server id). */
function productRowId(p: ProductAdVo): string {
  return `${p.asin ?? ''}-${p.sku ?? ''}-${p.campaignName ?? ''}-${p.adGroupName ?? ''}`;
}

function ProductAdTab({
  tableKey,
  summary,
  rows,
  loading,
  error,
  reload,
  emptyText,
  emptyIcon,
}: {
  tableKey: string;
  summary: string;
  rows: ProductAdVo[];
  loading: boolean;
  error: string | null;
  reload: () => void;
  emptyText: string;
  emptyIcon: ReactNode;
}) {
  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-500">{loading ? '加载中...' : summary}</p>
      <SharedDataTable<ProductAdVo>
        tableKey={tableKey}
        rows={rows}
        columns={columns}
        rowId={productRowId}
        loading={loading}
        error={error}
        onRetry={reload}
        onRefresh={reload}
        emptyState={
          <div className="flex flex-col items-center gap-2 py-16 text-slate-400">
            {emptyIcon}
            <p className="text-sm">{emptyText}</p>
          </div>
        }
      />
    </div>
  );
}

export function PromotedProductsTab({ storeId }: { storeId: string | null }) {
  const query = useApiQuery<ProductAdVo[]>(
    qk.productAds(storeId ?? undefined),
    () => fetchProductAds({ storeId: storeId! }),
    { enabled: !!storeId },
  );
  const data = query.data ?? [];
  return (
    <ProductAdTab
      tableKey="campaigns.promotedProducts"
      summary={`${data.length} 个推广商品`}
      rows={data}
      loading={!!storeId && query.isLoading}
      error={query.isError ? query.error?.message ?? '加载推广商品失败' : null}
      reload={() => query.refetch()}
      emptyText="该店铺暂无推广商品数据。导入广告商品报告后将在此展示。"
      emptyIcon={<Package size={24} />}
    />
  );
}

export function OtherProductsTab({ storeId }: { storeId: string | null }) {
  const query = useApiQuery<ProductAdVo[]>(
    qk.otherProducts(storeId ?? undefined),
    () => fetchOtherProducts({ storeId: storeId! }),
    { enabled: !!storeId },
  );
  const data = query.data ?? [];
  return (
    <ProductAdTab
      tableKey="campaigns.otherProducts"
      summary={`${data.length} 个购买商品`}
      rows={data}
      loading={!!storeId && query.isLoading}
      error={query.isError ? query.error?.message ?? '加载购买商品失败' : null}
      reload={() => query.refetch()}
      emptyText="暂无通过广告购买的其他商品数据。"
      emptyIcon={<ShoppingCart size={24} />}
    />
  );
}

export default PromotedProductsTab;
