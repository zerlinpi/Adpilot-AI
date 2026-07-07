import { useState, useEffect, useMemo } from 'react';
import {
  Brain,
  Key,
  Activity,
  Trophy,
  Trash2,
  TrendingUp,
  ShieldX,
  Wheat,
  Search,
  ChevronDown,
  ChevronRight,
  ChevronLeft,
  ArrowRight,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Info,
  Sparkles,
  Loader2,
  Filter,
  MoreHorizontal,
  Eye,
  X,
  Zap,
  RefreshCw,
  type LucideIcon,
} from 'lucide-react';

import {
  cn,
  formatCurrency,
  formatNumber,
  formatPercent,
  getRiskColor,
} from '../lib/utils';
import { Skeleton } from '../components/ui/skeleton';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '../components/ui/sheet';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/ui/dropdown-menu';
import {
  fetchKeywordIntelligenceOverview,
  fetchKeywordInsights,
  fetchKeywordSummary,
  analyzeKeywordHealth,
  applyKeywordInsight,
  watchKeywordInsight,
  dismissKeywordInsight,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';

// ─── Segment config ──────────────────────────────────────────────────────
const segmentConfig: Record<string, { label: string; color: string }> = {
  all: { label: '全部', color: 'bg-slate-100 text-slate-700 border-slate-200' },
  winner: { label: '优质', color: 'bg-emerald-100 text-emerald-700 border-emerald-200' },
  waste: { label: '浪费', color: 'bg-red-100 text-red-700 border-red-200' },
  add_to_exact: { label: '加入精确', color: 'bg-green-100 text-green-700 border-green-200' },
  add_to_phrase: { label: '加入词组', color: 'bg-teal-100 text-teal-700 border-teal-200' },
  add_to_broad: { label: '加入广泛', color: 'bg-cyan-100 text-cyan-700 border-cyan-200' },
  add_to_negative: { label: '加入否定', color: 'bg-orange-100 text-orange-700 border-orange-200' },
  watchlist: { label: '观察列表', color: 'bg-yellow-100 text-yellow-700 border-yellow-200' },
  brand: { label: '品牌', color: 'bg-blue-100 text-blue-700 border-blue-200' },
  category: { label: '品类', color: 'bg-violet-100 text-violet-700 border-violet-200' },
  competitor: { label: '竞品', color: 'bg-rose-100 text-rose-700 border-rose-200' },
  long_tail: { label: '长尾', color: 'bg-indigo-100 text-indigo-700 border-indigo-200' },
  ranking: { label: '排名', color: 'bg-amber-100 text-amber-700 border-amber-200' },
  listing_missing: { label: 'Listing 缺失', color: 'bg-pink-100 text-pink-700 border-pink-200' },
};

// ─── Match type styles ───────────────────────────────────────────────────
const matchTypeStyles: Record<string, string> = {
  exact: 'bg-emerald-100 text-emerald-700',
  phrase: 'bg-blue-100 text-blue-700',
  broad: 'bg-orange-100 text-orange-700',
};

// ─── Source badge styles ─────────────────────────────────────────────────
const sourceStyles: Record<string, string> = {
  keyword: 'bg-blue-100 text-blue-700',
  search_term: 'bg-purple-100 text-purple-700',
  competitor: 'bg-rose-100 text-rose-700',
  product_listing: 'bg-teal-100 text-teal-700',
};

// ─── Insight normalization ───────────────────────────────────────────────
// The backend KeywordInsightVo exposes { text, source, segment, *Score,
// recommendedAction, reason, currentData (JSON), ... }. The table/drawer below
// reference flat metric fields (keywordText, impressions, roas, etc.). This
// maps a raw VO to a display-friendly shape, deriving metrics from currentData
// and defaulting everything so rendering never crashes on undefined (e.g.
// `ins.roas.toFixed`).
function toNum(v: any): number {
  const n = typeof v === 'string' ? parseFloat(v) : v;
  return Number.isFinite(n) ? Number(n) : 0;
}

function normalizeInsight(raw: any) {
  const cd = raw && typeof raw.currentData === 'object' && raw.currentData ? raw.currentData : {};
  const impressions = toNum(cd.impressions);
  const clicks = toNum(cd.clicks);
  const orders = toNum(cd.orders);
  const spend = toNum(cd.spend);
  const sales = toNum(cd.sales);
  const acos = toNum(cd.acos);
  const cvr = toNum(cd.cvr);
  const ctr = cd.ctr != null ? toNum(cd.ctr) : impressions > 0 ? (clicks / impressions) * 100 : 0;
  const cpc = cd.cpc != null ? toNum(cd.cpc) : clicks > 0 ? spend / clicks : 0;
  const roas = cd.roas != null ? toNum(cd.roas) : spend > 0 ? sales / spend : 0;
  return {
    ...raw,
    source: raw?.source ?? '',
    segment: raw?.segment ?? 'all',
    keywordText: raw?.text ?? '',
    matchType: cd.matchType ?? cd.match_type ?? '',
    impressions,
    clicks,
    orders,
    spend,
    sales,
    acos,
    cvr,
    ctr,
    cpc,
    roas,
    currentBid: toNum(cd.currentBid),
    suggestedBid: toNum(cd.suggestedBid),
    listingCovered: cd.inListing === true || cd.listingCovered === true,
    recommendation: raw?.recommendedAction || raw?.reason || '',
    campaignName: raw?.campaignName || '-',
    goalName: raw?.goalName || '-',
    healthScore: toNum(raw?.healthScore),
    opportunityScore: toNum(raw?.opportunityScore),
    wasteScore: toNum(raw?.wasteScore),
    confidenceScore: toNum(raw?.confidenceScore),
  };
}

// ─── Circular Progress Component ─────────────────────────────────────────
function CircularProgress({
  value,
  size = 80,
  strokeWidth = 6,
  color,
  label,
}: {
  value: number;
  size?: number;
  strokeWidth?: number;
  color: string;
  label?: string;
}) {
  const radius = (size - strokeWidth) / 2;
  const circumference = radius * 2 * Math.PI;
  const offset = circumference - (value / 100) * circumference;

  return (
    <div className="relative inline-flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="#e2e8f0"
          strokeWidth={strokeWidth}
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="currentColor"
          strokeWidth={strokeWidth}
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          strokeLinecap="round"
          className={cn('transition-all duration-700', color)}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className={cn('text-lg font-bold', color)}>{value}</span>
        {label && <span className="text-[10px] text-slate-500">{label}</span>}
      </div>
    </div>
  );
}

// ─── Confidence Bar ──────────────────────────────────────────────────────
function ConfidenceBar({ value }: { value: number }) {
  const color = value >= 80 ? 'bg-emerald-500' : value >= 60 ? 'bg-yellow-500' : 'bg-red-500';
  return (
    <div className="flex items-center gap-1.5 min-w-[80px]">
      <div className="flex-1 h-1.5 rounded-full bg-slate-100">
        <div
          className={cn('h-full rounded-full transition-all', color)}
          style={{ width: `${value}%` }}
        />
      </div>
      <span className="text-xs text-slate-600 w-6 text-right">{value}</span>
    </div>
  );
}

// ─── Metric Card ─────────────────────────────────────────────────────────
function MetricCard({
  icon: Icon,
  label,
  value,
  iconColor = 'text-slate-600',
  iconBg = 'bg-slate-100',
}: {
  icon: LucideIcon;
  label: string;
  value: string | number;
  iconColor?: string;
  iconBg?: string;
}) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-5 hover:shadow-sm transition-shadow">
      <div className="flex items-center gap-3">
        <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', iconBg)}>
          <Icon size={20} className={iconColor} />
        </div>
        <div>
          <p className="text-sm text-slate-500">{label}</p>
          <p className="text-2xl font-bold text-slate-900">{value}</p>
        </div>
      </div>
    </div>
  );
}

