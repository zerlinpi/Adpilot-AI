import { useState, useMemo } from 'react';
import {
  Search,
  Calendar,
  Wheat,
  ShieldBan,
  Eye,
  AlertCircle,
  RefreshCw,
} from 'lucide-react';

import { fetchSearchTerms, harvestSearchTerm } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { notify } from '../lib/toast';
import type { SearchTerm, HarvestingStatus } from '../types';
import {
  cn,
  formatCurrency,
  formatNumber,
  formatPercent,
  getHarvestingStatusColor,
} from '../lib/utils';
import { Skeleton } from '../components/ui/skeleton';

// ─── Harvesting status filter options ──────────────────────────────────
const harvestingFilters = [
  'All',
  'Candidate',
  'Add to Exact',
  'Add to Phrase',
  'Add to Broad',
  'Add Negative',
  'Watchlist',
  'Waste',
] as const;
type HarvestingFilter = (typeof harvestingFilters)[number];

// ─── Harvesting filter display labels (中文) ────────────────────────────
const harvestingFilterLabels: Record<HarvestingFilter, string> = {
  All: '全部',
  Candidate: '候选',
  'Add to Exact': '加入精确',
  'Add to Phrase': '加入词组',
  'Add to Broad': '加入广泛',
  'Add Negative': '加入否定',
  Watchlist: '观察列表',
  Waste: '浪费',
};

const harvestingFilterMap: Record<HarvestingFilter, HarvestingStatus | null> = {
  All: null,
  Candidate: 'candidate',
  'Add to Exact': 'add_exact',
  'Add to Phrase': 'add_phrase',
  'Add to Broad': 'add_broad',
  'Add Negative': 'add_negative',
  Watchlist: 'watchlist',
  Waste: 'waste',
};

// ─── Harvesting status display labels ──────────────────────────────────
const harvestingLabels: Record<HarvestingStatus, string> = {
  candidate: '候选',
  add_exact: '加入精确',
  add_phrase: '加入词组',
  add_broad: '加入广泛',
  add_negative: '加入否定',
  watchlist: '观察列表',
  waste: '浪费',
};

// ─── ACoS color helper ────────────────────────────────────────────────
function getAcosColor(acos: number, target = 25): string {
  if (acos === 0) return 'text-slate-400';
  if (acos <= target) return 'text-emerald-600';
  return 'text-red-600';
}

// ─── Waste spend threshold for row highlight ───────────────────────────
const WASTE_SPEND_THRESHOLD = 100;

