import { useState, useEffect, useMemo } from 'react';
import { Plus, Search, Layers } from 'lucide-react';
import {
  fetchAdPortfolios,
  createAdPortfolio,
  type AdPortfolio,
  type AdPortfolioCreateInput,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { useStoreContext } from '../lib/StoreContext';
import { formatCurrency, formatNumber, formatPercent, cn } from '../lib/utils';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// ─── Label maps ──────────────────────────────────────────────────────
const stateLabelMap: Record<string, string> = {
  enabled: '投放中',
  paused: '已暂停',
};

const budgetTypeLabelMap: Record<string, string> = {
  none: '无预算上限',
  recurring: '循环预算',
  date_range: '日期范围预算',
};

/** Render the budget type label; {@code none} renders 无预算上限 (Req 20.3). */
export function budgetTypeLabel(budgetType: string): string {
  return budgetTypeLabelMap[budgetType] || budgetType;
}

function getStateBadge(state: string): string {
  const styles: Record<string, string> = {
    enabled: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    paused: 'bg-slate-100 text-slate-500 border-slate-200',
  };
  return styles[state] || styles.enabled;
}

// ─── Loading skeleton ────────────────────────────────────────────────
function PortfoliosSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="h-8 w-40 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-36 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="h-14 bg-white rounded-xl border border-slate-200 animate-pulse" />
      <div className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="h-12 bg-slate-100 rounded animate-pulse" />
        ))}
      </div>
    </div>
  );
}