// ─── AI Summary Callout ──────────────────────────────────────────────────
function Callout({
  title,
  children,
  variant = 'info',
  icon: Icon,
}: {
  title: string;
  children: React.ReactNode;
  variant?: 'danger' | 'success' | 'warning' | 'info' | 'purple';
  icon?: LucideIcon;
}) {
  const styles = {
    danger: 'border-red-200 bg-red-50',
    success: 'border-emerald-200 bg-emerald-50',
    warning: 'border-orange-200 bg-orange-50',
    info: 'border-blue-200 bg-blue-50',
    purple: 'border-purple-200 bg-purple-50',
  };
  const titleStyles = {
    danger: 'text-red-800',
    success: 'text-emerald-800',
    warning: 'text-orange-800',
    info: 'text-blue-800',
    purple: 'text-purple-800',
  };

  return (
    <div className={cn('rounded-lg border p-4', styles[variant])}>
      <div className="flex items-start gap-2">
        {Icon && <Icon size={16} className={cn('mt-0.5 shrink-0', titleStyles[variant])} />}
        <div>
          <p className={cn('text-sm font-semibold mb-1', titleStyles[variant])}>{title}</p>
          <div className="text-sm text-slate-700">{children}</div>
        </div>
      </div>
    </div>
  );
}

// ─── Date Range Selector ─────────────────────────────────────────────────
const dateRanges = ['7d', '14d', '30d'] as const;
type DateRange = (typeof dateRanges)[number];

// ─── Error State Component ───────────────────────────────────────────────
function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center min-h-[300px] gap-4">
      <div className="w-12 h-12 rounded-full bg-red-100 flex items-center justify-center">
        <AlertTriangle size={24} className="text-red-600" />
      </div>
      <div className="text-center">
        <p className="text-sm font-medium text-slate-900">加载数据失败</p>
        <p className="text-sm text-slate-500 mt-1">{message}</p>
      </div>
      <button
        onClick={onRetry}
        className="inline-flex items-center gap-2 px-4 py-2.5 bg-violet-600 text-white text-sm font-medium rounded-lg hover:bg-violet-700 transition-colors shadow-sm"
      >
        <RefreshCw size={16} />
        重试
      </button>
    </div>
  );
}

