import { useState, useMemo } from 'react';
import {
  TrendingUp,
  TrendingDown,
  ArrowUp,
  ArrowDown,
  Plus,
  Minus,
  Crosshair,
  Pause,
  Rocket,
  Target,
  Package,
  AlertTriangle,
  Eye,
  Zap,
  Search,
  ChevronDown,
  ChevronUp,
  Sparkles,
  CheckCircle2,
  XCircle,
  Clock,
  ShieldAlert,
  DollarSign,
} from 'lucide-react';

import {
  fetchRecommendations,
  applyRecommendation,
  dismissRecommendation,
  watchRecommendation,
} from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useQueryClient } from '@tanstack/react-query';
import { notify } from '../lib/toast';
import { useStoreId } from '../lib/useStoreId';
import { getRiskColor, formatCurrency, formatPercent, cn } from '../lib/utils';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/ui/select';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '../components/ui/collapsible';
import type { Recommendation, RecommendationType, RiskLevel } from '../types';

// ─── Icon mapping for recommendation types ────────────────────────────
const recIconMap: Record<RecommendationType, React.ComponentType<{ className?: string }>> = {
  increase_budget: TrendingUp,
  decrease_budget: TrendingDown,
  increase_bid: ArrowUp,
  decrease_bid: ArrowDown,
  add_keyword: Plus,
  add_negative_keyword: Minus,
  add_competitor_asin: Crosshair,
  pause_target: Pause,
  launch_boost: Rocket,
  move_to_exact: Target,
  split_campaign: Target,
  inventory_warning: Package,
  high_acos_warning: AlertTriangle,
  low_impression_warning: Eye,
  rank_opportunity: Zap,
};

const riskBorderColor: Record<RiskLevel, string> = {
  low: 'border-l-emerald-500',
  medium: 'border-l-amber-500',
  high: 'border-l-red-500',
};

const riskIconBg: Record<RiskLevel, string> = {
  low: 'bg-emerald-100 text-emerald-600',
  medium: 'bg-amber-100 text-amber-600',
  high: 'bg-red-100 text-red-600',
};

const statusConfig: Record<string, { label: string; className: string; icon: React.ComponentType<{ className?: string }> }> = {
  pending: { label: '待处理', className: 'bg-blue-100 text-blue-700 border-blue-200', icon: Clock },
  applied: { label: '已应用', className: 'bg-emerald-100 text-emerald-700 border-emerald-200', icon: CheckCircle2 },
  dismissed: { label: '已忽略', className: 'bg-slate-100 text-slate-600 border-slate-200', icon: XCircle },
  watching: { label: '已关注', className: 'bg-purple-100 text-purple-700 border-purple-200', icon: Eye },
};

const typeLabelMap: Record<RecommendationType, string> = {
  increase_budget: '增加预算',
  decrease_budget: '减少预算',
  increase_bid: '提高竞价',
  decrease_bid: '降低竞价',
  add_keyword: '添加关键词',
  add_negative_keyword: '添加否定关键词',
  add_competitor_asin: '添加竞品 ASIN',
  pause_target: '暂停目标',
  launch_boost: '新品助推',
  move_to_exact: '移至精确匹配',
  split_campaign: '拆分广告活动',
  inventory_warning: '库存警告',
  high_acos_warning: '高 ACoS 警告',
  low_impression_warning: '低曝光警告',
  rank_opportunity: '排名机会',
};

// ─── Filter Tab ───────────────────────────────────────────────────────
const filterTabs = [
  { key: 'all' as const, label: '全部' },
  { key: 'pending' as const, label: '待处理' },
  { key: 'applied' as const, label: '已应用' },
  { key: 'dismissed' as const, label: '已忽略' },
  { key: 'watching' as const, label: '已关注' },
] as const;

type FilterTab = (typeof filterTabs)[number]['key'];

