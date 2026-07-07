import { useState } from 'react';
import {
  Search,
  Filter,
  AlertTriangle,
  Download,
  ChevronDown,
} from 'lucide-react';

import { fetchProductProfit } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { notify } from '../lib/toast';
import { useStoreId } from '../lib/useStoreId';
import { t } from '../i18n';
import {
  cn,
  formatCurrency,
  formatPercent,
  formatNumber,
} from '../lib/utils';

// ─── Types ──────────────────────────────────────────────────────────
interface ProductProfitRow {
  sku: string;
  asin: string;
  name: string;
  unitsSold: number;
  sales: number;
  organicSales: number;
  adSales: number;
  adSpend: number;
  amazonFees: number;
  fbaFees: number;
  cogs: number;
  grossProfit: number;
  netProfit: number;
  netMargin: number;
  acos: number;
  tacos: number;
  breakEvenAcos: number;
}

// ─── Loading skeleton ───────────────────────────────────────────────
function ProductProfitSkeleton() {
  return (
    <div className="space-y-6">
      <div>
        <div className="h-8 w-48 bg-slate-200 rounded animate-pulse" />
        <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
      </div>
      <div className="flex flex-col sm:flex-row gap-3">
        <div className="h-10 w-64 bg-slate-200 rounded-lg animate-pulse" />
        <div className="h-10 w-40 bg-slate-200 rounded-lg animate-pulse" />
        <div className="h-10 w-40 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <div className="space-y-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="h-12 bg-slate-100 rounded-lg animate-pulse" />
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── Main Page ──────────────────────────────────────────────────────
export function ProductProfitPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const [search, setSearch] = useState('');
  const [dateRange, setDateRange] = useState('30d');
  // Marketplace filter state backing the "全部站点" select (previously referenced
  // but never declared, which crashed the page at runtime).
  const [marketplace, setMarketplace] = useState('all');

  const profitQuery = useApiQuery<ProductProfitRow[]>(
    ['product-profit', storeId, { dateRange, marketplace }],
    () => fetchProductProfit({
      storeId: storeId!,
      marketplace: marketplace !== 'all' ? marketplace : undefined,
    }).then((result: any) => result?.items ?? result?.records ?? (Array.isArray(result) ? result : [])),
    { enabled: !!storeId },
  );
  const products = profitQuery.data ?? [];
  const loading = !!storeId && profitQuery.isLoading;
  const error = profitQuery.isError ? profitQuery.error?.message ?? '加载失败' : null;
  const loadData = () => profitQuery.refetch();

  function flashMessage(type: 'success' | 'error', text: string) {
    if (type === 'success') notify.success(text);
    else notify.error(text);
  }

  function exportCsv(rows: ProductProfitRow[]) {
    try {
      if (rows.length === 0) {
        flashMessage('error', '暂无可导出的数据');
        return;
      }
      const header = [
        'SKU', 'ASIN', '名称', '销量', '销售额', '自然销售额', '广告销售额',
        '广告花费', '亚马逊费用', 'FBA费用', '成本', '毛利', '净利', '净利率%', 'ACoS%', 'TACoS%',
      ];
      const escape = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;
      const lines = rows.map((p) =>
        [
          p.sku, p.asin, p.name, p.unitsSold, p.sales, p.organicSales, p.adSales,
          p.adSpend, p.amazonFees, p.fbaFees, p.cogs, p.grossProfit, p.netProfit,
          p.netMargin, p.acos, p.tacos,
        ].map(escape).join(','),
      );
      const csv = '\uFEFF' + [header.map(escape).join(','), ...lines].join('\r\n');
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `product-profit-${new Date().toISOString().split('T')[0]}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      flashMessage('success', `已导出 ${rows.length} 条 SKU 利润数据`);
    } catch (err) {
      flashMessage('error', err instanceof Error ? err.message : 'CSV 导出失败');
    }
  }

  if (loading || storeLoading) return <ProductProfitSkeleton />;

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
        <Filter size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">
          请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
        </p>
      </div>
    );
  }

  const filtered = products.filter(
    (p) =>
      search === '' ||
      p.name.toLowerCase().includes(search.toLowerCase()) ||
      p.sku.toLowerCase().includes(search.toLowerCase()) ||
      p.asin.toLowerCase().includes(search.toLowerCase()),
  );

  // Summary totals
  const totals = filtered.reduce(
    (acc, p) => ({
      unitsSold: acc.unitsSold + p.unitsSold,
      sales: acc.sales + p.sales,
      organicSales: acc.organicSales + p.organicSales,
      adSales: acc.adSales + p.adSales,
      adSpend: acc.adSpend + p.adSpend,
      amazonFees: acc.amazonFees + p.amazonFees,
      fbaFees: acc.fbaFees + p.fbaFees,
      cogs: acc.cogs + p.cogs,
      grossProfit: acc.grossProfit + p.grossProfit,
      netProfit: acc.netProfit + p.netProfit,
    }),
    { unitsSold: 0, sales: 0, organicSales: 0, adSales: 0, adSpend: 0, amazonFees: 0, fbaFees: 0, cogs: 0, grossProfit: 0, netProfit: 0 },
  );

  const totalsWithMargins = {
    ...totals,
    netMargin: totals.sales > 0 ? (totals.netProfit / totals.sales) * 100 : 0,
    acos: totals.adSales > 0 ? (totals.adSpend / totals.adSales) * 100 : 0,
    tacos: totals.sales > 0 ? (totals.adSpend / totals.sales) * 100 : 0,
    breakEvenAcos: 0,
  };

  return (
    <div className="space-y-6">
      {/* ── Page Header ────────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">{t('pages.productProfit.title')}</h1>
          <p className="text-sm text-slate-500 mt-1">{t('pages.productProfit.subtitle')}</p>
        </div>
        <button
          onClick={() => exportCsv(filtered)}
          className="inline-flex items-center gap-2 px-4 py-2 bg-white border border-slate-200 rounded-lg text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors"
        >
          <Download size={16} />
          导出 CSV
        </button>
      </div>

      {/* ── Filter Bar ─────────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row gap-3">
        <div className="relative flex-1 max-w-sm">
          <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            placeholder="搜索 SKU、ASIN 或名称..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-9 pr-4 py-2 bg-white border border-slate-200 rounded-lg text-sm text-slate-700 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
        </div>
        <div className="relative">
          <select
            value={dateRange}
            onChange={(e) => setDateRange(e.target.value)}
            className="appearance-none pl-3 pr-8 py-2 bg-white border border-slate-200 rounded-lg text-sm text-slate-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          >
            <option value="7d">过去 7 天</option>
            <option value="14d">过去 14 天</option>
            <option value="30d">过去 30 天</option>
            <option value="90d">过去 90 天</option>
          </select>
          <ChevronDown size={14} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none" />
        </div>
        <div className="relative">
          <select
            value={marketplace}
            onChange={(e) => setMarketplace(e.target.value)}
            className="appearance-none pl-3 pr-8 py-2 bg-white border border-slate-200 rounded-lg text-sm text-slate-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          >
            <option value="all">全部站点</option>
            <option value="US">美国</option>
            <option value="CA">加拿大</option>
            <option value="UK">英国</option>
            <option value="DE">德国</option>
          </select>
          <ChevronDown size={14} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none" />
        </div>
      </div>

      {/* ── Product Profit Table ───────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        {filtered.length === 0 ? (
          <div className="py-16 text-center">
            <Filter size={40} className="mx-auto text-slate-300 mb-3" />
            <p className="text-sm text-slate-500">没有匹配筛选条件的产品</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm whitespace-nowrap">
              <thead>
                <tr className="bg-slate-50 border-b border-slate-200">
                  <th className="text-left px-4 py-3 font-medium text-slate-600 sticky left-0 bg-slate-50">SKU</th>
                  <th className="text-left px-4 py-3 font-medium text-slate-600">ASIN</th>
                  <th className="text-left px-4 py-3 font-medium text-slate-600">产品名称</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">销量</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">销售额</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">自然销售额</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">广告销售额</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">广告花费</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">亚马逊费用</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">FBA 费用</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">成本</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">毛利</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">净利润</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">净利润率</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">ACoS</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">TACoS</th>
                  <th className="text-right px-4 py-3 font-medium text-slate-600">盈亏平衡 ACoS</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((p) => (
                  <tr key={p.sku} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-3 font-mono text-xs text-slate-600 sticky left-0 bg-white">{p.sku}</td>
                    <td className="px-4 py-3 font-mono text-xs text-blue-600">{p.asin}</td>
                    <td className="px-4 py-3 font-medium text-slate-800 max-w-[200px] truncate">{p.name}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatNumber(p.unitsSold)}</td>
                    <td className="px-4 py-3 text-right font-medium text-slate-800">{formatCurrency(p.sales)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(p.organicSales)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(p.adSales)}</td>
                    <td className="px-4 py-3 text-right text-orange-600">{formatCurrency(p.adSpend)}</td>
                    <td className="px-4 py-3 text-right text-slate-600">{formatCurrency(p.amazonFees)}</td>
                    <td className="px-4 py-3 text-right text-slate-600">{formatCurrency(p.fbaFees)}</td>
                    <td className="px-4 py-3 text-right text-slate-600">{formatCurrency(p.cogs)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(p.grossProfit)}</td>
                    <td className={cn('px-4 py-3 text-right font-medium', p.netProfit >= 0 ? 'text-emerald-600' : 'text-red-600')}>
                      {formatCurrency(p.netProfit)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <span
                        className={cn(
                          'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
                          p.netMargin >= 20
                            ? 'bg-emerald-50 text-emerald-700'
                            : p.netMargin >= 0
                              ? 'bg-yellow-50 text-yellow-700'
                              : 'bg-red-50 text-red-700',
                        )}
                      >
                        {formatPercent(p.netMargin)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <span
                        className={cn(
                          'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
                          p.acos <= 25
                            ? 'bg-emerald-50 text-emerald-700'
                            : p.acos <= 35
                              ? 'bg-yellow-50 text-yellow-700'
                              : 'bg-red-50 text-red-700',
                        )}
                      >
                        {formatPercent(p.acos)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-slate-600">{formatPercent(p.tacos)}</td>
                    <td className="px-4 py-3 text-right text-slate-500">{formatPercent(p.breakEvenAcos)}</td>
                  </tr>
                ))}

                {/* Summary Row */}
                <tr className="bg-slate-50 border-t-2 border-slate-200 font-semibold">
                  <td className="px-4 py-3 text-slate-800 sticky left-0 bg-slate-50" colSpan={3}>
                    合计 ({filtered.length} 个产品)
                  </td>
                  <td className="px-4 py-3 text-right text-slate-800">{formatNumber(totalsWithMargins.unitsSold)}</td>
                  <td className="px-4 py-3 text-right text-slate-800">{formatCurrency(totalsWithMargins.sales)}</td>
                  <td className="px-4 py-3 text-right text-slate-800">{formatCurrency(totalsWithMargins.organicSales)}</td>
                  <td className="px-4 py-3 text-right text-slate-800">{formatCurrency(totalsWithMargins.adSales)}</td>
                  <td className="px-4 py-3 text-right text-orange-700">{formatCurrency(totalsWithMargins.adSpend)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(totalsWithMargins.amazonFees)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(totalsWithMargins.fbaFees)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(totalsWithMargins.cogs)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(totalsWithMargins.grossProfit)}</td>
                  <td className={cn('px-4 py-3 text-right', totalsWithMargins.netProfit >= 0 ? 'text-emerald-700' : 'text-red-700')}>
                    {formatCurrency(totalsWithMargins.netProfit)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <span
                      className={cn(
                        'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold',
                        totalsWithMargins.netMargin >= 20
                          ? 'bg-emerald-100 text-emerald-800'
                          : totalsWithMargins.netMargin >= 0
                            ? 'bg-yellow-100 text-yellow-800'
                            : 'bg-red-100 text-red-800',
                      )}
                    >
                      {formatPercent(totalsWithMargins.netMargin)}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatPercent(totalsWithMargins.acos)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatPercent(totalsWithMargins.tacos)}</td>
                  <td className="px-4 py-3 text-right text-slate-600">--</td>
                </tr>
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
