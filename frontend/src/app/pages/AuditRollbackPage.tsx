import { useState, useMemo, Fragment } from 'react';
import {
  History,
  Filter,
  RefreshCw,
  ChevronDown,
  ChevronUp,
} from 'lucide-react';

import { cn } from '../lib/utils';
import { fetchAuditLogs } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';

// ─── Status config ──────────────────────────────────────────────

const actionConfig: Record<string, { label: string; color: string }> = {
  login: { label: '登录', color: 'bg-blue-50 text-blue-600' },
  create: { label: '创建', color: 'bg-emerald-50 text-emerald-600' },
  update: { label: '更新', color: 'bg-amber-50 text-amber-600' },
  delete: { label: '删除', color: 'bg-red-50 text-red-600' },
  generate: { label: '生成', color: 'bg-purple-50 text-purple-600' },
  apply: { label: '应用', color: 'bg-teal-50 text-teal-600' },
  rollback: { label: '回滚', color: 'bg-orange-50 text-orange-600' },
  approve: { label: '审批', color: 'bg-indigo-50 text-indigo-600' },
  ignore: { label: '忽略', color: 'bg-slate-50 text-slate-600' },
};

const entityTypeConfig: Record<string, { label: string; color: string }> = {
  user: { label: '用户', color: 'bg-blue-50 text-blue-600' },
  store: { label: '店铺', color: 'bg-teal-50 text-teal-600' },
  product: { label: '商品', color: 'bg-emerald-50 text-emerald-600' },
  campaign: { label: '广告活动', color: 'bg-purple-50 text-purple-600' },
  keyword: { label: '关键词', color: 'bg-violet-50 text-violet-600' },
  report: { label: '报告', color: 'bg-amber-50 text-amber-600' },
  recommendation: { label: '建议', color: 'bg-blue-50 text-blue-600' },
  task: { label: '任务', color: 'bg-orange-50 text-orange-600' },
  approval: { label: '审批', color: 'bg-indigo-50 text-indigo-600' },
};

// ─── Main Page Component ───────────────────────────────────────

