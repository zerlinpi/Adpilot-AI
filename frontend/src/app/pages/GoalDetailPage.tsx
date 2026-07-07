import { lazy, Suspense, useState, useEffect } from 'react';
import { useParams, Link } from 'react-router';
import {
  ArrowLeft, Edit2, Pause, Play, Rocket, TrendingUp, BarChart3,
  Shield, Crosshair, Layers, Tag, Zap, Target,
  DollarSign, ShoppingCart, Activity, Percent,
  Package, Megaphone,
} from 'lucide-react';
import { fetchGoalById, updateGoal } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { formatCurrency, formatPercent, formatNumber, getGoalTypeColor, getCampaignStatusColor, cn } from '../lib/utils';
import type { GoalTypeConfig } from '../types';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// The recharts-based performance chart is lazy-loaded so the heavy charts
// bundle is only fetched when the performance tab's chart is rendered (keeping
// it out of the goal-detail page chunk).
const GoalTrendChart = lazy(() => import('./GoalTrendChart'));


// ─── Icon map for goal types ─────────────────────────────────────────
const iconMap: Record<string, React.ComponentType<any>> = {
  Rocket, TrendingUp, BarChart3, Shield, Crosshair, Layers, Tag, Zap,
};

// ─── Goal type configurations ────────────────────────────────────────
const goalTypeConfigs: GoalTypeConfig[] = [
  { type: 'launch', label: '新品推广', description: '新品冷启动 -- 最大化曝光并收集数据', icon: 'Rocket', color: '#8B5CF6', bgColor: '#F3E8FF', defaultTargetAcos: 35, defaultRisk: 'aggressive', campaignTypes: ['auto', 'manual_keyword', 'pat'] },
  { type: 'profit', label: '利润目标', description: '控制 ACoS 并最大化利润率', icon: 'TrendingUp', color: '#16A34A', bgColor: '#F0FDF4', defaultTargetAcos: 20, defaultRisk: 'conservative', campaignTypes: ['manual_keyword', 'brand'] },
  { type: 'growth', label: '增长目标', description: '激进地扩大销售规模', icon: 'BarChart3', color: '#2563EB', bgColor: '#EFF6FF', defaultTargetAcos: 30, defaultRisk: 'aggressive', campaignTypes: ['auto', 'manual_keyword', 'category'] },
  { type: 'brand_defense', label: '品牌防御', description: '保护品牌关键词免受竞品抢夺', icon: 'Shield', color: '#0D9488', bgColor: '#F0FDFA', defaultTargetAcos: 10, defaultRisk: 'conservative', campaignTypes: ['brand'] },
  { type: 'competitor', label: '竞品定位', description: '拦截竞品产品的流量', icon: 'Crosshair', color: '#DC2626', bgColor: '#FEF2F2', defaultTargetAcos: 40, defaultRisk: 'aggressive', campaignTypes: ['competitor', 'pat'] },
  { type: 'category', label: '品类扩展', description: '拓展新品类关键词', icon: 'Layers', color: '#F97316', bgColor: '#FFF7ED', defaultTargetAcos: 30, defaultRisk: 'balanced', campaignTypes: ['auto', 'manual_keyword', 'category'] },
  { type: 'clearance', label: '清仓目标', description: '快速清空库存', icon: 'Tag', color: '#6366F1', bgColor: '#EEF2FF', defaultTargetAcos: 50, defaultRisk: 'aggressive', campaignTypes: ['auto', 'manual_keyword'] },
  { type: 'rank_boost', label: '排名提升', description: '将关键词排名推至首页', icon: 'Zap', color: '#EAB308', bgColor: '#FEFCE8', defaultTargetAcos: 45, defaultRisk: 'aggressive', campaignTypes: ['manual_keyword', 'category'] },
];

// ─── Tab definitions ─────────────────────────────────────────────────
type TabKey = 'overview' | 'campaigns' | 'keywords' | 'performance';

const TABS: { key: TabKey; label: string }[] = [
  { key: 'overview', label: '概览' },
  { key: 'campaigns', label: '广告活动' },
  { key: 'keywords', label: '关键词' },
  { key: 'performance', label: '表现' },
];

