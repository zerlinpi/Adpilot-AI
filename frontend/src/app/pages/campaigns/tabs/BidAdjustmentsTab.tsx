// 竞价调整 (Bid Adjustments) tab — recomposed on SharedDataTable (Req 1.2, 1.9).

import { SlidersHorizontal } from 'lucide-react';

import { fetchBidChanges, type BidChangeVo } from '../../../lib/api';
import { useApiQuery } from '../../../lib/hooks/useApiQuery';
import { qk } from '../../../lib/queryKeys';
import { cn, formatCurrency } from '../../../lib/utils';
import { SharedDataTable } from '../../../components/table/SharedDataTable';
import type { ColumnDef } from '../../../components/table/types';

const columns: ColumnDef<BidChangeVo>[] = [
  { key: 'createdAt', header: '时间', render: (b) => b.createdAt ?? '—' },
  { key: 'campaignName', header: '广告活动', render: (b) => b.campaignName ?? '—' },
  { key: 'entityType', header: '对象类型', render: (b) => b.entityType ?? '—' },
  { key: 'oldBid', header: '原竞价', render: (b) => (b.oldBid != null ? formatCurrency(b.oldBid) : '—') },
  { key: 'newBid', header: '新竞价', render: (b) => (b.newBid != null ? formatCurrency(b.newBid) : '—') },
  {
    key: 'delta',
    header: '变化',
    render: (b) => {
      const delta = (b.newBid ?? 0) - (b.oldBid ?? 0);
      return (
        <span
          className={cn(
            'font-medium',
            delta > 0 ? 'text-emerald-600' : delta < 0 ? 'text-red-600' : 'text-slate-400',
          )}
        >
          {delta > 0 ? '+' : ''}
          {formatCurrency(delta)}
        </span>
      );
    },
  },
  { key: 'changeReason', header: '原因', render: (b) => b.changeReason ?? '—' },
  {
    key: 'automated',
    header: '来源',
    render: (b) => (
      <span
        className={cn(
          'inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold',
          b.automated ? 'bg-blue-100 text-blue-700' : 'bg-slate-100 text-slate-500',
        )}
      >
        {b.automated ? '自动' : '手动'}
      </span>
    ),
  },
];

export function BidAdjustmentsTab({ storeId }: { storeId: string | null }) {
  const query = useApiQuery<BidChangeVo[]>(
    qk.bidChanges(storeId ?? undefined),
    () => fetchBidChanges({ storeId: storeId! }),
    { enabled: !!storeId },
  );

  const data = query.data ?? [];
  const loading = !!storeId && query.isLoading;
  const error = query.isError ? query.error?.message ?? '加载竞价调整失败' : null;
  const reload = () => query.refetch();

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-500">{loading ? '加载中...' : `${data.length} 条竞价调整记录`}</p>
      <SharedDataTable<BidChangeVo>
        tableKey="campaigns.bidAdjustments"
        rows={data}
        columns={columns}
        rowId={(b) => b.id}
        loading={loading}
        error={error}
        onRetry={reload}
        onRefresh={reload}
        emptyState={
          <div className="flex flex-col items-center gap-2 py-16 text-slate-400">
            <SlidersHorizontal size={24} />
            <p className="text-sm">该店铺暂无竞价调整记录。</p>
          </div>
        }
      />
    </div>
  );
}

export default BidAdjustmentsTab;
