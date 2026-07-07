import type React from 'react';
import { Link } from 'react-router';
import {
  Package,
  AlertTriangle,
  TrendingUp,
  Clock,
  ShieldAlert,
  Tag,
  ShoppingCart,
  ArrowRight,
} from 'lucide-react';

import { fetchInventoryHealth } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { t } from '../i18n';
import {
  cn,
  formatCurrency,
  formatDate,
  formatNumber,
  getRiskColor,
} from '../lib/utils';

// ─── Types ──────────────────────────────────────────────────────────
interface StockoutRisk {
  sku: string;
  name: string;
  inventory: number;
  dailyVelocity: number;
  daysOfSupply: number;
  stockoutDate: string;
  risk: 'low' | 'medium' | 'high';
}

interface OverstockRisk {
  sku: string;
  name: string;
  inventory: number;
  daysOfSupply: number;
  value: number;
  recommendation: string;
}

interface InventoryHealthData {
  totalValue: number;
  lowStockCount: number;
  overstockCount: number;
  avgDaysOfSupply: number;
  stockoutRisks: StockoutRisk[];
  overstockRisks: OverstockRisk[];
  notSafeToScale?: Array<{ sku: string; name: string; reason: string }>;
  clearanceCandidates?: Array<{ sku: string; name: string; inventory: number; daysOfSupply: number; value: number }>;
}

// ─── Risk badge ─────────────────────────────────────────────────────
function RiskBadge({ risk }: { risk: string }) {
  const riskLabels: Record<string, string> = { low: '低', medium: '中', high: '高' };
  return (
    <span className={cn('inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium', getRiskColor(risk))}>
      {riskLabels[risk] || risk}
    </span>
  );
}

// ─── KPI Card ───────────────────────────────────────────────────────
function InventoryKPICard({
  label,
  value,
  icon: Icon,
  color,
  bgColor,
}: {
  label: string;
  value: string;
  icon: React.ComponentType<any>;
  color: string;
  bgColor: string;
}) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-5 flex flex-col gap-3 hover:shadow-sm transition-shadow">
      <div className="flex items-center gap-2">
        <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center', bgColor)}>
          <Icon size={16} className={color} />
        </div>
        <span className="text-sm font-medium text-slate-500">{label}</span>
      </div>
      <span className="text-2xl font-bold text-slate-900 tracking-tight">{value}</span>
    </div>
  );
}

