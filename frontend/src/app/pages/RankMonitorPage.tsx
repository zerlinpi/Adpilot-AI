import { useState, useEffect, useMemo } from 'react';
import { Plus, LineChart, AlertTriangle, Store as StoreIcon, Package } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import {
  fetchRankMonitorTasks,
  fetchRankMonitorQuota,
  createRankMonitorTask,
  fetchStoreProductOptions,
  type RankMonitorTask,
  type RankMonitorQuota,
  type RankMonitorCreateInput,
  type StoreProductOption,
} from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreContext } from '../lib/StoreContext';
import { cn } from '../lib/utils';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// ─── Status labels ───────────────────────────────────────────────────
const statusLabelMap: Record<string, string> = {
  active: '监控中',
  paused: '已暂停',
};

function getStatusBadge(status: string): string {
  const styles: Record<string, string> = {
    active: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    paused: 'bg-slate-100 text-slate-500 border-slate-200',
  };
  return styles[status] || styles.active;
}

// ─── Loading skeleton ────────────────────────────────────────────────
function RankSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="h-8 w-40 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-36 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="h-12 bg-slate-100 rounded animate-pulse" />
        ))}
      </div>
    </div>
  );
}

// ─── Quota bar (per store) ───────────────────────────────────────────
function QuotaBar({ quota, storeName }: { quota: RankMonitorQuota; storeName?: string }) {
  const pct = quota.total > 0 ? Math.min(100, (quota.consumed / quota.total) * 100) : 0;
  return (
    <div className="bg-white rounded-xl border border-slate-200 px-5 py-4">
      <div className="flex items-center justify-between mb-2">
        <p className="text-sm font-medium text-slate-700">
          监控配额{storeName ? <span className="text-slate-400 font-normal"> · {storeName}</span> : null}
        </p>
        <p className="text-sm text-slate-500">
          已使用{' '}
          <span className={cn('font-semibold', quota.exhausted ? 'text-red-600' : 'text-slate-800')}>
            {quota.consumed}
          </span>{' '}
          / {quota.total} 个关键词
        </p>
      </div>
      <div className="h-2 w-full bg-slate-100 rounded-full overflow-hidden">
        <div
          className={cn('h-full rounded-full transition-all', quota.exhausted ? 'bg-red-500' : 'bg-blue-500')}
          style={{ width: `${pct}%` }}
        />
      </div>
      {quota.exhausted && (
        <p className="mt-2 text-xs text-red-600 flex items-center gap-1">
          <AlertTriangle size={13} />
          该店铺监控配额已用尽，无法添加新的监控任务。
        </p>
      )}
    </div>
  );
}

