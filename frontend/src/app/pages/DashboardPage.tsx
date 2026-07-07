import { useState, useEffect, useCallback } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import {
  TrendingUp,
  TrendingDown,
  AlertTriangle,
  Calendar,
  Sparkles,
  Bot,
  Bell,
  RefreshCw,
  Download,
} from 'lucide-react';
import {
  ComposedChart,
  Area,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';

import {
  fetchSalesOverview,
  fetchSalesTrend,
  fetchAiActions,
  fetchAiUsage,
  fetchAiNotificationsSummary,
  type DashboardPanelParams,
} from '../lib/api';
import { useStoreContext } from '../lib/StoreContext';
import { formatCurrency, formatPercent, formatNumber, cn } from '../lib/utils';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import {
  DATE_RANGE_OPTIONS,
  GRANULARITY_OPTIONS,
  getDashboardDateRange,
  buildSalesOverviewTiles,
  isSalesOverviewEmpty,
  isTrendEmpty,
  isAiActionsEmpty,
  isAiUsageEmpty,
  resolveNotificationCategories,
  type Granularity,
  type OverviewTile,
  type SalesOverviewVo,
  type SalesTrendVo,
  type AiActionsVo,
  type AiUsageVo,
  type AiNotificationsSummaryVo,
} from '../lib/dashboardPanels';

// ─── Generic panel state hook ────────────────────────────────────────
// Each dashboard panel loads independently so it can show its own
// loading / empty / error-with-retry state (Req 18.7, 18.8).

interface PanelState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  reload: () => void;
}

function usePanel<T>(loader: () => Promise<T>, deps: unknown[], enabled: boolean): PanelState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(async () => {
    if (!enabled) return;
    setLoading(true);
    setError(null);
    try {
      setData(await loader());
    } catch (err: any) {
      setError(err?.message || '加载失败');
    } finally {
      setLoading(false);
    }
    // loader identity changes with deps; intentional dependency list below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps.concat(enabled));

  useEffect(() => {
    run();
  }, [run]);

  return { data, loading, error, reload: run };
}

// ─── Panel shell (header + loading / error / empty / content) ─────────

function Panel({
  title,
  icon,
  subtitle,
  actions,
  state,
  isEmpty,
  emptyHint,
  children,
}: {
  title: string;
  icon: ReactNode;
  subtitle?: string;
  actions?: ReactNode;
  state: PanelState<any>;
  isEmpty: boolean;
  emptyHint: string;
  children: ReactNode;
}) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-5">
      <div className="flex items-center gap-2 mb-4">
        <div className="w-7 h-7 rounded-md bg-gradient-to-br from-blue-600 to-indigo-600 flex items-center justify-center text-white">
          {icon}
        </div>
        <div className="min-w-0">
          <h2 className="text-base font-semibold text-slate-900 truncate">{title}</h2>
          {subtitle && <p className="text-xs text-slate-400 mt-0.5">{subtitle}</p>}
        </div>
        <div className="ml-auto flex items-center gap-2">{actions}</div>
      </div>

      {state.loading ? (
        <div className="h-32 bg-slate-100 rounded-lg animate-pulse" />
      ) : state.error ? (
        <div className="flex flex-col items-center justify-center py-10 text-center">
          <AlertTriangle size={28} className="text-red-300 mb-3" />
          <p className="text-sm text-slate-600">{state.error}</p>
          <button
            onClick={state.reload}
            className="mt-3 inline-flex items-center gap-1.5 px-3 py-1.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
          >
            <RefreshCw size={14} /> 重试
          </button>
        </div>
      ) : isEmpty ? (
        <p className="text-sm text-slate-400 py-8 text-center">{emptyHint}</p>
      ) : (
        children
      )}
    </div>
  );
}

// ─── Sales Overview metric tile (Req 18.1) ───────────────────────────

function formatTile(tile: OverviewTile, currency: string): string {
  switch (tile.format) {
    case 'currency':
      return formatCurrency(tile.rawValue, currency);
    case 'percent':
      return formatPercent(tile.rawValue);
    case 'number':
    default:
      return formatNumber(tile.rawValue);
  }
}

