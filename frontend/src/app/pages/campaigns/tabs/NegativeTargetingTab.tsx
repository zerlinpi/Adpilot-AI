// 否定投放 (Negative Targeting) tab — recomposed on SharedDataTable (Req 1.2, 1.9).

import { Ban } from 'lucide-react';

import { fetchNegativeKeywords, type NegativeKeywordVo } from '../../../lib/api';
import { useApiQuery } from '../../../lib/hooks/useApiQuery';
import { qk } from '../../../lib/queryKeys';
import { SharedDataTable } from '../../../components/table/SharedDataTable';
import type { ColumnDef } from '../../../components/table/types';

import { StatusBadge, negativeScopeLabel } from './tabHelpers';

const columns: ColumnDef<NegativeKeywordVo>[] = [
  { key: 'keywordText', header: '否定词 / 目标', render: (n) => <span className="font-medium text-slate-800">{n.keywordText}</span> },
  { key: 'matchType', header: '匹配类型', render: (n) => n.matchType ?? '—' },
  { key: 'level', header: '范围', render: (n) => negativeScopeLabel(n.level) },
  { key: 'campaignName', header: '广告活动', render: (n) => n.campaignName ?? '—' },
  { key: 'source', header: '来源', render: (n) => n.source ?? '—' },
  { key: 'status', header: '状态', render: (n) => <StatusBadge status={n.status ?? ''} /> },
  { key: 'createdAt', header: '创建时间', render: (n) => n.createdAt ?? '—' },
];

export function NegativeTargetingTab({ storeId }: { storeId: string | null }) {
  const query = useApiQuery<NegativeKeywordVo[]>(
    qk.negativeKeywords(storeId ?? undefined),
    () => fetchNegativeKeywords({ storeId: storeId! }),
    { enabled: !!storeId },
  );

  const data = query.data ?? [];
  const loading = !!storeId && query.isLoading;
  const error = query.isError ? query.error?.message ?? '加载否定投放失败' : null;
  const reload = () => query.refetch();

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-500">{loading ? '加载中...' : `${data.length} 个否定投放`}</p>
      <SharedDataTable<NegativeKeywordVo>
        tableKey="campaigns.negativeTargeting"
        rows={data}
        columns={columns}
        rowId={(n) => n.id}
        loading={loading}
        error={error}
        onRetry={reload}
        onRefresh={reload}
        emptyState={
          <div className="flex flex-col items-center gap-2 py-16 text-slate-400">
            <Ban size={24} />
            <p className="text-sm">该店铺暂无否定投放数据。</p>
          </div>
        }
      />
    </div>
  );
}

export default NegativeTargetingTab;
