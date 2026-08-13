// 广告活动 (Campaigns) tab — extracted from CampaignsPage and recomposed on the
// reusable table building blocks. This view keeps campaign operations dense:
// high-signal advertising metrics stay in the primary table while secondary
// diagnostics remain available through the existing column manager.

import { lazy, Suspense, useCallback, useMemo, useState } from 'react';
import {
  AlertCircle, Bot, DollarSign, Eye, Loader2, Megaphone, MoreHorizontal,
  Pause, Play, Plus, RefreshCw, Trash2, TrendingDown, TrendingUp,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { notify } from '../../../lib/toast';

const CampaignTrendChart = lazy(() => import('./CampaignTrendChart'));

import {
  bulkCampaignOp, createCampaign, fetchAdPortfolios, fetchCampaigns,
  fetchCampaignTrend, fetchColumnConfig, saveColumnConfig, setCampaignState, updateCampaign,
  type AdPortfolio, type CampaignCreateInput, type CampaignTrendPoint,
  type CampaignVo,
} from '../../../lib/api';
import { useApiMutation, useApiQuery } from '../../../lib/hooks/useApiQuery';
import { qk } from '../../../lib/queryKeys';
import { useStoreContext } from '../../../lib/StoreContext';
import type { SortState } from '../../../lib/advertisingQueryState';
import { cn, formatCurrency, formatNumber, formatPercent } from '../../../lib/utils';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Skeleton } from '../../../components/ui/skeleton';
import { RecordModal, type RecordField } from '../../../components/ui/RecordModal';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '../../../components/ui/dropdown-menu';
import { Dialog, DialogContent, DialogTitle } from '../../../components/ui/dialog';
import { SharedDataTable } from '../../../components/table/SharedDataTable';
import type { BulkOperation } from '../../../components/table/BulkActionBar';
import type { SavedViewConfig } from '../../../components/table/useSavedViews';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../../../components/ui/alert-dialog';
import {
  emptyFilterState,
  type ColumnDef, type FilterState, type TypeSelectorDef,
} from '../../../components/table/types';

import {
  StatusBadge, acosColor, adTypeOptions, isRunning, smartFilterOptions,
  targetingTypeLabel,
} from './tabHelpers';
import { getStatusFilterOptions } from '../../../components/table/advertisingStatusFilters';

const CAMPAIGN_VIEW_FIELDS: RecordField[] = [
  { key: 'name', label: '广告活动' },
  { key: 'status', label: '状态' },
  { key: 'campaignType', label: '类型' },
  { key: 'budget', label: '日预算', format: (v) => (v != null ? `$${v}` : '—') },
  { key: 'spend', label: '花费', format: (v) => (v != null ? `$${v}` : '—') },
  { key: 'sales', label: '销售额', format: (v) => (v != null ? `$${v}` : '—') },
  { key: 'orders', label: '订单', format: (v) => (v != null ? String(v) : '—') },
  { key: 'clicks', label: '点击', format: (v) => (v != null ? String(v) : '—') },
  { key: 'ctr', label: 'CTR', format: (v) => (v != null ? `${Number(v).toFixed(2)}%` : '—') },
  { key: 'conversionRate', label: 'CVR', format: (v) => (v != null ? `${Number(v).toFixed(2)}%` : '—') },
  { key: 'avgCpc', label: 'CPC', format: (v) => (v != null ? `$${v}` : '—') },
  { key: 'acos', label: 'ACoS', format: (v) => (v != null ? `${Number(v).toFixed(2)}%` : '—') },
  { key: 'roas', label: 'ROAS', format: (v) => (v != null ? `${Number(v).toFixed(2)}x` : '—') },
];

const CAMPAIGN_BUDGET_FIELDS: RecordField[] = [
  { key: 'name', label: '广告活动', type: 'readonly' },
  { key: 'dailyBudget', label: '日预算 ($)', type: 'number' },
];

const BULK_OP_LABEL: Record<'enable' | 'pause' | 'delete', string> = {
  enable: '启用',
  pause: '暂停',
  delete: '删除',
};

const CAMPAIGNS_TABLE_KEY = 'campaigns.campaigns';

/**
 * Primary operator view stays intentionally narrow. Secondary performance and
 * hosting metadata can be enabled at any time through the existing column
 * manager and will then persist through the existing column-config endpoint.
 */
