// SP预算上限 (Budget Caps) tab — recomposed on SharedDataTable (Req 1.2, 1.9).
// Lists SP campaigns with their budget utilization bar, preserved from the
// original inline implementation.

import { Wallet } from 'lucide-react';

import { fetchCampaigns, type CampaignVo } from '../../../lib/api';
import { useApiQuery } from '../../../lib/hooks/useApiQuery';
import { qk } from '../../../lib/queryKeys';
import { cn, formatCurrency } from '../../../lib/utils';
import { SharedDataTable } from '../../../components/table/SharedDataTable';
import type { ColumnDef } from '../../../components/table/types';

import { StatusBadge, budgetTypeLabel } from './tabHelpers';

const columns: ColumnDef<CampaignVo>[] = [
  { key: 'name', header: '广告活动', render: (c) => <span className="font-medium text-slate-800">{c.name}</span> },
  { key: 'status', header: '状态', render: (c) => <StatusBadge status={c.status} /> },
  { key: 'budget', header: '预算', render: (c) => ((c.budget ?? 0) > 0 ? formatCurrency(c.budget ?? 0) : '—') },
  { key: 'budgetType', header: '预算类型', render: (c) => budgetTypeLabel(c.budgetType) },
  { key: 'spend', header: '已花费', render: (c) => formatCurrency(c.spend) },
  {
    key: 'utilization',
    header: '预算使用率',
    render: (c) => {
      const budget = c.budget ?? 0;
      if (budget <= 0) return <span className="text-xs text-slate-400">—</span>;
      const util = Math.min((c.spend / budget) * 100, 999);
      const utilColor = util >= 100 ? 'bg-red-500' : util >= 80 ? 'bg-orange-500' : 'bg-emerald-500';
      return (
        <div className="flex items-center gap-2 min-w-[120px]">
          <div className="flex-1 h-2 rounded-full bg-slate-100">
            <div
              className={cn('h-full rounded-full transition-all', utilColor)}
              style={{ width: `${Math.min(util, 100)}%` }}
            />
          </div>
          <span className="text-xs font-medium text-slate-600 w-12 text-right">{util.toFixed(0)}%</span>
        </div>
      );
    },
  },
];

export function BudgetCapsTab({ storeId }: { storeId: string | null }) {
  const params = { adType: 'SP', pageSize: 200 };
  const query = useApiQuery<CampaignVo[]>(
    qk.campaigns(storeId ?? undefined, params),
    () => fetchCampaigns({ storeId: storeId!, ...params }).then((r) => r.items ?? []),
    { enabled: !!storeId },
  );

  const data = query.data ?? [];
  const loading = !!storeId && query.isLoading;
  const error = query.isError ? query.error?.message ?? '加载预算数据失败' : null;
  const reload = () => query.refetch();

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-500">{loading ? '加载中...' : `${data.length} 个 SP 广告活动`}</p>
      <SharedDataTable<CampaignVo>
        tableKey="campaigns.budgetCaps"
        rows={data}
        columns={columns}
        rowId={(c) => c.id}
        loading={loading}
        error={error}
        onRetry={reload}
        onRefresh={reload}
        emptyState={
          <div className="flex flex-col items-center gap-2 py-16 text-slate-400">
            <Wallet size={24} />
            <p className="text-sm">该店铺暂无 SP 广告活动预算数据。</p>
          </div>
        }
      />
    </div>
  );
}

export default BudgetCapsTab;