// ─── Helpers ─────────────────────────────────────────────────────────
function getStatusBadge(status: string) {
  const styles: Record<string, string> = {
    active: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    paused: 'bg-slate-100 text-slate-500 border-slate-200',
    completed: 'bg-blue-100 text-blue-700 border-blue-200',
  };
  return styles[status] || styles.active;
}

const statusLabelMap: Record<string, string> = {
  active: '进行中',
  paused: '已暂停',
  completed: '已完成',
};

const riskLabelMap: Record<string, string> = {
  conservative: '保守',
  balanced: '均衡',
  aggressive: '激进',
};

const frequencyLabelMap: Record<string, string> = {
  daily: '每日',
  hourly_12: '每 12 小时',
  hourly: '每小时',
};

const campaignTypeLabelMap: Record<string, string> = {
  auto: '自动',
  manual_keyword: '手动',
  pat: 'PAT',
  brand: '品牌',
  competitor: '竞品',
  category: '品类',
};

const campaignStatusLabelMap: Record<string, string> = {
  active: '进行中',
  paused: '已暂停',
  learning: '学习中',
  limited_budget: '预算受限',
  needs_review: '待审核',
};

function getGoalAggregates(goalCampaigns: any[]) {
  const totalSpend = goalCampaigns.reduce((s: number, c: any) => s + (c.performance?.spend || 0), 0);
  const totalSales = goalCampaigns.reduce((s: number, c: any) => s + (c.performance?.sales || 0), 0);
  const totalOrders = goalCampaigns.reduce((s: number, c: any) => s + (c.performance?.orders || 0), 0);
  const totalClicks = goalCampaigns.reduce((s: number, c: any) => s + (c.performance?.clicks || 0), 0);
  const totalImpressions = goalCampaigns.reduce((s: number, c: any) => s + (c.performance?.impressions || 0), 0);
  const acos = totalSales > 0 ? (totalSpend / totalSales) * 100 : 0;
  const roas = totalSpend > 0 ? totalSales / totalSpend : 0;
  const cpc = totalClicks > 0 ? totalSpend / totalClicks : 0;
  const ctr = totalImpressions > 0 ? (totalClicks / totalImpressions) * 100 : 0;
  const cvr = totalClicks > 0 ? (totalOrders / totalClicks) * 100 : 0;
  return { spend: totalSpend, sales: totalSales, orders: totalOrders, clicks: totalClicks, impressions: totalImpressions, acos, roas, cpc, ctr, cvr };
}

// ─── KPI Card component ─────────────────────────────────────────────
function KPICard({ label, value, icon: Icon, color, subtitle }: {
  label: string;
  value: string;
  icon: React.ComponentType<any>;
  color: string;
  subtitle?: string;
}) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-4">
      <div className="flex items-center gap-3 mb-2">
        <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center', color)}>
          <Icon size={16} className="text-current" />
        </div>
        <span className="text-xs font-medium text-slate-500">{label}</span>
      </div>
      <p className="text-xl font-bold text-slate-900">{value}</p>
      {subtitle && <p className="text-xs text-slate-400 mt-0.5">{subtitle}</p>}
    </div>
  );
}

// ─── Loading skeleton ────────────────────────────────────────────────
function GoalDetailSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div className="flex items-start gap-4">
          <div className="w-9 h-9 bg-slate-200 rounded-lg animate-pulse" />
          <div>
            <div className="flex items-center gap-3 mb-1">
              <div className="w-10 h-10 bg-slate-200 rounded-lg animate-pulse" />
              <div>
                <div className="h-7 w-48 bg-slate-200 rounded animate-pulse" />
                <div className="flex items-center gap-2 mt-1">
                  <div className="h-5 w-20 bg-slate-100 rounded animate-pulse" />
                  <div className="h-5 w-16 bg-slate-100 rounded-full animate-pulse" />
                </div>
              </div>
            </div>
          </div>
        </div>
        <div className="flex gap-2">
          <div className="h-9 w-20 bg-slate-200 rounded-lg animate-pulse" />
          <div className="h-9 w-20 bg-slate-200 rounded-lg animate-pulse" />
        </div>
      </div>
      <div className="h-10 w-80 bg-white rounded-xl border border-slate-200 animate-pulse" />
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="bg-white rounded-xl border border-slate-200 p-4">
            <div className="flex items-center gap-3 mb-2">
              <div className="w-8 h-8 bg-slate-200 rounded-lg animate-pulse" />
              <div className="h-3 w-16 bg-slate-100 rounded animate-pulse" />
            </div>
            <div className="h-6 w-20 bg-slate-200 rounded animate-pulse" />
          </div>
        ))}
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-6">
        <div className="h-5 w-40 bg-slate-200 rounded animate-pulse mb-4" />
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i}>
              <div className="h-3 w-20 bg-slate-100 rounded animate-pulse mb-1" />
              <div className="h-4 w-24 bg-slate-200 rounded animate-pulse" />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── Main Goal Detail Page ───────────────────────────────────────────