// ─── Create Monitor Task Modal ───────────────────────────────────────
function CreateTaskModal({
  stores,
  defaultStoreId,
  onClose,
  onCreated,
}: {
  stores: { id: string; name: string }[];
  defaultStoreId: string;
  onClose: () => void;
  onCreated: (created: RankMonitorTask) => void;
}) {
  const [storeId, setStoreId] = useState(defaultStoreId);
  const [productId, setProductId] = useState('');
  const [keywordText, setKeywordText] = useState('');
  const [products, setProducts] = useState<StoreProductOption[]>([]);
  const [productsLoading, setProductsLoading] = useState(false);
  const [quota, setQuota] = useState<RankMonitorQuota | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  // Load the chosen store's products + quota so products stay store-scoped and
  // the quota check reflects the store the task will be created in.
  useEffect(() => {
    if (!storeId) {
      setProducts([]);
      setQuota(null);
      return;
    }
    let cancelled = false;
    setProductsLoading(true);
    setProductId('');
    Promise.all([
      fetchStoreProductOptions(storeId).catch(() => [] as StoreProductOption[]),
      fetchRankMonitorQuota(storeId).catch(() => null),
    ])
      .then(([prods, q]) => {
        if (cancelled) return;
        setProducts(prods ?? []);
        setQuota(q ?? null);
      })
      .finally(() => {
        if (!cancelled) setProductsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [storeId]);

  const quotaExhausted = quota?.exhausted ?? false;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);
    setFormError(null);

    if (!storeId) {
      setFieldError('请选择店铺');
      return;
    }
    const keyword = keywordText.trim();
    if (!keyword) {
      setFieldError('请输入要监控的关键词');
      return;
    }
    if (keyword.length > 255) {
      setFieldError('关键词不能超过 255 个字符');
      return;
    }
    if (quotaExhausted) {
      setFieldError('该店铺监控配额已用尽');
      return;
    }

    const payload: RankMonitorCreateInput = {
      storeId,
      keywordText: keyword,
      productId: productId || null,
    };

    try {
      setSubmitting(true);
      const created = await createRankMonitorTask(payload);
      onCreated(created);
    } catch (err: any) {
      setFormError(err?.message || '添加监控任务失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose(); }}>
      <DialogContent className="block gap-0 w-full sm:max-w-lg rounded-xl border-0 bg-white p-0 shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <DialogTitle className="text-lg font-semibold text-slate-900">添加监控任务</DialogTitle>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4">
          {/* Store — tasks are store-scoped (Req 28.1) */}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">店铺</label>
            <select
              value={storeId}
              onChange={(e) => setStoreId(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 bg-white"
            >
              <option value="">请选择店铺</option>
              {stores.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>

          {/* Product — scoped to the chosen store (optional) */}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              商品 <span className="text-slate-400 font-normal">（可选）</span>
            </label>
            <select
              value={productId}
              onChange={(e) => setProductId(e.target.value)}
              disabled={!storeId || productsLoading}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 bg-white disabled:bg-slate-50 disabled:text-slate-400"
            >
              <option value="">{productsLoading ? '加载商品中…' : '不绑定商品'}</option>
              {products.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                  {p.asin ? ` (${p.asin})` : ''}
                </option>
              ))}
            </select>
          </div>

          {/* Keyword */}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">关键词</label>
            <input
              type="text"
              value={keywordText}
              onChange={(e) => setKeywordText(e.target.value)}
              placeholder="例如：wireless earbuds"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
          </div>

          {quota && quotaExhausted && (
            <p className="text-xs text-red-600 flex items-center gap-1">
              <AlertTriangle size={13} />
              该店铺监控配额已用尽（{quota.consumed}/{quota.total}）。
            </p>
          )}

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
              disabled={submitting || quotaExhausted}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {submitting ? '添加中...' : '添加'}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ─── Task rows table ─────────────────────────────────────────────────
function TaskTable({ tasks, showStore }: { tasks: RankMonitorTask[]; showStore: boolean }) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
            {showStore && <th className="px-4 py-3">店铺</th>}
            <th className="px-4 py-3">商品</th>
            <th className="px-4 py-3">关键词</th>
            <th className="px-4 py-3 text-right">自然排名</th>
            <th className="px-4 py-3 text-right">广告排名</th>
            <th className="px-4 py-3">状态</th>
            <th className="px-4 py-3">最近更新</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((t) => (
            <tr key={t.id} className="border-b border-slate-50 hover:bg-slate-50/60">
              {showStore && (
                <td className="px-4 py-3 text-slate-700">
                  {t.storeName || <span className="text-slate-400">-</span>}
                </td>
              )}
              <td className="px-4 py-3 text-slate-700">
                {t.productName || <span className="text-slate-400">未绑定</span>}
              </td>
              <td className="px-4 py-3 font-medium text-slate-900">{t.keywordText}</td>
              <td className="px-4 py-3 text-right text-slate-700">
                {t.organicRank != null ? `#${t.organicRank}` : <span className="text-slate-400">-</span>}
              </td>
              <td className="px-4 py-3 text-right text-slate-700">
                {t.adRank != null ? `#${t.adRank}` : <span className="text-slate-400">-</span>}
              </td>
              <td className="px-4 py-3">
                <span
                  className={cn(
                    'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
                    getStatusBadge(t.status),
                  )}
                >
                  {statusLabelMap[t.status] || t.status}
                </span>
              </td>
              <td className="px-4 py-3 text-slate-600">{t.lastCapturedAt || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const ALL_STORES = '__all__';

// ─── Main Rank Monitoring Page ───────────────────────────────────────
export function RankMonitorPage() {
  const { stores, storeId: activeStoreId, loading: storeLoading, error: storeError } = useStoreContext();
  const queryClient = useQueryClient();

  // Store filter: defaults to the active store, with an "all stores" option so
  // the user can review every store at once or segment to one store.
  const [filterStoreId, setFilterStoreId] = useState<string>('');
  const [filterProductId, setFilterProductId] = useState<string>('');

  const [products, setProducts] = useState<StoreProductOption[]>([]);
  const [showCreate, setShowCreate] = useState(false);

  const viewingAllStores = filterStoreId === ALL_STORES;

  const rankKey = ['rank-monitor', filterStoreId] as const;
  const rankQuery = useApiQuery<{ tasks: RankMonitorTask[]; quota: RankMonitorQuota | null }>(
    rankKey,
    () => {
      const storeParam = viewingAllStores ? undefined : filterStoreId;
      return Promise.all([
        fetchRankMonitorTasks(storeParam),
        // Quota is per store; only meaningful when a single store is selected.
        viewingAllStores ? Promise.resolve(null) : fetchRankMonitorQuota(filterStoreId),
      ]).then(([taskList, quotaInfo]) => ({ tasks: taskList ?? [], quota: quotaInfo ?? null }));
    },
    { enabled: !!filterStoreId },
  );
  const tasks = rankQuery.data?.tasks ?? [];
  const quota = rankQuery.data?.quota ?? null;
  const loading = !filterStoreId || rankQuery.isLoading;
  const error = rankQuery.isError ? rankQuery.error?.message ?? '加载排名监控数据失败' : null;
  const loadData = () => rankQuery.refetch();

  // Seed the filter with the active store once it resolves.
  useEffect(() => {
    if (activeStoreId && !filterStoreId) {
      setFilterStoreId(activeStoreId);
    }
  }, [activeStoreId]); // eslint-disable-line react-hooks/exhaustive-deps

  // Reset the product filter and load the selected store's products for the
  // product filter dropdown. The "all stores" view has no single product list.
  useEffect(() => {
    setFilterProductId('');
    if (!filterStoreId || viewingAllStores) {
      setProducts([]);
      return;
    }
    let cancelled = false;
    fetchStoreProductOptions(filterStoreId)
      .then((prods) => {
        if (!cancelled) setProducts(prods ?? []);
      })
      .catch(() => {
        if (!cancelled) setProducts([]);
      });
    return () => {
      cancelled = true;
    };
  }, [filterStoreId, viewingAllStores]);

  const storeNameById = useMemo(() => {
    const m = new Map<string, string>();
    stores.forEach((s) => m.set(s.id, s.name));
    return m;
  }, [stores]);

  // Client-side product filter (only applies in single-store view).
  const visibleTasks = useMemo(() => {
    if (viewingAllStores || !filterProductId) return tasks;
    return tasks.filter((t) => t.productId === filterProductId);
  }, [tasks, filterProductId, viewingAllStores]);

  // Group tasks by store for the "all stores" view so different stores are
  // managed distinctly (Req: 不同店铺可以区分管理).
  const groupedByStore = useMemo(() => {
    const groups = new Map<string, RankMonitorTask[]>();
    for (const t of visibleTasks) {
      const key = t.storeId || 'unknown';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(t);
    }
    return Array.from(groups.entries()).map(([sid, list]) => ({
      storeId: sid,
      storeName: list[0]?.storeName || storeNameById.get(sid) || sid,
      tasks: list,
    }));
  }, [visibleTasks, storeNameById]);

  // Quota-exhausted blocks the add control for the selected store (Req 28.4).
  const quotaExhausted = quota?.exhausted ?? false;
  const selectedStoreName = viewingAllStores ? undefined : storeNameById.get(filterStoreId);
  // In "all stores" view we cannot pre-check a single store's quota, so let the
  // modal (and backend) enforce it.
  const addDisabled = !filterStoreId || (!viewingAllStores && quotaExhausted);

  if (loading || storeLoading) return <RankSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <LineChart size={48} className="text-red-300 mb-4" />
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

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">排名监控</h1>
          <p className="text-sm text-slate-500 mt-1">按店铺与商品监控关键词的自然排名与广告排名</p>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          disabled={addDisabled}
          title={!viewingAllStores && quotaExhausted ? '监控配额已用尽' : undefined}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors shadow-sm disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <Plus size={16} />
          添加监控任务
        </button>
      </div>

      {/* Filters: store + product */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2">
          <StoreIcon size={16} className="text-slate-400" />
          <select
            value={filterStoreId}
            onChange={(e) => setFilterStoreId(e.target.value)}
            className="h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 bg-white"
          >
            <option value={ALL_STORES}>全部店铺</option>
            {stores.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </div>
        <div className="flex items-center gap-2">
          <Package size={16} className="text-slate-400" />
          <select
            value={filterProductId}
            onChange={(e) => setFilterProductId(e.target.value)}
            disabled={viewingAllStores}
            title={viewingAllStores ? '选择单个店铺后可按商品筛选' : undefined}
            className="h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 bg-white disabled:bg-slate-50 disabled:text-slate-400"
          >
            <option value="">全部商品</option>
            {products.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
                {p.asin ? ` (${p.asin})` : ''}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Quota (per store, single-store view only) */}
      {quota && !viewingAllStores && <QuotaBar quota={quota} storeName={selectedStoreName} />}

      {/* Tasks Table / Empty State */}
      {visibleTasks.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <LineChart size={48} className="text-slate-300 mb-4" />
          <p className="text-lg font-medium text-slate-500">暂无监控任务</p>
          <p className="text-sm text-slate-400 mt-1">点击「添加监控任务」开始监控关键词排名</p>
        </div>
      ) : viewingAllStores ? (
        // Grouped by store so different stores are managed distinctly.
        <div className="space-y-6">
          {groupedByStore.map((group) => (
            <div key={group.storeId} className="space-y-2">
              <div className="flex items-center gap-2">
                <StoreIcon size={15} className="text-slate-400" />
                <h2 className="text-sm font-semibold text-slate-700">{group.storeName}</h2>
                <span className="text-xs text-slate-400">（{group.tasks.length}）</span>
              </div>
              <TaskTable tasks={group.tasks} showStore={false} />
            </div>
          ))}
        </div>
      ) : (
        <TaskTable tasks={visibleTasks} showStore={false} />
      )}

      {showCreate && (
        <CreateTaskModal
          stores={stores}
          defaultStoreId={viewingAllStores ? activeStoreId ?? '' : filterStoreId}
          onClose={() => setShowCreate(false)}
          onCreated={(created) => {
            setShowCreate(false);
            // Reflect the new task in the current view when it matches the
            // selected store, and bump the per-store consumed quota.
            const matchesView = viewingAllStores || created.storeId === filterStoreId;
            const bumpQuota = !viewingAllStores && created.storeId === filterStoreId;
            queryClient.setQueryData<{ tasks: RankMonitorTask[]; quota: RankMonitorQuota | null }>(rankKey, (prev) => {
              if (!prev) return prev;
              let next = prev;
              if (matchesView) {
                next = { ...next, tasks: [created, ...next.tasks] };
              }
              if (bumpQuota && next.quota) {
                next = {
                  ...next,
                  quota: {
                    ...next.quota,
                    consumed: next.quota.consumed + 1,
                    remaining: Math.max(0, next.quota.remaining - 1),
                    exhausted: next.quota.consumed + 1 >= next.quota.total,
                  },
                };
              }
              return next;
            });
          }}
        />
      )}
    </div>
  );
}
