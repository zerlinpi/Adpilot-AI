import { useState, useEffect, useCallback } from 'react';
import { BarChart3, Package, FileText, Tag, Globe, Search, Lock, AlertTriangle, Unlock } from 'lucide-react';
import {
  fetchInsightsProductList,
  fetchInsightsBrandMetrics,
  fetchInsightsMarketInsights,
  fetchInsightsSqp,
  activateDataSource,
  type ProductInsights,
  type BrandMetrics,
  type MarketInsights,
  type SqpInsights,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { formatCurrency, formatPercent, formatNumber, cn } from '../lib/utils';

type TabKey = 'products' | 'reports' | 'brand' | 'market' | 'sqp';

const TABS: { key: TabKey; label: string; icon: typeof Package }[] = [
  { key: 'products', label: '商品列表', icon: Package },
  { key: 'reports', label: '自定义报告', icon: FileText },
  { key: 'brand', label: '品牌指标', icon: Tag },
  { key: 'market', label: '市场洞察', icon: Globe },
  { key: 'sqp', label: 'SQP分析', icon: Search },
];

const inventoryStatusLabel: Record<string, string> = {
  healthy: '库存充足',
  low: '库存偏低',
  out_of_stock: '缺货',
};

function inventoryBadge(status?: string): string {
  const styles: Record<string, string> = {
    healthy: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    low: 'bg-amber-100 text-amber-700 border-amber-200',
    out_of_stock: 'bg-red-100 text-red-700 border-red-200',
  };
  return styles[status || ''] || 'bg-slate-100 text-slate-500 border-slate-200';
}

// ─── Shared state helpers ────────────────────────────────────────────
function EmptyState({ icon: Icon, title, hint }: { icon: typeof Package; title: string; hint?: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <Icon size={44} className="text-slate-300 mb-3" />
      <p className="text-base font-medium text-slate-500">{title}</p>
      {hint && <p className="text-sm text-slate-400 mt-1 max-w-md">{hint}</p>}
    </div>
  );
}

function ActivationState({
  message,
  onActivate,
  activating,
}: {
  message?: string;
  onActivate?: () => void;
  activating?: boolean;
}) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <Lock size={44} className="text-slate-300 mb-3" />
      <p className="text-base font-medium text-slate-600">需要激活品牌数据源</p>
      <p className="text-sm text-slate-400 mt-1 max-w-md">
        {message || '该功能需先激活对应数据源后方可使用。'}
      </p>
      {onActivate && (
        <button
          onClick={onActivate}
          disabled={activating}
          className="mt-4 inline-flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
        >
          <Unlock size={15} />
          {activating ? '激活中...' : '激活品牌数据源'}
        </button>
      )}
    </div>
  );
}

function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <AlertTriangle size={44} className="text-red-300 mb-3" />
      <p className="text-base font-medium text-slate-700">加载失败</p>
      <p className="text-sm text-slate-500 mt-1 max-w-md">{message}</p>
      <button
        onClick={onRetry}
        className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700"
      >
        重试
      </button>
    </div>
  );
}

function Loading() {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-12 bg-slate-100 rounded animate-pulse" />
      ))}
    </div>
  );
}

