import { useState, useMemo } from 'react';
import { Link } from 'react-router';
import {
  Plus, Search, Filter, TrendingUp,
  Rocket, BarChart3, Shield, Crosshair, Layers, Tag, Zap, Target,
  Package, Megaphone, ArrowRight,
} from 'lucide-react';
import { fetchGoals } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { t } from '../i18n';
import { formatCurrency, formatPercent, getGoalTypeColor, cn } from '../lib/utils';
import type { GoalType, GoalStatus, GoalTypeConfig } from '../types';

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

// ─── Helpers ─────────────────────────────────────────────────────────
function getStatusBadge(status: GoalStatus) {
  const styles: Record<GoalStatus, string> = {
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

function getGoalPerformance(goal: any) {
  const p = goal.performance;
  if (p) {
    return {
      spend: p.spend ?? 0,
      sales: p.sales ?? 0,
      orders: p.orders ?? 0,
      acos: p.acos ?? 0,
      roas: p.roas ?? 0,
    };
  }
  return { spend: 0, sales: 0, orders: 0, acos: 0, roas: 0 };
}

// ─── Loading skeleton ────────────────────────────────────────────────
function GoalsSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="h-8 w-32 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-36 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="h-14 bg-white rounded-xl border border-slate-200 animate-pulse" />
      <div className="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-5">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="flex items-start justify-between mb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 bg-slate-200 rounded-lg animate-pulse" />
                <div className="h-5 w-20 bg-slate-200 rounded animate-pulse" />
              </div>
              <div className="h-6 w-16 bg-slate-100 rounded-full animate-pulse" />
            </div>
            <div className="h-5 w-48 bg-slate-200 rounded animate-pulse mb-3" />
            <div className="grid grid-cols-3 gap-3 mb-4">
              {[1, 2, 3].map((j) => (
                <div key={j} className="text-center">
                  <div className="h-3 w-16 bg-slate-100 rounded animate-pulse mx-auto mb-1" />
                  <div className="h-4 w-12 bg-slate-200 rounded animate-pulse mx-auto" />
                </div>
              ))}
            </div>
            <div className="h-4 w-32 bg-slate-100 rounded animate-pulse mb-4" />
            <div className="h-20 bg-slate-100 rounded-lg animate-pulse" />
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Main Goals Page ─────────────────────────────────────────────────
export function GoalsPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const [typeFilter, setTypeFilter] = useState<GoalType | 'all'>('all');
  const [statusFilter, setStatusFilter] = useState<GoalStatus | 'all'>('all');
  const [searchQuery, setSearchQuery] = useState('');

  const goalsQuery = useApiQuery<any[]>(
    ['goals', storeId],
    () => fetchGoals(storeId!).then((result) => result ?? []),
    { enabled: !!storeId },
  );
  const goalsData = goalsQuery.data ?? [];
  const loading = !!storeId && goalsQuery.isLoading;
  const error = goalsQuery.isError ? goalsQuery.error?.message ?? '加载目标失败' : null;
  const loadData = () => goalsQuery.refetch();

  const filteredGoals = useMemo(() => {
    return goalsData.filter((goal: any) => {
      if (typeFilter !== 'all' && goal.type !== typeFilter) return false;
      if (statusFilter !== 'all' && goal.status !== statusFilter) return false;
      if (searchQuery) {
        const q = searchQuery.toLowerCase();
        return (
          goal.name.toLowerCase().includes(q) ||
          goal.type.toLowerCase().includes(q)
        );
      }
      return true;
    });
  }, [goalsData, typeFilter, statusFilter, searchQuery]);

  if (loading || storeLoading) return <GoalsSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Target size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出错了</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">{storeError || error}</p>
        <button
          onClick={loadData}
          className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
        >
          重试
        </button>
      </div>
    );
  }

  if (!storeId) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Target size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">
          请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">{t('pages.goals.title')}</h1>
          <p className="text-sm text-slate-500 mt-1">
            管理您的广告目标和活动策略
          </p>
        </div>
        <Link
          to="/goals/new"
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors shadow-sm"
        >
          <Plus size={16} />
          创建目标
        </Link>
      </div>

      {/* Filter Bar */}
      <div className="flex items-center gap-3 bg-white rounded-xl border border-slate-200 px-4 py-3">
        <div className="flex items-center gap-2 text-slate-400">
          <Filter size={16} />
        </div>

        {/* Type Filter */}
        <select
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value as GoalType | 'all')}
          className="h-9 rounded-lg border border-slate-200 bg-white px-3 text-sm text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
        >
          <option value="all">全部类型</option>
          {goalTypeConfigs.map((cfg) => (
            <option key={cfg.type} value={cfg.type}>{cfg.label}</option>
          ))}
        </select>

        {/* Status Filter */}
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as GoalStatus | 'all')}
          className="h-9 rounded-lg border border-slate-200 bg-white px-3 text-sm text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
        >
          <option value="all">全部状态</option>
          <option value="active">进行中</option>
          <option value="paused">已暂停</option>
          <option value="completed">已完成</option>
        </select>

        {/* Search */}
        <div className="flex-1 flex items-center gap-2 h-9 rounded-lg border border-slate-200 bg-white px-3 ml-auto">
          <Search size={14} className="text-slate-400 flex-shrink-0" />
          <input
            type="text"
            placeholder="搜索目标..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="flex-1 text-sm text-slate-700 placeholder-slate-400 outline-none bg-transparent"
          />
        </div>
      </div>

      {/* Goals Grid */}
      {filteredGoals.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <Target size={48} className="text-slate-300 mb-4" />
          <p className="text-lg font-medium text-slate-500">未找到目标</p>
          <p className="text-sm text-slate-400 mt-1">请尝试调整筛选条件或创建新目标</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-5">
          {filteredGoals.map((goal: any) => {
            const config = goalTypeConfigs.find((c) => c.type === goal.type);
            const Icon = iconMap[config?.icon || 'Target'];
            const perf = getGoalPerformance(goal);
            const goalProductCount = goal.productIds?.length ?? goal.products?.length ?? 0;
            const goalCampaignCount = goal.campaigns?.length ?? 0;

            return (
              <div
                key={goal.id}
                className="bg-white rounded-xl border border-slate-200 hover:border-slate-300 hover:shadow-md transition-all duration-200 overflow-hidden"
              >
                <div className="p-5">
                  {/* Header */}
                  <div className="flex items-start justify-between mb-4">
                    <div className="flex items-center gap-3">
                      <div
                        className="w-10 h-10 rounded-lg flex items-center justify-center"
                        style={{ backgroundColor: config?.bgColor || '#F1F5F9' }}
                      >
                        <Icon size={20} style={{ color: config?.color || '#64748B' }} />
                      </div>
                      <div>
                        <span
                          className={cn(
                            'inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium border',
                            getGoalTypeColor(goal.type),
                          )}
                        >
                          {config?.label || goal.type}
                        </span>
                      </div>
                    </div>
                    <span
                      className={cn(
                        'inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium border',
                        getStatusBadge(goal.status),
                      )}
                    >
                      {statusLabelMap[goal.status] || goal.status}
                    </span>
                  </div>

                  {/* Goal Name */}
                  <h3 className="text-base font-semibold text-slate-900 mb-3 line-clamp-1">
                    {goal.name}
                  </h3>

                  {/* Key Metrics Row */}
                  <div className="grid grid-cols-3 gap-3 mb-4">
                    <div className="text-center">
                      <p className="text-xs text-slate-400 mb-0.5">目标 ACoS</p>
                      <p className="text-sm font-semibold text-slate-800">{formatPercent(goal.targetAcos)}</p>
                    </div>
                    <div className="text-center">
                      <p className="text-xs text-slate-400 mb-0.5">日预算</p>
                      <p className="text-sm font-semibold text-slate-800">{formatCurrency(goal.dailyBudget)}</p>
                    </div>
                    <div className="text-center">
                      <p className="text-xs text-slate-400 mb-0.5">风险</p>
                      <p className="text-sm font-semibold text-slate-800">{riskLabelMap[goal.riskPreference] || goal.riskPreference}</p>
                    </div>
                  </div>

                  {/* Product & Campaign Counts */}
                  <div className="flex items-center gap-4 mb-4 text-xs text-slate-500">
                    <span className="flex items-center gap-1.5">
                      <Package size={13} className="text-slate-400" />
                      {goalProductCount} 个产品
                    </span>
                    <span className="flex items-center gap-1.5">
                      <Megaphone size={13} className="text-slate-400" />
                      {goalCampaignCount} 个广告活动
                    </span>
                  </div>

                  {/* Performance Summary */}
                  <div className="bg-slate-50 rounded-lg p-3 space-y-2">
                    <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">表现</p>
                    <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
                      <div className="flex items-center justify-between">
                        <span className="text-xs text-slate-500">花费</span>
                        <span className="text-xs font-medium text-slate-700">{formatCurrency(perf.spend)}</span>
                      </div>
                      <div className="flex items-center justify-between">
                        <span className="text-xs text-slate-500">销售额</span>
                        <span className="text-xs font-medium text-slate-700">{formatCurrency(perf.sales)}</span>
                      </div>
                      <div className="flex items-center justify-between">
                        <span className="text-xs text-slate-500">ACoS</span>
                        <span className={cn(
                          'text-xs font-medium',
                          perf.acos <= goal.targetAcos ? 'text-emerald-600' : 'text-red-500',
                        )}>
                          {formatPercent(perf.acos)}
                        </span>
                      </div>
                      <div className="flex items-center justify-between">
                        <span className="text-xs text-slate-500">ROAS</span>
                        <span className="text-xs font-medium text-slate-700">{perf.roas.toFixed(2)}x</span>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Card Footer */}
                <div className="px-5 py-3 border-t border-slate-100 bg-slate-50/50">
                  <Link
                    to={`/goals/${goal.id}`}
                    className="flex items-center justify-center gap-1.5 text-sm font-medium text-blue-600 hover:text-blue-700 transition-colors"
                  >
                    查看详情
                    <ArrowRight size={14} />
                  </Link>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
