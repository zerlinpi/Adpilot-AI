// 操作日志 (Operation Log) tab — recomposed on SharedDataTable (Req 1.2, 1.9).

import { ScrollText } from 'lucide-react';

import { fetchOperationLog, type OperationLogVo } from '../../../lib/api';
import { useApiQuery } from '../../../lib/hooks/useApiQuery';
import { qk } from '../../../lib/queryKeys';
import { cn } from '../../../lib/utils';
import { SharedDataTable } from '../../../components/table/SharedDataTable';
import type { ColumnDef } from '../../../components/table/types';

import { operationStatusBadge } from './tabHelpers';

const columns: ColumnDef<OperationLogVo>[] = [
  { key: 'createdAt', header: '时间', render: (o) => o.createdAt ?? '—' },
  { key: 'actionType', header: '操作', render: (o) => <span className="font-medium text-slate-800">{o.actionType ?? '—'}</span> },
  { key: 'entityType', header: '对象类型', render: (o) => o.entityType ?? '—' },
  { key: 'source', header: '来源', render: (o) => o.source ?? '—' },
  {
    key: 'status',
    header: '状态',
    render: (o) => (
      <span
        className={cn(
          'inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold',
          operationStatusBadge(o.status),
        )}
      >
        {o.status ?? '—'}
      </span>
    ),
  },
  { key: 'riskLevel', header: '风险', render: (o) => o.riskLevel ?? '—' },
];

export function OperationLogTab({ storeId }: { storeId: string | null }) {
  const query = useApiQuery<OperationLogVo[]>(
    qk.operationLog(storeId ?? undefined),
    () => fetchOperationLog({ storeId: storeId! }),
    { enabled: !!storeId },
  );

  const data = query.data ?? [];
  const loading = !!storeId && query.isLoading;
  const error = query.isError ? query.error?.message ?? '加载操作日志失败' : null;
  const reload = () => query.refetch();

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-500">{loading ? '加载中...' : `${data.length} 条操作记录`}</p>
      <SharedDataTable<OperationLogVo>
        tableKey="campaigns.operationLog"
        rows={data}
        columns={columns}
        rowId={(o) => o.id}
        loading={loading}
        error={error}
        onRetry={reload}
        onRefresh={reload}
        emptyState={
          <div className="flex flex-col items-center gap-2 py-16 text-slate-400">
            <ScrollText size={24} />
            <p className="text-sm">该店铺暂无操作日志。</p>
          </div>
        }
      />
    </div>
  );
}

export default OperationLogTab;
