import type React from 'react';
import {
  DollarSign,
  TrendingUp,
  TrendingDown,
  Target,
  AlertTriangle,
  BarChart3,
  Sparkles,
  ShoppingCart,
  Megaphone,
  Leaf,
} from 'lucide-react';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';

import { fetchProfitDashboard } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { t } from '../i18n';
import {
  cn,
  formatCurrency,
  formatPercent,
} from '../lib/utils';

// ─── Types ──────────────────────────────────────────────────────────
interface ProfitKPI {
  label: string;
  value: string;
  icon: React.ComponentType<any>;
  color: string;
  bgColor: string;
}

interface TopSku {
  sku: string;
  name: string;
  sales: number;
  netProfit: number;
  netMargin: number;
}

interface LosingSku extends TopSku {
  acos: number;
}

interface ProfitTrendPoint {
  date: string;
  netProfit: number;
}

interface ProfitDashboardData {
  totalSales: number;
  adSales: number;
  organicSales: number;
  adSpend: number;
  grossProfit: number;
  netProfit: number;
  netMargin: number;
  acos: number;
  tacos: number;
  roas: number;
  topSkus: TopSku[];
  losingSkus: LosingSku[];
  trendData?: ProfitTrendPoint[];
  aiSummary?: string;
}

/**
 * Normalize a SKU row from the profit dashboard. The backend {@code ProductProfitVo}
 * uses {@code productName} and {@code grossSales}; this page renders {@code name}
 * and {@code sales}. Accept both shapes so the top/losing SKU tables are never
 * blank when the backend uses its native field names.
 */
function normalizeProfitSku(raw: any) {
  return {
    sku: raw?.sku ?? '',
    name: raw?.name ?? raw?.productName ?? '',
    sales: Number(raw?.sales ?? raw?.grossSales ?? 0),
    netProfit: Number(raw?.netProfit ?? 0),
    netMargin: Number(raw?.netMargin ?? 0),
    acos: Number(raw?.acos ?? 0),
  };
}

// ─── Chart tooltip ──────────────────────────────────────────────────
function CustomTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: Array<{ value: number; dataKey: string; color: string }>;
  label?: string;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-white border border-slate-200 rounded-lg shadow-lg px-4 py-3">
      <p className="text-sm font-medium text-slate-700 mb-2">{label}</p>
      {payload.map((entry) => (
        <div key={entry.dataKey} className="flex items-center gap-2 text-sm">
          <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: entry.color }} />
          <span className="text-slate-500">净利润：</span>
          <span className="font-medium text-slate-800">{formatCurrency(entry.value)}</span>
        </div>
      ))}
    </div>
  );
}

// ─── Loading skeleton ───────────────────────────────────────────────
function ProfitDashboardSkeleton() {
  return (
    <div className="space-y-6">
      <div>
        <div className="h-8 w-56 bg-slate-200 rounded animate-pulse" />
        <div className="h-4 w-72 bg-slate-100 rounded animate-pulse mt-2" />
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
        {Array.from({ length: 10 }).map((_, i) => (
          <div key={i} className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="h-4 w-20 bg-slate-200 rounded animate-pulse mb-3" />
            <div className="h-7 w-24 bg-slate-100 rounded animate-pulse" />
          </div>
        ))}
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <div className="h-5 w-40 bg-slate-200 rounded animate-pulse mb-4" />
        <div className="h-72 bg-slate-100 rounded-lg animate-pulse" />
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {[1, 2].map((i) => (
          <div key={i} className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="h-5 w-40 bg-slate-200 rounded animate-pulse mb-4" />
            <div className="space-y-3">
              {[1, 2, 3].map((j) => (
                <div key={j} className="h-14 bg-slate-100 rounded-lg animate-pulse" />
              ))}
            </div>
          </div>
        ))}
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <div className="h-5 w-40 bg-slate-200 rounded animate-pulse mb-4" />
        <div className="h-20 bg-slate-100 rounded-lg animate-pulse" />
      </div>
    </div>
  );
}