function OverviewTileCard({ tile, currency }: { tile: OverviewTile; currency: string }) {
  // For inverse metrics (spend / ACoS / TACoS) an increase is unfavorable.
  const favorable = tile.trend === 'flat' ? null : (tile.trend === 'up') !== tile.inverse;
  const deltaColor =
    favorable === null
      ? 'text-slate-400'
      : favorable
        ? 'text-emerald-600'
        : 'text-red-500';
  const prevTile: OverviewTile = { ...tile, rawValue: tile.previousValue };
  return (
    <div className="px-4 py-3 border-r border-slate-100 last:border-r-0">
      <p className="text-xs font-medium text-slate-500 mb-1.5">{tile.label}</p>
      <p className="text-xl font-bold text-slate-900 tracking-tight leading-none">
        {formatTile(tile, currency)}
      </p>
      <div className="flex items-center gap-1.5 mt-2">
        <span className="text-xs text-slate-400">{formatTile(prevTile, currency)}</span>
        <span className={cn('inline-flex items-center gap-0.5 text-xs font-medium', deltaColor)}>
          {tile.trend === 'up' ? (
            <TrendingUp size={12} />
          ) : tile.trend === 'down' ? (
            <TrendingDown size={12} />
          ) : null}
          {Math.abs(tile.deltaPct).toFixed(1)}%
        </span>
      </div>
    </div>
  );
}

// ─── Trend chart tooltip ─────────────────────────────────────────────

const trendKeyLabels: Record<string, string> = {
  totalSales: '总销售额',
  spend: '广告花费',
  sales: '广告销售额',
};

function TrendTooltip({
  active,
  payload,
  label,
  currency,
}: {
  active?: boolean;
  payload?: Array<{ value: number; dataKey: string; color: string }>;
  label?: string;
  currency: string;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-white border border-slate-200 rounded-lg shadow-lg px-4 py-3">
      <p className="text-sm font-medium text-slate-700 mb-2">{label}</p>
      {payload.map((entry) => (
        <div key={entry.dataKey} className="flex items-center gap-2 text-sm">
          <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: entry.color }} />
          <span className="text-slate-500">{trendKeyLabels[entry.dataKey] ?? entry.dataKey}:</span>
          <span className="font-medium text-slate-800">{formatCurrency(entry.value ?? 0, currency)}</span>
        </div>
      ))}
    </div>
  );
}

// ─── Loading skeleton ────────────────────────────────────────────────

function DashboardSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <div className="h-8 w-40 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-56 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-64 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="h-4 w-20 bg-slate-200 rounded animate-pulse mb-3" />
            <div className="h-7 w-24 bg-slate-100 rounded animate-pulse" />
          </div>
        ))}
      </div>
      <div className="h-80 bg-white rounded-xl border border-slate-200 animate-pulse" />
    </div>
  );
}

// ─── Main Dashboard Page (AI advertising home, Req 18) ───────────────

