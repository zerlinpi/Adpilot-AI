import { useState } from 'react';
import {
  FileText, Search, RefreshCw, Eye, Info, AlertTriangle, XCircle,
} from 'lucide-react';
import { cn } from '../lib/utils';
import { fetchSyncLogs } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { Sheet, SheetContent, SheetTitle } from '../components/ui/sheet';

const levelConfig: Record<string, { label: string; color: string; bg: string; icon: typeof Info }> = {
  INFO: { label: 'INFO', color: 'text-blue-700', bg: 'bg-blue-50', icon: Info },
  WARN: { label: 'WARN', color: 'text-amber-700', bg: 'bg-amber-50', icon: AlertTriangle },
  ERROR: { label: 'ERROR', color: 'text-red-700', bg: 'bg-red-50', icon: XCircle },
};

export function SyncLogsPage() {
  const [levelFilter, setLevelFilter] = useState('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [jobIdFilter, setJobIdFilter] = useState('');
  const [selectedLog, setSelectedLog] = useState<any | null>(null);

  const logsQuery = useApiQuery<any[]>(
    ['sync-logs', { levelFilter, searchQuery, jobIdFilter }],
    () => {
      const params: any = {};
      if (levelFilter !== 'all') params.level = levelFilter;
      if (searchQuery) params.search = searchQuery;
      if (jobIdFilter) params.jobId = jobIdFilter;
      return fetchSyncLogs(params).then((result: any) => (Array.isArray(result) ? result : result?.items || []));
    },
  );
  const logs = logsQuery.data ?? [];
  const loading = logsQuery.isLoading;
  const error = logsQuery.isError ? logsQuery.error?.message ?? '加载同步日志失败' : null;
  const loadLogs = () => logsQuery.refetch();

  const filteredLogs = logs.filter((log) => {
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      return (
        log.message?.toLowerCase().includes(q) ||
        log.jobId?.toLowerCase().includes(q)
      );
    }
    return true;
  });

  if (loading) return <div className="space-y-4"><ErpPageHeader title="同步日志" description="查看平台同步运行日志" /><ErpLoadingSkeleton rows={10} /></div>;
  if (error) return <ErpErrorState message={error} onRetry={loadLogs} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="同步日志"
        description={`共 ${logs.length} 条日志`}
        actions={
          <button onClick={loadLogs} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
            <RefreshCw size={14} /> 刷新
          </button>
        }
      />

      {/* Filters */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-0.5">
          {['all', 'INFO', 'WARN', 'ERROR'].map((l) => (
            <button key={l} onClick={() => setLevelFilter(l)}
              className={cn('px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                levelFilter === l ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700')}>
              {l === 'all' ? '全部' : l}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 rounded-lg px-2 py-1">
          <Search size={14} className="text-slate-400" />
          <input
            type="text"
            value={jobIdFilter}
            onChange={(e) => setJobIdFilter(e.target.value)}
            placeholder="按任务ID筛选..."
            className="text-xs bg-transparent outline-none w-32 placeholder-slate-400"
          />
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 rounded-lg px-2 py-1">
          <Search size={14} className="text-slate-400" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="搜索日志消息..."
            className="text-xs bg-transparent outline-none w-40 placeholder-slate-400"
          />
        </div>
      </div>

      {/* Table */}
      {filteredLogs.length === 0 ? (
        <ErpEmptyState
          title="暂无日志"
          description="没有匹配的同步日志记录"
          icon={<FileText size={24} className="text-slate-400" />}
        />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50">
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-36">时间</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">任务ID</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-20">级别</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">消息</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500 w-16">详情</th>
              </tr>
            </thead>
            <tbody>
              {filteredLogs.map((log) => {
                const level = levelConfig[log.level] || levelConfig.INFO;
                const LevelIcon = level.icon;

                return (
                  <tr key={log.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-3">
                      <span className="text-xs text-slate-500">
                        {log.timestamp ? new Date(log.timestamp).toLocaleString('zh-CN') : '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-sm font-mono text-slate-600">{log.jobId || '-'}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn('inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium', level.bg, level.color)}>
                        <LevelIcon size={10} />
                        {level.label}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <p className="text-sm text-slate-700 truncate max-w-[400px]">{log.message || '-'}</p>
                    </td>
                    <td className="px-4 py-3 text-right">
                      {(log.details || log.stackTrace) && (
                        <button
                          onClick={() => setSelectedLog(log)}
                          className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 bg-slate-50 rounded hover:bg-slate-100 transition-colors"
                        >
                          <Eye size={11} />
                        </button>
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
      {selectedLog && (
        <Sheet open onOpenChange={(o) => { if (!o) setSelectedLog(null); }}>
          <SheetContent side="right" className="w-full max-w-lg sm:max-w-lg gap-0 p-0 bg-white border-l border-slate-200 overflow-y-auto">
            <div className="sticky top-0 bg-white border-b border-slate-100 px-5 py-4 flex items-center justify-between">
              <SheetTitle className="text-base font-semibold text-slate-900">日志详情</SheetTitle>
            </div>
            <div className="p-5 space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-slate-500 mb-1">时间</p>
                  <p className="text-sm text-slate-700">
                    {selectedLog.timestamp ? new Date(selectedLog.timestamp).toLocaleString('zh-CN') : '-'}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">任务ID</p>
                  <p className="text-sm font-mono text-slate-700">{selectedLog.jobId || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">级别</p>
                  <span className={cn('inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium', levelConfig[selectedLog.level]?.bg || 'bg-slate-100', levelConfig[selectedLog.level]?.color || 'text-slate-600')}>
                    {selectedLog.level || '-'}
                  </span>
                </div>
              </div>
              <div>
                <p className="text-xs text-slate-500 mb-1">消息</p>
                <p className="text-sm text-slate-700">{selectedLog.message || '-'}</p>
              </div>
              {selectedLog.details && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">详情</p>
                  <pre className="text-xs text-slate-700 bg-slate-50 rounded-lg p-3 overflow-x-auto whitespace-pre-wrap border border-slate-200">
                    {typeof selectedLog.details === 'string' ? selectedLog.details : JSON.stringify(selectedLog.details, null, 2)}
                  </pre>
                </div>
              )}
              {selectedLog.stackTrace && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">堆栈信息</p>
                  <pre className="text-xs text-red-700 bg-red-50 rounded-lg p-3 overflow-x-auto whitespace-pre-wrap border border-red-200">
                    {selectedLog.stackTrace}
                  </pre>
                </div>
              )}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
