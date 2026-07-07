import { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Search,
  Package,
  Info,
  RefreshCw,
  CheckCircle2,
  AlertTriangle,
  Clock,
} from 'lucide-react';
import {
  fetchProductCampaigns,
  fetchProductAdSyncStatus,
  type ProductCampaign,
  type ProductAdSyncStatus,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { useStoreContext } from '../lib/StoreContext';
import { formatCurrency, formatNumber, formatPercent, formatDate, cn } from '../lib/utils';
import { FlowGuide } from '../components/onboarding/FlowGuide';

// ─── Label maps ──────────────────────────────────────────────────────
const statusLabelMap: Record<string, string> = {
  enabled: '投放中',
  active: '投放中',
  paused: '已暂停',
  archived: '已归档',
};

/**
 * Data-status badge (Req 2.3): every campaign row is tagged as preliminary
 * (初步, may be revised) or finalized (最终). When no performance row exists yet
 * the status is unknown (暂无数据).
 */
function DataStatusBadge({ dataStatus }: { dataStatus?: string | null }) {
  if (!dataStatus) {
    return (
      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border bg-slate-50 text-slate-400 border-slate-200">
        暂无数据
      </span>
    );
  }
  const finalized = dataStatus === 'finalized';
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border',
        finalized
          ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
          : 'bg-amber-50 text-amber-700 border-amber-200',
      )}
    >
      {finalized ? '最终数据' : '初步数据'}
    </span>
  );
}

function getStatusBadge(status?: string | null): string {
  const styles: Record<string, string> = {
    enabled: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    active: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    paused: 'bg-slate-100 text-slate-500 border-slate-200',
    archived: 'bg-slate-100 text-slate-400 border-slate-200',
  };
  return (status && styles[status]) || 'bg-slate-100 text-slate-500 border-slate-200';
}

// ─── T+1 / preliminary explanatory copy (Req 2.4) ────────────────────
function DataSourceNotice() {
  return (
    <div className="flex gap-3 rounded-xl border border-blue-100 bg-blue-50/60 px-4 py-3">
      <Info size={18} className="text-blue-500 flex-shrink-0 mt-0.5" />
      <div className="text-sm text-slate-600 leading-relaxed">
        <p className="font-medium text-slate-700">关于亚马逊广告数据</p>
        <p className="mt-1">
          亚马逊广告报表通常为<strong>隔天（T+1）</strong>生成，并非实时数据。最近几日的数据为
          <strong>初步值（preliminary）</strong>，会因归因延迟在后续被修订；只有结算后的数据才标记为
          <strong>最终值（finalized）</strong>。请据此判断数据的可靠程度。
        </p>
      </div>
    </div>
  );
}

// ─── Sync status section (Req 2.5, 2.6) ──────────────────────────────
function isFailedStatus(reportStatus?: string | null): boolean {
  if (!reportStatus) return false;
  const s = reportStatus.toLowerCase();
  return s === 'failed' || s === 'error' || s === 'failure';
}

function isSuccessStatus(reportStatus?: string | null): boolean {
  if (!reportStatus) return false;
  const s = reportStatus.toLowerCase();
  return s === 'completed' || s === 'success' || s === 'succeeded';
}

