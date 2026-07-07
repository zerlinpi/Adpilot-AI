import { useState, useMemo } from 'react';
import { Plus, Crosshair } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import {
  fetchPlacementLocks,
  createPlacementLock,
  fetchPlacementLockTasks,
  fetchPlacementLockAms,
  fetchCampaigns,
  type PlacementLockStrategy,
  type PlacementLockCreateInput,
  type PlacementLockTask,
  type PlacementLockAms,
  type CampaignVo,
} from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { formatCurrency, cn } from '../lib/utils';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// ─── Placement + status label maps ───────────────────────────────────
/**
 * Selectable placement strategy types (SparkX-style卡位). The first three are the
 * core SparkX targets; {@code custom} lets the operator enter any other placement
 * key. The chosen value (or the typed custom key) is stored in the strategy's
 * {@code targetPlacement} column (no schema change).
 */
const CUSTOM_PLACEMENT = 'custom';

const placementOptions: { value: string; label: string }[] = [
  { value: 'top_of_search_1_1', label: '首页首位 (搜索结果第一位)' },
  { value: 'rest_of_search', label: '首页其余 (搜索结果其余位置)' },
  { value: 'product_pages', label: '商品页面 (商品详情页)' },
  { value: 'page1_5_8', label: '第1页5-8位' },
  { value: CUSTOM_PLACEMENT, label: '自定义广告位' },
];

// Display labels for known placement keys (covers legacy keys too); a typed
// custom key has no entry and falls back to showing the raw value.
const placementLabelMap: Record<string, string> = placementOptions.reduce(
  (acc, o) => (o.value === CUSTOM_PLACEMENT ? acc : { ...acc, [o.value]: o.label }),
  {} as Record<string, string>,
);

function placementLabel(value?: string | null): string {
  if (!value) return '-';
  return placementLabelMap[value] || value;
}

const statusLabelMap: Record<string, string> = {
  active: '生效中',
  paused: '已暂停',
};

function getStatusBadge(status?: string | null): string {
  const styles: Record<string, string> = {
    active: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    paused: 'bg-slate-100 text-slate-500 border-slate-200',
  };
  return styles[status || 'active'] || styles.active;
}

type TabKey = 'strategies' | 'tasks' | 'ams';

const tabs: { key: TabKey; label: string }[] = [
  { key: 'strategies', label: '策略管理' },
  { key: 'tasks', label: '任务管理' },
  { key: 'ams', label: 'AMS实时数据' },
];

// ─── Loading skeleton ────────────────────────────────────────────────
function PageSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="h-8 w-40 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-28 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="h-12 bg-slate-100 rounded animate-pulse" />
        ))}
      </div>
    </div>
  );
}

