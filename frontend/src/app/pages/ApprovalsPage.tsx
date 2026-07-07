import { useState } from 'react';
import {
  ShieldCheck,
  CheckCircle2,
  XCircle,
  Eye,
  Filter,
  RefreshCw,
  Package,
  TrendingUp,
  Rocket,
  FileText,
} from 'lucide-react';

import { cn } from '../lib/utils';
import { fetchTasks, completeTask, dismissTask } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { ErpRiskBadge } from '../components/erp/ErpRiskBadge';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';

// ─── Type config ────────────────────────────────────────────────

const typeConfig: Record<string, { label: string; icon: React.ComponentType<{ className?: string }>; color: string }> = {
  recommendation: { label: '优化建议', icon: TrendingUp, color: 'bg-blue-50 text-blue-600' },
  keyword: { label: '关键词', icon: TrendingUp, color: 'bg-purple-50 text-purple-600' },
  listing: { label: 'Listing', icon: FileText, color: 'bg-emerald-50 text-emerald-600' },
  inventory: { label: '库存', icon: Package, color: 'bg-orange-50 text-orange-600' },
  upload: { label: '上传', icon: Rocket, color: 'bg-cyan-50 text-cyan-600' },
  profit: { label: '利润', icon: TrendingUp, color: 'bg-green-50 text-green-600' },
  ads: { label: '广告', icon: TrendingUp, color: 'bg-blue-50 text-blue-600' },
  approval: { label: '审批', icon: ShieldCheck, color: 'bg-indigo-50 text-indigo-600' },
};

const priorityConfig: Record<string, { label: string; color: string }> = {
  urgent: { label: '紧急', color: 'bg-red-50 text-red-700' },
  high: { label: '高', color: 'bg-orange-50 text-orange-700' },
  medium: { label: '中', color: 'bg-amber-50 text-amber-700' },
  low: { label: '低', color: 'bg-blue-50 text-blue-700' },
};

// ─── Loading Skeleton ──────────────────────────────────────────

function ApprovalsSkeleton() {
  return (
    <div className="space-y-4">
      <div className="h-8 w-40 bg-slate-200 rounded animate-pulse" />
      <div className="h-4 w-64 bg-slate-100 rounded animate-pulse" />
      <div className="grid grid-cols-3 gap-4">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="bg-white rounded-lg border border-slate-200 p-4 animate-pulse">
            <div className="h-3 w-16 bg-slate-200 rounded mb-2" />
            <div className="h-7 w-10 bg-slate-200 rounded" />
          </div>
        ))}
      </div>
      <div className="h-10 bg-white rounded-lg border border-slate-200 animate-pulse" />
      <ErpLoadingSkeleton rows={5} />
    </div>
  );
}

// ─── Main Page Component ───────────────────────────────────────

