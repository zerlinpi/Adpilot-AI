import { useState, useMemo } from 'react';
import { Link } from 'react-router';
import {
  Search,
  Pencil,
  Pause,
  Play,
  Wheat,
  AlertCircle,
  RefreshCw,
  Loader2,
} from 'lucide-react';

import { fetchKeywords, updateKeyword } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import type { Keyword, MatchType, EntityStatus } from '../types';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';
import {
  cn,
  formatCurrency,
  formatNumber,
  formatPercent,
} from '../lib/utils';
import { Skeleton } from '../components/ui/skeleton';

// ─── Match type badge colors ───────────────────────────────────────────
const matchTypeStyles: Record<MatchType, string> = {
  exact: 'bg-emerald-100 text-emerald-700',
  phrase: 'bg-blue-100 text-blue-700',
  broad: 'bg-orange-100 text-orange-700',
};

// ─── Status badge ──────────────────────────────────────────────────────
const statusStyles: Record<EntityStatus, string> = {
  active: 'bg-emerald-100 text-emerald-700',
  paused: 'bg-slate-100 text-slate-500',
  archived: 'bg-red-100 text-red-600',
};

// ─── Bid health color ──────────────────────────────────────────────────
function getBidHealthColor(score: number): string {
  if (score >= 75) return 'bg-emerald-500';
  if (score >= 50) return 'bg-yellow-500';
  return 'bg-red-500';
}

function getBidHealthBg(score: number): string {
  if (score >= 75) return 'bg-emerald-100';
  if (score >= 50) return 'bg-yellow-100';
  return 'bg-red-100';
}

// ─── ACoS color helper ────────────────────────────────────────────────
function getAcosColor(acos: number): string {
  if (acos <= 25) return 'text-emerald-600';
  if (acos <= 40) return 'text-yellow-600';
  return 'text-red-600';
}

// ─── Match type filter options ─────────────────────────────────────────
const matchTypeFilters = ['All', 'Exact', 'Phrase', 'Broad'] as const;
type MatchTypeFilter = (typeof matchTypeFilters)[number];

const statusFilters = ['All', 'Active', 'Paused', 'Archived'] as const;
type StatusFilter = (typeof statusFilters)[number];

// ─── Loading skeleton ─────────────────────────────────────────────────
function TableRowSkeleton() {
  return (
    <>
      {Array.from({ length: 6 }).map((_, i) => (
        <tr key={i} className="border-b border-slate-100">
          <td className="px-4 py-3"><Skeleton className="h-4 w-32" /></td>
          <td className="px-4 py-3"><Skeleton className="h-5 w-14 rounded-md" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-14 ml-auto" /></td>
          <td className="px-4 py-3"><Skeleton className="h-2 w-full rounded-full" /></td>
          <td className="px-4 py-3"><Skeleton className="h-5 w-16 rounded-md" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-14 ml-auto" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-12 ml-auto" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-10 ml-auto" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-14 ml-auto" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-14 ml-auto" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-12 ml-auto" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-12 ml-auto" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-12 ml-auto" /></td>
          <td className="px-4 py-3 text-right"><Skeleton className="h-4 w-12 ml-auto" /></td>
          <td className="px-4 py-3 text-center"><Skeleton className="h-6 w-24 mx-auto" /></td>
        </tr>
      ))}
    </>
  );
}

