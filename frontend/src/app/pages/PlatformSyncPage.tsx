import { useState, useEffect } from 'react';
import {
  RefreshCw, RotateCcw, XCircle, Eye, Loader2, Database,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { cn } from '../lib/utils';
import { fetchSyncJobs, retrySyncJob, cancelSyncJob, startStoreSync, fetchStores } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { Sheet, SheetContent, SheetTitle } from '../components/ui/sheet';

const platformLabels: Record<string, string> = {
  amazon_ads: 'Amazon Ads',
  amazon_sp_api: 'Amazon SP-API',
  google_ads: 'Google Ads',
  shopify: 'Shopify',
  woocommerce: 'WooCommerce',
  tiktok_shop: 'TikTok Shop',
};

const ENTITY_TYPES: { value: string; label: string }[] = [
  { value: 'order', label: '订单' },
  { value: 'product', label: '商品' },
  { value: 'inventory', label: '库存' },
];

const syncTypeLabels: Record<string, string> = {
  orders: '订单同步',
  products: '商品同步',
  inventory: '库存同步',
  ads_campaigns: '广告活动同步',
  ads_keywords: '广告关键词同步',
  reports: '报告同步',
  full: '全量同步',
};

export function PlatformSyncPage() {
  const queryClient = useQueryClient();
  const [statusFilter, setStatusFilter] = useState('all');
  const [platformFilter, setPlatformFilter] = useState('all');
  const [actingId, setActingId] = useState<string | null>(null);
  const [selectedJob, setSelectedJob] = useState<any | null>(null);
  // Page-level error raised by a failed retry/cancel action (preserves the
  // original behavior where such a failure shows the full-page error state).
  const [actionError, setActionError] = useState<string | null>(null);

  // Immediate-sync panel
  const [stores, setStores] = useState<any[]>([]);
  const [syncStoreId, setSyncStoreId] = useState('');
  const [syncEntityType, setSyncEntityType] = useState('order');
  const [syncing, setSyncing] = useState(false);
  const [syncMsg, setSyncMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const jobsKey = ['sync-jobs', { statusFilter, platformFilter }] as const;
  const jobsQuery = useApiQuery<any[]>(
    jobsKey,
    () => {
      const params: any = {};
      if (statusFilter !== 'all') params.status = statusFilter;
      if (platformFilter !== 'all') params.platform = platformFilter;
      return fetchSyncJobs(params).then((result: any) => (Array.isArray(result) ? result : result?.items || []));
    },
  );
  const jobs = jobsQuery.data ?? [];
  const loading = jobsQuery.isLoading;
  const error = actionError ?? (jobsQuery.isError ? jobsQuery.error?.message ?? '加载同步任务失败' : null);
  const loadJobs = () => { setActionError(null); return jobsQuery.refetch(); };

  useEffect(() => {
    fetchStores().then((s) => {
      const list = Array.isArray(s) ? s : [];
      setStores(list);
      if (list.length > 0) setSyncStoreId(list[0].id);
    }).catch(() => setStores([]));
  }, []);

  const handleStartSync = async () => {
    if (!syncStoreId) {
      setSyncMsg({ ok: false, text: '请选择店铺' });
      return;
    }
    setSyncing(true);
    setSyncMsg(null);
    try {
      await startStoreSync(syncStoreId, { entityType: syncEntityType });
      setSyncMsg({ ok: true, text: '同步任务已创建,稍后刷新查看结果' });
      loadJobs();
    } catch (err: any) {
      setSyncMsg({ ok: false, text: err?.message || '触发同步失败(请确认该店铺已连接对应平台)' });
    } finally {
      setSyncing(false);
    }
  };

  const handleRetry = async (jobId: string) => {
    setActingId(jobId);
    try {
      await retrySyncJob(jobId);
      queryClient.setQueryData<any[]>(jobsKey, (prev) => (prev ?? []).map((j) => (j.id === jobId ? { ...j, status: 'waiting' } : j)));
    } catch (err: any) {
      setActionError(err.message || '重试失败');
    } finally {
      setActingId(null);
    }
  };

  const handleCancel = async (jobId: string) => {
    setActingId(jobId);
    try {
      await cancelSyncJob(jobId);
      queryClient.setQueryData<any[]>(jobsKey, (prev) => (prev ?? []).map((j) => (j.id === jobId ? { ...j, status: 'cancelled' } : j)));
    } catch (err: any) {
      setActionError(err.message || '取消失败');
    } finally {
      setActingId(null);
    }
  };

  const platforms = [...new Set(jobs.map((j) => j.platform))];

  if (loading) return <div className="space-y-4"><ErpPageHeader title="平台同步" description="管理平台数据同步任务" /><ErpLoadingSkeleton rows={10} /></div>;
  if (error) return <ErpErrorState message={error} onRetry={loadJobs} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="平台同步"
        description={`共 ${jobs.length} 个同步任务`}
        actions={
          <button onClick={loadJobs} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
            <RefreshCw size={14} /> 刷新
          </button>
        }
      />

      {/* Immediate sync trigger — makes a connected platform actually pull data */}
      <div className="bg-white rounded-lg border border-slate-200 p-4">
        <div className="flex items-center gap-2 mb-3">
          <Database size={15} className="text-blue-600" />
          <h2 className="text-sm font-semibold text-slate-900">立即同步</h2>
          <span className="text-xs text-slate-400">从已连接的平台拉取订单 / 商品 / 库存</span>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">店铺</label>
            <select
              value={syncStoreId}
              onChange={(e) => setSyncStoreId(e.target.value)}
              className="h-9 px-3 rounded-lg border border-slate-200 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 min-w-[180px]"
            >
              {stores.length === 0 && <option value="">无可用店铺</option>}
              {stores.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">数据类型</label>
            <select
              value={syncEntityType}
              onChange={(e) => setSyncEntityType(e.target.value)}
              className="h-9 px-3 rounded-lg border border-slate-200 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            >
              {ENTITY_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
          </div>
          <button
            onClick={handleStartSync}
            disabled={syncing || !syncStoreId}
            className="inline-flex items-center gap-2 h-9 px-4 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 disabled:opacity-60"
          >
            {syncing ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
            立即同步
          </button>
          {syncMsg && (
            <span className={cn('text-xs', syncMsg.ok ? 'text-emerald-600' : 'text-red-600')}>{syncMsg.text}</span>
          )}
        </div>
      </div>

      {/* Filters */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-0.5">
          {['all', 'waiting', 'running', 'success', 'failed'].map((s) => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={cn('px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                statusFilter === s ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700')}>
              {s === 'all' ? '全部' : s === 'waiting' ? '等待中' : s === 'running' ? '运行中' : s === 'success' ? '成功' : '失败'}
            </button>
          ))}
        </div>
        {platforms.length > 0 && (
          <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-0.5">
            <button onClick={() => setPlatformFilter('all')}
              className={cn('px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                platformFilter === 'all' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700')}>
              全部平台
            </button>
            {platforms.map((p) => (
              <button key={p} onClick={() => setPlatformFilter(p)}
                className={cn('px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                  platformFilter === p ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700')}>
                {platformLabels[p] || p}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Table */}
      {jobs.length === 0 ? (
        <ErpEmptyState
          title="暂无同步任务"
          description="没有匹配的同步任务记录"
          icon={<Database size={24} className="text-slate-400" />}
        />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50">
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">同步任务ID</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-28">平台</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-28">同步类型</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-20">状态</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-36">开始时间</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-36">完成时间</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500 w-24">处理记录数</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-28">错误信息</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500 w-28">操作</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => {
                const isActing = actingId === job.id;
                const canRetry = job.status === 'failed';
                const canCancel = job.status === 'waiting' || job.status === 'running';

                return (
                  <tr key={job.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-3">
                      <span className="text-sm font-mono text-slate-900">{job.id}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-sm text-slate-700">{platformLabels[job.platform] || job.platform || '-'}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-sm text-slate-700">{syncTypeLabels[job.syncType] || job.syncType || '-'}</span>
                    </td>
                    <td className="px-4 py-3">
                      <ErpStatusBadge status={job.status} />
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs text-slate-500">
                        {job.startTime ? new Date(job.startTime).toLocaleString('zh-CN') : '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs text-slate-500">
                        {job.endTime ? new Date(job.endTime).toLocaleString('zh-CN') : '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <span className="text-sm font-medium text-slate-700">
                        {job.processedRecords != null ? job.processedRecords.toLocaleString() : '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      {job.errorMessage ? (
                        <span className="text-xs text-red-600 truncate max-w-[120px] block" title={job.errorMessage}>
                          {job.errorMessage}
                        </span>
                      ) : (
                        <span className="text-xs text-slate-400">-</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => setSelectedJob(job)}
                          className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 bg-slate-50 rounded hover:bg-slate-100 transition-colors"
                        >
                          <Eye size={11} />
                        </button>
                        {canRetry && (
                          <button
                            onClick={() => handleRetry(job.id)}
                            disabled={isActing}
                            className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-blue-700 bg-blue-50 rounded hover:bg-blue-100 transition-colors disabled:opacity-50"
                          >
                            {isActing ? <Loader2 size={11} className="animate-spin" /> : <RotateCcw size={11} />}
                            重试
                          </button>
                        )}
                        {canCancel && (
                          <button
                            onClick={() => handleCancel(job.id)}
                            disabled={isActing}
                            className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-red-700 bg-red-50 rounded hover:bg-red-100 transition-colors disabled:opacity-50"
                          >
                            {isActing ? <Loader2 size={11} className="animate-spin" /> : <XCircle size={11} />}
                            取消
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Detail Drawer */}
      {selectedJob && (
        <Sheet open onOpenChange={(o) => { if (!o) setSelectedJob(null); }}>
          <SheetContent side="right" className="w-full max-w-md sm:max-w-md gap-0 p-0 bg-white border-l border-slate-200 overflow-y-auto">
            <div className="sticky top-0 bg-white border-b border-slate-100 px-5 py-4 flex items-center justify-between">
              <SheetTitle className="text-base font-semibold text-slate-900">同步任务详情</SheetTitle>
            </div>
            <div className="p-5 space-y-4">
              <div>
                <p className="text-xs text-slate-500 mb-1">任务ID</p>
                <p className="text-sm font-mono text-slate-900 font-medium">{selectedJob.id}</p>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-slate-500 mb-1">平台</p>
                  <p className="text-sm text-slate-700">{platformLabels[selectedJob.platform] || selectedJob.platform || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">同步类型</p>
                  <p className="text-sm text-slate-700">{syncTypeLabels[selectedJob.syncType] || selectedJob.syncType || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">状态</p>
                  <ErpStatusBadge status={selectedJob.status} />
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">处理记录数</p>
                  <p className="text-sm font-medium text-slate-700">
                    {selectedJob.processedRecords != null ? selectedJob.processedRecords.toLocaleString() : '-'}
                  </p>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-slate-500 mb-1">开始时间</p>
                  <p className="text-sm text-slate-700">
                    {selectedJob.startTime ? new Date(selectedJob.startTime).toLocaleString('zh-CN') : '-'}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">完成时间</p>
                  <p className="text-sm text-slate-700">
                    {selectedJob.endTime ? new Date(selectedJob.endTime).toLocaleString('zh-CN') : '-'}
                  </p>
                </div>
              </div>
              {selectedJob.errorMessage && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">错误信息</p>
                  <div className="p-3 bg-red-50 rounded-lg border border-red-200">
                    <p className="text-sm text-red-700 whitespace-pre-wrap">{selectedJob.errorMessage}</p>
                  </div>
                </div>
              )}
              {(selectedJob.status === 'failed') && (
                <div className="flex items-center gap-2 pt-2 border-t border-slate-100">
                  <button
                    onClick={() => { handleRetry(selectedJob.id); setSelectedJob(null); }}
                    className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors"
                  >
                    <RotateCcw size={14} /> 重新同步
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
