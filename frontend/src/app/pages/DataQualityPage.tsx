import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useStoreId } from '../lib/useStoreId';
import {
  AlertTriangle,
  CheckCircle2,
  XCircle,
  Clock,
  Loader2,
  Play,
  RefreshCw,
  Info,
  ArrowRight,
  Eye,
  EyeOff,
} from 'lucide-react';
import { cn } from '../lib/utils';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '../components/ui/tabs';
import {
  fetchDataQualityIssues,
  runDataQualityCheck,
  resolveDataQualityIssue,
  ignoreDataQualityIssue,
} from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';

// ─── Constants ───────────────────────────────────────────────────────────────

const ISSUE_TABS = [
  { value: 'all', label: '全部' },
  { value: 'missing_date', label: '缺失报告日期' },
  { value: 'unmatched_campaign', label: '未匹配广告活动' },
  { value: 'invalid_metrics', label: '无效指标' },
  { value: 'impossible_metrics', label: '逻辑错误指标' },
  { value: 'duplicate_import', label: '重复导入' },
  { value: 'zero_spend_sales', label: '零花费有销售' },
] as const;

// Backend severities are `error` / `warning` (with `info` as a low fallback).
// Map them to display tiers, and tolerate legacy high/medium/low values.
const SEVERITY_CONFIG: Record<string, { label: string; color: string; bg: string }> = {
  error: { label: '高', color: 'text-red-700', bg: 'bg-red-50 border-red-200' },
  high: { label: '高', color: 'text-red-700', bg: 'bg-red-50 border-red-200' },
  warning: { label: '中', color: 'text-yellow-700', bg: 'bg-yellow-50 border-yellow-200' },
  medium: { label: '中', color: 'text-yellow-700', bg: 'bg-yellow-50 border-yellow-200' },
  info: { label: '低', color: 'text-blue-700', bg: 'bg-blue-50 border-blue-200' },
  low: { label: '低', color: 'text-blue-700', bg: 'bg-blue-50 border-blue-200' },
};

const STATUS_CONFIG: Record<string, { label: string; color: string; bg: string }> = {
  open: { label: '待处理', color: 'text-orange-700', bg: 'bg-orange-50 border-orange-200' },
  resolved: { label: '已解决', color: 'text-emerald-700', bg: 'bg-emerald-50 border-emerald-200' },
  ignored: { label: '已忽略', color: 'text-slate-600', bg: 'bg-slate-50 border-slate-200' },
};

const ISSUE_TYPE_LABELS: Record<string, string> = {
  missing_date: '缺失报告日期',
  unmatched_campaign: '未匹配广告活动',
  invalid_metrics: '无效指标',
  impossible_metrics: '逻辑错误指标',
  duplicate_import: '重复导入',
  zero_spend_sales: '零花费有销售',
};

// Maps a raw backend severity to one of high/medium/low buckets used for
// scoring and severity tallies.
function severityTier(severity: string): 'high' | 'medium' | 'low' {
  if (severity === 'error' || severity === 'high') return 'high';
  if (severity === 'warning' || severity === 'medium') return 'medium';
  return 'low';
}

// ─── Constants (mock removed — using real API) ───────────────────────────────

// ─── Severity badge component ────────────────────────────────────────────────

function SeverityBadge({ severity }: { severity: string }) {
  const config = SEVERITY_CONFIG[severity] || SEVERITY_CONFIG.low;
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border', config.bg, config.color)}>
      {config.label}
    </span>
  );
}

// ─── Status badge component ──────────────────────────────────────────────────

function StatusBadge({ status }: { status: string }) {
  const config = STATUS_CONFIG[status] || STATUS_CONFIG.open;
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border', config.bg, config.color)}>
      {config.label}
    </span>
  );
}

// ─── Circular gauge component ────────────────────────────────────────────────