const DEFAULT_HIDDEN_COLUMNS = [
  'clicks',
  'ctr',
  'cvr',
  'cpc',
  'store',
  'hostingGoal',
  'targetAcos',
  'aiManaged',
  'targetingType',
];

function parseColumnConfig(
  config?: string | null,
): { order?: string[]; hidden?: string[] } | null {
  if (!config) return null;
  try {
    const parsed = JSON.parse(config);
    if (parsed && typeof parsed === 'object') {
      return parsed as { order?: string[]; hidden?: string[] };
    }
    return null;
  } catch {
    return null;
  }
}

function finiteMetric(value: unknown): number | undefined {
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

function metricCurrency(value: unknown): string {
  const n = finiteMetric(value);
  return n === undefined ? '—' : formatCurrency(n);
}

function metricPercent(value: unknown): string {
  const n = finiteMetric(value);
  return n === undefined ? '—' : formatPercent(n, 2);
}

function campaignAcos(campaign: CampaignVo): number | undefined {
  const direct = finiteMetric(campaign.acos);
  if (direct !== undefined) return direct;
  const spend = finiteMetric(campaign.spend) ?? 0;
  const sales = finiteMetric(campaign.sales) ?? 0;
  return sales > 0 ? (spend / sales) * 100 : undefined;
}

function campaignRoas(campaign: CampaignVo): number | undefined {
  const direct = finiteMetric(campaign.roas);
  if (direct !== undefined) return direct;
  const spend = finiteMetric(campaign.spend) ?? 0;
  const sales = finiteMetric(campaign.sales) ?? 0;
  return spend > 0 ? sales / spend : undefined;
}

function campaignCtr(campaign: CampaignVo): number | undefined {
  const clicks = finiteMetric(campaign.clicks) ?? 0;
  const impressions = finiteMetric(campaign.impressions) ?? 0;
  return impressions > 0 ? (clicks / impressions) * 100 : undefined;
}

function campaignCvr(campaign: CampaignVo): number | undefined {
  const direct = finiteMetric(campaign.conversionRate);
  if (direct !== undefined) return direct;
  const orders = finiteMetric(campaign.orders) ?? 0;
  const clicks = finiteMetric(campaign.clicks) ?? 0;
  return clicks > 0 ? (orders / clicks) * 100 : undefined;
}

function campaignCpc(campaign: CampaignVo): number | undefined {
  const direct = finiteMetric(campaign.avgCpc);
  if (direct !== undefined) return direct;
  const spend = finiteMetric(campaign.spend) ?? 0;
  const clicks = finiteMetric(campaign.clicks) ?? 0;
  return clicks > 0 ? spend / clicks : undefined;
}

interface TrendMetric {
  key: string;
  label: string;
  value: number;
  delta: number;
  format: (n: number) => string;
  invertDelta?: boolean;
}

function pctDelta(current: number, previous: number): number {
  if (previous === 0) return current === 0 ? 0 : 100;
  return ((current - previous) / previous) * 100;
}

function TrendPanel({
  trend, loading, error, onRetry,
}: {
  trend: CampaignTrendPoint[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
}) {
  const metrics = useMemo<TrendMetric[]>(() => {
    const sum = (arr: CampaignTrendPoint[], pick: (p: CampaignTrendPoint) => number) =>
      arr.reduce((s, p) => s + (pick(p) || 0), 0);

    const mid = Math.floor(trend.length / 2);
    const prev = trend.slice(0, mid);
    const curr = trend.slice(mid);

    const aggSpend = sum(curr, (p) => p.spend);
    const aggSales = sum(curr, (p) => p.sales);
    const aggOrders = sum(curr, (p) => p.orders);
    const aggClicks = sum(curr, (p) => p.clicks);
    const prevSpend = sum(prev, (p) => p.spend);
    const prevSales = sum(prev, (p) => p.sales);
    const prevOrders = sum(prev, (p) => p.orders);
    const prevClicks = sum(prev, (p) => p.clicks);

    const acos = aggSales > 0 ? (aggSpend / aggSales) * 100 : 0;
    const prevAcos = prevSales > 0 ? (prevSpend / prevSales) * 100 : 0;
    const cpc = aggClicks > 0 ? aggSpend / aggClicks : 0;
    const prevCpc = prevClicks > 0 ? prevSpend / prevClicks : 0;
    const cpo = aggOrders > 0 ? aggSpend / aggOrders : 0;
    const prevCpo = prevOrders > 0 ? prevSpend / prevOrders : 0;

    return [
      { key: 'spend', label: '花费', value: aggSpend, delta: pctDelta(aggSpend, prevSpend), format: formatCurrency },
      { key: 'sales', label: '销售额', value: aggSales, delta: pctDelta(aggSales, prevSales), format: formatCurrency },
      { key: 'orders', label: '订单数', value: aggOrders, delta: pctDelta(aggOrders, prevOrders), format: (n) => formatNumber(n) },
      { key: 'acos', label: 'ACoS', value: acos, delta: pctDelta(acos, prevAcos), format: formatPercent, invertDelta: true },
      { key: 'cpc', label: '点击成本', value: cpc, delta: pctDelta(cpc, prevCpc), format: formatCurrency, invertDelta: true },
      { key: 'cpo', label: '订单成本', value: cpo, delta: pctDelta(cpo, prevCpo), format: formatCurrency, invertDelta: true },
    ];
  }, [trend]);

  if (error) {
    return (
      <div className="flex items-center gap-3 px-4 py-3 bg-red-50 border border-red-200 rounded-lg">
        <AlertCircle size={16} className="text-red-500 shrink-0" />
        <p className="text-sm text-red-700">{error}</p>
        <button onClick={onRetry} className="ml-auto text-sm font-medium text-red-600 hover:text-red-800 underline">
          重试
        </button>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-4 space-y-4">
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
        {metrics.map((m) => {
          const positive = m.invertDelta ? m.delta < 0 : m.delta > 0;
          const isFlat = Math.abs(m.delta) < 0.01;
          return (
            <div key={m.key} className="rounded-lg border border-slate-100 bg-slate-50/60 px-3 py-2.5">
              <p className="text-xs text-slate-500">{m.label}</p>
              {loading ? (
                <Skeleton className="h-6 w-20 mt-1" />
              ) : (
                <p className="text-lg font-semibold text-slate-900 tabular-nums mt-0.5">{m.format(m.value)}</p>
              )}
              {!loading && (
                <div className={cn(
                  'flex items-center gap-0.5 text-[11px] font-medium mt-0.5',
                  isFlat ? 'text-slate-400' : positive ? 'text-emerald-600' : 'text-red-600',
                )}>
                  {!isFlat && (m.delta > 0 ? <TrendingUp size={11} /> : <TrendingDown size={11} />)}
                  {isFlat ? '持平' : `${m.delta > 0 ? '+' : ''}${m.delta.toFixed(1)}%`}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div className="h-64">
        {loading ? (
          <Skeleton className="h-full w-full" />
        ) : trend.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center text-slate-400">
            <TrendingUp size={28} className="mb-2" />
            <p className="text-sm">所选周期暂无趋势数据</p>
          </div>
        ) : (
          <Suspense fallback={<Skeleton className="h-full w-full" />}>
            <CampaignTrendChart trend={trend} />
          </Suspense>
        )}
      </div>
    </div>
  );
}

function CreateCampaignModal({
  storeId, portfolios, onClose, onCreated,
}: {
  storeId: string;
  portfolios: AdPortfolio[];
  onClose: () => void;
  onCreated: (created: CampaignVo) => void;
}) {
  const [name, setName] = useState('');
  const [campaignType, setCampaignType] = useState('SP');
  const [targetingType, setTargetingType] = useState('auto');
  const [portfolioId, setPortfolioId] = useState('none');
  const [budget, setBudget] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);
    setFormError(null);
    if (!name.trim()) {
      setFieldError('请输入广告活动名称');
      return;
    }
    if (budget && Number(budget) < 0) {
      setFieldError('预算不能为负数');
      return;
    }
    const payload: CampaignCreateInput = {
      storeId,
      name: name.trim(),
      campaignType,
      targetingType,
      portfolioId: portfolioId !== 'none' ? portfolioId : undefined,
      budget: budget ? Number(budget) : undefined,
      status: 'enabled',
    };
    try {
      setSubmitting(true);
      const created = await createCampaign(payload);
      onCreated(created);
    } catch (err: any) {
      setFormError(err?.message || '创建广告活动失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose(); }}>
      <DialogContent className="block gap-0 p-0 w-full sm:max-w-lg rounded-xl border-0 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <DialogTitle className="text-lg font-semibold text-slate-900">新建广告活动</DialogTitle>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">广告活动名称</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：核心关键词 - SP 手动"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">广告类型</label>
              <select
                value={campaignType}
                onChange={(e) => setCampaignType(e.target.value)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              >
                <option value="SP">SP</option>
                <option value="SB">SB</option>
                <option value="SD">SD</option>
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">投放类型</label>
              <select
                value={targetingType}
                onChange={(e) => setTargetingType(e.target.value)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              >
                <option value="auto">自动</option>
                <option value="manual">手动</option>
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">广告组合</label>
              <select
                value={portfolioId}
                onChange={(e) => setPortfolioId(e.target.value)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              >
                <option value="none">无</option>
                {portfolios.map((p) => (
                  <option key={p.id} value={p.id}>{p.name}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">每日预算</label>
              <input
                type="number"
                min="0"
                step="0.01"
                value={budget}
                onChange={(e) => setBudget(e.target.value)}
                placeholder="0.00"
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              />
            </div>
          </div>

          {fieldError && <p className="text-sm text-red-500">{fieldError}</p>}
          {formError && <p className="text-sm text-red-500">{formError}</p>}

          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-lg border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
            >
              {submitting ? '创建中...' : '创建'}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function CampaignsTab({ storeId }: { storeId: string | null }) {
  const { stores } = useStoreContext();
  const queryClient = useQueryClient();

  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [detailCampaign, setDetailCampaign] = useState<CampaignVo | null>(null);
  const [budgetCampaign, setBudgetCampaign] = useState<CampaignVo | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [pendingBulk, setPendingBulk] = useState<{ operation: 'enable' | 'pause' | 'delete'; ids: string[] } | null>(null);

  const [filterState, setFilterState] = useState<FilterState>(() => emptyFilterState());
  const [parentAsin, setParentAsin] = useState('');
  const [targetAcosMin, setTargetAcosMin] = useState('');
  const [targetAcosMax, setTargetAcosMax] = useState('');
  const [sort, setSort] = useState<SortState | null>(null);

  const ts = filterState.typeSelections;
  const smartFilter = ts.smartFilter ?? 'all';
  const adType = ts.adType ?? 'all';
  const statusFilter = ts.status ?? 'all';
  const portfolioFilter = ts.portfolio ?? 'all';
  const searchQuery = filterState.search;

  const storeName = useMemo(
    () => stores.find((s) => s.id === storeId)?.name ?? '—',
    [stores, storeId],
  );

  const queryParams = useMemo(
    () => ({
      adType: adType !== 'all' ? adType : undefined,
      status: statusFilter !== 'all' ? statusFilter : undefined,
      portfolioId: portfolioFilter !== 'all' ? portfolioFilter : undefined,
      parentAsin: parentAsin.trim() || undefined,
      targetAcosMin: targetAcosMin ? Number(targetAcosMin) : undefined,
      targetAcosMax: targetAcosMax ? Number(targetAcosMax) : undefined,
      smartFilter: smartFilter !== 'all' ? smartFilter : undefined,
      sortField: sort?.field,
      sortDir: sort?.direction,
      pageSize: 200,
    }),
    [adType, statusFilter, portfolioFilter, parentAsin, targetAcosMin, targetAcosMax, smartFilter, sort],
  );

  const campaignsQuery = useApiQuery<CampaignVo[]>(
    qk.campaigns(storeId ?? undefined, queryParams),
    () => fetchCampaigns({ storeId: storeId!, ...queryParams }).then((r) => r.items ?? []),
    { enabled: !!storeId },
  );
  const campaigns = campaignsQuery.data ?? [];
  const loading = !!storeId && campaignsQuery.isLoading;
  const error = campaignsQuery.isError ? campaignsQuery.error?.message ?? '加载广告活动失败' : null;

  const trendQuery = useApiQuery<CampaignTrendPoint[]>(
    qk.campaignTrend(storeId ?? undefined, { granularity: 'day' }),
    () => fetchCampaignTrend({ storeId: storeId!, granularity: 'day' }),
    { enabled: !!storeId },
  );
  const trend = trendQuery.data ?? [];
  const trendLoading = !!storeId && trendQuery.isLoading;
  const trendError = trendQuery.isError ? trendQuery.error?.message ?? '加载趋势数据失败' : null;

  const portfoliosQuery = useApiQuery<AdPortfolio[]>(
    qk.adPortfolios(storeId ?? undefined),
    () => fetchAdPortfolios(storeId!),
    { enabled: !!storeId },
  );
  const portfolios = portfoliosQuery.data ?? [];

  const columnConfigQuery = useApiQuery(
    qk.columnConfig(CAMPAIGNS_TABLE_KEY),
    () => fetchColumnConfig(CAMPAIGNS_TABLE_KEY),
    { enabled: true },
  );
  const savedColumnConfig = useMemo(
    () => parseColumnConfig(columnConfigQuery.data?.config),
    [columnConfigQuery.data],
  );
  const columnConfigLoaded = !columnConfigQuery.isLoading;

  const saveColumnConfigMutation = useApiMutation(
    (config: { order: string[]; hidden: string[] }) =>
      saveColumnConfig({
        tableKey: CAMPAIGNS_TABLE_KEY,
        config: JSON.stringify(config),
      }),
  );

  const handleColumnConfigChange = useCallback(
    (config: { order: string[]; hidden: string[] }) => {
      saveColumnConfigMutation.mutate(config);
    },
    [saveColumnConfigMutation],
  );

  const handleApplySavedView = useCallback(
    (config: SavedViewConfig) => {
      if (config.filters) setFilterState(config.filters);
      setSort(config.sort ? { field: config.sort.field, direction: config.sort.direction } : null);
    },
    [],
  );

  const loadCampaigns = useCallback(() => campaignsQuery.refetch(), [campaignsQuery]);
  const loadTrend = useCallback(() => trendQuery.refetch(), [trendQuery]);

  const invalidateCampaigns = useCallback(
    () => queryClient.invalidateQueries({ queryKey: ['campaigns', storeId ?? undefined] }),
    [queryClient, storeId],
  );

  function flashToast(t: { type: 'success' | 'error'; text: string }) {
    if (t.type === 'success') notify.success(t.text);
    else notify.error(t.text);
  }

  const filtered = useMemo(() => {
    if (!searchQuery) return campaigns;
    const q = searchQuery.toLowerCase();
    return campaigns.filter((c) => c.name.toLowerCase().includes(q));
  }, [campaigns, searchQuery]);

  const statusOptions = useMemo(() => getStatusFilterOptions('objectStatus'), []);

  const toggleMutation = useApiMutation(
    (c: CampaignVo) => setCampaignState(c.id, isRunning(c.status) ? 'pause' : 'enable'),
    {
      onSuccess: (_updated, c) => {
        invalidateCampaigns();
        flashToast({ type: 'success', text: `广告活动已${isRunning(c.status) ? '暂停' : '启用'}` });
      },
      onError: (err) => flashToast({ type: 'error', text: err?.message ?? '更新状态失败' }),
    },
  );
  const togglingId = toggleMutation.isPending ? toggleMutation.variables?.id ?? null : null;

  const { mutate: toggleCampaignState } = toggleMutation;
  const handleToggle = useCallback(
    (c: CampaignVo) => {
      toggleCampaignState(c);
    },
    [toggleCampaignState],
  );

  const rowId = useCallback((c: CampaignVo) => c.id, []);

  const bulkMutation = useApiMutation(
    (vars: { operation: 'enable' | 'pause' | 'delete'; ids: string[] }) =>
      bulkCampaignOp(vars.ids, vars.operation),
    {
      onSuccess: (results) => {
        const ok = results.filter((r) => r.success).length;
        const failed = results.length - ok;
        flashToast({
          type: failed === 0 ? 'success' : 'error',
          text: `批量操作完成：成功 ${ok} 个${failed > 0 ? `，失败 ${failed} 个` : ''}`,
        });
        setSelectedIds([]);
        invalidateCampaigns();
      },
      onError: (err) => flashToast({ type: 'error', text: err?.message ?? '批量操作失败' }),
    },
  );
  const bulkRunning = bulkMutation.isPending;

  const handleBulk = useCallback(
    (operation: 'enable' | 'pause' | 'delete', ids: string[]) => {
      if (ids.length === 0) return;
      setPendingBulk({ operation, ids });
    },
    [],
  );

  const confirmBulk = useCallback(() => {
    if (!pendingBulk) return;
    bulkMutation.mutate(pendingBulk);
    setPendingBulk(null);
  }, [pendingBulk, bulkMutation]);

  const updateBudgetMutation = useApiMutation(
    (vars: { id: string; dailyBudget?: number }) => updateCampaign(vars.id, { dailyBudget: vars.dailyBudget }),
    { onSuccess: invalidateCampaigns },
  );

  function resetFilters() {
    setFilterState(emptyFilterState());
    setParentAsin('');
    setTargetAcosMin('');
    setTargetAcosMax('');
  }

  const typeSelectors: TypeSelectorDef[] = useMemo(
    () => [
      {
        key: 'smartFilter',
        label: '智能筛选',
        allLabel: '智能筛选',
        options: smartFilterOptions.filter((o) => o.value !== 'all'),
      },
      {
        key: 'adType',
        label: '广告类型',
        allLabel: '全部类型',
        options: adTypeOptions.filter((o) => o.value !== 'all'),
      },
      {
        key: 'status',
        label: '状态',
        allLabel: '全部状态',
        options: statusOptions,
      },
      {
        key: 'portfolio',
        label: '广告组合',
        allLabel: '全部组合',
        options: portfolios.map((p) => ({ value: p.id, label: p.name })),
      },
    ],
    [statusOptions, portfolios],
  );

  const bulkOperations: BulkOperation<CampaignVo>[] = useMemo(
    () => [
      { id: 'enable', label: '批量启用', icon: <Play size={12} />, disabled: bulkRunning, handler: (ids) => handleBulk('enable', ids) },
      { id: 'pause', label: '批量暂停', icon: <Pause size={12} />, disabled: bulkRunning, handler: (ids) => handleBulk('pause', ids) },
      { id: 'delete', label: '批量删除', icon: <Trash2 size={12} />, variant: 'destructive', disabled: bulkRunning, handler: (ids) => handleBulk('delete', ids) },
    ],
    [bulkRunning, handleBulk],
  );

  const columns = useMemo<ColumnDef<CampaignVo>[]>(() => [
    {
      key: 'enabled',
      header: '启用',
      width: 64,
      render: (c) => {
        const running = isRunning(c.status);
        return (
          <button
            role="switch"
            aria-checked={running}
            aria-label={running ? '暂停' : '启用'}
            disabled={togglingId === c.id}
            onClick={() => handleToggle(c)}
            className={cn(
              'relative inline-flex h-5 w-9 items-center rounded-full transition-colors disabled:opacity-50',
              running ? 'bg-emerald-500' : 'bg-slate-300',
            )}
          >
            <span className={cn(
              'inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform',
              running ? 'translate-x-4' : 'translate-x-0.5',
            )} />
          </button>
        );
      },
    },
    {
      key: 'name',
      header: '广告活动',
      width: 260,
      sortable: true,
      sortField: 'name',
      render: (c) => (
        <div className="flex items-center gap-2">
          {c.campaignType && (
            <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold uppercase bg-slate-100 text-slate-600">
              {c.campaignType}
            </span>
          )}
          <span className="text-sm font-medium text-slate-900 truncate max-w-[220px]" title={c.name}>
            {c.name}
          </span>
        </div>
      ),
      exportValue: (c) => c.name,
    },
    { key: 'status', header: '状态', width: 96, render: (c) => <StatusBadge status={c.status} />, exportValue: (c) => c.status },
    {
      key: 'budget',
      header: '日预算',
      width: 104,
      render: (c) => <span className="tabular-nums text-slate-700">{metricCurrency(c.budget)}</span>,
      exportValue: (c) => c.budget,
    },
    {
      key: 'spend',
      header: 'Spend',
      width: 104,
      render: (c) => <span className="tabular-nums text-slate-700">{metricCurrency(c.spend)}</span>,
      exportValue: (c) => c.spend,
    },
    {
      key: 'sales',
      header: 'Sales',
      width: 104,
      render: (c) => <span className="tabular-nums font-medium text-slate-900">{metricCurrency(c.sales)}</span>,
      exportValue: (c) => c.sales,
    },
    {
      key: 'orders',
      header: 'Orders',
      width: 82,
      render: (c) => <span className="tabular-nums text-slate-700">{formatNumber(c.orders)}</span>,
      exportValue: (c) => c.orders,
    },
    {
      key: 'clicks',
      header: 'Clicks',
      width: 82,
      render: (c) => <span className="tabular-nums text-slate-700">{formatNumber(c.clicks)}</span>,
      exportValue: (c) => c.clicks,
    },
    {
      key: 'ctr',
      header: 'CTR',
      width: 78,
      render: (c) => <span className="tabular-nums text-slate-700">{metricPercent(campaignCtr(c))}</span>,
      exportValue: (c) => campaignCtr(c),
    },
    {
      key: 'cvr',
      header: 'CVR',
      width: 78,
      render: (c) => <span className="tabular-nums text-slate-700">{metricPercent(campaignCvr(c))}</span>,
      exportValue: (c) => campaignCvr(c),
    },
    {
      key: 'cpc',
      header: 'CPC',
      width: 88,
      render: (c) => <span className="tabular-nums text-slate-700">{metricCurrency(campaignCpc(c))}</span>,
      exportValue: (c) => campaignCpc(c),
    },
    {
      key: 'acos',
      header: 'ACoS',
      width: 86,
      sortable: true,
      sortField: 'acos',
      render: (c) => {
        const acos = campaignAcos(c);
        return (
          <span className={cn('font-medium tabular-nums', acos === undefined ? 'text-slate-400' : acosColor(acos))}>
            {metricPercent(acos)}
          </span>
        );
      },
      exportValue: (c) => campaignAcos(c),
    },
    {
      key: 'roas',
      header: 'ROAS',
      width: 80,
      render: (c) => {
        const roas = campaignRoas(c);
        return <span className="font-medium tabular-nums text-slate-900">{roas === undefined ? '—' : `${roas.toFixed(2)}x`}</span>;
      },
      exportValue: (c) => campaignRoas(c),
    },
    { key: 'store', header: '店铺', render: () => storeName, exportValue: () => storeName },
    { key: 'hostingGoal', header: '托管目标', render: (c) => (c.hostingEnabled ? c.hostingGoal || '—' : '—') },
    { key: 'targetAcos', header: '目标ACoS', render: (c) => (c.targetAcos != null ? formatPercent(c.targetAcos) : '—'), exportValue: (c) => c.targetAcos },
    {
      key: 'aiManaged',
      header: 'AI入格',
      render: (c) =>
        c.aiManaged ? (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-violet-100 text-violet-700">
            <Bot size={11} /> 已入格
          </span>
        ) : (
          <span className="text-xs text-slate-400">—</span>
        ),
      exportValue: (c) => c.aiManaged,
    },
    { key: 'targetingType', header: '投放类型', render: (c) => targetingTypeLabel(c.targetingType), exportValue: (c) => c.targetingType },
    {
      key: 'rowActions',
      header: '',
      width: 48,
      render: (c) => {
        const running = isRunning(c.status);
        return (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button className="flex items-center justify-center w-7 h-7 rounded-md hover:bg-slate-100 transition-colors">
                <MoreHorizontal size={14} className="text-slate-400" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-40">
              <DropdownMenuItem className="gap-2 text-xs" onClick={() => handleToggle(c)}>
                {running ? <Pause size={13} /> : <Play size={13} />}
                {running ? '暂停' : '启用'}
              </DropdownMenuItem>
              <DropdownMenuItem className="gap-2 text-xs" onClick={() => setDetailCampaign(c)}>
                <Eye size={13} /> 查看详情
              </DropdownMenuItem>
              <DropdownMenuItem className="gap-2 text-xs" onClick={() => setBudgetCampaign(c)}>
                <DollarSign size={13} /> 编辑预算
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        );
      },
    },
  ], [handleToggle, togglingId, storeName]);

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="text-sm text-slate-600">
            {loading ? '加载中...' : `${filtered.length} 个广告活动 · ${storeName}`}
          </p>
          <p className="mt-0.5 text-xs text-slate-400">默认展示预算、Spend、Sales、Orders、ACoS 与 ROAS；更多效率指标可在列管理中开启。</p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" className="gap-1.5" onClick={() => { loadCampaigns(); loadTrend(); }} disabled={loading}>
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            刷新
          </Button>
          <Button size="sm" className="gap-1.5" onClick={() => setShowCreate(true)} disabled={!storeId}>
            <Plus size={14} />
            新建广告活动
          </Button>
        </div>
      </div>

      <TrendPanel trend={trend} loading={trendLoading} error={trendError} onRetry={loadTrend} />

      <div className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
        <SharedDataTable<CampaignVo>
          key={columnConfigLoaded ? 'cols-loaded' : 'cols-loading'}
          tableKey={CAMPAIGNS_TABLE_KEY}
          rows={filtered}
          columns={columns}
          rowId={rowId}
          loading={loading}
          error={error}
          onRetry={loadCampaigns}
          sort={sort}
          onSortChange={setSort}
          bulkOperations={bulkOperations}
          selectedIds={selectedIds}
          onSelectionChange={setSelectedIds}
          enableSavedViews
          enableColumnManagement
          onApplySavedView={handleApplySavedView}
          initialHiddenColumns={savedColumnConfig?.hidden ?? DEFAULT_HIDDEN_COLUMNS}
          initialColumnOrder={savedColumnConfig?.order}
          onColumnConfigChange={handleColumnConfigChange}
          filterState={filterState}
          onFilterChange={setFilterState}
          typeSelectors={typeSelectors}
          searchPlaceholder="搜索广告活动名称..."
          emptyState={
            <div className="flex flex-col items-center gap-2 py-16 text-slate-400">
              <Megaphone size={24} />
              <p className="text-sm">
                {campaigns.length === 0 ? '该店铺暂无广告活动' : '没有匹配当前筛选条件的广告活动'}
              </p>
            </div>
          }
        />

        <div className="flex flex-wrap items-center gap-2">
          <Input
            placeholder="父 ASIN"
            value={parentAsin}
            onChange={(e) => setParentAsin(e.target.value)}
            className="h-8 w-[130px] text-xs"
          />
          <div className="flex items-center gap-1">
            <Input
              placeholder="目标ACOS 最小"
              type="number"
              value={targetAcosMin}
              onChange={(e) => setTargetAcosMin(e.target.value)}
              className="h-8 w-[120px] text-xs"
            />
            <span className="text-slate-400 text-xs">-</span>
            <Input
              placeholder="最大"
              type="number"
              value={targetAcosMax}
              onChange={(e) => setTargetAcosMax(e.target.value)}
              className="h-8 w-[90px] text-xs"
            />
          </div>
          <button onClick={resetFilters} className="text-xs text-slate-500 hover:text-slate-700 underline">
            重置
          </button>
          {bulkRunning && <Loader2 size={14} className="animate-spin text-blue-600 ml-auto" />}
        </div>
      </div>

      {showCreate && storeId && (
        <CreateCampaignModal
          storeId={storeId}
          portfolios={portfolios}
          onClose={() => setShowCreate(false)}
          onCreated={(created) => {
            setShowCreate(false);
            invalidateCampaigns();
            flashToast({ type: 'success', text: `广告活动「${created.name}」已创建` });
          }}
        />
      )}

      <RecordModal
        open={!!detailCampaign}
        mode="view"
        title="广告活动详情"
        fields={CAMPAIGN_VIEW_FIELDS}
        record={detailCampaign ? { ...detailCampaign, ctr: campaignCtr(detailCampaign) } : null}
        onClose={() => setDetailCampaign(null)}
      />
      <RecordModal
        open={!!budgetCampaign}
        mode="edit"
        title="编辑预算"
        fields={CAMPAIGN_BUDGET_FIELDS}
        record={budgetCampaign ? { ...budgetCampaign, dailyBudget: budgetCampaign.budget } : null}
        onClose={() => setBudgetCampaign(null)}
        saveLabel="保存预算"
        onSave={async (values) => {
          if (!budgetCampaign) return;
          const next = values.dailyBudget;
          await updateBudgetMutation.mutateAsync({
            id: budgetCampaign.id,
            dailyBudget: next !== '' && next != null ? Number(next) : undefined,
          });
          setBudgetCampaign(null);
          flashToast({ type: 'success', text: '预算已更新' });
        }}
      />

      <AlertDialog open={!!pendingBulk} onOpenChange={(open) => { if (!open) setPendingBulk(null); }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {pendingBulk?.operation === 'delete'
                ? '确认批量删除广告活动？'
                : `确认批量${pendingBulk ? BULK_OP_LABEL[pendingBulk.operation] : ''}广告活动？`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {pendingBulk?.operation === 'delete'
                ? `将删除选中的 ${pendingBulk?.ids.length ?? 0} 个广告活动，删除后不可恢复。`
                : `将对选中的 ${pendingBulk?.ids.length ?? 0} 个广告活动执行${pendingBulk ? BULK_OP_LABEL[pendingBulk.operation] : ''}操作。`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className={pendingBulk?.operation === 'delete' ? 'bg-red-600 hover:bg-red-700' : undefined}
              onClick={confirmBulk}
            >
              确认{pendingBulk ? BULK_OP_LABEL[pendingBulk.operation] : ''}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

export default CampaignsTab;