// ─── Loading skeleton ─────────────────────────────────────────────────
function TableRowSkeleton() {
  return (
    <>
      {Array.from({ length: 6 }).map((_, i) => (
        <tr key={i} className="border-b border-slate-100">
          <td className="px-4 py-3"><Skeleton className="h-4 w-36" /></td>
          <td className="px-4 py-3"><Skeleton className="h-4 w-40" /></td>
          <td className="px-4 py-3"><Skeleton className="h-5 w-20 rounded-md" /></td>
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

// ─── Main Search Terms Page ────────────────────────────────────────────
export function SearchTermsPage() {
  const [actingId, setActingId] = useState<string | null>(null);

  const [statusFilter, setStatusFilter] = useState<HarvestingFilter>('All');
  const [searchQuery, setSearchQuery] = useState('');

  // ── Fetch data ─────────────────────────────────────────────────────
  const searchTermsQuery = useApiQuery<SearchTerm[]>(
    ['search-terms'],
    () => fetchSearchTerms() as Promise<SearchTerm[]>,
  );
  const searchTerms = searchTermsQuery.data ?? [];
  const loading = searchTermsQuery.isLoading;
  const error = searchTermsQuery.isError ? searchTermsQuery.error?.message ?? '加载搜索词失败' : null;
  const loadData = () => searchTermsQuery.refetch();

  // ── Action handlers ────────────────────────────────────────────────
  async function handleHarvest(st: SearchTerm) {
    setActingId(st.id);
    try {
      await harvestSearchTerm(st.id, 'add_exact');
      notify.success(`"${st.searchTerm}" 收割成功`);
      loadData();
    } catch (err) {
      notify.error(err instanceof Error ? err.message : '收割搜索词失败');
    } finally {
      setActingId(null);
    }
  }

  async function handleNegate(st: SearchTerm) {
    setActingId(st.id);
    try {
      await harvestSearchTerm(st.id, 'add_negative');
      notify.success(`"${st.searchTerm}" 已否定`);
      loadData();
    } catch (err) {
      notify.error(err instanceof Error ? err.message : '否定搜索词失败');
    } finally {
      setActingId(null);
    }
  }

  async function handleWatch(st: SearchTerm) {
    setActingId(st.id);
    try {
      await harvestSearchTerm(st.id, 'watchlist');
      notify.success(`"${st.searchTerm}" 已加入观察列表`);
      loadData();
    } catch (err) {
      notify.error(err instanceof Error ? err.message : '更新搜索词失败');
    } finally {
      setActingId(null);
    }
  }

  // Determine date range from data
  const dateRange = useMemo(() => {
    if (searchTerms.length === 0) return { start: '', end: '' };
    const starts = searchTerms.map((st) => st.periodStart);
    const ends = searchTerms.map((st) => st.periodEnd);
    return {
      start: starts.sort()[0],
      end: ends.sort().reverse()[0],
    };
  }, [searchTerms]);

  // Campaign name lookup - search terms may include campaign info
  const campaignNameMap = useMemo(() => {
    const map = new Map<string, string>();
    searchTerms.forEach((st) => {
      if (st.campaignId && !map.has(st.campaignId)) {
        map.set(st.campaignId, (st as any).campaignName ?? st.campaignId);
      }
    });
    return map;
  }, [searchTerms]);

  function getCampaignName(campaignId: string): string {
    return campaignNameMap.get(campaignId) ?? campaignId;
  }

  // Filtered search terms
  const filtered = useMemo(() => {
    return searchTerms.filter((st) => {
      const mappedStatus = harvestingFilterMap[statusFilter];
      if (mappedStatus && st.harvestingStatus !== mappedStatus) return false;
      if (searchQuery && !(st.searchTerm || '').toLowerCase().includes(searchQuery.toLowerCase())) return false;
      return true;
    });
  }, [searchTerms, statusFilter, searchQuery]);

  // Summary stats
  const summary = useMemo(() => {
    const total = filtered.length;
    const harvestable = filtered.filter((st) =>
      ['candidate', 'add_exact', 'add_phrase', 'add_broad'].includes(st.harvestingStatus),
    ).length;
    const wasteSpend = filtered
      .filter((st) => st.harvestingStatus === 'waste')
      .reduce((s, st) => s + st.spend, 0);
    return { total, harvestable, wasteSpend };
  }, [filtered]);

  return (
    <div className="space-y-6">

      {/* ── Page Header ────────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">搜索词分析</h1>
          <p className="text-sm text-slate-500 mt-1 flex items-center gap-1.5">
            {loading ? (
              '加载中...'
            ) : (
              <>
                <span>{searchTerms.length} 个搜索词</span>
                {dateRange.start && dateRange.end && (
                  <>
                    <span className="text-slate-300">|</span>
                    <Calendar size={14} className="text-slate-400" />
                    <span>{dateRange.start} &ndash; {dateRange.end}</span>
                  </>
                )}
              </>
            )}
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
        </div>
      </div>

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

          {/* Harvesting status filter */}
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-lg p-1 overflow-x-auto">
            {harvestingFilters.map((f) => (
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
                {harvestingFilterLabels[f]}
              </button>
            ))}
          </div>

          {/* Search */}
          <div className="relative flex-1 max-w-sm lg:ml-auto">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="搜索搜索词..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-9 pr-4 py-2 text-sm border border-slate-200 rounded-lg bg-white placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400 transition-all"
            />
          </div>
        </div>
      </div>

      {/* ── Search Term Table ──────────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-200">
                <th className="text-left px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">搜索词</th>
                <th className="text-left px-4 py-3 font-semibold text-slate-600 whitespace-nowrap">广告活动</th>
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
                  <td colSpan={13} className="px-4 py-12 text-center">
                    <div className="flex flex-col items-center gap-2 text-slate-400">
                      <AlertCircle size={24} />
                      <p className="text-sm">加载搜索词失败</p>
                      <button onClick={loadData} className="text-xs text-blue-600 hover:text-blue-800 underline">
                        重试
                      </button>
                    </div>
                  </td>
                </tr>
              ) : (
                filtered.map((st) => {
                  const isWasteHighlight =
                    st.harvestingStatus === 'waste' && st.spend >= WASTE_SPEND_THRESHOLD;
                  const isCandidate = st.harvestingStatus === 'candidate';
                  const isActing = actingId === st.id;

                  return (
                    <tr
                      key={st.id}
                      className={cn(
                        'transition-colors',
                        isWasteHighlight
                          ? 'bg-red-50/40 hover:bg-red-50/70'
                          : 'hover:bg-slate-50/50',
                        isActing && 'opacity-50 pointer-events-none',
                      )}
                    >
                      {/* Search Term */}
                      <td className="px-4 py-3 font-medium text-slate-800 whitespace-nowrap">
                        {st.searchTerm}
                      </td>

                      {/* Campaign Name */}
                      <td className="px-4 py-3 text-slate-600 whitespace-nowrap max-w-[200px] truncate" title={getCampaignName(st.campaignId)}>
                        {getCampaignName(st.campaignId)}
                      </td>

                      {/* Harvesting Status */}
                      <td className="px-4 py-3">
                        <span className={cn('inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold', getHarvestingStatusColor(st.harvestingStatus))}>
                          {harvestingLabels[st.harvestingStatus]}
                        </span>
                      </td>

                      {/* Impressions */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatNumber(st.impressions)}
                      </td>

                      {/* Clicks */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatNumber(st.clicks)}
                      </td>

                      {/* Orders */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatNumber(st.orders)}
                      </td>

                      {/* Spend */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(st.spend)}
                      </td>

                      {/* Sales */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(st.sales)}
                      </td>

                      {/* ACoS */}
                      <td className={cn('px-4 py-3 text-right font-medium whitespace-nowrap', getAcosColor(st.acos))}>
                        {st.acos === 0 ? '--' : formatPercent(st.acos)}
                      </td>

                      {/* CTR */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatPercent(st.ctr)}
                      </td>

                      {/* CVR */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatPercent(st.cvr)}
                      </td>

                      {/* CPC */}
                      <td className="px-4 py-3 text-right text-slate-700 whitespace-nowrap">
                        {formatCurrency(st.cpc)}
                      </td>

                      {/* Actions */}
                      <td className="px-4 py-3 text-center">
                        <div className="inline-flex items-center gap-1">
                          {isCandidate && (
                            <button
                              title="收割"
                              onClick={() => handleHarvest(st)}
                              disabled={isActing}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-md transition-colors disabled:opacity-50"
                            >
                              <Wheat size={12} />
                              收割
                            </button>
                          )}
                          <button
                            title="否定"
                            onClick={() => handleNegate(st)}
                            disabled={isActing}
                            className="p-1.5 rounded-md text-slate-400 hover:text-red-600 hover:bg-red-50 transition-colors disabled:opacity-50"
                          >
                            <ShieldBan size={14} />
                          </button>
                          <button
                            title="观察"
                            onClick={() => handleWatch(st)}
                            disabled={isActing}
                            className="p-1.5 rounded-md text-slate-400 hover:text-amber-600 hover:bg-amber-50 transition-colors disabled:opacity-50"
                          >
                            <Eye size={14} />
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
            {searchTerms.length === 0
              ? '该店铺未找到搜索词。'
              : '没有匹配筛选条件的搜索词。'}
          </div>
        )}
      </div>

      {/* ── Summary Cards ──────────────────────────────────────────── */}
      {!loading && !error && (
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <span className="text-sm font-medium text-slate-500">搜索词总数</span>
            <p className="text-2xl font-bold text-slate-900 mt-1">{summary.total}</p>
          </div>
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <span className="text-sm font-medium text-slate-500">可收割</span>
            <p className="text-2xl font-bold text-emerald-600 mt-1">{summary.harvestable}</p>
          </div>
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <span className="text-sm font-medium text-slate-500">浪费花费</span>
            <p className="text-2xl font-bold text-red-600 mt-1">{formatCurrency(summary.wasteSpend)}</p>
          </div>
        </div>
      )}
    </div>
  );
}