function CircularGauge({ value, size = 120 }: { value: number; size?: number }) {
  const radius = (size - 12) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (value / 100) * circumference;

  let color = '#10b981'; // emerald
  if (value < 50) color = '#ef4444'; // red
  else if (value < 75) color = '#f59e0b'; // amber

  return (
    <div className="relative inline-flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="#e2e8f0"
          strokeWidth="8"
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth="8"
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          className="transition-all duration-700 ease-out"
        />
      </svg>
      <div className="absolute flex flex-col items-center">
        <span className="text-2xl font-bold text-slate-900">{value}</span>
        <span className="text-xs text-slate-500">/ 100</span>
      </div>
    </div>
  );
}

// ─── Loading skeleton ────────────────────────────────────────────────────────

function LoadingSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-9 w-36" />
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="bg-white rounded-xl border border-slate-200 p-6">
            <Skeleton className="h-4 w-28 mb-3" />
            <Skeleton className="h-10 w-20" />
          </div>
        ))}
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-4">
        <Skeleton className="h-9 w-full mb-4" />
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-12 w-full mb-2" />
        ))}
      </div>
    </div>
  );
}

// ─── Error state ─────────────────────────────────────────────────────────────

function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center mb-4">
        <XCircle size={24} className="text-red-500" />
      </div>
      <h3 className="text-lg font-semibold text-slate-900 mb-1">加载数据失败</h3>
      <p className="text-sm text-slate-500 mb-4 max-w-sm">{message}</p>
      <Button variant="outline" size="sm" onClick={onRetry}>
        <RefreshCw size={14} className="mr-1.5" />
        重试
      </Button>
    </div>
  );
}

// ─── Empty state ─────────────────────────────────────────────────────────────

function EmptyState({ filter }: { filter: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="w-12 h-12 rounded-full bg-emerald-50 flex items-center justify-center mb-4">
        <CheckCircle2 size={24} className="text-emerald-500" />
      </div>
      <h3 className="text-lg font-semibold text-slate-900 mb-1">
        {filter === 'all' ? '未发现问题' : '没有匹配的问题'}
      </h3>
      <p className="text-sm text-slate-500 max-w-sm">
        {filter === 'all'
          ? '您的数据质量非常好，未检测到问题。'
          : '请尝试选择不同的筛选条件查看问题。'}
      </p>
    </div>
  );
}

// ─── Check results panel ─────────────────────────────────────────────────────