function SyncStatusCard({ row }: { row: ProductAdSyncStatus }) {
  const failed = isFailedStatus(row.reportStatus);
  const success = isSuccessStatus(row.reportStatus);
  return (
    <div
      className={cn(
        'rounded-xl border px-4 py-3',
        failed ? 'border-red-200 bg-red-50/60' : 'border-slate-200 bg-white',
      )}
    >
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-slate-700">{row.reportType}</span>
        <span
          className={cn(
            'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium',
            failed
              ? 'bg-red-100 text-red-700'
              : success
                ? 'bg-emerald-100 text-emerald-700'
                : 'bg-blue-100 text-blue-700',
          )}
        >
          {failed ? (
            <AlertTriangle size={12} />
          ) : success ? (
            <CheckCircle2 size={12} />
          ) : (
            <Clock size={12} />
          )}
          {row.reportStatus || '未知'}
        </span>
      </div>
      <div className="mt-2 text-xs text-slate-500">
        最近成功同步：
        <span className="text-slate-700 ml-1">
          {row.lastSuccessAt ? formatDate(row.lastSuccessAt) : '尚无成功记录'}
        </span>
      </div>
      {failed && row.lastError && (
        // Req 2.6: a failed sync is shown as failed with a readable reason —
        // never a blank or fabricated success state.
        <p className="mt-2 text-xs text-red-600 break-words">失败原因：{row.lastError}</p>
      )}
    </div>
  );
}

