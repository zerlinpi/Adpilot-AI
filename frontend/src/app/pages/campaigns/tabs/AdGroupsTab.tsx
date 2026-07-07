// 广告组 (Ad Groups) tab — extracted from CampaignsPage and recomposed on the
// reusable SharedDataTable building block (Req 1.2, 1.9). Read-only list with
// loading / empty / error+retry / refresh behavior preserved from the original.

import { Layers } from 'lucide-react';

import { fetchAdGroups, type AdGroupVo } from '../../../lib/api';
import { useApiQuery } from '../../../lib/hooks/useApiQuery';
import { qk } from '../../../lib/queryKeys';
import { cn, formatCurrency, formatNumber, formatPercent } from '../../../lib/utils';
import { SharedDataTable } from '../../../components/table/SharedDataTable';
import type { ColumnDef } from '../../../components/table/types';

import { StatusBadge, acosColor } from './tabHelpers';

const columns: ColumnDef<AdGroupVo>[] = [
  { key: 'name', header: '广告组名称', render: (g) => <span className="font-medium text-slate-800">{g.name}</span> },
  { key: 'campaignName', header: '广告活动', render: (g) => g.campaignName ?? '—' },
  { key: 'defaultBid', header: '默认竞价', render: (g) => formatCurrency(g.defaultBid) },
  { key: 'status', header: '状态', render: (g) => <StatusBadge status={g.status} /> },
  { key: 'keywordCount', header: '关键词数', render: (g) => formatNumber(g.keywordCount) },
  { key: 'spend', header: '花费', render: (g) => formatCurrency(g.spend) },
  { key: 'sales', header: '销售额', render: (g) => formatCurrency(g.sales) },
  {
    key: 'acos',
    header: 'ACoS',
    render: (g) => <span className={cn('font-medium', acosColor(g.acos))}>{formatPercent(g.acos)}</span>,
  },
];

export function AdGroupsTab({ storeId }: { storeId: string | null }) {
  const query = useApiQuery<AdGroupVo[]>(
    qk.adGroups(storeId ?? undefined),
    () => fetchAdGroups({ storeId: storeId! }),
    { enabled: !!storeId },
  );

  const data = query.data ?? [];
  const loading = !!storeId && query.isLoading;
  const error = query.isError ? query.error?.message ?? '加载广告组失败' : null;
  const reload = () => query.refetch();

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-500">{loading ? '加载中...' : `${data.length} 个广告组`}</p>
      <SharedDataTable<AdGroupVo>
        tableKey="campaigns.adGroups"
        rows={data}
        columns={columns}
        rowId={(g) => g.id}
        loading={loading}
        error={error}
        onRetry={reload}
        onRefresh={reload}
        emptyState={
          <div className="flex flex-col items-center gap-2 py-16 text-slate-400">
            <Layers size={24} />
            <p className="text-sm">该店铺暂无广告组数据。</p>
          </div>
        }
      />
    </div>
  );
}

export default AdGroupsTab;