// ─── Add Strategy Modal ──────────────────────────────────────────────
function AddStrategyModal({
  storeId,
  campaigns,
  onClose,
  onCreated,
}: {
  storeId: string;
  campaigns: CampaignVo[];
  onClose: () => void;
  onCreated: (created: PlacementLockStrategy) => void;
}) {
  const [campaignId, setCampaignId] = useState(campaigns[0]?.id ?? '');
  const [targetPlacement, setTargetPlacement] = useState(placementOptions[0].value);
  const [customPlacement, setCustomPlacement] = useState('');
  const [bidMin, setBidMin] = useState('');
  const [bidMax, setBidMax] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const isCustom = targetPlacement === CUSTOM_PLACEMENT;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);
    setFormError(null);

    if (!campaignId) {
      setFieldError('请选择广告活动');
      return;
    }
    // Resolve the effective placement key: a custom strategy stores the typed key.
    const effectivePlacement = isCustom ? customPlacement.trim() : targetPlacement;
    if (isCustom) {
      if (!effectivePlacement) {
        setFieldError('请输入自定义广告位标识');
        return;
      }
      if (effectivePlacement.length > 40) {
        setFieldError('自定义广告位标识不能超过 40 个字符');
        return;
      }
    }
    const min = Number(bidMin);
    const max = Number(bidMax);
    if (!bidMin.trim() || Number.isNaN(min) || min < 0) {
      setFieldError('请输入有效的最低竞价');
      return;
    }
    if (!bidMax.trim() || Number.isNaN(max) || max < 0) {
      setFieldError('请输入有效的最高竞价');
      return;
    }
    // Req 26.4: invalid bid range (min exceeds max) is rejected client-side.
    if (min > max) {
      setFieldError('最低竞价不能大于最高竞价');
      return;
    }

    const payload: PlacementLockCreateInput = {
      storeId,
      campaignId,
      targetPlacement: effectivePlacement,
      bidMin: min,
      bidMax: max,
    };

    try {
      setSubmitting(true);
      const created = await createPlacementLock(payload);
      onCreated(created);
    } catch (err: any) {
      setFormError(err?.message || '创建卡位策略失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose(); }}>
      <DialogContent className="block gap-0 w-full sm:max-w-lg rounded-xl border-0 bg-white p-0 shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <DialogTitle className="text-lg font-semibold text-slate-900">添加策略</DialogTitle>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">广告活动</label>
            {campaigns.length === 0 ? (
              <p className="text-sm text-slate-400">当前店铺暂无广告活动，请先创建广告活动</p>
            ) : (
              <select
                value={campaignId}
                onChange={(e) => setCampaignId(e.target.value)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              >
                {campaigns.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            )}
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">目标广告位</label>
            <select
              value={targetPlacement}
              onChange={(e) => setTargetPlacement(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            >
              {placementOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>

          {isCustom && (
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">自定义广告位标识</label>
              <input
                type="text"
                value={customPlacement}
                onChange={(e) => setCustomPlacement(e.target.value)}
                maxLength={40}
                placeholder="例如：detail_page_top"
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              />
              <p className="mt-1 text-xs text-slate-400">输入任意广告位标识（最多 40 个字符），用于自定义卡位目标。</p>
            </div>
          )}

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">最低竞价</label>
              <input
                type="number"
                min="0"
                step="0.01"
                value={bidMin}
                onChange={(e) => setBidMin(e.target.value)}
                placeholder="0.00"
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">最高竞价</label>
              <input
                type="number"
                min="0"
                step="0.01"
                value={bidMax}
                onChange={(e) => setBidMax(e.target.value)}
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
              disabled={submitting || campaigns.length === 0}
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

// ─── Tab tables ──────────────────────────────────────────────────────
function EmptyState({ text }: { text: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <Crosshair size={48} className="text-slate-300 mb-4" />
      <p className="text-lg font-medium text-slate-500">{text}</p>
    </div>
  );
}

function StrategiesTable({ rows }: { rows: PlacementLockStrategy[] }) {
  if (rows.length === 0) return <EmptyState text="暂无卡位策略，点击「添加策略」开始" />;
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
            <th className="px-4 py-3">广告活动</th>
            <th className="px-4 py-3">目标广告位</th>
            <th className="px-4 py-3 text-right">最低竞价</th>
            <th className="px-4 py-3 text-right">最高竞价</th>
            <th className="px-4 py-3">状态</th>
            <th className="px-4 py-3">创建时间</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((s) => (
            <tr key={s.id} className="border-b border-slate-50 hover:bg-slate-50/60">
              <td className="px-4 py-3 font-medium text-slate-900">{s.campaignName || s.campaignId}</td>
              <td className="px-4 py-3 text-slate-600">{placementLabel(s.targetPlacement)}</td>
              <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(s.bidMin)}</td>
              <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(s.bidMax)}</td>
              <td className="px-4 py-3">
                <span
                  className={cn(
                    'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
                    getStatusBadge(s.status),
                  )}
                >
                  {statusLabelMap[s.status] || s.status}
                </span>
              </td>
              <td className="px-4 py-3 text-slate-500">{s.createdAt || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TasksTable({ rows }: { rows: PlacementLockTask[] }) {
  if (rows.length === 0) return <EmptyState text="暂无卡位任务" />;
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
            <th className="px-4 py-3">广告活动</th>
            <th className="px-4 py-3">目标广告位</th>
            <th className="px-4 py-3">关键词</th>
            <th className="px-4 py-3 text-right">最近竞价</th>
            <th className="px-4 py-3">最近执行</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t) => (
            <tr key={t.id} className="border-b border-slate-50 hover:bg-slate-50/60">
              <td className="px-4 py-3 font-medium text-slate-900">{t.campaignName || '-'}</td>
              <td className="px-4 py-3 text-slate-600">{placementLabel(t.targetPlacement)}</td>
              <td className="px-4 py-3 text-slate-600">{t.keywordText || '-'}</td>
              <td className="px-4 py-3 text-right text-slate-700">
                {t.lastBid != null ? formatCurrency(t.lastBid) : '-'}
              </td>
              <td className="px-4 py-3 text-slate-500">{t.lastRunAt || '尚未执行'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AmsTable({ rows }: { rows: PlacementLockAms[] }) {
  if (rows.length === 0) return <EmptyState text="暂无 AMS 实时数据" />;
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
            <th className="px-4 py-3">广告活动</th>
            <th className="px-4 py-3">目标广告位</th>
            <th className="px-4 py-3">关键词</th>
            <th className="px-4 py-3 text-right">当前竞价</th>
            <th className="px-4 py-3 text-right">竞价区间</th>
            <th className="px-4 py-3">状态</th>
            <th className="px-4 py-3">观测时间</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((a, i) => (
            <tr key={`${a.strategyId}-${i}`} className="border-b border-slate-50 hover:bg-slate-50/60">
              <td className="px-4 py-3 font-medium text-slate-900">{a.campaignName || '-'}</td>
              <td className="px-4 py-3 text-slate-600">{placementLabel(a.targetPlacement)}</td>
              <td className="px-4 py-3 text-slate-600">{a.keywordText || '-'}</td>
              <td className="px-4 py-3 text-right text-slate-700">
                {a.currentBid != null ? formatCurrency(a.currentBid) : '-'}
              </td>
              <td className="px-4 py-3 text-right text-slate-500">
                {a.bidMin != null && a.bidMax != null
                  ? `${formatCurrency(a.bidMin)} ~ ${formatCurrency(a.bidMax)}`
                  : '-'}
              </td>
              <td className="px-4 py-3">
                <span
                  className={cn(
                    'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
                    getStatusBadge(a.status),
                  )}
                >
                  {statusLabelMap[a.status || 'active'] || a.status}
                </span>
              </td>
              <td className="px-4 py-3 text-slate-500">{a.observedAt || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ─── Main Placement Lock Page ────────────────────────────────────────
export function PlacementLockPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const queryClient = useQueryClient();
  const [activeTab, setActiveTab] = useState<TabKey>('strategies');
  const [showCreate, setShowCreate] = useState(false);

  const placementKey = ['placement-lock', storeId] as const;
  const placementQuery = useApiQuery<{ strategies: PlacementLockStrategy[]; tasks: PlacementLockTask[]; ams: PlacementLockAms[]; campaigns: CampaignVo[] }>(
    placementKey,
    async () => {
      const [s, t, a, c] = await Promise.all([
        fetchPlacementLocks(storeId!),
        fetchPlacementLockTasks(storeId!),
        fetchPlacementLockAms(storeId!),
        fetchCampaigns({ storeId: storeId!, pageSize: 200 }),
      ]);
      return {
        strategies: s ?? [],
        tasks: t ?? [],
        ams: a ?? [],
        campaigns: c?.items ?? [],
      };
    },
    { enabled: !!storeId },
  );
  const strategies = placementQuery.data?.strategies ?? [];
  const tasks = placementQuery.data?.tasks ?? [];
  const ams = placementQuery.data?.ams ?? [];
  const campaigns = placementQuery.data?.campaigns ?? [];
  const loading = !!storeId && placementQuery.isLoading;
  const error = placementQuery.isError ? placementQuery.error?.message ?? '加载广告位锁定数据失败' : null;
  const loadAll = () => placementQuery.refetch();

  const activeBody = useMemo(() => {
    switch (activeTab) {
      case 'strategies':
        return <StrategiesTable rows={strategies} />;
      case 'tasks':
        return <TasksTable rows={tasks} />;
      case 'ams':
        return <AmsTable rows={ams} />;
      default:
        return null;
    }
  }, [activeTab, strategies, tasks, ams]);

  if (loading || storeLoading) return <PageSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Crosshair size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出错了</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">{storeError || error}</p>
        <button
          onClick={loadAll}
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
        <Crosshair size={48} className="text-slate-300 mb-4" />
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
          <h1 className="text-2xl font-bold text-slate-900">广告位锁定</h1>
          <p className="text-sm text-slate-500 mt-1">定义卡位策略，将广告稳定保持在目标广告位</p>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          disabled={!storeId}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors shadow-sm disabled:opacity-60"
        >
          <Plus size={16} />
          添加策略
        </button>
      </div>

      {/* Tabs */}
      <div className="flex items-center gap-1 border-b border-slate-200">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              'px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors',
              activeTab === tab.key
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-slate-500 hover:text-slate-700',
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeBody}

      {showCreate && storeId && (
        <AddStrategyModal
          storeId={storeId}
          campaigns={campaigns}
          onClose={() => setShowCreate(false)}
          onCreated={(created) => {
            setShowCreate(false);
            queryClient.setQueryData<{ strategies: PlacementLockStrategy[]; tasks: PlacementLockTask[]; ams: PlacementLockAms[]; campaigns: CampaignVo[] }>(placementKey, (prev) =>
              prev ? { ...prev, strategies: [created, ...prev.strategies] } : prev);
            loadAll();
          }}
        />
      )}
    </div>
  );
}