// ─── Main Page ───────────────────────────────────────────────────────────
export function KeywordIntelligencePage() {
  const { storeId: STORE_ID, loading: storeLoading, error: storeError } = useStoreId();
  const [overview, setOverview] = useState<any>(null);
  const [insights, setInsights] = useState<any[]>([]);
  const [summary, setSummary] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeSegment, setActiveSegment] = useState('all');
  const [selectedInsight, setSelectedInsight] = useState<any>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [actionMessage, setActionMessage] = useState<{ type: 'success' | 'error' | 'info'; text: string } | null>(null);
  const [dateRange, setDateRange] = useState<DateRange>('14d');
  const [searchQuery, setSearchQuery] = useState('');
  const [sortField, setSortField] = useState<string>('confidenceScore');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [currentPage, setCurrentPage] = useState(1);
  const [selectedRows, setSelectedRows] = useState<Set<string>>(new Set());
  const [filterSource, setFilterSource] = useState<string>('all');
  const [filterRisk, setFilterRisk] = useState<string>('all');
  const pageSize = 10;

  // ─── Fetch data ───────────────────────────────────────────────────────
  async function fetchData() {
    if (!STORE_ID) return;
    setLoading(true);
    setError(null);
    try {
      const [overviewData, insightsData, summaryData] = await Promise.all([
        fetchKeywordIntelligenceOverview(STORE_ID),
        fetchKeywordInsights(STORE_ID),
        fetchKeywordSummary(STORE_ID),
      ]);
      setOverview(overviewData);
      setInsights((insightsData ?? []).map(normalizeInsight));
      setSummary(summaryData);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载关键词智能数据失败，请重试。');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (!STORE_ID) return;
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const [overviewData, insightsData, summaryData] = await Promise.all([
          fetchKeywordIntelligenceOverview(STORE_ID),
          fetchKeywordInsights(STORE_ID),
          fetchKeywordSummary(STORE_ID),
        ]);
        if (cancelled) return;
        setOverview(overviewData);
        setInsights((insightsData ?? []).map(normalizeInsight));
        setSummary(summaryData);
      } catch (err) {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : '加载关键词智能数据失败，请重试。');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [STORE_ID]);

  // ─── Run analysis ─────────────────────────────────────────────────────
  async function handleRunAnalysis() {
    setAnalyzing(true);
    setActionMessage(null);
    try {
      const result = await analyzeKeywordHealth(STORE_ID);
      // Re-fetch data after analysis
      const [overviewData, insightsData, summaryData] = await Promise.all([
        fetchKeywordIntelligenceOverview(STORE_ID),
        fetchKeywordInsights(STORE_ID),
        fetchKeywordSummary(STORE_ID),
      ]);
      setOverview(overviewData);
      setInsights((insightsData ?? []).map(normalizeInsight));
      setSummary(summaryData);

      const count = Number(result?.count ?? 0);
      if (count > 0) {
        setActionMessage({ type: 'success', text: `分析完成，本次生成 ${count} 条关键词洞察。` });
      } else {
        setActionMessage({
          type: 'info',
          text: '分析已完成，但没有可分析的数据。关键词洞察来自已同步的广告/搜索词数据——请先在「平台连接」同步亚马逊广告数据后再运行分析。',
        });
      }
    } catch (err) {
      setActionMessage({
        type: 'error',
        text: err instanceof Error ? err.message : '运行分析失败，请重试。',
      });
    } finally {
      setAnalyzing(false);
    }
  }

  // ─── Action handlers ──────────────────────────────────────────────────
  async function handleApplyInsight(id: string) {
    try {
      await applyKeywordInsight(id);
      const insightsData = await fetchKeywordInsights(STORE_ID);
      setInsights((insightsData ?? []).map(normalizeInsight));
      setActionMessage({ type: 'success', text: '已应用该建议。' });
    } catch (err) {
      setActionMessage({ type: 'error', text: err instanceof Error ? err.message : '应用建议失败，请重试。' });
    }
  }

  async function handleWatchInsight(id: string) {
    try {
      await watchKeywordInsight(id);
      const insightsData = await fetchKeywordInsights(STORE_ID);
      setInsights((insightsData ?? []).map(normalizeInsight));
      setActionMessage({ type: 'success', text: '已加入观察列表。' });
    } catch (err) {
      setActionMessage({ type: 'error', text: err instanceof Error ? err.message : '操作失败，请重试。' });
    }
  }

  async function handleDismissInsight(id: string) {
    try {
      await dismissKeywordInsight(id);
      const insightsData = await fetchKeywordInsights(STORE_ID);
      setInsights((insightsData ?? []).map(normalizeInsight));
      setActionMessage({ type: 'success', text: '已忽略该建议。' });
    } catch (err) {
      setActionMessage({ type: 'error', text: err instanceof Error ? err.message : '操作失败，请重试。' });
    }
  }

  // ─── Bulk actions over the selected rows ──────────────────────────────
  async function handleApplySelected() {
    const ids = Array.from(selectedRows);
    if (ids.length === 0) return;
    let ok = 0;
    for (const id of ids) {
      try {
        await applyKeywordInsight(id);
        ok++;
      } catch {
        /* keep going; report aggregate below */
      }
    }
    const insightsData = await fetchKeywordInsights(STORE_ID);
    setInsights((insightsData ?? []).map(normalizeInsight));
    setSelectedRows(new Set());
    setActionMessage(
      ok === ids.length
        ? { type: 'success', text: `已应用所选 ${ok} 条建议。` }
        : { type: 'error', text: `应用完成：成功 ${ok} 条，失败 ${ids.length - ok} 条。` },
    );
  }

  async function handleWatchSelected() {
    const ids = Array.from(selectedRows);
    if (ids.length === 0) return;
    let ok = 0;
    for (const id of ids) {
      try {
        await watchKeywordInsight(id);
        ok++;
      } catch {
        /* keep going */
      }
    }
    const insightsData = await fetchKeywordInsights(STORE_ID);
    setInsights((insightsData ?? []).map(normalizeInsight));
    setSelectedRows(new Set());
    setActionMessage(
      ok === ids.length
        ? { type: 'success', text: `已将所选 ${ok} 条加入观察列表。` }
        : { type: 'error', text: `操作完成：成功 ${ok} 条，失败 ${ids.length - ok} 条。` },
    );
  }

  // ─── Segment counts ───────────────────────────────────────────────────
  const segmentCounts = useMemo(() => {
    const counts: Record<string, number> = { all: insights.length };
    for (const ins of insights) {
      counts[ins.segment] = (counts[ins.segment] || 0) + 1;
    }
    return counts;
  }, [insights]);

  // ─── Filtered + sorted insights ───────────────────────────────────────
  const processedInsights = useMemo(() => {
    let result = [...insights];

    // Segment filter
    if (activeSegment !== 'all') {
      result = result.filter((i) => i.segment === activeSegment);
    }

    // Source filter
    if (filterSource !== 'all') {
      result = result.filter((i) => i.source === filterSource);
    }

    // Risk filter
    if (filterRisk !== 'all') {
      result = result.filter((i) => i.riskLevel === filterRisk);
    }

    // Search
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      result = result.filter((i) => i.keywordText.toLowerCase().includes(q));
    }

    // Sort
    result.sort((a, b) => {
      const aVal = a[sortField];
      const bVal = b[sortField];
      if (typeof aVal === 'number' && typeof bVal === 'number') {
        return sortDir === 'asc' ? aVal - bVal : bVal - aVal;
      }
      return sortDir === 'asc'
        ? String(aVal).localeCompare(String(bVal))
        : String(bVal).localeCompare(String(aVal));
    });

    return result;
  }, [insights, activeSegment, filterSource, filterRisk, searchQuery, sortField, sortDir]);

  const totalPages = Math.ceil(processedInsights.length / pageSize);
  const paginatedInsights = processedInsights.slice(
    (currentPage - 1) * pageSize,
    currentPage * pageSize,
  );

  // ─── Sort handler ─────────────────────────────────────────────────────
  function handleSort(field: string) {
    if (sortField === field) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortField(field);
      setSortDir('desc');
    }
    setCurrentPage(1);
  }

  // ─── Row selection ────────────────────────────────────────────────────
  function toggleRow(id: string) {
    setSelectedRows((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAll() {
    if (selectedRows.size === paginatedInsights.length) {
      setSelectedRows(new Set());
    } else {
      setSelectedRows(new Set(paginatedInsights.map((i) => i.id)));
    }
  }

  // ─── Health score color ───────────────────────────────────────────────
  function getScoreColor(score: number): string {
    if (score >= 75) return 'text-emerald-600';
    if (score >= 50) return 'text-yellow-600';
    return 'text-red-600';
  }

  function getScoreCircleColor(score: number): string {
    if (score >= 75) return 'text-emerald-500';
    if (score >= 50) return 'text-yellow-500';
    return 'text-red-500';
  }

  // ─── ACoS color ──────────────────────────────────────────────────────
  function getAcosColor(acos: number): string {
    if (acos <= 25) return 'text-emerald-600';
    if (acos <= 40) return 'text-yellow-600';
    return 'text-red-600';
  }

  // ─── Sort indicator ──────────────────────────────────────────────────
  function SortIndicator({ field }: { field: string }) {
    if (sortField !== field) return <ChevronDown size={12} className="text-slate-300" />;
    return sortDir === 'asc' ? (
      <ChevronDown size={12} className="text-slate-600 rotate-180" />
    ) : (
      <ChevronDown size={12} className="text-slate-600" />
    );
  }

  // ─── Error state ──────────────────────────────────────────────────────
  if (error || storeError) {
    return (
      <div className="space-y-6">
        {/* ── Page Header ────────────────────────────────────────────── */}
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <Brain size={24} className="text-violet-600" />
              <h1 className="text-2xl font-bold text-slate-900">关键词智能中心</h1>
            </div>
            <p className="text-sm text-slate-500 mt-1">
              AI 驱动的关键词健康分析与优化
            </p>
          </div>
        </div>
        <ErrorState message={storeError || error} onRetry={fetchData} />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* ── Page Header ────────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <Brain size={24} className="text-violet-600" />
            <h1 className="text-2xl font-bold text-slate-900">关键词智能中心</h1>
          </div>
          <p className="text-sm text-slate-500 mt-1">
            AI 驱动的关键词健康分析与优化
          </p>
        </div>
        <div className="flex items-center gap-3">
          {/* Date range selector */}
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-lg p-1">
            {dateRanges.map((range) => (
              <button
                key={range}
                onClick={() => setDateRange(range)}
                className={cn(
                  'px-3 py-1.5 text-sm font-medium rounded-md transition-all',
                  dateRange === range
                    ? 'bg-white text-slate-900 shadow-sm border border-slate-200'
                    : 'text-slate-500 hover:text-slate-700',
                )}
              >
                {range}
              </button>
            ))}
          </div>
          {/* Run Analysis button */}
          <button
            onClick={handleRunAnalysis}
            disabled={analyzing}
            className="inline-flex items-center gap-2 px-4 py-2.5 text-sm font-medium text-white bg-violet-600 hover:bg-violet-700 disabled:opacity-60 rounded-lg shadow-sm transition-colors"
          >
            {analyzing ? (
              <Loader2 size={16} className="animate-spin" />
            ) : (
              <Sparkles size={16} />
            )}
            运行分析
          </button>
        </div>
      </div>

      {/* ── Action feedback banner ─────────────────────────────────── */}
      {actionMessage && (
        <div
          className={cn(
            'flex items-start gap-2 px-4 py-3 rounded-lg border text-sm',
            actionMessage.type === 'success' && 'bg-emerald-50 border-emerald-200 text-emerald-700',
            actionMessage.type === 'error' && 'bg-red-50 border-red-200 text-red-700',
            actionMessage.type === 'info' && 'bg-blue-50 border-blue-200 text-blue-700',
          )}
        >
          <span className="flex-1">{actionMessage.text}</span>
          <button onClick={() => setActionMessage(null)} className="text-sm font-medium underline shrink-0">
            关闭
          </button>
        </div>
      )}

      {/* ── Keyword Health Overview ─────────────────────────────────── */}
      {loading || storeLoading ? (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-[88px] rounded-xl" />
          ))}
        </div>
      ) : (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <MetricCard
            icon={Key}
            label="关键词总数"
            value={formatNumber(overview?.totalKeywords ?? 0)}
            iconColor="text-slate-600"
            iconBg="bg-slate-100"
          />
          <MetricCard
            icon={Activity}
            label="活跃关键词"
            value={formatNumber(overview?.activeKeywords ?? 0)}
            iconColor="text-emerald-600"
            iconBg="bg-emerald-100"
          />
          <MetricCard
            icon={Trophy}
            label="优质关键词"
            value={formatNumber(overview?.winnerKeywords ?? 0)}
            iconColor="text-emerald-700"
            iconBg="bg-emerald-100"
          />
          <MetricCard
            icon={Trash2}
            label="浪费关键词"
            value={formatNumber(overview?.wasteKeywords ?? 0)}
            iconColor="text-red-600"
            iconBg="bg-red-100"
          />
          <MetricCard
            icon={TrendingUp}
            label="低曝光高转化"
            value={formatNumber(overview?.lowImpressionHighCvrKeywords ?? 0)}
            iconColor="text-blue-600"
            iconBg="bg-blue-100"
          />
          <MetricCard
            icon={ShieldX}
            label="否定候选词"
            value={formatNumber(overview?.negativeCandidates ?? 0)}
            iconColor="text-orange-600"
            iconBg="bg-orange-100"
          />
          <MetricCard
            icon={Wheat}
            label="收割候选词"
            value={formatNumber(overview?.harvestCandidates ?? 0)}
            iconColor="text-green-600"
            iconBg="bg-green-100"
          />
          {/* Health Score card with circular progress */}
          <div className="bg-white rounded-xl border border-slate-200 p-5 hover:shadow-sm transition-shadow">
            <div className="flex items-center gap-3">
              <CircularProgress
                value={overview?.keywordHealthScore ?? 0}
                size={64}
                strokeWidth={5}
                color={getScoreCircleColor(overview?.keywordHealthScore ?? 0)}
              />
              <div>
                <p className="text-sm text-slate-500">关键词健康评分</p>
                <p className={cn('text-2xl font-bold', getScoreColor(overview?.keywordHealthScore ?? 0))}>
                  {overview?.keywordHealthScore ?? 0}
                </p>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── AI Keyword Summary Panel ────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 p-6">
        <div className="flex items-center gap-2 mb-5">
          <Sparkles size={18} className="text-violet-600" />
          <h2 className="text-lg font-semibold text-slate-900">AI 关键词摘要</h2>
        </div>
        {loading ? (
          <div className="space-y-4">
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-32 w-full" />
          </div>
        ) : summary ? (
          <div className="space-y-4">
            <Callout title="最大问题" variant="danger" icon={AlertTriangle}>
              {summary.biggestProblem}
            </Callout>

            <Callout title="最值得扩展的关键词" variant="success" icon={TrendingUp}>
              {summary.bestKeywordToScale}
            </Callout>

            <Callout title="最值得否定的关键词" variant="warning" icon={ShieldX}>
              {summary.bestKeywordToNegate}
            </Callout>

            <Callout title="Listing 缺失但有转化" variant="info" icon={Info}>
              <div className="flex flex-wrap gap-1.5 mt-1">
                {summary.listingMissingButConverting?.map((term: string) => (
                  <span
                    key={term}
                    className="inline-flex px-2 py-0.5 bg-blue-100 text-blue-700 rounded-md text-xs font-medium"
                  >
                    {term}
                  </span>
                ))}
              </div>
            </Callout>

            <Callout title="竞品机会" variant="purple" icon={Zap}>
              {Array.isArray(summary.competitorOpportunities) ? (
                <div className="flex flex-wrap gap-1.5 mt-1">
                  {summary.competitorOpportunities.map((term: string) => (
                    <span
                      key={term}
                      className="inline-flex px-2 py-0.5 bg-purple-100 text-purple-700 rounded-md text-xs font-medium"
                    >
                      {term}
                    </span>
                  ))}
                </div>
              ) : (
                summary.competitorOpportunities
              )}
            </Callout>

            <div>
              <p className="text-sm font-semibold text-slate-800 mb-2">未来 7 天计划</p>
              <ol className="space-y-2">
                {summary.nextWeekPlan?.map((step: string, idx: number) => (
                  <li key={idx} className="flex items-start gap-2.5 text-sm text-slate-700">
                    <span className="flex items-center justify-center w-5 h-5 rounded-full bg-violet-100 text-violet-700 text-xs font-bold shrink-0 mt-0.5">
                      {idx + 1}
                    </span>
                    {step}
                  </li>
                ))}
              </ol>
            </div>
          </div>
        ) : null}
      </div>

      {/* ── Keyword Segments (filter chips) ─────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 p-4">
        <div className="flex items-center gap-2 overflow-x-auto pb-1 scrollbar-thin">
          {Object.entries(segmentConfig).map(([key, config]) => (
            <button
              key={key}
              onClick={() => {
                setActiveSegment(key);
                setCurrentPage(1);
              }}
              className={cn(
                'inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg border whitespace-nowrap transition-all shrink-0',
                activeSegment === key
                  ? cn(config.color, 'ring-2 ring-offset-1 ring-violet-300')
                  : 'bg-white text-slate-600 border-slate-200 hover:bg-slate-50',
              )}
            >
              {config.label}
              <span className="text-xs opacity-70">({segmentCounts[key] ?? 0})</span>
            </button>
          ))}
        </div>
      </div>

      {/* ── Keyword Diagnosis Table ─────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        {/* Table toolbar */}
        <div className="p-4 border-b border-slate-200">
          <div className="flex flex-col lg:flex-row lg:items-center gap-3">
            {/* Search */}
            <div className="relative flex-1 max-w-sm">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                type="text"
                placeholder="搜索关键词..."
                value={searchQuery}
                onChange={(e) => {
                  setSearchQuery(e.target.value);
                  setCurrentPage(1);
                }}
                className="w-full pl-9 pr-4 py-2 text-sm border border-slate-200 rounded-lg bg-white placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-violet-500/20 focus:border-violet-400 transition-all"
              />
            </div>

            {/* Source filter */}
            <div className="flex items-center gap-2">
              <Filter size={14} className="text-slate-400" />
              <select
                value={filterSource}
                onChange={(e) => {
                  setFilterSource(e.target.value);
                  setCurrentPage(1);
                }}
                className="text-sm border border-slate-200 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-violet-500/20"
              >
                <option value="all">全部来源</option>
                <option value="keyword">关键词</option>
                <option value="search_term">搜索词</option>
                <option value="competitor">竞品</option>
                <option value="product_listing">产品 Listing</option>
              </select>
              <select
                value={filterRisk}
                onChange={(e) => {
                  setFilterRisk(e.target.value);
                  setCurrentPage(1);
                }}
                className="text-sm border border-slate-200 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-violet-500/20"
              >
                <option value="all">全部风险等级</option>
                <option value="low">低风险</option>
                <option value="medium">中风险</option>
                <option value="high">高风险</option>
              </select>
            </div>

            {/* Bulk actions */}
            {selectedRows.size > 0 && (
              <div className="flex items-center gap-2 ml-auto">
                <span className="text-sm text-slate-500">
                  已选 {selectedRows.size} 项
                </span>
                <button
                  onClick={handleApplySelected}
                  className="px-3 py-1.5 text-sm font-medium text-white bg-violet-600 hover:bg-violet-700 rounded-lg transition-colors">
                  应用所选
                </button>
                <button
                  onClick={handleWatchSelected}
                  className="px-3 py-1.5 text-sm font-medium text-slate-700 bg-white border border-slate-200 hover:bg-slate-50 rounded-lg transition-colors">
                  关注所选
                </button>
              </div>
            )}
          </div>
        </div>

        {/* Table */}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-200">
                <th className="w-10 px-3 py-3">
                  <input
                    type="checkbox"
                    checked={paginatedInsights.length > 0 && selectedRows.size === paginatedInsights.length}
                    onChange={toggleAll}
                    className="rounded border-slate-300 text-violet-600 focus:ring-violet-500"
                  />
                </th>
                <th
                  className="text-left px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900"
                  onClick={() => handleSort('keywordText')}
                >
                  <span className="inline-flex items-center gap-1">
                    关键词 <SortIndicator field="keywordText" />
                  </span>
                </th>
                <th className="text-left px-3 py-3 font-semibold text-slate-600 whitespace-nowrap">分组</th>
                <th className="text-left px-3 py-3 font-semibold text-slate-600 whitespace-nowrap">匹配类型</th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('impressions')}>
                  <span className="inline-flex items-center gap-1 justify-end">曝光 <SortIndicator field="impressions" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('clicks')}>
                  <span className="inline-flex items-center gap-1 justify-end">点击 <SortIndicator field="clicks" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('orders')}>
                  <span className="inline-flex items-center gap-1 justify-end">订单 <SortIndicator field="orders" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('spend')}>
                  <span className="inline-flex items-center gap-1 justify-end">花费 <SortIndicator field="spend" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('sales')}>
                  <span className="inline-flex items-center gap-1 justify-end">销售额 <SortIndicator field="sales" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('ctr')}>
                  <span className="inline-flex items-center gap-1 justify-end">CTR <SortIndicator field="ctr" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('cvr')}>
                  <span className="inline-flex items-center gap-1 justify-end">CVR <SortIndicator field="cvr" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('cpc')}>
                  <span className="inline-flex items-center gap-1 justify-end">CPC <SortIndicator field="cpc" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('acos')}>
                  <span className="inline-flex items-center gap-1 justify-end">ACoS <SortIndicator field="acos" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap cursor-pointer hover:text-slate-900" onClick={() => handleSort('roas')}>
                  <span className="inline-flex items-center gap-1 justify-end">ROAS <SortIndicator field="roas" /></span>
                </th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap">竞价</th>
                <th className="text-right px-3 py-3 font-semibold text-slate-600 whitespace-nowrap">建议竞价</th>
                <th className="text-center px-3 py-3 font-semibold text-slate-600 whitespace-nowrap">Listing</th>
                <th className="text-left px-3 py-3 font-semibold text-slate-600 whitespace-nowrap">置信度</th>
                <th className="text-left px-3 py-3 font-semibold text-slate-600 whitespace-nowrap">风险</th>
                <th className="text-left px-3 py-3 font-semibold text-slate-600 whitespace-nowrap min-w-[200px]">建议</th>
                <th className="w-12 px-3 py-3"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {loading || storeLoading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i}>
                    <td colSpan={21} className="px-3 py-4">
                      <Skeleton className="h-5 w-full" />
                    </td>
                  </tr>
                ))
              ) : paginatedInsights.length === 0 ? (
                <tr>
                  <td colSpan={21} className="px-4 py-12 text-center text-sm text-slate-400">
                    没有匹配筛选条件的关键词。
                  </td>
                </tr>
              ) : (
                paginatedInsights.map((ins) => {
                  const seg = segmentConfig[ins.segment] || segmentConfig.all;
                  const bidDiff = ins.suggestedBid - ins.currentBid;
                  return (
                    <tr
                      key={ins.id}
                      className="hover:bg-slate-50/50 transition-colors"
                    >
                      {/* Checkbox */}
                      <td className="px-3 py-3">
                        <input
                          type="checkbox"
                          checked={selectedRows.has(ins.id)}
                          onChange={() => toggleRow(ins.id)}
                          className="rounded border-slate-300 text-violet-600 focus:ring-violet-500"
                        />
                      </td>

                      {/* Keyword + source */}
                      <td className="px-3 py-3">
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-slate-800 whitespace-nowrap">
                            {ins.keywordText}
                          </span>
                          <span
                            className={cn(
                              'inline-flex px-1.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wide',
                              sourceStyles[ins.source] || 'bg-slate-100 text-slate-600',
                            )}
                          >
                            {ins.source.replace('_', ' ')}
                          </span>
                        </div>
                      </td>

                      {/* Segment */}
                      <td className="px-3 py-3">
                        <span className={cn('inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold', seg.color)}>
                          {seg.label}
                        </span>
                      </td>

                      {/* Match type */}
                      <td className="px-3 py-3">
                        <span className={cn('inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold uppercase tracking-wide', matchTypeStyles[ins.matchType])}>
                          {ins.matchType}
                        </span>
                      </td>

                      {/* Impressions */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatNumber(ins.impressions)}
                      </td>

                      {/* Clicks */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatNumber(ins.clicks)}
                      </td>

                      {/* Orders */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatNumber(ins.orders)}
                      </td>

                      {/* Spend */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(ins.spend)}
                      </td>

                      {/* Sales */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(ins.sales)}
                      </td>

                      {/* CTR */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatPercent(ins.ctr)}
                      </td>

                      {/* CVR */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatPercent(ins.cvr)}
                      </td>

                      {/* CPC */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(ins.cpc)}
                      </td>

                      {/* ACoS */}
                      <td className={cn('px-3 py-3 text-right font-medium whitespace-nowrap', getAcosColor(ins.acos))}>
                        {formatPercent(ins.acos)}
                      </td>

                      {/* ROAS */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {ins.roas.toFixed(2)}x
                      </td>

                      {/* Current Bid */}
                      <td className="px-3 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(ins.currentBid)}
                      </td>

                      {/* Suggested Bid */}
                      <td className="px-3 py-3 text-right whitespace-nowrap">
                        <span className="inline-flex items-center gap-1">
                          {ins.suggestedBid > 0 ? formatCurrency(ins.suggestedBid) : '--'}
                          {ins.suggestedBid > 0 && bidDiff !== 0 && (
                            <ArrowRight
                              size={12}
                              className={cn(
                                bidDiff > 0 ? 'text-emerald-500' : 'text-red-500',
                              )}
                            />
                          )}
                        </span>
                      </td>

                      {/* Listing Coverage */}
                      <td className="px-3 py-3 text-center">
                        {ins.listingCovered ? (
                          <CheckCircle2 size={16} className="text-emerald-500 mx-auto" />
                        ) : (
                          <XCircle size={16} className="text-red-500 mx-auto" />
                        )}
                      </td>

                      {/* Confidence */}
                      <td className="px-3 py-3">
                        <ConfidenceBar value={ins.confidenceScore} />
                      </td>

                      {/* Risk */}
                      <td className="px-3 py-3">
                        <span className={cn('inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold capitalize', getRiskColor(ins.riskLevel))}>
                          {ins.riskLevel}
                        </span>
                      </td>

                      {/* Recommendation */}
                      <td className="px-3 py-3">
                        <p className="text-xs text-slate-600 line-clamp-2 max-w-[220px]">
                          {ins.recommendation}
                        </p>
                      </td>

                      {/* Actions */}
                      <td className="px-3 py-3">
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <button className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors">
                              <MoreHorizontal size={14} />
                            </button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-40">
                            <DropdownMenuItem className="text-sm" onClick={() => handleApplyInsight(ins.id)}>
                              <CheckCircle2 size={14} className="mr-2 text-emerald-500" />
                              应用
                            </DropdownMenuItem>
                            <DropdownMenuItem className="text-sm" onClick={() => handleWatchInsight(ins.id)}>
                              <Eye size={14} className="mr-2 text-blue-500" />
                              关注
                            </DropdownMenuItem>
                            <DropdownMenuItem className="text-sm" onClick={() => handleDismissInsight(ins.id)}>
                              <XCircle size={14} className="mr-2 text-slate-400" />
                              忽略
                            </DropdownMenuItem>
                            <DropdownMenuItem
                              className="text-sm"
                              onClick={() => setSelectedInsight(ins)}
                            >
                              <Info size={14} className="mr-2 text-violet-500" />
                              查看详情
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {!loading && !storeLoading && processedInsights.length > 0 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-slate-200">
            <p className="text-sm text-slate-500">
              显示 {(currentPage - 1) * pageSize + 1} 至{' '}
              {Math.min(currentPage * pageSize, processedInsights.length)} 条，共{' '}
              {processedInsights.length} 条结果
            </p>
            <div className="flex items-center gap-1">
              <button
                onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                disabled={currentPage === 1}
                className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                <ChevronLeft size={16} />
              </button>
              {Array.from({ length: totalPages }, (_, i) => i + 1).map((page) => (
                <button
                  key={page}
                  onClick={() => setCurrentPage(page)}
                  className={cn(
                    'w-8 h-8 rounded-md text-sm font-medium transition-colors',
                    currentPage === page
                      ? 'bg-violet-600 text-white'
                      : 'text-slate-600 hover:bg-slate-100',
                  )}
                >
                  {page}
                </button>
              ))}
              <button
                onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                disabled={currentPage === totalPages}
                className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                <ChevronRight size={16} />
              </button>
            </div>
          </div>
        )}
      </div>

      {/* ── Keyword Detail Drawer ───────────────────────────────────── */}
      <Sheet open={!!selectedInsight} onOpenChange={(open) => !open && setSelectedInsight(null)}>
        <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
          {selectedInsight && (() => {
            const ins = selectedInsight;
            const seg = segmentConfig[ins.segment] || segmentConfig.all;
            return (
              <>
                <SheetHeader>
                  <SheetTitle className="text-lg">关键词详情</SheetTitle>
                </SheetHeader>

                <div className="px-4 pb-6 space-y-6 overflow-y-auto">
                  {/* Keyword text */}
                  <div>
                    <p className="text-xl font-bold text-slate-900">{ins.keywordText}</p>
                    <div className="flex items-center gap-2 mt-2">
                      <span
                        className={cn(
                          'inline-flex px-2 py-0.5 rounded text-[11px] font-semibold uppercase tracking-wide',
                          sourceStyles[ins.source] || 'bg-slate-100 text-slate-600',
                        )}
                      >
                        {ins.source.replace('_', ' ')}
                      </span>
                      <span className={cn('inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold', seg.color)}>
                        {seg.label}
                      </span>
                    </div>
                  </div>

                  {/* Score gauges */}
                  <div className="grid grid-cols-3 gap-4">
                    <div className="text-center">
                      <CircularProgress
                        value={ins.healthScore}
                        size={72}
                        strokeWidth={5}
                        color={getScoreCircleColor(ins.healthScore)}
                        label="健康度"
                      />
                    </div>
                    <div className="text-center">
                      <CircularProgress
                        value={ins.opportunityScore}
                        size={72}
                        strokeWidth={5}
                        color={getScoreCircleColor(ins.opportunityScore)}
                        label="机会"
                      />
                    </div>
                    <div className="text-center">
                      <CircularProgress
                        value={ins.wasteScore}
                        size={72}
                        strokeWidth={5}
                        color={ins.wasteScore > 50 ? 'text-red-500' : ins.wasteScore > 25 ? 'text-yellow-500' : 'text-emerald-500'}
                        label="浪费"
                      />
                    </div>
                  </div>

                  {/* Confidence */}
                  <div>
                    <p className="text-sm font-medium text-slate-500 mb-1">置信度评分</p>
                    <ConfidenceBar value={ins.confidenceScore} />
                  </div>

                  {/* Current Data grid */}
                  <div>
                    <p className="text-sm font-semibold text-slate-800 mb-3">当前数据</p>
                    <div className="grid grid-cols-2 gap-3">
                      {[
                        { label: '曝光量', value: formatNumber(ins.impressions) },
                        { label: '点击量', value: formatNumber(ins.clicks) },
                        { label: '订单数', value: formatNumber(ins.orders) },
                        { label: '花费', value: formatCurrency(ins.spend) },
                        { label: '销售额', value: formatCurrency(ins.sales) },
                        { label: 'ACoS', value: formatPercent(ins.acos) },
                        { label: 'ROAS', value: `${ins.roas.toFixed(2)}x` },
                        { label: 'CTR', value: formatPercent(ins.ctr) },
                        { label: 'CVR', value: formatPercent(ins.cvr) },
                        { label: 'CPC', value: formatCurrency(ins.cpc) },
                      ].map((item) => (
                        <div key={item.label} className="bg-slate-50 rounded-lg px-3 py-2">
                          <p className="text-xs text-slate-500">{item.label}</p>
                          <p className="text-sm font-semibold text-slate-800">{item.value}</p>
                        </div>
                      ))}
                    </div>
                  </div>

                  {/* Related info */}
                  <div className="grid grid-cols-2 gap-3">
                    <div className="bg-slate-50 rounded-lg px-3 py-2">
                      <p className="text-xs text-slate-500">广告活动</p>
                      <p className="text-sm font-medium text-slate-800">{ins.campaignName}</p>
                    </div>
                    <div className="bg-slate-50 rounded-lg px-3 py-2">
                      <p className="text-xs text-slate-500">目标</p>
                      <p className="text-sm font-medium text-slate-800">{ins.goalName}</p>
                    </div>
                  </div>

                  {/* Listing Coverage */}
                  <div>
                    <p className="text-sm font-medium text-slate-500 mb-1">Listing 覆盖</p>
                    <div className="flex items-center gap-2">
                      {ins.listingCovered ? (
                        <>
                          <CheckCircle2 size={16} className="text-emerald-500" />
                          <span className="text-sm text-emerald-700 font-medium">已覆盖</span>
                        </>
                      ) : (
                        <>
                          <XCircle size={16} className="text-red-500" />
                          <span className="text-sm text-red-700 font-medium">未覆盖</span>
                        </>
                      )}
                    </div>
                  </div>

                  {/* AI Explanation */}
                  <div>
                    <p className="text-sm font-medium text-slate-500 mb-1">AI 解释</p>
                    <p className="text-sm text-slate-700 leading-relaxed">{ins.reason}</p>
                  </div>

                  {/* Recommended Action */}
                  <div>
                    <p className="text-sm font-medium text-slate-500 mb-1">建议操作</p>
                    <p className="text-sm text-slate-800 font-medium">{ins.recommendation}</p>
                  </div>

                  {/* Expected Impact */}
                  <div>
                    <p className="text-sm font-medium text-slate-500 mb-1">预期影响</p>
                    <p className="text-sm text-emerald-700 font-medium">{ins.expectedImpact}</p>
                  </div>

                  {/* Risk Level */}
                  <div>
                    <p className="text-sm font-medium text-slate-500 mb-1">风险等级</p>
                    <span className={cn('inline-flex px-2.5 py-1 rounded-md text-xs font-semibold capitalize', getRiskColor(ins.riskLevel))}>
                      {ins.riskLevel}
                    </span>
                  </div>

                  {/* Action buttons */}
                  <div className="flex items-center gap-2 pt-2 border-t border-slate-200">
                    <button
                      onClick={() => handleApplyInsight(ins.id)}
                      className="flex-1 inline-flex items-center justify-center gap-2 px-4 py-2.5 text-sm font-medium text-white bg-violet-600 hover:bg-violet-700 rounded-lg transition-colors"
                    >
                      <CheckCircle2 size={16} />
                      应用
                    </button>
                    <button
                      onClick={() => handleWatchInsight(ins.id)}
                      className="flex-1 inline-flex items-center justify-center gap-2 px-4 py-2.5 text-sm font-medium text-slate-700 bg-white border border-slate-200 hover:bg-slate-50 rounded-lg transition-colors"
                    >
                      <Eye size={16} />
                      关注
                    </button>
                    <button
                      onClick={() => handleDismissInsight(ins.id)}
                      className="flex-1 inline-flex items-center justify-center gap-2 px-4 py-2.5 text-sm font-medium text-slate-500 bg-white border border-slate-200 hover:bg-slate-50 rounded-lg transition-colors"
                    >
                      <X size={16} />
                      忽略
                    </button>
                  </div>
                </div>
              </>
            );
          })()}
        </SheetContent>
      </Sheet>
    </div>
  );
}