export function DashboardPage() {
  const { storeId, stores, loading: storeLoading, error: storeError } = useStoreContext();
  const [selectedRange, setSelectedRange] = useState<string>('近7天');
  const [granularity, setGranularity] = useState<Granularity>('day');
  const [customRange, setCustomRange] = useState<{ start: string; end: string }>({ start: '', end: '' });

  // Marketplace / currency derive from the active store selected in the top bar;
  // changing the store re-scopes every panel (Req 18.6).
  const activeStore = stores.find((s) => s.id === storeId);
  const marketplace = activeStore?.marketplaceCode ?? undefined;

  // Custom date range (used when selectedRange === '自定义').
  const isCustomRange = selectedRange === '自定义';
  const preset = getDashboardDateRange(isCustomRange ? '近7天' : selectedRange);
  const startDate = isCustomRange && customRange.start ? customRange.start : preset.startDate;
  const endDate = isCustomRange && customRange.end ? customRange.end : preset.endDate;
  const baseParams: DashboardPanelParams = { storeId: storeId ?? undefined, marketplace, startDate, endDate };
  const scopeKey = [storeId, marketplace, startDate, endDate];
  const enabled = !!storeId;

  const overview = usePanel<SalesOverviewVo>(() => fetchSalesOverview(baseParams), scopeKey, enabled);
  const trend = usePanel<SalesTrendVo>(
    () => fetchSalesTrend({ ...baseParams, granularity }),
    scopeKey.concat(granularity),
    enabled,
  );
  const aiActions = usePanel<AiActionsVo>(() => fetchAiActions(baseParams), scopeKey, enabled);
  const aiUsage = usePanel<AiUsageVo>(() => fetchAiUsage(baseParams), scopeKey, enabled);
  const notifications = usePanel<AiNotificationsSummaryVo>(
    () => fetchAiNotificationsSummary(baseParams),
    scopeKey,
    enabled,
  );

  if (storeLoading) return <DashboardSkeleton />;

  if (storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <AlertTriangle size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出错了</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">{storeError}</p>
      </div>
    );
  }

  const overviewCurrency = overview.data?.currency ?? 'USD';
  const tiles = buildSalesOverviewTiles(overview.data);
  const trendCurrency = trend.data?.currency ?? overviewCurrency;
  const trendPoints = Array.isArray(trend.data?.points) ? trend.data!.points! : [];
  const actionsCurrency = aiActions.data?.currency ?? overviewCurrency;
  const actionRows = Array.isArray(aiActions.data?.actions) ? aiActions.data!.actions! : [];
  const usageCurrency = aiUsage.data?.currency ?? overviewCurrency;
  const categories = resolveNotificationCategories(notifications.data);

  // Exports the current trend series as a CSV download (download icon, Req 18.2).
  const exportTrendCsv = useCallback(() => {
    if (trendPoints.length === 0) return;
    const header = ['周期', '总销售额', '广告花费', '广告销售额'];
    const rows = trendPoints.map((p) => [
      p.period,
      String(p.totalSales ?? 0),
      String(p.spend ?? 0),
      String(p.sales ?? 0),
    ]);
    const csv = [header, ...rows].map((r) => r.join(',')).join('\n');
    const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `销售总览_${startDate}_${endDate}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }, [trendPoints, startDate, endDate]);

  return (
    <div className="space-y-6">
      {/* ── Page Header + date-range toggle ──────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">数据看板</h1>
          <p className="text-sm text-slate-500 mt-1">
            <Calendar size={14} className="inline mr-1.5 -mt-0.5" />
            {new Date().toLocaleDateString('zh-CN', {
              weekday: 'long',
              year: 'numeric',
              month: 'long',
              day: 'numeric',
            })}
            {marketplace && <span className="ml-2 text-slate-400">· {marketplace}</span>}
          </p>
        </div>
        <div className="flex flex-col sm:flex-row sm:items-center gap-2">
          <div className="flex items-center gap-1 bg-white border border-slate-200 rounded-lg p-1">
            {DATE_RANGE_OPTIONS.map((range) => (
              <button
                key={range}
                onClick={() => setSelectedRange(range)}
                className={cn(
                  'px-3 py-1.5 text-sm font-medium rounded-md transition-all',
                  selectedRange === range
                    ? 'bg-blue-600 text-white shadow-sm'
                    : 'text-slate-500 hover:text-slate-700 hover:bg-slate-50',
                )}
              >
                {range}
              </button>
            ))}
            <button
              onClick={() => setSelectedRange('自定义')}
              className={cn(
                'px-3 py-1.5 text-sm font-medium rounded-md transition-all',
                isCustomRange
                  ? 'bg-blue-600 text-white shadow-sm'
                  : 'text-slate-500 hover:text-slate-700 hover:bg-slate-50',
              )}
            >
              自定义
            </button>
          </div>
          {isCustomRange && (
            <div className="flex items-center gap-1.5 bg-white border border-slate-200 rounded-lg px-2 py-1">
              <input
                type="date"
                value={customRange.start}
                max={customRange.end || undefined}
                onChange={(e) => setCustomRange((r) => ({ ...r, start: e.target.value }))}
                className="text-sm text-slate-700 px-1.5 py-1 rounded focus:outline-none"
              />
              <span className="text-slate-400 text-sm">至</span>
              <input
                type="date"
                value={customRange.end}
                min={customRange.start || undefined}
                onChange={(e) => setCustomRange((r) => ({ ...r, end: e.target.value }))}
                className="text-sm text-slate-700 px-1.5 py-1 rounded focus:outline-none"
              />
            </div>
          )}
        </div>
      </div>

      {/* ── Sales Overview: metrics row + combo chart (Req 18.1 + 18.2) ── */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="flex items-center gap-2 px-5 pt-5 pb-3">
          <div className="w-7 h-7 rounded-md bg-gradient-to-br from-blue-600 to-indigo-600 flex items-center justify-center text-white">
            <TrendingUp size={14} />
          </div>
          <div className="min-w-0">
            <h2 className="text-base font-semibold text-slate-900 truncate">销售总览</h2>
            <p className="text-xs text-slate-400 mt-0.5">币种 {overviewCurrency}</p>
          </div>
          <div className="ml-auto flex items-center gap-2">
            <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-lg p-0.5">
              {GRANULARITY_OPTIONS.map((g) => (
                <button
                  key={g.key}
                  onClick={() => setGranularity(g.key)}
                  className={cn(
                    'px-2.5 py-1 text-xs font-medium rounded-md transition-all',
                    granularity === g.key
                      ? 'bg-white text-blue-600 shadow-sm'
                      : 'text-slate-500 hover:text-slate-700',
                  )}
                >
                  {g.label}
                </button>
              ))}
            </div>
            <button
              onClick={exportTrendCsv}
              disabled={trendPoints.length === 0}
              title="导出趋势数据"
              className="w-8 h-8 inline-flex items-center justify-center rounded-lg border border-slate-200 text-slate-500 hover:text-blue-600 hover:border-blue-300 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <Download size={15} />
            </button>
          </div>
        </div>

        {/* Metric tiles row */}
        {overview.loading ? (
          <div className="h-20 mx-5 mb-4 bg-slate-100 rounded-lg animate-pulse" />
        ) : overview.error ? (
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <AlertTriangle size={24} className="text-red-300 mb-2" />
            <p className="text-sm text-slate-600">{overview.error}</p>
            <button
              onClick={overview.reload}
              className="mt-3 inline-flex items-center gap-1.5 px-3 py-1.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
            >
              <RefreshCw size={14} /> 重试
            </button>
          </div>
        ) : isSalesOverviewEmpty(overview.data) ? (
          <div className="border-y border-slate-100">
            <ErpEmptyState
              title="暂无广告数据"
              description="请先在「连接与同步」页连接亚马逊广告账户，连接并同步后这里会显示真实的销售与广告数据。"
              icon={<TrendingUp size={24} className="text-slate-400" />}
              action={
                <Link
                  to="/data-sync"
                  className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
                >
                  前往连接与同步
                </Link>
              }
            />
          </div>
        ) : (
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 border-y border-slate-100 bg-slate-50/40">
            {tiles.map((tile) => (
              <OverviewTileCard key={tile.key} tile={tile} currency={overviewCurrency} />
            ))}
          </div>
        )}

        {/* Combo chart: bars 总销售额 + areas 广告花费 / 广告销售额 */}
        <div className="px-5 pt-4 pb-5">
          {trend.loading ? (
            <div className="h-72 bg-slate-100 rounded-lg animate-pulse" />
          ) : trend.error ? (
            <div className="flex flex-col items-center justify-center py-10 text-center">
              <AlertTriangle size={24} className="text-red-300 mb-2" />
              <p className="text-sm text-slate-600">{trend.error}</p>
              <button
                onClick={trend.reload}
                className="mt-3 inline-flex items-center gap-1.5 px-3 py-1.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
              >
                <RefreshCw size={14} /> 重试
              </button>
            </div>
          ) : isTrendEmpty(trend.data) ? (
            <p className="text-sm text-slate-400 py-10 text-center">所选周期暂无趋势数据</p>
          ) : (
            <div className="h-72">
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={trendPoints} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                  <defs>
                    <linearGradient id="spendGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#8B5CF6" stopOpacity={0.35} />
                      <stop offset="95%" stopColor="#8B5CF6" stopOpacity={0.03} />
                    </linearGradient>
                    <linearGradient id="salesGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#F59E0B" stopOpacity={0.35} />
                      <stop offset="95%" stopColor="#F59E0B" stopOpacity={0.03} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#E2E8F0" vertical={false} />
                  <XAxis
                    dataKey="period"
                    tick={{ fontSize: 12, fill: '#94A3B8' }}
                    axisLine={false}
                    tickLine={false}
                  />
                  {/* Left axis: 总销售额 (bars) */}
                  <YAxis
                    yAxisId="total"
                    tick={{ fontSize: 12, fill: '#3B82F6' }}
                    axisLine={false}
                    tickLine={false}
                    tickFormatter={(v: number) => `$${formatNumber(v)}`}
                  />
                  {/* Right axis: 广告花费 / 广告销售额 (areas) */}
                  <YAxis
                    yAxisId="ad"
                    orientation="right"
                    tick={{ fontSize: 12, fill: '#94A3B8' }}
                    axisLine={false}
                    tickLine={false}
                    tickFormatter={(v: number) => `$${formatNumber(v)}`}
                  />
                  <Tooltip content={<TrendTooltip currency={trendCurrency} />} />
                  <Legend
                    formatter={(value: string) => trendKeyLabels[value] ?? value}
                    wrapperStyle={{ fontSize: 12 }}
                  />
                  <Bar
                    yAxisId="total"
                    dataKey="totalSales"
                    name="totalSales"
                    fill="#3B82F6"
                    radius={[3, 3, 0, 0]}
                    barSize={18}
                  />
                  <Area
                    yAxisId="ad"
                    type="monotone"
                    dataKey="spend"
                    name="spend"
                    stroke="#8B5CF6"
                    strokeWidth={2}
                    fill="url(#spendGradient)"
                  />
                  <Area
                    yAxisId="ad"
                    type="monotone"
                    dataKey="sales"
                    name="sales"
                    stroke="#F59E0B"
                    strokeWidth={2}
                    fill="url(#salesGradient)"
                  />
                </ComposedChart>
              </ResponsiveContainer>
            </div>
          )}
        </div>
      </div>

      {/* ── AI Actions + AI Usage row ────────────────────────────────── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* AI Actions (Req 18.3) */}
        <div className="lg:col-span-2">
          <Panel
            title="AI 动作"
            subtitle="本周期 AI 执行的优化动作与影响"
            icon={<Sparkles size={14} />}
            state={aiActions}
            isEmpty={isAiActionsEmpty(aiActions.data)}
            emptyHint="所选周期暂无 AI 动作"
          >
            <div className="divide-y divide-slate-100">
              {actionRows.map((action) => (
                <div key={action.key} className="flex items-center gap-3 py-3">
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-slate-800 truncate">{action.label}</p>
                    {action.impactLabel != null && action.impactValue != null && (
                      <p className="text-xs text-slate-400 mt-0.5">
                        {action.impactLabel}: {formatCurrency(Number(action.impactValue), actionsCurrency)}
                      </p>
                    )}
                  </div>
                  <div className="text-right flex-shrink-0">
                    <p className="text-base font-semibold text-slate-900">{formatNumber(Number(action.count ?? 0))}</p>
                    {Number(action.affectedCampaigns ?? 0) > 0 && (
                      <p className="text-[11px] text-slate-400">
                        影响 {formatNumber(Number(action.affectedCampaigns))} 个广告活动
                      </p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </Panel>
        </div>

        {/* AI Usage (Req 18.4) */}
        <div>
          <Panel
            title="AI 使用"
            subtitle="AI 托管覆盖与表现"
            icon={<Bot size={14} />}
            state={aiUsage}
            isEmpty={isAiUsageEmpty(aiUsage.data)}
            emptyHint="所选周期暂无 AI 使用数据"
          >
            <div className="space-y-4">
              <div>
                <div className="flex items-end justify-between mb-1.5">
                  <span className="text-sm text-slate-500">AI 覆盖率</span>
                  <span className="text-2xl font-bold text-slate-900">
                    {formatPercent(Number(aiUsage.data?.coveragePercent ?? 0))}
                  </span>
                </div>
                <div className="h-2 w-full bg-slate-100 rounded-full overflow-hidden">
                  <div
                    className="h-full bg-gradient-to-r from-blue-500 to-indigo-500 rounded-full"
                    style={{ width: `${Math.min(100, Math.max(0, Number(aiUsage.data?.coveragePercent ?? 0)))}%` }}
                  />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="rounded-lg bg-slate-50 p-3">
                  <p className="text-xs text-slate-500">AI 广告花费</p>
                  <p className="text-base font-semibold text-slate-900 mt-1">
                    {formatCurrency(Number(aiUsage.data?.aiAdSpend ?? 0), usageCurrency)}
                  </p>
                </div>
                <div className="rounded-lg bg-slate-50 p-3">
                  <p className="text-xs text-slate-500">AI 广告销售额</p>
                  <p className="text-base font-semibold text-slate-900 mt-1">
                    {formatCurrency(Number(aiUsage.data?.aiAdSales ?? 0), usageCurrency)}
                  </p>
                </div>
              </div>
            </div>
          </Panel>
        </div>
      </div>

      {/* ── AI Notifications summary (Req 18.5) ──────────────────────── */}
      <Panel
        title="AI 通知"
        subtitle="待处理工作项概览"
        icon={<Bell size={14} />}
        state={notifications}
        isEmpty={false}
        emptyHint=""
      >
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {categories.map((cat) => (
            <div key={cat.key} className="rounded-xl border border-slate-200 p-4 hover:shadow-sm transition-shadow">
              <p className="text-sm text-slate-600 leading-snug">{cat.label}</p>
              <div className="flex items-baseline gap-1.5 mt-2">
                <span className="text-2xl font-bold text-slate-900">{formatNumber(Number(cat.pendingCount ?? 0))}</span>
                <span className="text-xs text-slate-400">待处理</span>
              </div>
            </div>
          ))}
        </div>
      </Panel>
    </div>
  );
}