export function GoalDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [activeTab, setActiveTab] = useState<TabKey>('overview');
  const [goalStatus, setGoalStatus] = useState<'active' | 'paused'>('active');

  const goalQuery = useApiQuery<any>(
    ['goal', id],
    () => fetchGoalById(id!),
    { enabled: !!id },
  );
  const goalData = goalQuery.data ?? null;
  const loading = !id || goalQuery.isLoading;
  const error = goalQuery.isError ? goalQuery.error?.message ?? '加载目标失败' : null;
  const loadData = () => goalQuery.refetch();

  // Seed the active/paused toggle from the loaded goal (the toggle is then
  // updated optimistically by toggleStatus).
  useEffect(() => {
    if (goalData?.status === 'active' || goalData?.status === 'paused') {
      setGoalStatus(goalData.status);
    }
  }, [goalData]);
  // Edit modal state
  const [editOpen, setEditOpen] = useState(false);
  const [editForm, setEditForm] = useState<{
    name: string; targetAcos: string; dailyBudget: string; maxCpc: string; minBid: string; maxBid: string;
  }>({ name: '', targetAcos: '', dailyBudget: '', maxCpc: '', minBid: '', maxBid: '' });
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  function openEdit() {
    setSaveError(null);
    setEditForm({
      name: goalData?.name ?? '',
      targetAcos: String(goalData?.targetAcos ?? ''),
      dailyBudget: String(goalData?.dailyBudget ?? ''),
      maxCpc: String(goalData?.maxCpc ?? ''),
      minBid: String(goalData?.minBid ?? ''),
      maxBid: String(goalData?.maxBid ?? ''),
    });
    setEditOpen(true);
  }

  async function saveEdit() {
    if (!id) return;
    setSaving(true);
    setSaveError(null);
    try {
      const num = (v: string) => (v.trim() === '' ? undefined : Number(v));
      await updateGoal(id, {
        name: editForm.name.trim() || undefined,
        targetAcos: num(editForm.targetAcos),
        dailyBudget: num(editForm.dailyBudget),
        maxCpc: num(editForm.maxCpc),
        minBid: num(editForm.minBid),
        maxBid: num(editForm.maxBid),
      });
      setEditOpen(false);
      await loadData();
    } catch (err: any) {
      setSaveError(err?.message || '保存失败，请重试。');
    } finally {
      setSaving(false);
    }
  }

  // Persist the active/paused status toggle (previously local-only).
  async function toggleStatus() {
    const next = goalStatus === 'active' ? 'paused' : 'active';
    setGoalStatus(next);
    if (!id) return;
    try {
      await updateGoal(id, { status: next });
    } catch {
      // Revert on failure so the UI reflects the persisted state.
      setGoalStatus(goalStatus);
    }
  }

  if (loading) return <GoalDetailSkeleton />;

  if (error) {
    // Check for 404 / not found
    const isNotFound = error.toLowerCase().includes('not found') || error.toLowerCase().includes('404');
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Target size={48} className={isNotFound ? 'text-slate-300 mb-4' : 'text-red-300 mb-4'} />
        <p className="text-lg font-medium text-slate-700">{isNotFound ? '未找到目标' : '出错了'}</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">{error}</p>
        {isNotFound ? (
          <Link to="/goals" className="mt-4 text-sm text-blue-600 hover:text-blue-700">
            返回目标列表
          </Link>
        ) : (
          <button
            onClick={loadData}
            className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
          >
            重试
          </button>
        )}
      </div>
    );
  }

  const goal = goalData;
  if (!goal) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Target size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-500">未找到目标</p>
        <Link to="/goals" className="text-sm text-blue-600 hover:text-blue-700 mt-2">
          返回目标列表
        </Link>
      </div>
    );
  }

  // ── Derive data from API response ──────────────────────────────
  const goalCampaigns: any[] = goal.campaigns ?? [];
  const goalProducts: any[] = goal.products ?? [];
  const config = goalTypeConfigs.find((c) => c.type === goal.type);
  const Icon = iconMap[config?.icon || 'Target'] || Target;
  const aggregates = goal.performance ?? getGoalAggregates(goalCampaigns);
  const trendData = goal.trendData ?? goal.dailyPerformance ?? [];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div className="flex items-start gap-4">
          <Link
            to="/goals"
            className="w-9 h-9 rounded-lg border border-slate-200 bg-white flex items-center justify-center text-slate-400 hover:text-slate-600 hover:border-slate-300 transition-colors mt-0.5"
          >
            <ArrowLeft size={16} />
          </Link>
          <div>
            <div className="flex items-center gap-3 mb-1">
              <div
                className="w-10 h-10 rounded-lg flex items-center justify-center"
                style={{ backgroundColor: config?.bgColor || '#F1F5F9' }}
              >
                <Icon size={20} style={{ color: config?.color || '#64748B' }} />
              </div>
              <div>
                <h1 className="text-2xl font-bold text-slate-900">{goal.name}</h1>
                <div className="flex items-center gap-2 mt-1">
                  <span
                    className={cn(
                      'inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium border',
                      getGoalTypeColor(goal.type),
                    )}
                  >
                    {config?.label || goal.type}
                  </span>
                  <span
                    className={cn(
                      'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border',
                      getStatusBadge(goalStatus),
                    )}
                  >
                    {statusLabelMap[goalStatus] || goalStatus}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={openEdit}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-slate-600 bg-white border border-slate-200 hover:bg-slate-50 transition-colors"
          >
            <Edit2 size={14} />
            编辑
          </button>
          <button
            type="button"
            onClick={toggleStatus}
            className={cn(
              'inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors',
              goalStatus === 'active'
                ? 'text-amber-700 bg-amber-50 border border-amber-200 hover:bg-amber-100'
                : 'text-emerald-700 bg-emerald-50 border border-emerald-200 hover:bg-emerald-100',
            )}
          >
            {goalStatus === 'active' ? <Pause size={14} /> : <Play size={14} />}
            {goalStatus === 'active' ? '暂停' : '恢复'}
          </button>
        </div>
      </div>

      {/* Tab Navigation */}
      <div className="bg-white rounded-xl border border-slate-200 px-1 py-1 inline-flex gap-1">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              'px-4 py-2 rounded-lg text-sm font-medium transition-colors',
              activeTab === tab.key
                ? 'bg-blue-600 text-white shadow-sm'
                : 'text-slate-500 hover:text-slate-700 hover:bg-slate-50',
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      {activeTab === 'overview' && (
        <div className="space-y-6">
          {/* KPI Cards */}
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
            <KPICard label="花费" value={formatCurrency(aggregates.spend ?? 0)} icon={DollarSign} color="bg-orange-50 text-orange-600" />
            <KPICard label="销售额" value={formatCurrency(aggregates.sales ?? 0)} icon={ShoppingCart} color="bg-emerald-50 text-emerald-600" />
            <KPICard label="ACoS" value={formatPercent(aggregates.acos ?? 0)} icon={Percent} color="bg-blue-50 text-blue-600" subtitle={`目标: ${formatPercent(goal.targetAcos)}`} />
            <KPICard label="ROAS" value={`${(aggregates.roas ?? 0).toFixed(2)}x`} icon={TrendingUp} color="bg-violet-50 text-violet-600" />
            <KPICard label="订单数" value={formatNumber(aggregates.orders ?? 0)} icon={Package} color="bg-teal-50 text-teal-600" />
            <KPICard label="CPC" value={formatCurrency(aggregates.cpc ?? 0)} icon={DollarSign} color="bg-pink-50 text-pink-600" />
          </div>

          {/* Goal Configuration Summary */}
          <div className="bg-white rounded-xl border border-slate-200 p-6">
            <h3 className="text-base font-semibold text-slate-900 mb-4">目标配置</h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
              <div>
                <p className="text-xs text-slate-400 mb-1">目标 ACoS</p>
                <p className="text-sm font-semibold text-slate-800">{formatPercent(goal.targetAcos)}</p>
              </div>
              <div>
                <p className="text-xs text-slate-400 mb-1">日预算</p>
                <p className="text-sm font-semibold text-slate-800">{formatCurrency(goal.dailyBudget)}</p>
              </div>
              <div>
                <p className="text-xs text-slate-400 mb-1">最高 CPC</p>
                <p className="text-sm font-semibold text-slate-800">{formatCurrency(goal.maxCpc)}</p>
              </div>
              <div>
                <p className="text-xs text-slate-400 mb-1">竞价范围</p>
                <p className="text-sm font-semibold text-slate-800">{formatCurrency(goal.minBid)} - {formatCurrency(goal.maxBid)}</p>
              </div>
              <div>
                <p className="text-xs text-slate-400 mb-1">风险偏好</p>
                <p className="text-sm font-semibold text-slate-800">{riskLabelMap[goal.riskPreference] || goal.riskPreference}</p>
              </div>
              <div>
                <p className="text-xs text-slate-400 mb-1">优化频率</p>
                <p className="text-sm font-semibold text-slate-800">{frequencyLabelMap[goal.optimizeFrequency] || goal.optimizeFrequency?.replace('_', ' ')}</p>
              </div>
              <div>
                <p className="text-xs text-slate-400 mb-1">自动化</p>
                <div className="flex gap-1.5 mt-0.5">
                  {goal.autoNegate && <span className="px-1.5 py-0.5 bg-blue-50 text-blue-600 rounded text-xs">否定</span>}
                  {goal.autoBid && <span className="px-1.5 py-0.5 bg-blue-50 text-blue-600 rounded text-xs">竞价</span>}
                  {goal.autoExpand && <span className="px-1.5 py-0.5 bg-blue-50 text-blue-600 rounded text-xs">扩展</span>}
                </div>
              </div>
              <div>
                <p className="text-xs text-slate-400 mb-1">产品</p>
                <div className="space-y-1 mt-0.5">
                  {goalProducts.length > 0 ? goalProducts.map((p: any) => (
                    <p key={p.id} className="text-xs text-slate-600">{p.sku}</p>
                  )) : (goal.productIds ?? []).map((pid: string) => (
                    <p key={pid} className="text-xs text-slate-600">{pid}</p>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'campaigns' && (
        <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100">
                  <th className="text-left px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">广告活动</th>
                  <th className="text-left px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">类型</th>
                  <th className="text-left px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">状态</th>
                  <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">预算</th>
                  <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">花费</th>
                  <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">销售额</th>
                  <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">ACoS</th>
                  <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">ROAS</th>
                  <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">订单数</th>
                </tr>
              </thead>
              <tbody>
                {goalCampaigns.map((camp: any) => {
                  const p = camp.performance;
                  return (
                    <tr key={camp.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                      <td className="px-4 py-3">
                        <p className="font-medium text-slate-900">{camp.name}</p>
                        <p className="text-xs text-slate-400 mt-0.5">开始于 {camp.startDate}</p>
                      </td>
                      <td className="px-4 py-3">
                        <span className="inline-flex items-center px-2 py-0.5 bg-slate-100 text-slate-600 rounded-md text-xs font-medium">
                          {campaignTypeLabelMap[camp.type] || camp.type?.replace('_', ' ')}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn('inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium', getCampaignStatusColor(camp.status))}>
                          {campaignStatusLabelMap[camp.status] || camp.status?.replace('_', ' ')}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right text-slate-700">
                        <p>{formatCurrency(camp.dailyBudget ?? 0)}</p>
                        <p className="text-xs text-slate-400">已用 {formatCurrency(camp.budgetUsedToday ?? 0)}</p>
                      </td>
                      <td className="px-4 py-3 text-right font-medium text-slate-800">{formatCurrency(p?.spend || 0)}</td>
                      <td className="px-4 py-3 text-right font-medium text-slate-800">{formatCurrency(p?.sales || 0)}</td>
                      <td className="px-4 py-3 text-right">
                        <span className={cn(
                          'font-medium',
                          (p?.acos || 0) <= goal.targetAcos ? 'text-emerald-600' : 'text-red-500',
                        )}>
                          {formatPercent(p?.acos || 0)}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right font-medium text-slate-800">{(p?.roas || 0).toFixed(2)}x</td>
                      <td className="px-4 py-3 text-right font-medium text-slate-800">{p?.orders || 0}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {goalCampaigns.length === 0 && (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <Megaphone size={36} className="text-slate-300 mb-3" />
              <p className="text-sm text-slate-500">该目标未找到广告活动</p>
            </div>
          )}
        </div>
      )}

      {activeTab === 'keywords' && (
        <div className="space-y-6">
          {/* Brand Keywords */}
          <div className="bg-white rounded-xl border border-slate-200 p-6">
            <h3 className="text-base font-semibold text-slate-900 mb-3">品牌关键词</h3>
            <div className="flex flex-wrap gap-2">
              {(goal.brandKeywords ?? []).length > 0 ? (goal.brandKeywords ?? []).map((kw: string, i: number) => (
                <span key={i} className="px-3 py-1.5 bg-blue-50 text-blue-700 rounded-lg text-sm font-medium border border-blue-200">
                  {kw}
                </span>
              )) : <p className="text-sm text-slate-400">未配置品牌关键词</p>}
            </div>
          </div>

          {/* Category Keywords */}
          <div className="bg-white rounded-xl border border-slate-200 p-6">
            <h3 className="text-base font-semibold text-slate-900 mb-3">品类关键词</h3>
            <div className="flex flex-wrap gap-2">
              {(goal.categoryKeywords ?? []).length > 0 ? (goal.categoryKeywords ?? []).map((kw: string, i: number) => (
                <span key={i} className="px-3 py-1.5 bg-emerald-50 text-emerald-700 rounded-lg text-sm font-medium border border-emerald-200">
                  {kw}
                </span>
              )) : <p className="text-sm text-slate-400">未配置品类关键词</p>}
            </div>
          </div>

          {/* Competitor Brands */}
          <div className="bg-white rounded-xl border border-slate-200 p-6">
            <h3 className="text-base font-semibold text-slate-900 mb-3">竞品品牌</h3>
            <div className="flex flex-wrap gap-2">
              {(goal.competitorBrands ?? []).length > 0 ? (goal.competitorBrands ?? []).map((brand: string, i: number) => (
                <span key={i} className="px-3 py-1.5 bg-red-50 text-red-700 rounded-lg text-sm font-medium border border-red-200">
                  {brand}
                </span>
              )) : <p className="text-sm text-slate-400">未配置竞品品牌</p>}
            </div>
          </div>

          {/* Competitor ASINs */}
          <div className="bg-white rounded-xl border border-slate-200 p-6">
            <h3 className="text-base font-semibold text-slate-900 mb-3">竞品 ASIN</h3>
            <div className="flex flex-wrap gap-2">
              {(goal.competitorAsins ?? []).length > 0 ? (goal.competitorAsins ?? []).map((asin: string, i: number) => (
                <span key={i} className="px-3 py-1.5 bg-orange-50 text-orange-700 rounded-lg text-sm font-medium border border-orange-200">
                  {asin}
                </span>
              )) : <p className="text-sm text-slate-400">未配置竞品 ASIN</p>}
            </div>
          </div>
        </div>
      )}

      {activeTab === 'performance' && (
        <div className="space-y-6">
          {/* 7-Day Trend Chart */}
          {trendData.length > 0 && (
            <div className="bg-white rounded-xl border border-slate-200 p-6">
              <h3 className="text-base font-semibold text-slate-900 mb-1">7 天表现趋势</h3>
              <p className="text-sm text-slate-500 mb-6">过去 7 天的花费与销售额对比</p>
              <div className="h-80">
                <Suspense fallback={<div className="h-full w-full animate-pulse rounded-lg bg-slate-100" />}>
                  <GoalTrendChart data={trendData} />
                </Suspense>
              </div>
            </div>
          )}

          {/* Daily Breakdown */}
          <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
            <div className="px-6 py-4 border-b border-slate-100">
              <h3 className="text-base font-semibold text-slate-900">每日明细</h3>
            </div>
            {trendData.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-slate-100">
                      <th className="text-left px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">日期</th>
                      <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">曝光量</th>
                      <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">点击量</th>
                      <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">订单数</th>
                      <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">花费</th>
                      <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">销售额</th>
                      <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">ACoS</th>
                      <th className="text-right px-4 py-3 text-xs font-medium text-slate-500 uppercase tracking-wider">ROAS</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trendData.map((day: any) => (
                      <tr key={day.date} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                        <td className="px-4 py-3 font-medium text-slate-800">{day.date}</td>
                        <td className="px-4 py-3 text-right text-slate-600">{(day.impressions ?? 0).toLocaleString()}</td>
                        <td className="px-4 py-3 text-right text-slate-600">{(day.clicks ?? 0).toLocaleString()}</td>
                        <td className="px-4 py-3 text-right text-slate-600">{day.orders ?? 0}</td>
                        <td className="px-4 py-3 text-right font-medium text-slate-800">{formatCurrency(day.spend ?? 0)}</td>
                        <td className="px-4 py-3 text-right font-medium text-slate-800">{formatCurrency(day.sales ?? 0)}</td>
                        <td className={cn(
                          'px-4 py-3 text-right font-medium',
                          (day.acos ?? 0) <= goal.targetAcos ? 'text-emerald-600' : 'text-red-500',
                        )}>
                          {formatPercent(day.acos ?? 0)}
                        </td>
                        <td className="px-4 py-3 text-right font-medium text-slate-800">{(day.roas ?? 0).toFixed(2)}x</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <Activity size={36} className="text-slate-300 mb-3" />
                <p className="text-sm text-slate-500">暂无表现数据</p>
              </div>
            )}
          </div>
        </div>
      )}

      {/* ── Edit Goal modal ─────────────────────────────────────────── */}
      {editOpen && (
        <Dialog open onOpenChange={(o) => { if (!o) setEditOpen(false); }}>
          <DialogContent className="block gap-0 w-full sm:max-w-md rounded-xl border border-slate-200 bg-white p-0 shadow-xl">
            <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100">
              <DialogTitle className="text-base font-semibold text-slate-900">编辑目标</DialogTitle>
            </div>
            <div className="px-5 py-4 space-y-3">
              <div>
                <label className="block text-xs font-medium text-slate-500 mb-1">目标名称</label>
                <input
                  type="text"
                  value={editForm.name}
                  onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                  className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-500 mb-1">目标 ACoS (%)</label>
                  <input type="number" step="0.1" value={editForm.targetAcos}
                    onChange={(e) => setEditForm({ ...editForm, targetAcos: e.target.value })}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-500 mb-1">日预算 ($)</label>
                  <input type="number" step="0.01" value={editForm.dailyBudget}
                    onChange={(e) => setEditForm({ ...editForm, dailyBudget: e.target.value })}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-500 mb-1">最高 CPC ($)</label>
                  <input type="number" step="0.01" value={editForm.maxCpc}
                    onChange={(e) => setEditForm({ ...editForm, maxCpc: e.target.value })}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400" />
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <label className="block text-xs font-medium text-slate-500 mb-1">最低竞价</label>
                    <input type="number" step="0.01" value={editForm.minBid}
                      onChange={(e) => setEditForm({ ...editForm, minBid: e.target.value })}
                      className="w-full px-2 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-500 mb-1">最高竞价</label>
                    <input type="number" step="0.01" value={editForm.maxBid}
                      onChange={(e) => setEditForm({ ...editForm, maxBid: e.target.value })}
                      className="w-full px-2 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400" />
                  </div>
                </div>
              </div>
              {saveError && <p className="text-xs text-red-600">{saveError}</p>}
            </div>
            <div className="flex items-center justify-end gap-2 px-5 py-4 border-t border-slate-100">
              <button onClick={() => setEditOpen(false)}
                className="px-4 py-2 text-sm font-medium text-slate-600 bg-white border border-slate-200 rounded-lg hover:bg-slate-50">
                取消
              </button>
              <button onClick={saveEdit} disabled={saving}
                className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-60">
                {saving ? '保存中…' : '保存'}
              </button>
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