export function DataInsightsPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const [tab, setTab] = useState<TabKey>('products');

  // Per-surface state.
  const [product, setProduct] = useState<ProductInsights | null>(null);
  const [brand, setBrand] = useState<BrandMetrics | null>(null);
  const [market, setMarket] = useState<MarketInsights | null>(null);
  const [sqp, setSqp] = useState<SqpInsights | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activating, setActivating] = useState(false);

  const loadTab = useCallback(
    async (which: TabKey) => {
      if (!storeId) return;
      setLoading(true);
      setError(null);
      try {
        if (which === 'products' || which === 'reports') {
          if (!product) setProduct(await fetchInsightsProductList({ storeId }));
        } else if (which === 'brand') {
          if (!brand) setBrand(await fetchInsightsBrandMetrics({ storeId }));
        } else if (which === 'market') {
          if (!market) setMarket(await fetchInsightsMarketInsights(storeId));
        } else if (which === 'sqp') {
          if (!sqp) setSqp(await fetchInsightsSqp({ storeId }));
        }
      } catch (err: any) {
        setError(err?.message || '加载数据洞察失败');
      } finally {
        setLoading(false);
      }
    },
    [storeId, product, brand, market, sqp],
  );

  // Reset caches when the active store changes.
  useEffect(() => {
    setProduct(null);
    setBrand(null);
    setMarket(null);
    setSqp(null);
  }, [storeId]);

  useEffect(() => {
    loadTab(tab);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, storeId]);

  function retry() {
    // Clear the relevant cache then reload.
    if (tab === 'products' || tab === 'reports') setProduct(null);
    if (tab === 'brand') setBrand(null);
    if (tab === 'market') setMarket(null);
    if (tab === 'sqp') setSqp(null);
    loadTab(tab);
  }

  /** Activate the brand data source (item 8), then refresh the gated surfaces. */
  async function handleActivateBrand() {
    if (!storeId) return;
    setActivating(true);
    setError(null);
    try {
      await activateDataSource(storeId, 'brand_analytics');
      // Brand + SQP both gate on brand_analytics — clear both caches and reload.
      setBrand(null);
      setSqp(null);
      await loadTab(tab);
    } catch (err: any) {
      setError(err?.message || '激活品牌数据源失败');
    } finally {
      setActivating(false);
    }
  }

  const currency = product?.currency || 'USD';

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <BarChart3 size={24} className="text-blue-600" />
        <div>
          <h1 className="text-2xl font-bold text-slate-900">数据洞察</h1>
          <p className="text-sm text-slate-500 mt-0.5">商品表现、自定义报告、品牌指标、市场洞察与 SQP 分析</p>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex flex-wrap gap-1 border-b border-slate-200">
        {TABS.map((t) => {
          const Icon = t.icon;
          const active = tab === t.key;
          return (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={cn(
                'inline-flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors',
                active
                  ? 'border-blue-600 text-blue-600'
                  : 'border-transparent text-slate-500 hover:text-slate-700',
              )}
            >
              <Icon size={15} />
              {t.label}
            </button>
          );
        })}
      </div>

      {storeLoading ? (
        <Loading />
      ) : storeError ? (
        <ErrorState message={storeError} onRetry={() => { }} />
      ) : !storeId ? (
        <EmptyState icon={Package} title="请选择店铺" hint="在顶部切换店铺以查看数据洞察。" />
      ) : loading ? (
        <Loading />
      ) : error ? (
        <ErrorState message={error} onRetry={retry} />
      ) : (
        <>
          {tab === 'products' && <ProductListTab data={product} currency={currency} />}
          {tab === 'reports' && <CustomReportsTab data={product} />}
          {tab === 'brand' && (
            <BrandMetricsTab data={brand} onActivate={handleActivateBrand} activating={activating} />
          )}
          {tab === 'market' && <MarketInsightsTab data={market} />}
          {tab === 'sqp' && <SqpTab data={sqp} onActivate={handleActivateBrand} activating={activating} />}
        </>
      )}
    </div>
  );
}