// ─── Create Portfolio Modal ──────────────────────────────────────────
function CreatePortfolioModal({
  storeId,
  onClose,
  onCreated,
}: {
  storeId: string;
  onClose: () => void;
  onCreated: (created: AdPortfolio) => void;
}) {
  const [name, setName] = useState('');
  const [state, setState] = useState('enabled');
  const [budgetType, setBudgetType] = useState('none');
  const [budget, setBudget] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  const requiresBudget = budgetType !== 'none';

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);
    setFormError(null);

    if (!name.trim()) {
      setFieldError('请输入广告组合名称');
      return;
    }
    if (requiresBudget && (!budget.trim() || Number(budget) <= 0)) {
      setFieldError('该预算类型需要填写大于 0 的预算');
      return;
    }

    const payload: AdPortfolioCreateInput = {
      storeId,
      name: name.trim(),
      state,
      budgetType,
      budget: requiresBudget ? Number(budget) : null,
      startDate: startDate || undefined,
      endDate: endDate || undefined,
    };

    try {
      setSubmitting(true);
      const created = await createAdPortfolio(payload);
      onCreated(created);
    } catch (err: any) {
      setFormError(err?.message || '创建广告组合失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose(); }}>
      <DialogContent className="block gap-0 p-0 w-full sm:max-w-lg rounded-xl border-0 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <DialogTitle className="text-lg font-semibold text-slate-900">新建广告组合</DialogTitle>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">广告组合名称</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：核心品类组合"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">投放状态</label>
              <select
                value={state}
                onChange={(e) => setState(e.target.value)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              >
                <option value="enabled">投放中</option>
                <option value="paused">已暂停</option>
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">预算类型</label>
              <select
                value={budgetType}
                onChange={(e) => setBudgetType(e.target.value)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              >
                <option value="none">无预算上限</option>
                <option value="recurring">循环预算</option>
                <option value="date_range">日期范围预算</option>
              </select>
            </div>
          </div>

          {requiresBudget && (
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">预算</label>
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
          )}

          {budgetType === 'date_range' && (
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">开始日期</label>
                <input
                  type="date"
                  value={startDate}
                  onChange={(e) => setStartDate(e.target.value)}
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">结束日期</label>
                <input
                  type="date"
                  value={endDate}
                  onChange={(e) => setEndDate(e.target.value)}
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
                />
              </div>
            </div>
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

// ─── Main Ad Portfolios Page ─────────────────────────────────────────
export function AdPortfoliosPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const { stores } = useStoreContext();
  const [portfolios, setPortfolios] = useState<AdPortfolio[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [showCreate, setShowCreate] = useState(false);

  const storeName = useMemo(
    () => stores.find((s) => s.id === storeId)?.name ?? '-',
    [stores, storeId],
  );

  useEffect(() => {
    if (storeId) loadData();
    else if (!storeLoading) setLoading(false);
  }, [storeId, storeLoading]);

  async function loadData() {
    if (!storeId) return;
    try {
      setLoading(true);
      setError(null);
      const result = await fetchAdPortfolios(storeId);
      setPortfolios(result ?? []);
    } catch (err: any) {
      setError(err?.message || '加载广告组合失败');
    } finally {
      setLoading(false);
    }
  }

  const filtered = useMemo(() => {
    if (!searchQuery) return portfolios;
    const q = searchQuery.toLowerCase();
    return portfolios.filter((p) => p.name.toLowerCase().includes(q));
  }, [portfolios, searchQuery]);

  if (loading || storeLoading) return <PortfoliosSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Layers size={48} className="text-red-300 mb-4" />
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
        <Layers size={48} className="text-slate-300 mb-4" />
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
          <h1 className="text-2xl font-bold text-slate-900">全部广告组合</h1>
          <p className="text-sm text-slate-500 mt-1">将广告活动组织为广告组合，统一管理预算与表现</p>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          disabled={!storeId}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors shadow-sm disabled:opacity-60"
        >
          <Plus size={16} />
          新建广告组合
        </button>
      </div>

      {/* Search Bar */}
      <div className="flex items-center gap-3 bg-white rounded-xl border border-slate-200 px-4 py-3">
        <div className="flex-1 flex items-center gap-2 h-9 rounded-lg border border-slate-200 bg-white px-3">
          <Search size={14} className="text-slate-400 flex-shrink-0" />
          <input
            type="text"
            placeholder="搜索广告组合..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="flex-1 text-sm text-slate-700 placeholder-slate-400 outline-none bg-transparent"
          />
        </div>
      </div>

      {/* Portfolios Table / Empty State */}
      {filtered.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <Layers size={48} className="text-slate-300 mb-4" />
          <p className="text-lg font-medium text-slate-500">暂无广告组合</p>
          <p className="text-sm text-slate-400 mt-1">点击「新建广告组合」开始组织您的广告活动</p>
        </div>
      ) : (
        <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
                <th className="px-4 py-3">广告组合</th>
                <th className="px-4 py-3">投放状态</th>
                <th className="px-4 py-3">店铺</th>
                <th className="px-4 py-3">开始/结束</th>
                <th className="px-4 py-3">预算类型</th>
                <th className="px-4 py-3 text-right">预算</th>
                <th className="px-4 py-3 text-right">广告活动数量</th>
                <th className="px-4 py-3 text-right">曝光量</th>
                <th className="px-4 py-3 text-right">点击量</th>
                <th className="px-4 py-3 text-right">点击率</th>
                <th className="px-4 py-3 text-right">花费</th>
                <th className="px-4 py-3 text-right">点击成本</th>
                <th className="px-4 py-3 text-right">订单数</th>
                <th className="px-4 py-3 text-right">销售额</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((p) => (
                <tr key={p.id} className="border-b border-slate-50 hover:bg-slate-50/60">
                  <td className="px-4 py-3 font-medium text-slate-900">{p.name}</td>
                  <td className="px-4 py-3">
                    <span
                      className={cn(
                        'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
                        getStateBadge(p.state),
                      )}
                    >
                      {stateLabelMap[p.state] || p.state}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-slate-600">{storeName}</td>
                  <td className="px-4 py-3 text-slate-600">
                    {p.startDate || p.endDate ? `${p.startDate || '-'} ~ ${p.endDate || '-'}` : '-'}
                  </td>
                  <td className="px-4 py-3 text-slate-600">{budgetTypeLabel(p.budgetType)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">
                    {p.budgetType === 'none' || p.budget == null ? '无预算上限' : formatCurrency(p.budget)}
                  </td>
                  <td className="px-4 py-3 text-right text-slate-700">{p.campaignCount}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatNumber(p.impressions)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatNumber(p.clicks)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatPercent(p.ctr)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(p.spend)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(p.cpc)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{p.orders}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatCurrency(p.sales)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showCreate && storeId && (
        <CreatePortfolioModal
          storeId={storeId}
          onClose={() => setShowCreate(false)}
          onCreated={(created) => {
            setShowCreate(false);
            // Reflect the new portfolio without a full reload (optimistic prepend),
            // then refresh to pick up server-side rollup metrics.
            setPortfolios((prev) => [created, ...prev]);
            loadData();
          }}
        />
      )}
    </div>
  );
}