export function AuditRollbackPage() {
  const [actionFilter, setActionFilter] = useState('all');
  const [entityTypeFilter, setEntityTypeFilter] = useState('all');
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const logsQuery = useApiQuery<any[]>(
    ['audit-logs', { actionFilter, entityTypeFilter }],
    () => {
      const params: any = {};
      if (actionFilter !== 'all') params.action = actionFilter;
      if (entityTypeFilter !== 'all') params.entityType = entityTypeFilter;
      return fetchAuditLogs(params).then((result: any) => (Array.isArray(result) ? result : []));
    },
  );
  const logs = logsQuery.data ?? [];
  const loading = logsQuery.isLoading;
  const error = logsQuery.isError ? logsQuery.error?.message ?? '加载审计日志失败' : null;
  const loadLogs = () => logsQuery.refetch();

  const filteredLogs = useMemo(() => {
    return logs;
  }, [logs]);

  if (loading) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="审计日志" description="查看系统操作记录和回滚状态" />
        <ErpLoadingSkeleton rows={8} />
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="审计日志" description="查看系统操作记录和回滚状态" />
        <ErpErrorState message={error} onRetry={loadLogs} />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="审计日志"
        description="查看系统操作记录和回滚状态"
        actions={
          <button onClick={loadLogs} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-slate-600 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
            <RefreshCw size={14} />
            刷新
          </button>
        }
      />

      {/* Filter Bar */}
      <div className="flex items-center gap-3 bg-white rounded-lg border border-slate-200 px-4 py-2">
        <Filter size={14} className="text-slate-400" />
        <select
          value={actionFilter}
          onChange={(e) => setActionFilter(e.target.value)}
          className="h-8 rounded border border-slate-200 bg-white px-2 text-sm text-slate-700 outline-none focus:border-blue-400"
        >
          <option value="all">全部操作</option>
          {Object.entries(actionConfig).map(([key, cfg]) => (
            <option key={key} value={key}>{cfg.label}</option>
          ))}
        </select>
        <select
          value={entityTypeFilter}
          onChange={(e) => setEntityTypeFilter(e.target.value)}
          className="h-8 rounded border border-slate-200 bg-white px-2 text-sm text-slate-700 outline-none focus:border-blue-400"
        >
          <option value="all">全部实体</option>
          {Object.entries(entityTypeConfig).map(([key, cfg]) => (
            <option key={key} value={key}>{cfg.label}</option>
          ))}
        </select>
        <span className="ml-auto text-xs text-slate-500">{filteredLogs.length} 条记录</span>
      </div>

      {/* Table */}
      {filteredLogs.length === 0 ? (
        <ErpEmptyState title="暂无审计记录" description="当前筛选条件下没有找到审计日志" icon={<History size={24} className="text-slate-400" />} />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50">
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide w-8"></th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">时间</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">操作</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">实体类型</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">实体ID</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">来源</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">状态</th>
              </tr>
            </thead>
            <tbody>
              {filteredLogs.map((log) => {
                const action = log.action || 'update';
                const actionCode = String(action).toLowerCase();
                // Prefer the backend-provided human-readable label (always
                // non-empty); fall back to local config or a humanized code.
                const aColor = actionConfig[actionCode]?.color || 'bg-slate-50 text-slate-600';
                const aLabel = log.actionLabel || actionConfig[actionCode]?.label || action;
                const entityType = log.entityType || log.entity_type || '';
                const entityCode = String(entityType).toLowerCase();
                const eColor = entityTypeConfig[entityCode]?.color || 'bg-slate-50 text-slate-600';
                const eLabel = log.entityTypeLabel || entityTypeConfig[entityCode]?.label || entityType || '--';
                const isExpanded = expandedId === log.id;
                const createdAt = log.createdAt || log.created_at;
                const entityId = log.entityId || log.entity_id;
                const newData = log.newData || log.new_data;
                const oldData = log.oldData || log.old_data;
                const hasDetails = newData || oldData;

                return (
                  <Fragment key={log.id}>
                    <tr className="border-t border-slate-100 hover:bg-slate-50 transition-colors">
                      <td className="px-4 py-3">
                        {hasDetails && (
                          <button onClick={() => setExpandedId(isExpanded ? null : log.id)} className="p-0.5">
                            {isExpanded ? <ChevronUp size={14} className="text-slate-400" /> : <ChevronDown size={14} className="text-slate-400" />}
                          </button>
                        )}
                      </td>
                      <td className="px-4 py-3 text-xs text-slate-500 whitespace-nowrap">
                        {createdAt ? new Date(createdAt).toLocaleString('zh-CN') : '--'}
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn('px-2 py-0.5 rounded text-xs font-medium', aColor)}>{aLabel}</span>
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn('px-2 py-0.5 rounded text-xs font-medium', eColor)}>{eLabel}</span>
                      </td>
                      <td className="px-4 py-3 text-xs text-slate-600 font-mono max-w-[160px] truncate">
                        {entityId || '--'}
                      </td>
                      <td className="px-4 py-3 text-xs text-slate-500">
                        {log.source || 'app'}
                      </td>
                      <td className="px-4 py-3">
                        <ErpStatusBadge status="success" />
                      </td>
                    </tr>
                    {isExpanded && hasDetails && (
                      <tr className="bg-slate-50">
                        <td colSpan={7} className="px-4 py-3">
                          <div className="grid grid-cols-2 gap-4">
                            <div>
                              <p className="text-xs font-medium text-slate-500 mb-1">变更前</p>
                              <pre className="text-xs text-slate-600 bg-white rounded border border-slate-200 p-2 overflow-auto max-h-32">
                                {JSON.stringify(oldData || {}, null, 2)}
                              </pre>
                            </div>
                            <div>
                              <p className="text-xs font-medium text-slate-500 mb-1">变更后</p>
                              <pre className="text-xs text-slate-600 bg-white rounded border border-slate-200 p-2 overflow-auto max-h-32">
                                {JSON.stringify(newData || {}, null, 2)}
                              </pre>
                            </div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