// ─── Recommendation Card ──────────────────────────────────────────────
function RecommendationCard({
  rec,
  onApply,
  onDismiss,
  onWatch,
}: {
  rec: Recommendation;
  onApply: (id: string) => void;
  onDismiss: (id: string) => void;
  onWatch: (id: string) => void;
}) {
  const [whyOpen, setWhyOpen] = useState(true);
  const Icon = recIconMap[rec.type] || Zap;
  const statusInfo = statusConfig[rec.status];

  return (
    <div
      className={cn(
        'bg-white rounded-lg border border-slate-200 border-l-4 shadow-sm hover:shadow-md transition-shadow',
        riskBorderColor[rec.riskLevel],
      )}
    >
      <div className="p-5">
        {/* Top row: icon, title, badges */}
        <div className="flex items-start gap-3">
          <div
            className={cn(
              'flex-shrink-0 w-10 h-10 rounded-lg flex items-center justify-center',
              riskIconBg[rec.riskLevel],
            )}
          >
            <Icon className="w-5 h-5" />
          </div>

          <div className="flex-1 min-w-0">
            <div className="flex flex-wrap items-center gap-2 mb-1">
              <h3 className="text-sm font-semibold text-slate-900 leading-tight">
                {rec.title}
              </h3>
              <Badge className={cn('text-[10px] font-semibold uppercase tracking-wide border', getRiskColor(rec.riskLevel))}>
                {rec.riskLevel}
              </Badge>
              {rec.status !== 'pending' && statusInfo && (
                <Badge className={cn('text-[10px] font-semibold border', statusInfo.className)}>
                  <statusInfo.icon className="w-3 h-3 mr-1" />
                  {statusInfo.label}
                </Badge>
              )}
            </div>

            <p className="text-sm text-slate-600 leading-relaxed mb-2">
              {rec.description}
            </p>

            {/* Target entity tag */}
            {rec.targetEntityName && (
              <div className="flex items-center gap-1.5 mb-3">
                <span className="text-xs text-slate-400">
                  {rec.targetEntityType}:
                </span>
                <span className="text-xs font-medium text-slate-700 bg-slate-100 px-2 py-0.5 rounded">
                  {rec.targetEntityName}
                </span>
              </div>
            )}

            {/* Why section - collapsible */}
            <Collapsible open={whyOpen} onOpenChange={setWhyOpen}>
              <CollapsibleTrigger asChild>
                <button className="flex items-center gap-1 text-xs font-semibold text-slate-500 hover:text-slate-700 transition-colors mb-2">
                  <Sparkles className="w-3.5 h-3.5 text-blue-500" />
                  为什么推荐这个？
                  {whyOpen ? (
                    <ChevronUp className="w-3.5 h-3.5" />
                  ) : (
                    <ChevronDown className="w-3.5 h-3.5" />
                  )}
                </button>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="bg-blue-50 border border-blue-100 rounded-md p-3 mb-3">
                  <p className="text-xs text-blue-800 leading-relaxed">
                    {rec.reason}
                  </p>
                </div>
              </CollapsibleContent>
            </Collapsible>

            {/* Current Data grid */}
            {Object.keys(rec.currentData ?? {}).length > 0 && (
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-x-4 gap-y-2 mb-3">
                {Object.entries(rec.currentData ?? {}).map(([key, value]) => (
                  <div key={key} className="flex flex-col">
                    <span className="text-[10px] uppercase tracking-wide text-slate-400 font-medium">
                      {key.replace(/([A-Z])/g, ' $1').replace(/^./, (s) => s.toUpperCase())}
                    </span>
                    <span className="text-sm font-semibold text-slate-800">
                      {formatDataValue(key, value)}
                    </span>
                  </div>
                ))}
              </div>
            )}

            {/* Expected Impact */}
            <div className="flex items-start gap-2 bg-emerald-50 border border-emerald-100 rounded-md p-2.5 mb-4">
              <Zap className="w-4 h-4 text-emerald-600 flex-shrink-0 mt-0.5" />
              <div>
                <span className="text-[10px] uppercase tracking-wide text-emerald-600 font-semibold block mb-0.5">
                  预期影响
                </span>
                <span className="text-xs text-emerald-800 leading-relaxed">
                  {rec.expectedImpact}
                </span>
              </div>
            </div>

            {/* Action buttons */}
            {rec.status === 'pending' && (
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  className="bg-blue-600 hover:bg-blue-700 text-white h-8 text-xs font-medium"
                  onClick={() => onApply(rec.id)}
                >
                  <CheckCircle2 className="w-3.5 h-3.5 mr-1" />
                  应用
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  className="h-8 text-xs font-medium border-slate-300 text-slate-600 hover:bg-slate-50"
                  onClick={() => onDismiss(rec.id)}
                >
                  <XCircle className="w-3.5 h-3.5 mr-1" />
                  忽略
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-8 text-xs font-medium text-slate-500 hover:text-slate-700 hover:bg-slate-100"
                  onClick={() => onWatch(rec.id)}
                >
                  <Eye className="w-3.5 h-3.5 mr-1" />
                  关注
                </Button>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Helper: format currentData values based on key name ──────────────
function formatDataValue(key: string, value: number | string): string {
  if (typeof value === 'string') return value;
  const lowerKey = key.toLowerCase();
  if (lowerKey.includes('acos') || lowerKey.includes('margin') || lowerKey.includes('cvr') || lowerKey.includes('utilization')) {
    return formatPercent(value);
  }
  if (lowerKey.includes('spend') || lowerKey.includes('sales') || lowerKey.includes('budget') || lowerKey.includes('bid') || lowerKey.includes('cpc') || lowerKey.includes('profit') || lowerKey.includes('suggested')) {
    return formatCurrency(value);
  }
  if (lowerKey.includes('orders') || lowerKey.includes('clicks') || lowerKey.includes('impressions') || lowerKey.includes('inventory') || lowerKey.includes('days')) {
    return value.toLocaleString();
  }
  return value.toLocaleString();
}

// ─── Loading skeleton ─────────────────────────────────────────────────
function LoadingSkeleton() {
  return (
    <div className="space-y-4">
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="bg-white rounded-lg border border-slate-200 border-l-4 border-l-slate-200 p-5 animate-pulse">
          <div className="flex items-start gap-3">
            <div className="w-10 h-10 rounded-lg bg-slate-200 flex-shrink-0" />
            <div className="flex-1 space-y-3">
              <div className="h-4 bg-slate-200 rounded w-2/3" />
              <div className="h-3 bg-slate-100 rounded w-full" />
              <div className="h-3 bg-slate-100 rounded w-4/5" />
              <div className="flex gap-2">
                <div className="h-8 bg-slate-200 rounded w-20" />
                <div className="h-8 bg-slate-100 rounded w-20" />
                <div className="h-8 bg-slate-100 rounded w-20" />
              </div>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Summary stat card ────────────────────────────────────────────────
function StatCard({
  label,
  value,
  icon: Icon,
  iconBg,
  iconColor,
}: {
  label: string;
  value: string | number;
  icon: React.ComponentType<{ className?: string }>;
  iconBg: string;
  iconColor: string;
}) {
  return (
    <div className="bg-white rounded-lg border border-slate-200 p-4 flex items-center gap-3 shadow-sm">
      <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', iconBg)}>
        <Icon className={cn('w-5 h-5', iconColor)} />
      </div>
      <div>
        <p className="text-xs text-slate-500 font-medium">{label}</p>
        <p className="text-lg font-bold text-slate-900">{value}</p>
      </div>
    </div>
  );
}

// ─── Main Page Component ──────────────────────────────────────────────
export function RecommendationsPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const [activeTab, setActiveTab] = useState<FilterTab>('all');
  const [typeFilter, setTypeFilter] = useState<string>('all');
  const [riskFilter, setRiskFilter] = useState<string>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const queryClient = useQueryClient();
  const recsKey = ['recommendations', storeId] as const;
  const recsQuery = useApiQuery<Recommendation[]>(
    recsKey,
    () => fetchRecommendations({ storeId: storeId! }).then((data: any) => (Array.isArray(data) ? data : data.items ?? [])),
    { enabled: !!storeId },
  );
  const localRecs = recsQuery.data ?? [];
  const loading = !!storeId && recsQuery.isLoading;
  const error = recsQuery.isError ? recsQuery.error?.message ?? '加载建议失败' : null;
  const loadData = () => recsQuery.refetch();

  function flashMessage(type: 'success' | 'error', text: string) {
    if (type === 'success') notify.success(text);
    else notify.error(text);
  }

  // ─── Computed counts ──────────────────────────────────────────────
  const counts = useMemo(() => {
    return {
      all: localRecs.length,
      pending: localRecs.filter((r) => r.status === 'pending').length,
      applied: localRecs.filter((r) => r.status === 'applied').length,
      dismissed: localRecs.filter((r) => r.status === 'dismissed').length,
      watching: localRecs.filter((r) => r.status === 'watching').length,
    };
  }, [localRecs]);

  const pendingRecs = localRecs.filter((r) => r.status === 'pending');
  const highRiskPending = pendingRecs.filter((r) => r.riskLevel === 'high').length;

  // Estimate weekly savings from low-risk pending recommendations
  const estimatedWeeklySavings = useMemo(() => {
    return pendingRecs
      .filter((r) => r.riskLevel === 'low')
      .reduce((sum, r) => {
        const match = (r.expectedImpact || '').match(/\$([0-9,.]+)/);
        if (match) return sum + parseFloat(match[1].replace(',', ''));
        return sum;
      }, 0);
  }, [pendingRecs]);

  // ─── Filtering ────────────────────────────────────────────────────
  const filteredRecs = useMemo(() => {
    return localRecs.filter((rec) => {
      if (activeTab !== 'all' && rec.status !== activeTab) return false;
      if (typeFilter !== 'all' && rec.type !== typeFilter) return false;
      if (riskFilter !== 'all' && rec.riskLevel !== riskFilter) return false;
      if (searchQuery) {
        const q = searchQuery.toLowerCase();
        return (
          (rec.title || '').toLowerCase().includes(q) ||
          (rec.description || '').toLowerCase().includes(q) ||
          (rec.targetEntityName?.toLowerCase().includes(q) ?? false)
        );
      }
      return true;
    });
  }, [localRecs, activeTab, typeFilter, riskFilter, searchQuery]);

  // ─── Unique recommendation types in data ──────────────────────────
  const availableTypes = useMemo(() => {
    const types = new Set(localRecs.map((r) => r.type));
    return Array.from(types).sort();
  }, [localRecs]);

  // ─── Action handlers ──────────────────────────────────────────────
  const handleApply = async (id: string) => {
    try {
      await applyRecommendation(id);
      queryClient.setQueryData<Recommendation[]>(recsKey, (prev) =>
        (prev ?? []).map((r) => (r.id === id ? { ...r, status: 'applied' as const } : r)),
      );
      flashMessage('success', '建议已应用');
    } catch (err) {
      flashMessage('error', err instanceof Error ? err.message : '应用建议失败');
    }
  };

  const handleDismiss = async (id: string) => {
    try {
      await dismissRecommendation(id);
      queryClient.setQueryData<Recommendation[]>(recsKey, (prev) =>
        (prev ?? []).map((r) => (r.id === id ? { ...r, status: 'dismissed' as const } : r)),
      );
      flashMessage('success', '建议已忽略');
    } catch (err) {
      flashMessage('error', err instanceof Error ? err.message : '忽略建议失败');
    }
  };

  const handleWatch = async (id: string) => {
    try {
      await watchRecommendation(id);
      queryClient.setQueryData<Recommendation[]>(recsKey, (prev) =>
        (prev ?? []).map((r) => (r.id === id ? { ...r, status: 'watching' as const } : r)),
      );
      flashMessage('success', '已加入关注');
    } catch (err) {
      flashMessage('error', err instanceof Error ? err.message : '关注建议失败');
    }
  };

  const handleApplyAllLowRisk = async () => {
    const lowRiskPending = pendingRecs.filter((r) => r.riskLevel === 'low');
    let failures = 0;
    await Promise.all(
      lowRiskPending.map(async (r) => {
        try {
          await applyRecommendation(r.id);
          queryClient.setQueryData<Recommendation[]>(recsKey, (prev) =>
            (prev ?? []).map((rec) => (rec.id === r.id ? { ...rec, status: 'applied' as const } : rec)),
          );
        } catch (err) {
          failures += 1;
        }
      }),
    );
    if (failures > 0) {
      flashMessage('error', `${failures} 条低风险建议应用失败，请重试`);
    } else if (lowRiskPending.length > 0) {
      flashMessage('success', `已应用 ${lowRiskPending.length} 条低风险建议`);
    }
  };

  const lowRiskPendingCount = pendingRecs.filter((r) => r.riskLevel === 'low').length;

  // ─── Loading state ────────────────────────────────────────────────
  if (loading || storeLoading) {
    return (
      <div className="space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <div className="h-8 bg-slate-200 rounded w-64 animate-pulse" />
            <div className="h-4 bg-slate-100 rounded w-48 mt-2 animate-pulse" />
          </div>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="bg-white rounded-lg border border-slate-200 p-4 animate-pulse">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-lg bg-slate-200" />
                <div className="space-y-1">
                  <div className="h-3 bg-slate-200 rounded w-24" />
                  <div className="h-5 bg-slate-200 rounded w-16" />
                </div>
              </div>
            </div>
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
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
              <Sparkles className="w-6 h-6 text-blue-600" />
              AI 建议
            </h1>
          </div>
        </div>
        <div className="text-center py-16">
          <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <AlertTriangle className="w-8 h-8 text-red-400" />
          </div>
          <h3 className="text-lg font-semibold text-slate-700 mb-1">
            加载建议失败
          </h3>
          <p className="text-sm text-slate-500 mb-4">{storeError || error}</p>
          <Button onClick={loadData} variant="outline" className="font-medium">
            重试
          </Button>
        </div>
      </div>
    );
  }

  // ─── No store state ───────────────────────────────────────────────
  if (!storeId) {
    return (
      <div className="space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
              <Sparkles className="w-6 h-6 text-blue-600" />
              AI 建议
            </h1>
          </div>
        </div>
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <Sparkles className="w-12 h-12 text-slate-300 mb-4" />
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
      {/* ─── Page Header ─────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
            <Sparkles className="w-6 h-6 text-blue-600" />
            AI 建议
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            {counts.pending} 条建议等待您审核
          </p>
        </div>
        {lowRiskPendingCount > 0 && (
          <Button
            className="bg-emerald-600 hover:bg-emerald-700 text-white font-medium shadow-sm"
            onClick={handleApplyAllLowRisk}
          >
            <CheckCircle2 className="w-4 h-4 mr-2" />
            应用全部低风险 ({lowRiskPendingCount})
          </Button>
        )}
      </div>

      {/* ─── Summary Stats ───────────────────────────────────────── */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <StatCard
          label="待处理建议"
          value={counts.pending}
          icon={Clock}
          iconBg="bg-blue-100"
          iconColor="text-blue-600"
        />
        <StatCard
          label="高风险项"
          value={highRiskPending}
          icon={ShieldAlert}
          iconBg="bg-red-100"
          iconColor="text-red-600"
        />
        <StatCard
          label="预估每周节省 (低风险)"
          value={estimatedWeeklySavings > 0 ? formatCurrency(estimatedWeeklySavings) : '$0'}
          icon={DollarSign}
          iconBg="bg-emerald-100"
          iconColor="text-emerald-600"
        />
      </div>

      {/* ─── Filter Tabs ─────────────────────────────────────────── */}
      <div className="flex flex-wrap gap-1 bg-slate-100 rounded-lg p-1">
        {filterTabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              'px-4 py-2 text-sm font-medium rounded-md transition-all',
              activeTab === tab.key
                ? 'bg-white text-slate-900 shadow-sm'
                : 'text-slate-500 hover:text-slate-700 hover:bg-slate-50',
            )}
          >
            {tab.label}
            <span
              className={cn(
                'ml-1.5 text-xs font-semibold px-1.5 py-0.5 rounded-full',
                activeTab === tab.key
                  ? 'bg-blue-100 text-blue-700'
                  : 'bg-slate-200 text-slate-500',
              )}
            >
              {counts[tab.key]}
            </span>
          </button>
        ))}
      </div>

      {/* ─── Filter Bar ──────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row gap-3">
        <div className="w-full sm:w-48">
          <Select value={typeFilter} onValueChange={setTypeFilter}>
            <SelectTrigger className="h-9 text-sm">
              <SelectValue placeholder="全部类型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部类型</SelectItem>
              {availableTypes.map((t) => (
                <SelectItem key={t} value={t}>
                  {typeLabelMap[t] || t}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="w-full sm:w-40">
          <Select value={riskFilter} onValueChange={setRiskFilter}>
            <SelectTrigger className="h-9 text-sm">
              <SelectValue placeholder="全部风险等级" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部风险等级</SelectItem>
              <SelectItem value="low">低风险</SelectItem>
              <SelectItem value="medium">中风险</SelectItem>
              <SelectItem value="high">高风险</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
          <Input
            placeholder="搜索建议..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="h-9 pl-9 text-sm"
          />
        </div>
      </div>

      {/* ─── Results count ───────────────────────────────────────── */}
      <div className="flex items-center justify-between">
        <p className="text-sm text-slate-500">
          显示{' '}
          <span className="font-semibold text-slate-700">
            {filteredRecs.length}
          </span>{' '}
          条建议
        </p>
      </div>

      {/* ─── Recommendation Cards ────────────────────────────────── */}
      {filteredRecs.length > 0 ? (
        <div className="space-y-4">
          {filteredRecs.map((rec) => (
            <RecommendationCard
              key={rec.id}
              rec={rec}
              onApply={handleApply}
              onDismiss={handleDismiss}
              onWatch={handleWatch}
            />
          ))}
        </div>
      ) : (
        <div className="text-center py-16">
          <div className="w-16 h-16 bg-slate-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <Sparkles className="w-8 h-8 text-slate-300" />
          </div>
          <h3 className="text-lg font-semibold text-slate-700 mb-1">
            未找到建议
          </h3>
          <p className="text-sm text-slate-500">
            请尝试调整筛选条件或稍后再查看。
          </p>
        </div>
      )}
    </div>
  );
}