// ─── Main page ───────────────────────────────────────────────────────
export function ProductAdDataPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const { stores } = useStoreContext();

  const [asinInput, setAsinInput] = useState('');
  const [productIdInput, setProductIdInput] = useState('');
  const [activeQuery, setActiveQuery] = useState<{ parentAsin?: string; productId?: string } | null>(
    null,
  );

  const [campaigns, setCampaigns] = useState<ProductCampaign[]>([]);
  const [campaignsLoading, setCampaignsLoading] = useState(false);
  const [campaignsError, setCampaignsError] = useState<string | null>(null);

  const [syncStatus, setSyncStatus] = useState<ProductAdSyncStatus[]>([]);
  const [syncLoading, setSyncLoading] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  const storeName = useMemo(
    () => stores.find((s) => s.id === storeId)?.name ?? '-',
    [stores, storeId],
  );

  const loadSyncStatus = useCallback(async () => {
    if (!storeId) return;
    try {
      setSyncLoading(true);
      setSyncError(null);
      const result = await fetchProductAdSyncStatus(storeId);
      setSyncStatus(result ?? []);
    } catch (err: any) {
      setSyncError(err?.message || '加载同步状态失败');
    } finally {
      setSyncLoading(false);
    }
  }, [storeId]);

  useEffect(() => {
    if (storeId) loadSyncStatus();
  }, [storeId, loadSyncStatus]);

  // Reset the product-centric view whenever the active store changes.
  useEffect(() => {
    setActiveQuery(null);
    setCampaigns([]);
    setCampaignsError(null);
  }, [storeId]);

  const loadCampaigns = useCallback(
    async (query: { parentAsin?: string; productId?: string }) => {
      if (!storeId) return;
      try {
        setCampaignsLoading(true);
        setCampaignsError(null);
        const result = await fetchProductCampaigns({ storeId, ...query });
        setCampaigns(result ?? []);
      } catch (err: any) {
        setCampaignsError(err?.message || '加载产品广告数据失败');
      } finally {
        setCampaignsLoading(false);
      }
    },
    [storeId],
  );

  function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    const parentAsin = asinInput.trim() || undefined;
    const productId = productIdInput.trim() || undefined;
    if (!parentAsin && !productId) return;
    const query = { parentAsin, productId };
    setActiveQuery(query);
    loadCampaigns(query);
  }

  // ─── Aggregate totals for the looked-up product ────────────────────
  const totals = useMemo(() => {
    return campaigns.reduce(
      (acc, c) => {
        acc.spend += c.spend || 0;
        acc.clicks += c.clicks || 0;
        acc.orders += c.orders || 0;
        acc.sales += c.sales || 0;
        return acc;
      },
      { spend: 0, clicks: 0, orders: 0, sales: 0 },
    );
  }, [campaigns]);
  const totalAcos = totals.sales > 0 ? (totals.spend / totals.sales) * 100 : 0;

  if (storeLoading) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-48 bg-slate-200 rounded animate-pulse" />
        <div className="h-20 bg-white rounded-xl border border-slate-200 animate-pulse" />
        <div className="h-40 bg-white rounded-xl border border-slate-200 animate-pulse" />
      </div>
    );
  }

  if (storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Package size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出错了</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">{storeError}</p>
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

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-2xl font-bold text-slate-900">产品广告数据</h1>
        <p className="text-sm text-slate-500 mt-1">
          以产品为视角查看其关联的广告活动、表现指标与数据同步状态 · 当前店铺：{storeName}
        </p>
      </div>

      {/* Step-by-step guidance: report sync & product-ad data (Req 8.3, 8.4) */}
      <FlowGuide
        title="操作指引：报表同步与产品广告数据"
        intro="如何让广告数据进来、以及怎么看懂它"
        storageKey="product-ad-data"
        steps={[
          {
            title: '连接亚马逊店铺与广告账户',
            detail: '前往「连接与同步 → 平台连接」，用真实凭证绑定亚马逊店铺与广告账户，系统才能为你拉取广告报表。',
          },
          {
            title: '触发并等待报表同步',
            detail:
              '系统向亚马逊提交「生成报表」请求后需轮询等待（活动级、关键词级、搜索词级报表），完成后才会下载入库。可在上方「报表同步状态」查看最近成功时间与运行状态；失败会如实显示原因。',
          },
          {
            title: '以产品为视角查看广告数据',
            detail:
              '在下方输入产品的父 ASIN 或产品 ID，即可看到该产品关联的广告活动及其花费、点击、订单、销售额与 ACoS。',
          },
          {
            title: '辨别数据状态',
            detail:
              '每条数据带有「初步」或「最终」标签：初步数据可能因归因延迟被修订，结算后才为最终值。',
          },
        ]}
        note={
          <>
            <strong className="text-slate-600">关于亚马逊广告数据来源：</strong>
            亚马逊广告报表通常为<strong>隔天（T+1）</strong>生成，并非实时数据。最近几日的数据为
            <strong>初步值（preliminary）</strong>，会因归因延迟在后续被修订；只有结算后的数据才标记为
            <strong>最终值（finalized）</strong>。
          </>
        }
      />

      {/* T+1 / preliminary explanatory copy (Req 2.4) */}
      <DataSourceNotice />

      {/* Sync status (Req 2.5, 2.6) */}
      <div className="bg-white rounded-xl border border-slate-200 p-4">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-slate-700">报表同步状态</h2>
          <button
            onClick={loadSyncStatus}
            disabled={syncLoading}
            className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-500 hover:text-slate-700 disabled:opacity-60"
          >
            <RefreshCw size={13} className={cn(syncLoading && 'animate-spin')} />
            刷新
          </button>
        </div>
        {syncError ? (
          <p className="text-sm text-red-500">{syncError}</p>
        ) : syncLoading && syncStatus.length === 0 ? (
          <p className="text-sm text-slate-400">加载中...</p>
        ) : syncStatus.length === 0 ? (
          <p className="text-sm text-slate-400">暂无同步记录</p>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {syncStatus.map((row, i) => (
              <SyncStatusCard key={`${row.reportType}-${i}`} row={row} />
            ))}
          </div>
        )}
      </div>

      {/* Product lookup (product-first entry, Req 2.1) */}
      <form
        onSubmit={handleSearch}
        className="flex flex-col sm:flex-row items-stretch sm:items-end gap-3 bg-white rounded-xl border border-slate-200 px-4 py-3"
      >
        <div className="flex-1">
          <label className="block text-xs font-medium text-slate-500 mb-1">父 ASIN</label>
          <div className="flex items-center gap-2 h-10 rounded-lg border border-slate-200 bg-white px-3">
            <Package size={14} className="text-slate-400 flex-shrink-0" />
            <input
              type="text"
              placeholder="例如：B0XXXXXXXX"
              value={asinInput}
              onChange={(e) => setAsinInput(e.target.value)}
              className="flex-1 text-sm text-slate-700 placeholder-slate-400 outline-none bg-transparent"
            />
          </div>
        </div>
        <div className="flex-1">
          <label className="block text-xs font-medium text-slate-500 mb-1">产品 ID（可选）</label>
          <div className="flex items-center gap-2 h-10 rounded-lg border border-slate-200 bg-white px-3">
            <input
              type="text"
              placeholder="本地产品 ID"
              value={productIdInput}
              onChange={(e) => setProductIdInput(e.target.value)}
              className="flex-1 text-sm text-slate-700 placeholder-slate-400 outline-none bg-transparent"
            />
          </div>
        </div>
        <button
          type="submit"
          disabled={(!asinInput.trim() && !productIdInput.trim()) || campaignsLoading}
          className="inline-flex items-center justify-center gap-2 h-10 px-4 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-60"
        >
          <Search size={15} />
          查看广告数据
        </button>
      </form>

      {/* Results */}
      {activeQuery == null ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <Package size={44} className="text-slate-300 mb-3" />
          <p className="text-sm text-slate-500">输入产品的父 ASIN 或产品 ID，查看其关联的广告活动与表现</p>
        </div>
      ) : campaignsLoading ? (
        <div className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-12 bg-slate-100 rounded animate-pulse" />
          ))}
        </div>
      ) : campaignsError ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <AlertTriangle size={44} className="text-red-300 mb-3" />
          <p className="text-sm text-slate-600 max-w-md">{campaignsError}</p>
          <button
            onClick={() => activeQuery && loadCampaigns(activeQuery)}
            className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
          >
            重试
          </button>
        </div>
      ) : campaigns.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <Package size={44} className="text-slate-300 mb-3" />
          <p className="text-sm text-slate-500">该产品暂无关联的广告活动</p>
        </div>
      ) : (
        <div className="space-y-3">
          {/* Aggregated summary for the product */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <SummaryTile label="总花费" value={formatCurrency(totals.spend)} />
            <SummaryTile label="总点击" value={formatNumber(totals.clicks)} />
            <SummaryTile label="总订单" value={formatNumber(totals.orders)} />
            <SummaryTile label="ACoS" value={formatPercent(totalAcos)} />
          </div>

          {/* Associated campaigns + performance metrics (Req 2.1, 2.2, 2.3) */}
          <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
                  <th className="px-4 py-3">广告活动</th>
                  <th className="px-4 py-3">状态</th>
                  <th className="px-4 py-3">数据状态</th>
                  <th className="px-4 py-3 text-right">花费</th>
                  <th className="px-4 py-3 text-right">点击</th>
                  <th className="px-4 py-3 text-right">订单</th>
                  <th className="px-4 py-3 text-right">销售额</th>
                  <th className="px-4 py-3 text-right">ACoS</th>
                </tr>
              </thead>
              <tbody>
                {campaigns.map((c) => (
                  <tr key={c.campaignId} className="border-b border-slate-50 hover:bg-slate-50/60">
                    <td className="px-4 py-3">
                      <div className="font-medium text-slate-900">{c.campaignName || c.campaignId}</div>
                      {(c.parentAsin || c.productId) && (
                        <div className="text-xs text-slate-400 mt-0.5">
                          {c.parentAsin ? `ASIN ${c.parentAsin}` : ''}
                          {c.parentAsin && c.productId ? ' · ' : ''}
                          {c.productId ? `产品 ${c.productId}` : ''}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
                          getStatusBadge(c.status),
                        )}
                      >
                        {(c.status && statusLabelMap[c.status]) || c.status || '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <DataStatusBadge dataStatus={c.dataStatus} />
                    </td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(c.spend)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatNumber(c.clicks)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatNumber(c.orders)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(c.sales)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatPercent(c.acos)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

function SummaryTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 px-4 py-3">
      <p className="text-xs text-slate-500">{label}</p>
      <p className="text-lg font-semibold text-slate-900 mt-1">{value}</p>
    </div>
  );
}