// ─── Product list tab (Req 30.1) ─────────────────────────────────────
function ProductListTab({ data, currency }: { data: ProductInsights | null; currency: string }) {
  if (!data || data.items.length === 0) {
    return <EmptyState icon={Package} title="暂无商品数据" hint="该店铺下暂无可展示的商品表现数据。" />;
  }
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
            <th className="px-4 py-3">商品 / ASIN</th>
            <th className="px-4 py-3 text-right">广告销额</th>
            <th className="px-4 py-3 text-right">广告花费</th>
            <th className="px-4 py-3 text-right">TACoS</th>
            <th className="px-4 py-3 text-right">总销额</th>
            <th className="px-4 py-3 text-right">总订单数</th>
            <th className="px-4 py-3">库存状态</th>
          </tr>
        </thead>
        <tbody>
          {data.items.map((it) => (
            <tr key={it.productId || it.asin} className="border-b border-slate-50 hover:bg-slate-50/60">
              <td className="px-4 py-3">
                <div className="font-medium text-slate-900">{it.name || '-'}</div>
                <div className="text-xs text-slate-400">
                  {it.parentAsin || it.asin || '-'}
                  {it.sku ? ` · ${it.sku}` : ''}
                </div>
              </td>
              <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(it.adSales ?? 0, currency)}</td>
              <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(it.adSpend ?? 0, currency)}</td>
              <td className="px-4 py-3 text-right text-slate-700">{formatPercent(it.tacos ?? 0)}</td>
              <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(it.totalSales ?? 0, currency)}</td>
              <td className="px-4 py-3 text-right text-slate-700">{formatNumber(it.totalOrders ?? 0)}</td>
              <td className="px-4 py-3">
                <span
                  className={cn(
                    'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
                    inventoryBadge(it.inventoryStatus),
                  )}
                >
                  {inventoryStatusLabel[it.inventoryStatus || ''] || '未知'}
                  {typeof it.inventory === 'number' ? ` · ${it.inventory}` : ''}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ─── Custom reports tab (Req 30.2) ───────────────────────────────────
function CustomReportsTab({ data }: { data: ProductInsights | null }) {
  if (!data) {
    return <EmptyState icon={FileText} title="暂无自定义报告" />;
  }
  const consumed = data.customReportConsumed ?? 0;
  const total = data.customReportTotal ?? 0;
  const pct = total > 0 ? Math.min(100, (consumed / total) * 100) : 0;

  return (
    <div className="space-y-4">
      <div className="bg-white rounded-xl border border-slate-200 p-4">
        <div className="flex items-center justify-between">
          <p className="text-sm font-medium text-slate-700">报告配额</p>
          <p className="text-sm text-slate-500">
            已用 <span className="font-semibold text-slate-900">{consumed}</span> / {total}
          </p>
        </div>
        <div className="mt-2 h-2 w-full rounded-full bg-slate-100 overflow-hidden">
          <div className="h-full bg-blue-600 rounded-full" style={{ width: `${pct}%` }} />
        </div>
      </div>

      {data.customReports.length === 0 ? (
        <EmptyState icon={FileText} title="暂无定时自定义报告" hint="尚未创建定时自定义报告。" />
      ) : (
        <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
                <th className="px-4 py-3">报告标题</th>
                <th className="px-4 py-3">类型</th>
                <th className="px-4 py-3">统计周期</th>
                <th className="px-4 py-3">创建时间</th>
              </tr>
            </thead>
            <tbody>
              {data.customReports.map((r) => (
                <tr key={r.id} className="border-b border-slate-50 hover:bg-slate-50/60">
                  <td className="px-4 py-3 font-medium text-slate-900">{r.title || '-'}</td>
                  <td className="px-4 py-3 text-slate-600">{r.type || '-'}</td>
                  <td className="px-4 py-3 text-slate-600">
                    {r.periodStart || '-'} ~ {r.periodEnd || '-'}
                  </td>
                  <td className="px-4 py-3 text-slate-600">{r.createdAt?.slice(0, 10) || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ─── Brand metrics tab (Req 30.3) ────────────────────────────────────
function BrandMetricsTab({
  data,
  onActivate,
  activating,
}: {
  data: BrandMetrics | null;
  onActivate: () => void;
  activating: boolean;
}) {
  if (!data) return <EmptyState icon={Tag} title="暂无品牌指标" />;
  if (data.requiresActivation) {
    return <ActivationState message={data.message} onActivate={onActivate} activating={activating} />;
  }

  const cards = [
    { label: '品牌顾客总数', value: data.totalBrandCustomers != null ? formatNumber(data.totalBrandCustomers) : '-' },
    { label: '顾客互动率', value: data.engagementRate != null ? formatPercent(data.engagementRate) : '-' },
    { label: '顾客转化率', value: data.conversionRate != null ? formatPercent(data.conversionRate) : '-' },
    { label: '品牌新客销售额占比', value: data.newToBrandSalesShare != null ? formatPercent(data.newToBrandSalesShare) : '-' },
  ];
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {cards.map((c) => (
        <div key={c.label} className="bg-white rounded-xl border border-slate-200 p-4">
          <p className="text-xs text-slate-500">{c.label}</p>
          <p className="text-xl font-semibold text-slate-900 mt-1">{c.value}</p>
        </div>
      ))}
    </div>
  );
}

// ─── Market insights tab (Req 30.4) ──────────────────────────────────
function MarketInsightsTab({ data }: { data: MarketInsights | null }) {
  if (!data) return <EmptyState icon={Globe} title="暂无市场洞察" />;
  if (data.requiresActivation) return <ActivationState message={data.message} />;
  if (data.reports.length === 0) {
    return <EmptyState icon={Globe} title="暂无市场监控报告" hint={data.message} />;
  }
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
            <th className="px-4 py-3">报告名称</th>
            <th className="px-4 py-3">分类</th>
            <th className="px-4 py-3">更新时间</th>
          </tr>
        </thead>
        <tbody>
          {data.reports.map((r) => (
            <tr key={r.id} className="border-b border-slate-50 hover:bg-slate-50/60">
              <td className="px-4 py-3 font-medium text-slate-900">{r.name || '-'}</td>
              <td className="px-4 py-3 text-slate-600">{r.category || '-'}</td>
              <td className="px-4 py-3 text-slate-600">{r.updatedAt || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ─── SQP tab (Req 30.5) ──────────────────────────────────────────────
function SqpTab({
  data,
  onActivate,
  activating,
}: {
  data: SqpInsights | null;
  onActivate: () => void;
  activating: boolean;
}) {
  if (!data) return <EmptyState icon={Search} title="暂无 SQP 数据" />;
  if (data.requiresActivation) {
    return <ActivationState message={data.message} onActivate={onActivate} activating={activating} />;
  }
  if (data.rows.length === 0) {
    return <EmptyState icon={Search} title="暂无 SQP 数据" hint={data.message} />;
  }
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
            <th className="px-4 py-3">搜索词</th>
            <th className="px-4 py-3 text-right">曝光量</th>
            <th className="px-4 py-3 text-right">曝光份额</th>
            <th className="px-4 py-3 text-right">点击率 (品牌/市场)</th>
            <th className="px-4 py-3 text-right">加购率 (品牌/市场)</th>
            <th className="px-4 py-3 text-right">转化率 (品牌/市场)</th>
          </tr>
        </thead>
        <tbody>
          {data.rows.map((r, i) => (
            <tr key={r.searchQuery || i} className="border-b border-slate-50 hover:bg-slate-50/60">
              <td className="px-4 py-3 font-medium text-slate-900">{r.searchQuery || '-'}</td>
              <td className="px-4 py-3 text-right text-slate-700">{formatNumber(r.impressions ?? 0)}</td>
              <td className="px-4 py-3 text-right text-slate-700">{formatPercent(r.impressionShare ?? 0)}</td>
              <td className="px-4 py-3 text-right text-slate-700">
                {formatPercent(r.brandClickRate ?? 0)} / {formatPercent(r.marketClickRate ?? 0)}
              </td>
              <td className="px-4 py-3 text-right text-slate-700">
                {formatPercent(r.brandAddToCartRate ?? 0)} / {formatPercent(r.marketAddToCartRate ?? 0)}
              </td>
              <td className="px-4 py-3 text-right text-slate-700">
                {formatPercent(r.brandConversionRate ?? 0)} / {formatPercent(r.marketConversionRate ?? 0)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