export function ApprovalsPage() {
  const { storeId, loading: storeLoading } = useStoreId();
  const [typeFilter, setTypeFilter] = useState('all');
  const [actingId, setActingId] = useState<string | null>(null);
  const [selectedTask, setSelectedTask] = useState<any | null>(null);
  // Page-level error raised by a failed approve/reject action (preserves the
  // original behavior where such a failure shows the full-page error state).
  const [actionError, setActionError] = useState<string | null>(null);

  const approvalsQuery = useApiQuery<any[]>(
    ['approvals', storeId],
    () => fetchTasks({ storeId: storeId!, status: 'waiting_approval' }).then((result: any) =>
      Array.isArray(result) ? result : result?.items || []),
    { enabled: !!storeId },
  );
  const tasks = approvalsQuery.data ?? [];
  const loading = !!storeId && approvalsQuery.isLoading;
  const error = actionError ?? (approvalsQuery.isError ? approvalsQuery.error?.message ?? '加载审批任务失败' : null);
  const loadTasks = () => { setActionError(null); return approvalsQuery.refetch(); };

  const filteredTasks = typeFilter === 'all'
    ? tasks
    : tasks.filter((t) => t.task_type === typeFilter || t.taskType === typeFilter);

  const counts = {
    total: tasks.length,
    high: tasks.filter((t) => t.risk_level === 'high' || t.riskLevel === 'high').length,
    medium: tasks.filter((t) => t.risk_level === 'medium' || t.riskLevel === 'medium').length,
  };

  const handleApprove = async (id: string) => {
    try {
      setActingId(id);
      await completeTask(id);
      await loadTasks();
    } catch (err: any) {
      setActionError(err.message || '审批失败');
    } finally {
      setActingId(null);
    }
  };

  const handleReject = async (id: string) => {
    try {
      setActingId(id);
      await dismissTask(id);
      await loadTasks();
    } catch (err: any) {
      setActionError(err.message || '拒绝失败');
    } finally {
      setActingId(null);
    }
  };

  if (storeLoading || loading) return <ApprovalsSkeleton />;

  if (error) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="审批管理" description="审核并处理待审批的操作" />
        <ErpErrorState message={error} onRetry={loadTasks} />
      </div>
    );
  }

  if (!storeId) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="审批管理" description="审核并处理待审批的操作" />
        <ErpEmptyState
          title="暂无可用店铺"
          description="请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。"
        />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="审批管理"
        description="审核并处理待审批的操作"
        actions={
          <button onClick={loadTasks} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-slate-600 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
            <RefreshCw size={14} />
            刷新
          </button>
        }
      />

      {/* Summary Cards */}
      <div className="grid grid-cols-3 gap-4">
        <div className="bg-white rounded-lg border border-slate-200 p-4">
          <p className="text-xs text-slate-500">待审批总数</p>
          <p className="text-xl font-semibold text-slate-900">{counts.total}</p>
        </div>
        <div className="bg-white rounded-lg border border-red-200 p-4">
          <p className="text-xs text-red-600">高风险</p>
          <p className="text-xl font-semibold text-red-700">{counts.high}</p>
        </div>
        <div className="bg-white rounded-lg border border-amber-200 p-4">
          <p className="text-xs text-amber-600">中风险</p>
          <p className="text-xl font-semibold text-amber-700">{counts.medium}</p>
        </div>
      </div>

      {/* Filter Bar */}
      <div className="flex items-center gap-3 bg-white rounded-lg border border-slate-200 px-4 py-2">
        <Filter size={14} className="text-slate-400" />
        <select
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value)}
          className="h-8 rounded border border-slate-200 bg-white px-2 text-sm text-slate-700 outline-none focus:border-blue-400"
        >
          <option value="all">全部类型</option>
          {Object.entries(typeConfig).map(([key, cfg]) => (
            <option key={key} value={key}>{cfg.label}</option>
          ))}
        </select>
        <span className="ml-auto text-xs text-slate-500">{filteredTasks.length} 条记录</span>
      </div>

      {/* Table */}
      {filteredTasks.length === 0 ? (
        <ErpEmptyState title="暂无待审批任务" description="当前没有需要审批的操作，所有任务已处理完毕" />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50">
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">标题</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">类型</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">优先级</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">风险等级</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">提交时间</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 uppercase tracking-wide">操作</th>
              </tr>
            </thead>
            <tbody>
              {filteredTasks.map((task) => {
                const taskType = task.task_type || task.taskType || 'approval';
                const tCfg = typeConfig[taskType] || typeConfig.approval;
                const TypeIcon = tCfg.icon;
                const priority = task.priority || 'medium';
                const pCfg = priorityConfig[priority] || priorityConfig.medium;
                const riskLevel = task.risk_level || task.riskLevel || 'low';
                const isActing = actingId === task.id;

                return (
                  <tr key={task.id} className="border-t border-slate-100 hover:bg-slate-50 transition-colors">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <div className={cn('w-7 h-7 rounded flex items-center justify-center', tCfg.color)}>
                          <TypeIcon className="w-3.5 h-3.5" />
                        </div>
                        <div className="min-w-0">
                          <p className="text-sm font-medium text-slate-800 truncate max-w-xs">{task.title}</p>
                          {task.description && (
                            <p className="text-xs text-slate-400 truncate max-w-xs">{task.description}</p>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn('px-2 py-0.5 rounded text-xs font-medium', tCfg.color)}>{tCfg.label}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn('px-2 py-0.5 rounded text-xs font-medium', pCfg.color)}>{pCfg.label}</span>
                    </td>
                    <td className="px-4 py-3">
                      <ErpRiskBadge level={riskLevel} />
                    </td>
                    <td className="px-4 py-3 text-xs text-slate-500">
                      {(() => {
                        const created = task.createdAt || task.created_at;
                        return created ? new Date(created).toLocaleDateString('zh-CN') : '--';
                      })()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1.5">
                        <button
                          onClick={() => handleApprove(task.id)}
                          disabled={isActing}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded transition-colors disabled:opacity-50"
                        >
                          <CheckCircle2 size={12} />
                          通过
                        </button>
                        <button
                          onClick={() => handleReject(task.id)}
                          disabled={isActing}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-red-700 bg-white border border-red-200 hover:bg-red-50 rounded transition-colors disabled:opacity-50"
                        >
                          <XCircle size={12} />
                          拒绝
                        </button>
                        <button
                          onClick={() => setSelectedTask(task)}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-slate-600 bg-white border border-slate-200 hover:bg-slate-50 rounded transition-colors"
                        >
                          <Eye size={12} />
                          详情
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Detail Modal */}
      {selectedTask && (
        <Dialog open onOpenChange={(o) => { if (!o) setSelectedTask(null); }}>
          <DialogContent className="block gap-0 w-full sm:max-w-lg rounded-xl border-0 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between mb-4">
              <DialogTitle className="text-base font-semibold text-slate-900">任务详情</DialogTitle>
            </div>
            <div className="space-y-3">
              <div>
                <p className="text-xs text-slate-500">标题</p>
                <p className="text-sm text-slate-800">{selectedTask.title}</p>
              </div>
              {selectedTask.description && (
                <div>
                  <p className="text-xs text-slate-500">描述</p>
                  <p className="text-sm text-slate-700">{selectedTask.description}</p>
                </div>
              )}
              <div className="flex gap-4">
                <div>
                  <p className="text-xs text-slate-500">优先级</p>
                  <span className={cn('px-2 py-0.5 rounded text-xs font-medium', priorityConfig[selectedTask.priority]?.color || priorityConfig.medium.color)}>
                    {priorityConfig[selectedTask.priority]?.label || selectedTask.priority}
                  </span>
                </div>
                <div>
                  <p className="text-xs text-slate-500">风险等级</p>
                  <ErpRiskBadge level={selectedTask.risk_level || selectedTask.riskLevel || 'low'} />
                </div>
                <div>
                  <p className="text-xs text-slate-500">状态</p>
                  <ErpStatusBadge status={selectedTask.status || 'waiting_approval'} />
                </div>
              </div>
              {(selectedTask.suggestedAction || selectedTask.suggested_action) && (
                <div>
                  <p className="text-xs text-slate-500">建议操作</p>
                  <p className="text-sm text-slate-700">{selectedTask.suggestedAction || selectedTask.suggested_action}</p>
                </div>
              )}
              {(selectedTask.expectedImpact || selectedTask.expected_impact) && (
                <div>
                  <p className="text-xs text-slate-500">预期影响</p>
                  <p className="text-sm text-emerald-700">{selectedTask.expectedImpact || selectedTask.expected_impact}</p>
                </div>
              )}
              <div>
                <p className="text-xs text-slate-500">创建时间</p>
                <p className="text-sm text-slate-600">{(() => {
                  const created = selectedTask.createdAt || selectedTask.created_at;
                  return created ? new Date(created).toLocaleString('zh-CN') : '--';
                })()}</p>
              </div>
            </div>
            <div className="flex justify-end gap-2 mt-6">
              <button onClick={() => setSelectedTask(null)} className="px-4 py-2 text-sm text-slate-600 bg-white border border-slate-200 rounded-lg hover:bg-slate-50">
                关闭
              </button>
              <button
                onClick={() => { handleApprove(selectedTask.id); setSelectedTask(null); }}
                className="px-4 py-2 text-sm text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg"
              >
                批准
              </button>
              <button
                onClick={() => { handleReject(selectedTask.id); setSelectedTask(null); }}
                className="px-4 py-2 text-sm text-red-700 bg-white border border-red-200 hover:bg-red-50 rounded-lg"
              >
                拒绝
              </button>
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