// ─── Main Keywords Page ────────────────────────────────────────────────
export function KeywordsPage() {
  const [actionMessage, setActionMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const [matchFilter, setMatchFilter] = useState<MatchTypeFilter>('All');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('All');
  const [searchQuery, setSearchQuery] = useState('');

  // Inline bid-edit state — wires the previously dead "编辑竞价" pencils to the
  // real PATCH /api/keywords/{id} endpoint (updateKeyword).
  const [editBidKw, setEditBidKw] = useState<Keyword | null>(null);
  const [bidInput, setBidInput] = useState('');
  const [savingBid, setSavingBid] = useState(false);
  const [bidError, setBidError] = useState<string | null>(null);

  // ── Fetch data ─────────────────────────────────────────────────────
  const keywordsQuery = useApiQuery<Keyword[]>(
    ['keywords'],
    () => fetchKeywords() as Promise<Keyword[]>,
  );
  const keywords = keywordsQuery.data ?? [];
  const loading = keywordsQuery.isLoading;
  const error = keywordsQuery.isError ? keywordsQuery.error?.message ?? '加载关键词失败' : null;
  const loadData = () => keywordsQuery.refetch();

  // ── Action handlers ────────────────────────────────────────────────
  async function handleToggleStatus(kw: Keyword) {
    const newStatus: EntityStatus = kw.status === 'active' ? 'paused' : 'active';
    try {
      await updateKeyword(kw.id, { status: newStatus });
      setActionMessage({ type: 'success', text: `关键词已${newStatus === 'active' ? '恢复' : '暂停'}` });
      loadData();
    } catch (err) {
      setActionMessage({ type: 'error', text: err instanceof Error ? err.message : '更新关键词失败' });
    }
    setTimeout(() => setActionMessage(null), 4000);
  }

  function openEditBid(kw: Keyword) {
    setEditBidKw(kw);
    setBidInput(kw.bid != null ? String(kw.bid) : '');
    setBidError(null);
  }

  async function handleSaveBid() {
    if (!editBidKw) return;
    const next = Number(bidInput);
    if (bidInput.trim() === '' || Number.isNaN(next) || next < 0) {
      setBidError('请输入有效的竞价（非负数字）');
      return;
    }
    setSavingBid(true);
    setBidError(null);
    try {
      await updateKeyword(editBidKw.id, { bid: next });
      setEditBidKw(null);
      setActionMessage({ type: 'success', text: '竞价已更新' });
      loadData();
      setTimeout(() => setActionMessage(null), 4000);
    } catch (err) {
      setBidError(err instanceof Error ? err.message : '更新竞价失败');
    } finally {
      setSavingBid(false);
    }
  }

  // Filtered keywords
  const filtered = useMemo(() => {
    return keywords.filter((kw) => {
      if (matchFilter !== 'All' && kw.matchType !== matchFilter.toLowerCase()) return false;
      if (statusFilter !== 'All' && kw.status !== statusFilter.toLowerCase()) return false;
      if (searchQuery && !(kw.keywordText || '').toLowerCase().includes(searchQuery.toLowerCase())) return false;
      return true;
    });
  }, [keywords, matchFilter, statusFilter, searchQuery]);

  // Summary stats
  const summary = useMemo(() => {
    const total = filtered.length;
    const avgBid = total > 0 ? filtered.reduce((s, k) => s + k.bid, 0) / total : 0;
    const avgAcos = total > 0
      ? filtered.reduce((s, k) => s + (k.performance?.acos ?? 0), 0) / total
      : 0;
    const totalSpend = filtered.reduce((s, k) => s + (k.performance?.spend ?? 0), 0);
    return { total, avgBid, avgAcos, totalSpend };
  }, [filtered]);

  return (
    <div className="space-y-6">

      {/* ── Page Header ────────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">关键词管理</h1>
          <p className="text-sm text-slate-500 mt-1">
            {loading ? '加载中...' : `${keywords.length} 个关键词，覆盖所有广告活动`}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={loadData}
            disabled={loading}
            className="inline-flex items-center gap-2 px-3 py-2.5 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            刷新
          </button>
          <Link
            to="/search-terms"
            className="inline-flex items-center gap-2 px-4 py-2.5 text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 rounded-lg shadow-sm transition-colors"
          >
            <Wheat size={16} />
            收割新关键词
          </Link>
        </div>
      </div>

      {/* ── Action Toast ───────────────────────────────────────────── */}
      {actionMessage && (
        <div className={cn(
          'flex items-center gap-3 px-4 py-3 rounded-lg border',
          actionMessage.type === 'success'
            ? 'bg-emerald-50 border-emerald-200 text-emerald-700'
            : 'bg-red-50 border-red-200 text-red-700',
        )}>
          <AlertCircle size={16} className="shrink-0" />
          <p className="text-sm">{actionMessage.text}</p>
          <button onClick={() => setActionMessage(null)} className="ml-auto text-sm font-medium underline">
            关闭
          </button>
        </div>
      )}

      {/* ── Error State ────────────────────────────────────────────── */}
      {error && (
        <div className="flex items-center gap-3 px-4 py-3 bg-red-50 border border-red-200 rounded-lg">
          <AlertCircle size={16} className="text-red-500 shrink-0" />
          <p className="text-sm text-red-700">{error}</p>
          <button onClick={loadData} className="ml-auto text-sm font-medium text-red-600 hover:text-red-800 underline">
            重试
          </button>
        </div>
      )}

      {/* ── Filter Bar ─────────────────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 p-4">
        <div className="flex flex-col lg:flex-row lg:items-center gap-3">

          {/* Match type filter */}
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-lg p-1">
            {matchTypeFilters.map((f) => (
              <button
                key={f}
                onClick={() => setMatchFilter(f)}
                className={cn(
                  'px-3 py-1.5 text-sm font-medium rounded-md transition-all whitespace-nowrap',
                  matchFilter === f
                    ? 'bg-white text-slate-900 shadow-sm border border-slate-200'
                    : 'text-slate-500 hover:text-slate-700',
                )}
              >
                {f}
              </button>
            ))}
          </div>

          {/* Status filter */}
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-lg p-1">
            {statusFilters.map((f) => (
              <button
                key={f}
                onClick={() => setStatusFilter(f)}
                className={cn(
                  'px-3 py-1.5 text-sm font-medium rounded-md transition-all whitespace-nowrap',
                  statusFilter === f
                    ? 'bg-white text-slate-900 shadow-sm border border-slate-200'
                    : 'text-slate-500 hover:text-slate-700',
                )}
              >
                {f}
              </button>
            ))}
          </div>

          {/* Search */}
          <div className="relative flex-1 max-w-sm lg:ml-auto">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="搜索关键词..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-9 pr-4 py-2 text-sm border border-slate-200 rounded-lg bg-white placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400 transition-all"
            />
          </div>
        </div>
      </div>

      {/* ── Keyword Table ──────────────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-200">
                <th className="text-left px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">关键词文本</th>
                <th className="text-left px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">匹配类型</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">竞价</th>
                <th className="text-left px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">竞价健康度</th>
                <th className="text-left px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">状态</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">曝光量</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">点击量</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">订单数</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">花费</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">销售额</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">ACoS</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">CTR</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">CVR</th>
                <th className="text-right px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">CPC</th>
                <th className="text-center px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {loading ? (
                <TableRowSkeleton />
              ) : error ? (
                <tr>
                  <td colSpan={15} className="px-4 py-12 text-center">
                    <div className="flex flex-col items-center gap-2 text-slate-400">
                      <AlertCircle size={24} />
                      <p className="text-sm">加载关键词失败</p>
                      <button onClick={loadData} className="text-xs text-blue-600 hover:text-blue-800 underline">
                        重试
                      </button>
                    </div>
                  </td>
                </tr>
              ) : (
                filtered.map((kw) => {
                  const perf = kw.performance;
                  return (
                    <tr key={kw.id} className="hover:bg-slate-50/50 transition-colors">
                      {/* Keyword Text */}
                      <td className="px-4 py-3 font-medium text-slate-800 whitespace-nowrap">
                        {kw.keywordText}
                      </td>

                      {/* Match Type */}
                      <td className="px-4 py-3">
                        <span className={cn('inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold uppercase tracking-wide', matchTypeStyles[kw.matchType])}>
                          {kw.matchType}
                        </span>
                      </td>

                      {/* Bid */}
                      <td className="px-4 py-3 text-right whitespace-nowrap">
                        <span className="inline-flex items-center gap-1.5 text-slate-800 font-medium">
                          {formatCurrency(kw.bid)}
                          <button
                            title="编辑竞价"
                            onClick={() => openEditBid(kw)}
                            className="text-slate-400 hover:text-blue-600 transition-colors"
                          >
                            <Pencil size={12} />
                          </button>
                        </span>
                      </td>

                      {/* Bid Health Score */}
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2 min-w-[100px]">
                          <div className={cn('flex-1 h-2 rounded-full', getBidHealthBg(kw.bidHealthScore))}>
                            <div
                              className={cn('h-full rounded-full transition-all', getBidHealthColor(kw.bidHealthScore))}
                              style={{ width: `${kw.bidHealthScore}%` }}
                            />
                          </div>
                          <span className="text-xs font-medium text-slate-600 w-8 text-right">{kw.bidHealthScore}</span>
                        </div>
                      </td>

                      {/* Status */}
                      <td className="px-4 py-3">
                        <span className={cn('inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold capitalize', statusStyles[kw.status])}>
                          {kw.status}
                        </span>
                      </td>

                      {/* Impressions */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatNumber(perf?.impressions ?? 0)}
                      </td>

                      {/* Clicks */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatNumber(perf?.clicks ?? 0)}
                      </td>

                      {/* Orders */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatNumber(perf?.orders ?? 0)}
                      </td>

                      {/* Spend */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(perf?.spend ?? 0)}
                      </td>

                      {/* Sales */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(perf?.sales ?? 0)}
                      </td>

                      {/* ACoS */}
                      <td className={cn('px-4 py-3 text-right font-medium whitespace-nowrap', getAcosColor(perf?.acos ?? 0))}>
                        {formatPercent(perf?.acos ?? 0)}
                      </td>

                      {/* CTR */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatPercent(perf?.ctr ?? 0)}
                      </td>

                      {/* CVR */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatPercent(perf?.cvr ?? 0)}
                      </td>

                      {/* CPC */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(perf?.cpc ?? 0)}
                      </td>

                      {/* Actions */}
                      <td className="px-4 py-3 text-center">
                        <div className="inline-flex items-center gap-1">
                          <button
                            title={kw.status === 'active' ? '暂停' : '恢复'}
                            onClick={() => handleToggleStatus(kw)}
                            className="p-1.5 rounded-md text-slate-400 hover:text-amber-600 hover:bg-amber-50 transition-colors"
                          >
                            {kw.status === 'active' ? <Pause size={14} /> : <Play size={14} />}
                          </button>
                          <button
                            title="编辑竞价"
                            onClick={() => openEditBid(kw)}
                            className="p-1.5 rounded-md text-slate-400 hover:text-blue-600 hover:bg-blue-50 transition-colors"
                          >
                            <Pencil size={14} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {!loading && !error && filtered.length === 0 && (
          <div className="px-4 py-12 text-center text-sm text-slate-400">
            {keywords.length === 0
              ? '该店铺未找到关键词。'
              : '没有匹配筛选条件的关键词。'}
          </div>
        )}
      </div>

      {/* ── Summary Row ────────────────────────────────────────────── */}
      {!loading && !error && (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <span className="text-sm font-medium text-slate-500">关键词总数</span>
            <p className="text-2xl font-bold text-slate-900 mt-1">{summary.total}</p>
          </div>
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <span className="text-sm font-medium text-slate-500">平均竞价</span>
            <p className="text-2xl font-bold text-slate-900 mt-1">{formatCurrency(summary.avgBid)}</p>
          </div>
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <span className="text-sm font-medium text-slate-500">平均 ACoS</span>
            <p className={cn('text-2xl font-bold mt-1', getAcosColor(summary.avgAcos))}>
              {formatPercent(summary.avgAcos)}
            </p>
          </div>
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <span className="text-sm font-medium text-slate-500">总花费</span>
            <p className="text-2xl font-bold text-slate-900 mt-1">{formatCurrency(summary.totalSpend)}</p>
          </div>
        </div>
      )}

      {/* ── Edit bid modal ─────────────────────────────────────────── */}
      {editBidKw && (
        <Dialog open onOpenChange={(o) => { if (!o && !savingBid) setEditBidKw(null); }}>
          <DialogContent className="block gap-0 p-6 w-full sm:max-w-sm rounded-xl border-0 bg-white shadow-xl">
            <div className="flex items-center justify-between mb-4">
              <DialogTitle className="text-base font-semibold text-slate-900">编辑竞价</DialogTitle>
            </div>
            <p className="text-sm text-slate-500 mb-3 truncate">关键词：<span className="font-medium text-slate-700">{editBidKw.keywordText}</span></p>
            {bidError && (
              <div className="bg-red-50 border border-red-200 rounded-lg p-2.5 mb-4 text-sm text-red-700">{bidError}</div>
            )}
            <label className="block text-sm font-medium text-slate-700 mb-1.5">新竞价 *</label>
            <input
              type="number"
              min="0"
              step="0.01"
              value={bidInput}
              onChange={(e) => setBidInput(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
            <div className="flex items-center justify-end gap-3 mt-6">
              <button onClick={() => setEditBidKw(null)} disabled={savingBid} className="px-4 py-2 text-sm font-medium text-slate-600 hover:text-slate-800 disabled:opacity-50">
                取消
              </button>
              <button
                onClick={handleSaveBid}
                disabled={savingBid}
                className="inline-flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors shadow-sm disabled:opacity-60"
              >
                {savingBid ? <Loader2 size={15} className="animate-spin" /> : <Pencil size={15} />}
                保存
              </button>
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
