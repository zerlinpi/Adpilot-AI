import { useState, useMemo } from 'react';
import { FileText, Download, Calendar, Filter, AlertTriangle } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { fetchReports, generateReport } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { notify } from '../lib/toast';
import { useStoreId } from '../lib/useStoreId';
import { t } from '../i18n';
import { cn } from '../lib/utils';
import { Button } from '../components/ui/button';
import type { Report, ReportType } from '../types';

const typeBadge: Record<ReportType, string> = {
  daily: 'bg-blue-100 text-blue-700 border-blue-200',
  weekly: 'bg-violet-100 text-violet-700 border-violet-200',
  monthly: 'bg-emerald-100 text-emerald-700 border-emerald-200',
};

const typeLabel: Record<ReportType, string> = {
  daily: '日报',
  weekly: '周报',
  monthly: '月报',
};

// ─── Loading skeleton ─────────────────────────────────────────────────
function LoadingSkeleton() {
  return (
    <div className="grid gap-4">
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="bg-white rounded-xl border border-slate-200 p-6 animate-pulse">
          <div className="flex items-start justify-between gap-4">
            <div className="flex-1 space-y-3">
              <div className="flex items-center gap-3">
                <div className="h-5 bg-slate-200 rounded w-48" />
                <div className="h-5 bg-slate-100 rounded-full w-16" />
              </div>
              <div className="h-4 bg-slate-100 rounded w-64" />
              <div className="h-3 bg-slate-100 rounded w-full max-w-md" />
            </div>
            <div className="flex gap-2">
              <div className="h-9 bg-slate-100 rounded-lg w-20" />
              <div className="h-9 bg-slate-100 rounded-lg w-20" />
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

export function ReportsPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const queryClient = useQueryClient();
  const [typeFilter, setTypeFilter] = useState<ReportType | 'all'>('all');
  const [generating, setGenerating] = useState(false);

  function flashMessage(type: 'success' | 'error', text: string) {
    if (type === 'success') notify.success(text);
    else notify.error(text);
  }

  const reportsKey = ['reports', storeId] as const;
  const reportsQuery = useApiQuery<Report[]>(
    reportsKey,
    () => fetchReports(storeId!) as Promise<Report[]>,
    { enabled: !!storeId },
  );
  const reports = Array.isArray(reportsQuery.data) ? reportsQuery.data : [];
  const loading = !!storeId && reportsQuery.isLoading;
  const error = reportsQuery.isError ? reportsQuery.error?.message ?? '加载报告失败' : null;
  const loadData = () => reportsQuery.refetch();

  const handleGenerate = async () => {
    if (!storeId) return;
    try {
      setGenerating(true);
      const now = new Date();
      const thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
      const newReport = await generateReport({
        storeId,
        type: 'monthly',
        periodStart: thirtyDaysAgo.toISOString().split('T')[0],
        periodEnd: now.toISOString().split('T')[0],
      });
      // Prepend new report and switch to all to show it
      queryClient.setQueryData<Report[]>(reportsKey, (prev) => [newReport, ...(prev ?? [])]);
      setTypeFilter('all');
      flashMessage('success', '报告已生成');
    } catch (err) {
      flashMessage('error', err instanceof Error ? err.message : '生成报告失败');
    } finally {
      setGenerating(false);
    }
  };

  // ─── Export handlers ──────────────────────────────────────────────
  // No backend export endpoint exists, so both controls perform a real,
  // self-contained client-side export of the report fields the page already
  // holds, and surface success/error feedback (Req 17.2, 17.4).
  const handleExportCsv = (report: Report) => {
    try {
      const rows: string[][] = [
        ['标题', '类型', '开始日期', '结束日期', '摘要'],
        [
          report.title ?? '',
          typeLabel[report.type] ?? report.type,
          report.periodStart ?? '',
          report.periodEnd ?? '',
          report.summary ?? '',
        ],
      ];
      const escape = (v: string) => `"${String(v).replace(/"/g, '""')}"`;
      const csv = '\uFEFF' + rows.map((r) => r.map(escape).join(',')).join('\r\n');
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${report.title || 'report'}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      flashMessage('success', 'CSV 已导出');
    } catch (err) {
      flashMessage('error', err instanceof Error ? err.message : 'CSV 导出失败');
    }
  };

  const handleExportPdf = (report: Report) => {
    try {
      const win = window.open('', '_blank');
      if (!win) {
        flashMessage('error', '无法打开打印窗口，请检查浏览器弹窗设置');
        return;
      }
      const safe = (v: string) =>
        String(v ?? '').replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] as string));
      win.document.write(`<!doctype html><html lang="zh"><head><meta charset="utf-8"><title>${safe(report.title)}</title>
        <style>body{font-family:system-ui,sans-serif;padding:32px;color:#1e293b}h1{font-size:20px}dl{margin-top:16px}dt{font-weight:600;margin-top:12px}dd{margin:4px 0 0}</style>
        </head><body>
        <h1>${safe(report.title)}</h1>
        <dl>
          <dt>类型</dt><dd>${safe(typeLabel[report.type] ?? report.type)}</dd>
          <dt>周期</dt><dd>${safe(report.periodStart)} — ${safe(report.periodEnd)}</dd>
          <dt>摘要</dt><dd>${safe(report.summary ?? '')}</dd>
        </dl>
        </body></html>`);
      win.document.close();
      win.focus();
      win.print();
      flashMessage('success', '已打开 PDF 打印视图');
    } catch (err) {
      flashMessage('error', err instanceof Error ? err.message : 'PDF 导出失败');
    }
  };

  const filtered = useMemo(() => {
    if (typeFilter === 'all') return reports;
    return reports.filter((r) => r.type === typeFilter);
  }, [typeFilter, reports]);

  // ─── Loading state ────────────────────────────────────────────────
  if (loading || storeLoading) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <div className="h-8 bg-slate-200 rounded w-24 animate-pulse" />
            <div className="h-4 bg-slate-100 rounded w-64 mt-2 animate-pulse" />
          </div>
          <div className="h-10 bg-slate-200 rounded-lg w-40 animate-pulse" />
        </div>
        <div className="flex items-center gap-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-8 bg-slate-100 rounded-lg w-20 animate-pulse" />
          ))}
        </div>
        <LoadingSkeleton />
      </div>
    );
  }

  // ─── Error state ──────────────────────────────────────────────────
  if (error || storeError) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">{t('pages.reports.title')}</h1>
          </div>
        </div>
        <div className="text-center py-16">
          <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <AlertTriangle className="w-8 h-8 text-red-400" />
          </div>
          <h3 className="text-lg font-semibold text-slate-700 mb-1">
            加载报告失败
          </h3>
          <p className="text-sm text-slate-500 mb-4">{storeError || error}</p>
          <Button onClick={loadData} variant="outline" className="font-medium">
            {t('forms.retry')}
          </Button>
        </div>
      </div>
    );
  }

  // ─── No store state ───────────────────────────────────────────────
  if (!storeId) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">{t('pages.reports.title')}</h1>
          </div>
        </div>
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <FileText size={48} className="text-slate-300 mb-4" />
          <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
          <p className="text-sm text-slate-500 mt-1 max-w-md">
            请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">{t('pages.reports.title')}</h1>
          <p className="text-sm text-slate-500 mt-1">{t('pages.reports.subtitle')}</p>
        </div>
        <button
          onClick={handleGenerate}
          disabled={generating}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <FileText size={16} />
          {generating ? '生成中...' : '生成报告'}
        </button>
      </div>

      {/* Filters */}
      <div className="flex items-center gap-2">
        <Filter size={16} className="text-slate-400" />
        {(['all', 'daily', 'weekly', 'monthly'] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTypeFilter(t)}
            className={cn(
              'px-3 py-1.5 text-sm font-medium rounded-lg border transition-colors',
              typeFilter === t
                ? 'bg-indigo-50 text-indigo-700 border-indigo-200'
                : 'bg-white text-slate-600 border-slate-200 hover:bg-slate-50',
            )}
          >
            {t === 'all' ? '全部' : typeLabel[t]}
          </button>
        ))}
      </div>

      {/* Empty state */}
      {reports.length === 0 ? (
        <div className="text-center py-16">
          <div className="w-16 h-16 bg-slate-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <FileText className="w-8 h-8 text-slate-300" />
          </div>
          <h3 className="text-lg font-semibold text-slate-700 mb-1">
            暂无报告
          </h3>
          <p className="text-sm text-slate-500">
            生成您的第一份报告即可开始使用。
          </p>
        </div>
      ) : (
        /* Report cards */
        <div className="grid gap-4">
          {filtered.map((report: Report) => (
            <div
              key={report.id}
              className="bg-white rounded-xl border border-slate-200 p-6 hover:shadow-md transition-shadow"
            >
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-3 mb-2">
                    <h3 className="text-base font-semibold text-slate-900 truncate">{report.title}</h3>
                    <span
                      className={cn(
                        'inline-flex px-2.5 py-0.5 text-xs font-medium rounded-full border capitalize',
                        typeBadge[report.type],
                      )}
                    >
                      {typeLabel[report.type]}
                    </span>
                  </div>

                  <div className="flex items-center gap-1.5 text-sm text-slate-500 mb-3">
                    <Calendar size={14} />
                    <span>
                      {report.periodStart} &mdash; {report.periodEnd}
                    </span>
                  </div>

                  {report.summary && (
                    <p className="text-sm text-slate-600 leading-relaxed">{report.summary}</p>
                  )}
                </div>

                <div className="flex items-center gap-2 flex-shrink-0">
                  <button
                    onClick={() => handleExportPdf(report)}
                    className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors"
                  >
                    <Download size={14} />
                    PDF
                  </button>
                  <button
                    onClick={() => handleExportCsv(report)}
                    className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors"
                  >
                    <Download size={14} />
                    CSV
                  </button>
                </div>
              </div>
            </div>
          ))}

          {filtered.length === 0 && (
            <div className="text-center py-16 text-slate-400">
              <FileText size={40} className="mx-auto mb-3 opacity-40" />
              <p className="text-sm">该筛选条件下暂无报告。</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