function CheckResultsPanel({ results, onClose }: { results: any; onClose: () => void }) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-6 space-y-5">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-slate-900 flex items-center gap-2">
          <CheckCircle2 size={20} className="text-emerald-500" />
          质量检查结果
        </h3>
        <Button variant="ghost" size="sm" onClick={onClose}>
          <XCircle size={16} className="mr-1" />
          关闭
        </Button>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div className="bg-slate-50 rounded-lg p-4">
          <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">已执行检查</p>
          <p className="text-2xl font-bold text-slate-900">{results.checksPerformed ?? 0}</p>
        </div>
        <div className="bg-slate-50 rounded-lg p-4">
          <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">发现问题</p>
          <p className="text-2xl font-bold text-slate-900">{results.issuesFound ?? 0}</p>
        </div>
        <div className="bg-slate-50 rounded-lg p-4">
          <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">按类别</p>
          <div className="flex flex-wrap gap-1.5 mt-1">
            {results.issuesByCategory
              ? Object.entries(results.issuesByCategory as Record<string, number>).map(([cat, count]) => (
                <span key={cat} className="inline-flex items-center px-2 py-0.5 rounded text-xs bg-slate-200 text-slate-700">
                  {ISSUE_TYPE_LABELS[cat] ?? cat}: {count}
                </span>
              ))
              : <span className="text-xs text-slate-400">-</span>}
          </div>
        </div>
      </div>

      {results.recommendations && results.recommendations.length > 0 && (
        <div>
          <h4 className="text-sm font-semibold text-slate-700 mb-2 flex items-center gap-1.5">
            <Info size={14} className="text-indigo-500" />
            建议
          </h4>
          <ul className="space-y-1.5">
            {results.recommendations.map((rec: string, i: number) => (
              <li key={i} className="flex items-start gap-2 text-sm text-slate-600">
                <ArrowRight size={14} className="text-indigo-400 flex-shrink-0 mt-0.5" />
                {rec}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

export function DataQualityPage() {
  const { storeId } = useStoreId();
  const queryClient = useQueryClient();
  const [checking, setChecking] = useState(false);
  const [activeTab, setActiveTab] = useState('all');
  const [checkResults, setCheckResults] = useState<any | null>(null);
  const [lastCheckTime, setLastCheckTime] = useState<string | null>(null);
  // Page-level error raised by a failed check/status action.
  const [actionError, setActionError] = useState<string | null>(null);

  // ─── Fetch issues ────────────────────────────────────────────────────────

  const issuesKey = ['data-quality-issues', storeId] as const;
  const issuesQuery = useApiQuery<any[]>(
    issuesKey,
    () => fetchDataQualityIssues({ storeId: storeId || '' }).then((data) => (Array.isArray(data) ? data : [])),
  );
  const issues = issuesQuery.data ?? [];
  const loading = issuesQuery.isLoading;
  const error = actionError ?? (issuesQuery.isError ? issuesQuery.error?.message ?? '加载数据质量问题失败' : null);
  const fetchIssues = () => { setActionError(null); return issuesQuery.refetch(); };

  // ─── Run quality check ───────────────────────────────────────────────────

  const runQualityCheck = async () => {
    setChecking(true);
    setCheckResults(null);
    try {
      const data = await runDataQualityCheck(storeId || '');
      setCheckResults(data);
      setLastCheckTime(new Date().toLocaleString());
      fetchIssues();
    } catch (err: any) {
      setActionError(err.message || '数据质量检查失败');
    } finally {
      setChecking(false);
    }
  };

  // ─── Resolve / Ignore issue ──────────────────────────────────────────────

  const updateIssueStatus = async (issueId: string, newStatus: string) => {
    try {
      if (newStatus === 'resolved') {
        await resolveDataQualityIssue(issueId);
      } else if (newStatus === 'ignored') {
        await ignoreDataQualityIssue(issueId);
      }
    } catch (err: any) {
      setActionError(err.message || '更新状态失败');
    }
    queryClient.setQueryData<any[]>(issuesKey, (prev) =>
      (prev ?? []).map((i) => (i.id === issueId ? { ...i, status: newStatus } : i)),
    );
  };

  // ─── Computed data ───────────────────────────────────────────────────────

  const qualityScore = (() => {
    if (issues.length === 0) return 100;
    const highWeight = 15;
    const medWeight = 7;
    const lowWeight = 3;
    const openIssues = issues.filter((i) => i.status === 'open');
    const penalty = openIssues.reduce((sum, i) => {
      const tier = severityTier(i.severity);
      if (tier === 'high') return sum + highWeight;
      if (tier === 'medium') return sum + medWeight;
      return sum + lowWeight;
    }, 0);
    return Math.max(0, Math.min(100, 100 - penalty));
  })();

  const severityCounts = {
    high: issues.filter((i) => severityTier(i.severity) === 'high').length,
    medium: issues.filter((i) => severityTier(i.severity) === 'medium').length,
    low: issues.filter((i) => severityTier(i.severity) === 'low').length,
  };

  const statusCounts = {
    open: issues.filter((i) => i.status === 'open').length,
    resolved: issues.filter((i) => i.status === 'resolved').length,
    ignored: issues.filter((i) => i.status === 'ignored').length,
  };

  const tabCounts = {
    all: issues.length,
    ...Object.fromEntries(
      ISSUE_TABS.filter((t) => t.value !== 'all').map((t) => [
        t.value,
        issues.filter((i) => i.issueType === t.value).length,
      ]),
    ),
  };

  const filteredIssues = activeTab === 'all'
    ? issues
    : issues.filter((i) => i.issueType === activeTab);

  // ─── Render ──────────────────────────────────────────────────────────────

  if (loading) {
    return (
      <div className="space-y-6">
        <LoadingSkeleton />
      </div>
    );
  }

  if (error && issues.length === 0) {
    return <ErrorState message={error} onRetry={fetchIssues} />;
  }

  return (
    <div className="space-y-6">
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-amber-50 flex items-center justify-center">
            <AlertTriangle size={22} className="text-amber-500" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-900">数据质量中心</h1>
            {lastCheckTime && (
              <p className="text-xs text-slate-500 flex items-center gap-1 mt-0.5">
                <Clock size={12} />
                上次检查: {lastCheckTime}
              </p>
            )}
          </div>
        </div>
        <Button onClick={runQualityCheck} disabled={checking}>
          {checking ? (
            <Loader2 size={16} className="mr-1.5 animate-spin" />
          ) : (
            <Play size={16} className="mr-1.5" />
          )}
          {checking ? '运行中...' : '运行质量检查'}
        </Button>
      </div>

      {/* ── Check Results (if any) ─────────────────────────────────────────── */}
      {checkResults && (
        <CheckResultsPanel results={checkResults} onClose={() => setCheckResults(null)} />
      )}

      {/* ── Quality Score Cards ────────────────────────────────────────────── */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Overall quality score */}
        <div className="bg-white rounded-xl border border-slate-200 p-6 flex flex-col items-center justify-center">
          <p className="text-xs font-medium text-slate-500 uppercase tracking-wide mb-3">总体质量评分</p>
          <CircularGauge value={qualityScore} />
        </div>

        {/* Issues by severity */}
        <div className="bg-white rounded-xl border border-slate-200 p-6">
          <p className="text-xs font-medium text-slate-500 uppercase tracking-wide mb-3">按严重程度</p>
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-2 text-sm text-slate-700">
                <span className="w-2.5 h-2.5 rounded-full bg-red-500" />
                高
              </span>
              <span className="text-lg font-bold text-red-600">{severityCounts.high}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-2 text-sm text-slate-700">
                <span className="w-2.5 h-2.5 rounded-full bg-yellow-500" />
                中
              </span>
              <span className="text-lg font-bold text-yellow-600">{severityCounts.medium}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-2 text-sm text-slate-700">
                <span className="w-2.5 h-2.5 rounded-full bg-blue-500" />
                低
              </span>
              <span className="text-lg font-bold text-blue-600">{severityCounts.low}</span>
            </div>
          </div>
        </div>

        {/* Issues by status */}
        <div className="bg-white rounded-xl border border-slate-200 p-6">
          <p className="text-xs font-medium text-slate-500 uppercase tracking-wide mb-3">按状态</p>
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-2 text-sm text-slate-700">
                <span className="w-2.5 h-2.5 rounded-full bg-orange-500" />
                待处理
              </span>
              <span className="text-lg font-bold text-orange-600">{statusCounts.open}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-2 text-sm text-slate-700">
                <span className="w-2.5 h-2.5 rounded-full bg-emerald-500" />
                已解决
              </span>
              <span className="text-lg font-bold text-emerald-600">{statusCounts.resolved}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-2 text-sm text-slate-700">
                <span className="w-2.5 h-2.5 rounded-full bg-slate-400" />
                已忽略
              </span>
              <span className="text-lg font-bold text-slate-600">{statusCounts.ignored}</span>
            </div>
          </div>
        </div>

        {/* Quick stats */}
        <div className="bg-white rounded-xl border border-slate-200 p-6">
          <p className="text-xs font-medium text-slate-500 uppercase tracking-wide mb-3">摘要</p>
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm text-slate-700">总问题数</span>
              <span className="text-lg font-bold text-slate-900">{issues.length}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-slate-700">需处理</span>
              <span className="text-lg font-bold text-orange-600">{statusCounts.open}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-slate-700">已处理</span>
              <span className="text-lg font-bold text-emerald-600">{statusCounts.resolved + statusCounts.ignored}</span>
            </div>
          </div>
        </div>
      </div>

      {/* ── Issues Table with Tabs ─────────────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200">
        <div className="p-4 pb-0">
          <h2 className="text-base font-semibold text-slate-900 mb-3">数据质量问题</h2>
        </div>

        <Tabs value={activeTab} onValueChange={setActiveTab}>
          <div className="px-4">
            <TabsList className="bg-slate-100 w-full justify-start overflow-x-auto flex-wrap h-auto p-1 gap-1">
              {ISSUE_TABS.map((tab) => (
                <TabsTrigger
                  key={tab.value}
                  value={tab.value}
                  className="text-xs px-3 py-1.5 h-auto"
                >
                  {tab.label}
                  <span className={cn(
                    'ml-1.5 inline-flex items-center justify-center min-w-[20px] h-5 px-1.5 rounded-full text-[10px] font-semibold',
                    activeTab === tab.value
                      ? 'bg-indigo-100 text-indigo-700'
                      : 'bg-slate-200 text-slate-600',
                  )}>
                    {tabCounts[tab.value] ?? 0}
                  </span>
                </TabsTrigger>
              ))}
            </TabsList>
          </div>

          <TabsContent value={activeTab} className="mt-0">
            {filteredIssues.length === 0 ? (
              <EmptyState filter={activeTab} />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="w-[90px]">严重程度</TableHead>
                    <TableHead className="w-[130px]">问题类型</TableHead>
                    <TableHead>标题</TableHead>
                    <TableHead className="hidden lg:table-cell">描述</TableHead>
                    <TableHead className="hidden md:table-cell w-[150px]">关联实体</TableHead>
                    <TableHead className="hidden xl:table-cell w-[120px]">导入任务</TableHead>
                    <TableHead className="w-[100px]">状态</TableHead>
                    <TableHead className="w-[100px]">创建时间</TableHead>
                    <TableHead className="w-[120px] text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filteredIssues.map((issue) => (
                    <TableRow key={issue.id} className="group">
                      <TableCell>
                        <SeverityBadge severity={issue.severity} />
                      </TableCell>
                      <TableCell>
                        <span className="text-xs font-medium text-slate-600">
                          {ISSUE_TYPE_LABELS[issue.issueType] ?? issue.issueType}
                        </span>
                      </TableCell>
                      <TableCell>
                        <span className="text-sm font-medium text-slate-900">{issue.title}</span>
                      </TableCell>
                      <TableCell className="hidden lg:table-cell">
                        <span className="text-sm text-slate-500 line-clamp-2 max-w-xs">{issue.description}</span>
                      </TableCell>
                      <TableCell className="hidden md:table-cell">
                        <span className="text-sm text-slate-600">{issue.relatedEntityType ?? '-'}</span>
                      </TableCell>
                      <TableCell className="hidden xl:table-cell">
                        <span className="text-xs text-slate-500">{issue.importJobId ?? '-'}</span>
                      </TableCell>
                      <TableCell>
                        <StatusBadge status={issue.status} />
                      </TableCell>
                      <TableCell>
                        <span className="text-xs text-slate-500">{issue.createdAt}</span>
                      </TableCell>
                      <TableCell className="text-right">
                        {issue.status === 'open' && (
                          <div className="flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-7 px-2 text-xs text-emerald-600 hover:text-emerald-700 hover:bg-emerald-50"
                              onClick={() => updateIssueStatus(issue.id, 'resolved')}
                            >
                              <CheckCircle2 size={13} className="mr-1" />
                              解决
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-7 px-2 text-xs text-slate-500 hover:text-slate-600 hover:bg-slate-50"
                              onClick={() => updateIssueStatus(issue.id, 'ignored')}
                            >
                              <EyeOff size={13} className="mr-1" />
                              忽略
                            </Button>
                          </div>
                        )}
                        {issue.status !== 'open' && (
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-7 px-2 text-xs text-slate-400 hover:text-slate-600 opacity-0 group-hover:opacity-100 transition-opacity"
                            onClick={() => updateIssueStatus(issue.id, 'open')}
                          >
                            <Eye size={13} className="mr-1" />
                            重新打开
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </TabsContent>
        </Tabs>
      </div>
    </div>
  );
}
