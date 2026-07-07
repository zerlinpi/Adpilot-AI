import { useState } from 'react';
import {
  ListTodo, Search, CheckCircle2, XCircle, Eye, Loader2,
  RefreshCw,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { cn } from '../lib/utils';
import { fetchTasks, completeTask, dismissTask } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { ErpRiskBadge } from '../components/erp/ErpRiskBadge';
import { Sheet, SheetContent, SheetTitle } from '../components/ui/sheet';

const priorityConfig: Record<string, { label: string; color: string }> = {
  urgent: { label: '紧急', color: 'bg-red-100 text-red-700' },
  high: { label: '高', color: 'bg-orange-100 text-orange-700' },
  medium: { label: '中', color: 'bg-yellow-100 text-yellow-700' },
  low: { label: '低', color: 'bg-blue-100 text-blue-700' },
};

const typeConfig: Record<string, { label: string; color: string }> = {
  ads: { label: '广告', color: 'bg-blue-50 text-blue-600' },
  keyword: { label: '关键词', color: 'bg-purple-50 text-purple-600' },
  listing: { label: 'Listing', color: 'bg-emerald-50 text-emerald-600' },
  inventory: { label: '库存', color: 'bg-orange-50 text-orange-600' },
  profit: { label: '利润', color: 'bg-green-50 text-green-600' },
  upload: { label: '上传', color: 'bg-cyan-50 text-cyan-600' },
  data_quality: { label: '数据质量', color: 'bg-amber-50 text-amber-600' },
  approval: { label: '审批', color: 'bg-indigo-50 text-indigo-600' },
};

export function TasksPage() {
  const { storeId, loading: storeLoading } = useStoreId();
  const queryClient = useQueryClient();
  const [statusFilter, setStatusFilter] = useState('open');
  const [priorityFilter, setPriorityFilter] = useState('all');
  const [typeFilter, setTypeFilter] = useState('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [actingId, setActingId] = useState<string | null>(null);
  const [selectedTask, setSelectedTask] = useState<any | null>(null);
  // Page-level error raised by a failed complete/dismiss action (preserves the
  // original behavior where such a failure shows the full-page error state).
  const [actionError, setActionError] = useState<string | null>(null);

  const tasksKey = ['tasks', storeId, { statusFilter, priorityFilter, typeFilter }] as const;
  const tasksQuery = useApiQuery<any[]>(
    tasksKey,
    () => {
      const params: any = { storeId };
      if (statusFilter !== 'all') params.status = statusFilter;
      if (priorityFilter !== 'all') params.priority = priorityFilter;
      if (typeFilter !== 'all') params.taskType = typeFilter;
      return fetchTasks(params).then((result: any) => (Array.isArray(result) ? result : result?.items || []));
    },
    { enabled: !!storeId },
  );
  const tasks = tasksQuery.data ?? [];
  const loading = storeLoading || (!!storeId && tasksQuery.isLoading);
  const error = actionError ?? (tasksQuery.isError ? tasksQuery.error?.message ?? '加载任务失败' : null);
  const loadTasks = () => { setActionError(null); return tasksQuery.refetch(); };

  const handleComplete = async (id: string) => {
    setActingId(id);
    try {
      await completeTask(id);
      queryClient.setQueryData<any[]>(tasksKey, (prev) => (prev ?? []).map((t) => t.id === id ? { ...t, status: 'completed' } : t));
    } catch (err: any) { setActionError(err.message || '操作失败'); }
    finally { setActingId(null); }
  };

  const handleDismiss = async (id: string) => {
    setActingId(id);
    try {
      await dismissTask(id);
      queryClient.setQueryData<any[]>(tasksKey, (prev) => (prev ?? []).map((t) => t.id === id ? { ...t, status: 'dismissed' } : t));
    } catch (err: any) { setActionError(err.message || '操作失败'); }
    finally { setActingId(null); }
  };

  const filteredTasks = tasks.filter((t) => {
    if (searchQuery && !t.title?.toLowerCase().includes(searchQuery.toLowerCase())) return false;
    return true;
  });

  if (loading) return <div className="space-y-4"><ErpPageHeader title="全部任务" description="管理所有运营任务" /><ErpLoadingSkeleton rows={10} /></div>;
  if (error) return <ErpErrorState message={error} onRetry={loadTasks} />;
  if (!storeId) return (
    <div className="space-y-4">
      <ErpPageHeader title="全部任务" description="管理所有运营任务" />
      <div className="bg-white rounded-lg border border-slate-200">
        <ErpEmptyState
          title="暂无可用店铺"
          description="请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。"
          icon={<ListTodo size={24} className="text-slate-400" />}
        />
      </div>
    </div>
  );

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="全部任务"
        description={`共 ${tasks.length} 个任务`}
        actions={
          <button onClick={loadTasks} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
            <RefreshCw size={14} /> 刷新
          </button>
        }
      />

      {/* Filters */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-0.5">
          {['all', 'open', 'in_progress', 'waiting_approval', 'completed', 'dismissed'].map((s) => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={cn('px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                statusFilter === s ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700')}>
              {s === 'all' ? '全部' : s === 'open' ? '待处理' : s === 'in_progress' ? '处理中' : s === 'waiting_approval' ? '待审批' : s === 'completed' ? '已完成' : '已忽略'}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 rounded-lg px-2 py-1">
          <Search size={14} className="text-slate-400" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="搜索任务..."
            className="text-xs bg-transparent outline-none w-32 placeholder-slate-400"
          />
        </div>
      </div>

      {/* Table */}
      {filteredTasks.length === 0 ? (
        <ErpEmptyState title="暂无任务" description="没有匹配的任务" />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50">
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">任务</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-20">类型</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-20">优先级</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-20">风险</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-20">状态</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500 w-32">操作</th>
              </tr>
            </thead>
            <tbody>
              {filteredTasks.map((task) => {
                const priority = priorityConfig[task.priority] || priorityConfig.medium;
                const type = typeConfig[task.taskType] || typeConfig.ads;
                const isActing = actingId === task.id;

                return (
                  <tr key={task.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-3">
                      <p className="text-sm font-medium text-slate-900">{task.title}</p>
                      <p className="text-xs text-slate-500 mt-0.5 line-clamp-1">{task.description}</p>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', type.color)}>
                        {type.label}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', priority.color)}>
                        {priority.label}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <ErpRiskBadge level={task.riskLevel || 'low'} />
                    </td>
                    <td className="px-4 py-3">
                      <ErpStatusBadge status={task.status} />
                    </td>
                    <td className="px-4 py-3 text-right">
                      {task.status === 'open' || task.status === 'in_progress' ? (
                        <div className="flex items-center justify-end gap-1">
                          <button
                            onClick={() => handleComplete(task.id)}
                            disabled={isActing}
                            className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-emerald-700 bg-emerald-50 rounded hover:bg-emerald-100 transition-colors disabled:opacity-50"
                          >
                            {isActing ? <Loader2 size={11} className="animate-spin" /> : <CheckCircle2 size={11} />}
                            完成
                          </button>
                          <button
                            onClick={() => handleDismiss(task.id)}
                            disabled={isActing}
                            className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 bg-slate-50 rounded hover:bg-slate-100 transition-colors disabled:opacity-50"
                          >
                            <XCircle size={11} />
                            忽略
                          </button>
                          <button
                            onClick={() => setSelectedTask(task)}
                            className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 bg-slate-50 rounded hover:bg-slate-100 transition-colors"
                          >
                            <Eye size={11} />
                          </button>
                        </div>
                      ) : (
                        <span className="text-xs text-slate-400">—</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Detail Drawer */}
      {selectedTask && (
        <Sheet open onOpenChange={(o) => { if (!o) setSelectedTask(null); }}>
          <SheetContent side="right" className="w-full max-w-md sm:max-w-md gap-0 p-0 bg-white border-l border-slate-200 overflow-y-auto">
            <div className="sticky top-0 bg-white border-b border-slate-100 px-5 py-4 flex items-center justify-between">
              <SheetTitle className="text-base font-semibold text-slate-900">任务详情</SheetTitle>
            </div>
            <div className="p-5 space-y-4">
              <div>
                <p className="text-xs text-slate-500 mb-1">标题</p>
                <p className="text-sm text-slate-900 font-medium">{selectedTask.title}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500 mb-1">描述</p>
                <p className="text-sm text-slate-700">{selectedTask.description || '无'}</p>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-slate-500 mb-1">类型</p>
                  <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', typeConfig[selectedTask.taskType]?.color || 'bg-slate-100 text-slate-600')}>
                    {typeConfig[selectedTask.taskType]?.label || selectedTask.taskType}
                  </span>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">优先级</p>
                  <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', priorityConfig[selectedTask.priority]?.color || 'bg-slate-100 text-slate-600')}>
                    {priorityConfig[selectedTask.priority]?.label || selectedTask.priority}
                  </span>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">风险等级</p>
                  <ErpRiskBadge level={selectedTask.riskLevel || 'low'} />
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">状态</p>
                  <ErpStatusBadge status={selectedTask.status} />
                </div>
              </div>
              {selectedTask.expectedImpact && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">预期效果</p>
                  <p className="text-sm text-emerald-600">{selectedTask.expectedImpact}</p>
                </div>
              )}
              {selectedTask.suggestedAction && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">建议操作</p>
                  <p className="text-sm text-blue-600">{selectedTask.suggestedAction}</p>
                </div>
              )}
              {(selectedTask.status === 'open' || selectedTask.status === 'in_progress') && (
                <div className="flex items-center gap-2 pt-2 border-t border-slate-100">
                  <button
                    onClick={() => { handleComplete(selectedTask.id); setSelectedTask(null); }}
                    className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-emerald-600 rounded-lg hover:bg-emerald-700 transition-colors"
                  >
                    <CheckCircle2 size={14} /> 标记完成
                  </button>
                  <button
                    onClick={() => { handleDismiss(selectedTask.id); setSelectedTask(null); }}
                    className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors"
                  >
                    <XCircle size={14} /> 忽略
                  </button>
                </div>
              )}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