// ─── Main Page ──────────────────────────────────────────────────────
export function ProfitDashboardPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const dashboardQuery = useApiQuery<ProfitDashboardData | null>(
    ['profit-dashboard', storeId],
    () => fetchProfitDashboard({ storeId: storeId! }) as Promise<ProfitDashboardData | null>,
    { enabled: !!storeId },
  );
  const data = dashboardQuery.data ?? null;
  const loading = !!storeId && dashboardQuery.isLoading;
  const error = dashboardQuery.isError ? dashboardQuery.error?.message ?? '加载失败' : null;
  const loadData = () => dashboardQuery.refetch();

  if (loading || storeLoading) return <ProfitDashboardSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <AlertTriangle size={48} className="text-red-300 mb-4" />
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
        <DollarSign size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">
          请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
        </p>
      </div>
    );
  }

  const raw = (data ?? {}) as any;
  // Normalize defensively: the backend may return a partial shape (missing
  // arrays or numeric fields). Without this, `d.topSkus.length` / `d.roas.toFixed`
  // throw and white-screen the page.
  const d = {
    totalSales: raw.totalSales ?? 0,
    adSales: raw.adSales ?? 0,
    organicSales: raw.organicSales ?? 0,
    adSpend: raw.adSpend ?? 0,
    grossProfit: raw.grossProfit ?? 0,
    netProfit: raw.netProfit ?? 0,
    netMargin: raw.netMargin ?? 0,
    acos: raw.acos ?? 0,
    tacos: raw.tacos ?? 0,
    roas: raw.roas ?? 0,
    trendData: Array.isArray(raw.trendData) ? raw.trendData : [],
    // Backend ProfitDashboardVo exposes topProfitableSkus / profitLosingSkus;
    // tolerate the legacy topSkus / losingSkus names too. Normalize each row so
    // productName/grossSales surface as name/sales (Req: tables must not be blank).
    topSkus: (Array.isArray(raw.topProfitableSkus)
      ? raw.topProfitableSkus
      : Array.isArray(raw.topSkus) ? raw.topSkus : []).map(normalizeProfitSku),
    losingSkus: (Array.isArray(raw.profitLosingSkus)
      ? raw.profitLosingSkus
      : Array.isArray(raw.losingSkus) ? raw.losingSkus : []).map(normalizeProfitSku),
    // The backend always returns aiSummary; carry it through so it actually renders.
    aiSummary: raw.aiSummary ?? '',
  };

  const kpis: ProfitKPI[] = [
    { label: '总销售额', value: formatCurrency(d.totalSales), icon: ShoppingCart, color: 'text-blue-600', bgColor: 'bg-blue-50' },
    { label: '广告销售额', value: formatCurrency(d.adSales), icon: Megaphone, color: 'text-violet-600', bgColor: 'bg-violet-50' },
    { label: '自然销售额', value: formatCurrency(d.organicSales), icon: Leaf, color: 'text-emerald-600', bgColor: 'bg-emerald-50' },
    { label: '广告花费', value: formatCurrency(d.adSpend), icon: DollarSign, color: 'text-orange-600', bgColor: 'bg-orange-50' },
    { label: '毛利润', value: formatCurrency(d.grossProfit), icon: TrendingUp, color: 'text-teal-600', bgColor: 'bg-teal-50' },
    { label: '净利润', value: formatCurrency(d.netProfit), icon: DollarSign, color: 'text-emerald-600', bgColor: 'bg-emerald-50' },
    { label: '净利率', value: formatPercent(d.netMargin), icon: BarChart3, color: 'text-blue-600', bgColor: 'bg-blue-50' },
    { label: 'ACoS', value: formatPercent(d.acos), icon: Target, color: 'text-red-600', bgColor: 'bg-red-50' },
    { label: 'TACoS', value: formatPercent(d.tacos), icon: Target, color: 'text-orange-600', bgColor: 'bg-orange-50' },
    { label: 'ROAS', value: `${d.roas.toFixed(2)}x`, icon: TrendingUp, color: 'text-emerald-600', bgColor: 'bg-emerald-50' },
  ];

  return (
    <div className="space-y-6">
      {/* ── Page Header ────────────────────────────────────────────── */}
      <div>
        <h1 className="text-2xl font-bold text-slate-900">{t('pages.profitDashboard.title')}</h1>
        <p className="text-sm text-slate-500 mt-1">{t('pages.profitDashboard.subtitle')}</p>
      </div>

      {/* ── KPI Cards ──────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
        {kpis.map((kpi) => {
          const Icon = kpi.icon;
          return (
            <div
              key={kpi.label}
              className="bg-white rounded-xl border border-slate-200 p-5 flex flex-col gap-3 hover:shadow-sm transition-shadow"
            >
              <div className="flex items-center gap-2">
                <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center', kpi.bgColor)}>
                  <Icon size={16} className={kpi.color} />
                </div>
                <span className="text-sm font-medium text-slate-500">{kpi.label}</span>
              </div>
              <span className="text-2xl font-bold text-slate-900 tracking-tight">{kpi.value}</span>
            </div>
          );
        })}
      </div>

      {/* ── Profit Trend Chart ─────────────────────────────────────── */}
      {d.trendData && d.trendData.length > 0 && (
        <div className="bg-white rounded-xl border border-slate-200 p-5">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-base font-semibold text-slate-900">净利润趋势</h2>
              <p className="text-sm text-slate-500 mt-0.5">过去 30 天每日净利润</p>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-sm bg-emerald-500" />
              <span className="text-xs text-slate-500">净利润</span>
            </div>
          </div>
          <div className="h-72">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={d.trendData} margin={{ top: 4, right: 4, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id="profitGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#10B981" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="#10B981" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#E2E8F0" vertical={false} />
                <XAxis
                  dataKey="date"
                  tick={{ fontSize: 12, fill: '#94A3B8' }}
                  axisLine={false}
                  tickLine={false}
                />
                <YAxis
                  tick={{ fontSize: 12, fill: '#94A3B8' }}
                  axisLine={false}
                  tickLine={false}
                  tickFormatter={(v: number) => `$${(v / 1000).toFixed(1)}k`}
                />
                <Tooltip content={<CustomTooltip />} />
                <Area
                  type="monotone"
                  dataKey="netProfit"
                  stroke="#10B981"
                  strokeWidth={2}
                  fill="url(#profitGradient)"
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}

      {/* ── Top Profitable + Profit Losing SKUs ───────────────────── */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Top Profitable SKUs */}
        <div className="bg-white rounded-xl border border-slate-200 p-5">
          <div className="flex items-center gap-2 mb-4">
            <TrendingUp size={18} className="text-emerald-500" />
            <h2 className="text-base font-semibold text-slate-900">最赚钱的 SKU</h2>
          </div>
          {d.topSkus.length === 0 ? (
            <p className="text-sm text-slate-400 py-8 text-center">暂无盈利 SKU 数据</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left py-2 pr-4 font-medium text-slate-500">SKU</th>
                    <th className="text-left py-2 pr-4 font-medium text-slate-500">商品</th>
                    <th className="text-right py-2 pr-4 font-medium text-slate-500">销售额</th>
                    <th className="text-right py-2 pr-4 font-medium text-slate-500">净利润</th>
                    <th className="text-right py-2 font-medium text-slate-500">利润率</th>
                  </tr>
                </thead>
                <tbody>
                  {d.topSkus.map((sku) => (
                    <tr key={sku.sku} className="border-b border-slate-50 last:border-0">
                      <td className="py-3 pr-4 font-mono text-xs text-slate-600">{sku.sku}</td>
                      <td className="py-3 pr-4 font-medium text-slate-800">{sku.name}</td>
                      <td className="py-3 pr-4 text-right text-slate-700">{formatCurrency(sku.sales)}</td>
                      <td className="py-3 pr-4 text-right font-medium text-emerald-600">{formatCurrency(sku.netProfit)}</td>
                      <td className="py-3 text-right">
                        <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-emerald-50 text-emerald-700">
                          {formatPercent(sku.netMargin)}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Profit Losing SKUs */}
        <div className="bg-white rounded-xl border border-slate-200 p-5">
          <div className="flex items-center gap-2 mb-4">
            <TrendingDown size={18} className="text-red-500" />
            <h2 className="text-base font-semibold text-slate-900">亏损的 SKU</h2>
          </div>
          {d.losingSkus.length === 0 ? (
            <p className="text-sm text-slate-400 py-8 text-center">未检测到亏损 SKU</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left py-2 pr-4 font-medium text-slate-500">SKU</th>
                    <th className="text-left py-2 pr-4 font-medium text-slate-500">商品</th>
                    <th className="text-right py-2 pr-4 font-medium text-slate-500">销售额</th>
                    <th className="text-right py-2 pr-4 font-medium text-slate-500">净利润</th>
                    <th className="text-right py-2 pr-4 font-medium text-slate-500">利润率</th>
                    <th className="text-right py-2 font-medium text-slate-500">ACoS</th>
                  </tr>
                </thead>
                <tbody>
                  {d.losingSkus.map((sku) => (
                    <tr key={sku.sku} className="border-b border-slate-50 last:border-0">
                      <td className="py-3 pr-4 font-mono text-xs text-slate-600">{sku.sku}</td>
                      <td className="py-3 pr-4 font-medium text-slate-800">{sku.name}</td>
                      <td className="py-3 pr-4 text-right text-slate-700">{formatCurrency(sku.sales)}</td>
                      <td className="py-3 pr-4 text-right font-medium text-red-600">{formatCurrency(sku.netProfit)}</td>
                      <td className="py-3 pr-4 text-right">
                        <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-red-50 text-red-700">
                          {formatPercent(sku.netMargin)}
                        </span>
                      </td>
                      <td className="py-3 text-right">
                        <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-orange-50 text-orange-700">
                          {formatPercent(sku.acos)}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* ── AI Profit Summary ──────────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-md bg-gradient-to-br from-blue-600 to-indigo-600 flex items-center justify-center">
            <Sparkles size={14} className="text-white" />
          </div>
          <h2 className="text-base font-semibold text-slate-900">AI 利润摘要</h2>
          <span className="ml-auto text-xs text-slate-400">自动生成的分析</span>
        </div>
        <p className="text-sm text-slate-700 leading-relaxed">
          {d.aiSummary || '暂无 AI 摘要。导入销售数据后将自动生成利润分析。'}
        </p>
      </div>
    </div>
  );
}