// ─── Loading skeleton ───────────────────────────────────────────────
function InventorySkeleton() {
  return (
    <div className="space-y-6">
      <div>
        <div className="h-8 w-48 bg-slate-200 rounded animate-pulse" />
        <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
      </div>
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="h-4 w-24 bg-slate-200 rounded animate-pulse mb-3" />
            <div className="h-7 w-20 bg-slate-100 rounded animate-pulse" />
          </div>
        ))}
      </div>
      {[1, 2, 3].map((i) => (
        <div key={i} className="bg-white rounded-xl border border-slate-200 p-5">
          <div className="h-5 w-40 bg-slate-200 rounded animate-pulse mb-4" />
          <div className="space-y-3">
            {[1, 2].map((j) => (
              <div key={j} className="h-14 bg-slate-100 rounded-lg animate-pulse" />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Main Page ──────────────────────────────────────────────────────
export function InventoryHealthPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const healthQuery = useApiQuery<InventoryHealthData | null>(
    ['inventory-health', storeId],
    () => fetchInventoryHealth({ storeId: storeId! }) as Promise<InventoryHealthData | null>,
    { enabled: !!storeId },
  );
  const data = healthQuery.data ?? null;
  const loading = !!storeId && healthQuery.isLoading;
  const error = healthQuery.isError ? healthQuery.error?.message ?? '加载失败' : null;
  const loadData = () => healthQuery.refetch();

  if (loading || storeLoading) return <InventorySkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <AlertTriangle size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出现错误</p>
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
        <Package size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">
          请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
        </p>
      </div>
    );
  }

  const d = data ?? {
    totalValue: 0,
    lowStockCount: 0,
    overstockCount: 0,
    avgDaysOfSupply: 0,
    stockoutRisks: [],
    overstockRisks: [],
    notSafeToScale: [],
    clearanceCandidates: [],
  };

  return (
    <div className="space-y-6">
      {/* ── Page Header ────────────────────────────────────────────── */}
      <div>
        <h1 className="text-2xl font-bold text-slate-900">{t('pages.inventoryHealth.title')}</h1>
        <p className="text-sm text-slate-500 mt-1">{t('pages.inventoryHealth.subtitle')}</p>
      </div>

      {/* ── KPI Cards ──────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <InventoryKPICard
          label="库存总价值"
          value={formatCurrency(d.totalValue)}
          icon={Package}
          color="text-blue-600"
          bgColor="bg-blue-50"
        />
        <InventoryKPICard
          label="低库存 SKU"
          value={String(d.lowStockCount)}
          icon={AlertTriangle}
          color="text-red-600"
          bgColor="bg-red-50"
        />
        <InventoryKPICard
          label="积压 SKU"
          value={String(d.overstockCount)}
          icon={TrendingUp}
          color="text-orange-600"
          bgColor="bg-orange-50"
        />
        <InventoryKPICard
          label="平均可供应天数"
          value={`${d.avgDaysOfSupply} 天`}
          icon={Clock}
          color="text-emerald-600"
          bgColor="bg-emerald-50"
        />
      </div>

      {/* ── Stockout Risk Table ────────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <div className="flex items-center gap-2 mb-4">
          <ShieldAlert size={18} className="text-red-500" />
          <h2 className="text-base font-semibold text-slate-900">缺货风险</h2>
          <span className="ml-auto text-xs text-slate-400">已追踪 {d.stockoutRisks.length} 个产品</span>
          <Link
            to="/replenishment"
            className="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700"
          >
            补货计划
            <ArrowRight size={13} />
          </Link>
        </div>
        {d.stockoutRisks.length === 0 ? (
          <p className="text-sm text-slate-400 py-8 text-center">未检测到缺货风险</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100">
                  <th className="text-left py-2 pr-4 font-medium text-slate-500">SKU</th>
                  <th className="text-left py-2 pr-4 font-medium text-slate-500">产品名称</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">当前库存</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">日均销量</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">可供应天数</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">缺货日期</th>
                  <th className="text-center py-2 font-medium text-slate-500">风险等级</th>
                </tr>
              </thead>
              <tbody>
                {d.stockoutRisks.map((item) => (
                  <tr key={item.sku} className="border-b border-slate-50 last:border-0 hover:bg-slate-50/50 transition-colors">
                    <td className="py-3 pr-4 font-mono text-xs text-slate-600">{item.sku}</td>
                    <td className="py-3 pr-4 font-medium text-slate-800">{item.name}</td>
                    <td className="py-3 pr-4 text-right text-slate-700">{formatNumber(item.inventory)}</td>
                    <td className="py-3 pr-4 text-right text-slate-700">{item.dailyVelocity}/天</td>
                    <td className="py-3 pr-4 text-right">
                      <span
                        className={cn(
                          'font-medium',
                          item.daysOfSupply <= 7
                            ? 'text-red-600'
                            : item.daysOfSupply <= 14
                              ? 'text-orange-600'
                              : 'text-slate-700',
                        )}
                      >
                        {item.daysOfSupply} 天
                      </span>
                    </td>
                    <td className="py-3 pr-4 text-right text-slate-600">
                      {formatDate(item.stockoutDate)}
                    </td>
                    <td className="py-3 text-center">
                      <RiskBadge risk={item.risk} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ── Overstock Table ────────────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <div className="flex items-center gap-2 mb-4">
          <TrendingUp size={18} className="text-orange-500" />
          <h2 className="text-base font-semibold text-slate-900">积压预警</h2>
        </div>
        {d.overstockRisks.length === 0 ? (
          <p className="text-sm text-slate-400 py-8 text-center">暂无积压预警</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100">
                  <th className="text-left py-2 pr-4 font-medium text-slate-500">SKU</th>
                  <th className="text-left py-2 pr-4 font-medium text-slate-500">产品名称</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">库存</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">可供应天数</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">库存价值</th>
                  <th className="text-left py-2 font-medium text-slate-500">建议</th>
                </tr>
              </thead>
              <tbody>
                {d.overstockRisks.map((item) => (
                  <tr key={item.sku} className="border-b border-slate-50 last:border-0 hover:bg-slate-50/50 transition-colors">
                    <td className="py-3 pr-4 font-mono text-xs text-slate-600">{item.sku}</td>
                    <td className="py-3 pr-4 font-medium text-slate-800">{item.name}</td>
                    <td className="py-3 pr-4 text-right text-slate-700">{formatNumber(item.inventory)}</td>
                    <td className="py-3 pr-4 text-right">
                      <span className="font-medium text-orange-600">{item.daysOfSupply} 天</span>
                    </td>
                    <td className="py-3 pr-4 text-right font-medium text-slate-700">{formatCurrency(item.value)}</td>
                    <td className="py-3">
                      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-orange-50 text-orange-700 rounded-md text-xs font-medium">
                        <Tag size={12} />
                        {item.recommendation}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ── Products Not Safe to Scale + Clearance Candidates ─────── */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Not Safe to Scale */}
        <div className="bg-white rounded-xl border border-slate-200 p-5">
          <div className="flex items-center gap-2 mb-4">
            <AlertTriangle size={18} className="text-red-500" />
            <h2 className="text-base font-semibold text-slate-900">不宜放量推广</h2>
          </div>
          {!d.notSafeToScale || d.notSafeToScale.length === 0 ? (
            <p className="text-sm text-slate-400 py-8 text-center">所有产品库存均充足，可放量投放广告</p>
          ) : (
            <div className="space-y-3">
              {d.notSafeToScale.map((item) => (
                <div
                  key={item.sku}
                  className="flex items-start gap-3 p-3 rounded-lg border border-red-100 bg-red-50/50"
                >
                  <ShieldAlert size={16} className="text-red-500 mt-0.5 flex-shrink-0" />
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-slate-800">{item.name}</p>
                    <p className="text-xs text-slate-500 font-mono mt-0.5">{item.sku}</p>
                    <p className="text-xs text-red-600 mt-1">{item.reason}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Clearance Candidates */}
        <div className="bg-white rounded-xl border border-slate-200 p-5">
          <div className="flex items-center gap-2 mb-4">
            <Tag size={18} className="text-orange-500" />
            <h2 className="text-base font-semibold text-slate-900">清仓候选</h2>
          </div>
          {!d.clearanceCandidates || d.clearanceCandidates.length === 0 ? (
            <p className="text-sm text-slate-400 py-8 text-center">未识别到清仓候选产品</p>
          ) : (
            <div className="space-y-3">
              {d.clearanceCandidates.map((item) => (
                <div
                  key={item.sku}
                  className="flex items-start gap-3 p-3 rounded-lg border border-orange-100 bg-orange-50/50"
                >
                  <ShoppingCart size={16} className="text-orange-500 mt-0.5 flex-shrink-0" />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between">
                      <p className="text-sm font-medium text-slate-800">{item.name}</p>
                      <span className="text-xs font-medium text-orange-700">{formatCurrency(item.value)}</span>
                    </div>
                    <p className="text-xs text-slate-500 font-mono mt-0.5">{item.sku}</p>
                    <div className="flex items-center gap-3 mt-1">
                      <span className="text-xs text-slate-500">{formatNumber(item.inventory)} 件</span>
                      <span className="text-xs text-orange-600">{item.daysOfSupply} 天可供应</span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
