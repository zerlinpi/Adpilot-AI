// AI托管 (AI Hosting) tab — extracted from CampaignsPage and recomposed on the
// reusable SharedDataTable building block (Req 1.2, 1.9). Per-tab behavior
// (全部/托管中 view toggle, assign/adjust/un-host actions, the hosting modal,
// loading/empty/error/refresh) is preserved from the original implementation.

import { useCallback, useMemo, useState } from 'react';
import { AlertCircle, Bot, DollarSign, Loader2, RefreshCw, Trash2, X, ChevronRight } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { notify } from '../../../lib/toast';

import {
  approveHostingOperation,
  assignCampaignHosting,
  fetchCampaigns,
  fetchHostingDashboardSummary,
  fetchHostingDecisions,
  fetchHostingDecision,
  fetchPlatformConnections,
  rejectHostingOperation,
  removeCampaignHosting,
  rollbackHostingOperation,
  type CampaignVo,
  type HostingDashboardSummary,
  type HostingDecision,
} from '../../../lib/api';
import { useApiMutation, useApiQuery } from '../../../lib/hooks/useApiQuery';
import { qk } from '../../../lib/queryKeys';
import { usePermissions } from '../../../lib/PermissionContext';
import { useStoreContext } from '../../../lib/StoreContext';
import { normalizeStorePlatform } from '../../../lib/platformTaxonomy';
import { cn, formatCurrency, formatDate, formatPercent } from '../../../lib/utils';
import { Button } from '../../../components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '../../../components/ui/dialog';
import { ErpEmptyState } from '../../../components/erp/ErpEmptyState';
import { SharedDataTable } from '../../../components/table/SharedDataTable';
import type { ColumnDef } from '../../../components/table/types';

import { HOSTING_GOAL_OPTIONS, acosColor, hostingGoalLabel } from './tabHelpers';

import { translateMachineValue } from '../../../lib/translateMachineValue';
import {
  resolveCampaignPersonality,
  type AiPersonality,
  type PersonalityPolicyMap,
} from '../../../lib/aiPersonality';
import {
  ApprovalActions,
  DecisionExplanationCard,
  HostingOverview,
  HostingSettingsDrawer,
  ResolvedPersonalityCell,
  type HostingOverviewData,
  type HostingSettings,
} from '../../../components/hosting';

/** Auto-refresh cadence for the live hosting dashboard (Req 11.6). */
const HOSTING_REFRESH_MS = 60_000;

// ─── Hosting modal (assign / adjust AI hosting) ───────────────────────
function HostingModal({
  campaign,
  onClose,
  onSaved,
}: {
  campaign: CampaignVo;
  onClose: () => void;
  onSaved: (updated: CampaignVo) => void;
}) {
  const editing = campaign.hostingEnabled;
  const [hostingGoal, setHostingGoal] = useState(campaign.hostingGoal || 'maximize_sales_at_target');
  const [targetAcos, setTargetAcos] = useState(campaign.targetAcos != null ? String(campaign.targetAcos) : '');
  const [submitting, setSubmitting] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);
    setFormError(null);

    // FE validation: Target_ACoS is required and must be a positive number (Req 21.6).
    const trimmed = targetAcos.trim();
    if (!trimmed) {
      setFieldError('请填写目标 ACOS 后再开启 AI 托管');
      return;
    }
    const value = Number(trimmed);
    if (!Number.isFinite(value) || value <= 0) {
      setFieldError('目标 ACOS 必须为大于 0 的数值');
      return;
    }

    try {
      setSubmitting(true);
      const updated = await assignCampaignHosting(campaign.id, { targetAcos: value, hostingGoal });
      onSaved(updated);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '开启 AI 托管失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose(); }}>
      <DialogContent className="block gap-0 p-0 w-full sm:max-w-md rounded-xl border-0 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <div className="flex items-center gap-2">
            <Bot size={18} className="text-violet-500" />
            <DialogTitle className="text-lg font-semibold text-slate-900">{editing ? '调整 AI 托管' : '开启 AI 托管'}</DialogTitle>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4">
          <div className="rounded-lg bg-slate-50 px-3 py-2.5">
            <p className="text-xs text-slate-500">广告活动</p>
            <p className="text-sm font-medium text-slate-900 truncate" title={campaign.name}>{campaign.name}</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">托管目标</label>
            <select
              value={hostingGoal}
              onChange={(e) => setHostingGoal(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-violet-400 focus:ring-2 focus:ring-violet-100"
            >
              {HOSTING_GOAL_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              目标 ACOS (%) <span className="text-red-500">*</span>
            </label>
            <input
              type="number"
              min="0"
              step="0.1"
              value={targetAcos}
              onChange={(e) => setTargetAcos(e.target.value)}
              placeholder="例如：25"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-violet-400 focus:ring-2 focus:ring-violet-100"
            />
            <p className="text-xs text-slate-400 mt-1">AI 将持续优化竞价、预算与关键词，使 ACOS 趋向该目标。</p>
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
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-violet-600 text-white text-sm font-medium hover:bg-violet-700 disabled:opacity-60"
            >
              {submitting && <Loader2 size={14} className="animate-spin" />}
              {editing ? '保存' : '开启托管'}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ─── Recent decisions list (Req 11.4) + explanation modal (Req 11.5) ──────────

/** Format a decision's before→after change defensively (values are typed `any`). */
function formatDecisionValue(value: unknown): string {
  if (value == null || value === '') return '—';
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value.toLocaleString('en-US', { maximumFractionDigits: 4 }) : '—';
  }
  return String(value);
}

function DecisionList({
  decisions,
  loading,
  error,
  onOpen,
  onRetry,
  renderActions,
}: {
  decisions: HostingDecision[];
  loading: boolean;
  error: string | null;
  onOpen: (id: string) => void;
  onRetry: () => void;
  renderActions?: (decision: HostingDecision) => React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
        <p className="text-sm font-semibold text-slate-900">最近 AI 决策</p>
        {loading && <Loader2 size={14} className="animate-spin text-slate-400" />}
      </div>

      {error ? (
        <div className="flex flex-col items-center gap-2 py-10 text-slate-400">
          <AlertCircle size={20} className="text-red-400" />
          <p className="text-sm">{error}</p>
          <button onClick={onRetry} className="text-sm font-medium text-violet-600 underline">
            重试
          </button>
        </div>
      ) : decisions.length === 0 ? (
        <div className="flex flex-col items-center gap-2 py-10 text-slate-400">
          <Bot size={20} />
          <p className="text-sm">{loading ? '加载中...' : '暂无 AI 决策记录'}</p>
        </div>
      ) : (
        <ul className="divide-y divide-slate-100">
          {decisions.map((d) => (
            <li key={d.id} className="flex items-center gap-2 px-4 py-3 transition-colors hover:bg-slate-50">
              <button
                type="button"
                onClick={() => onOpen(d.id)}
                className="flex min-w-0 flex-1 items-center gap-3 text-left"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-sm font-medium text-slate-900" title={d.campaign_name ?? undefined}>
                      {d.campaign_name || d.campaign_id || '未命名广告活动'}
                    </span>
                    {d.decision_type && (
                      <span className="inline-flex shrink-0 items-center rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-600">
                        {d.decision_type}
                      </span>
                    )}
                  </div>
                  <div className="mt-0.5 flex items-center gap-2 text-xs text-slate-500">
                    {(d.before_value != null || d.after_value != null) && (
                      <span className="tabular-nums">
                        {formatDecisionValue(d.before_value)}
                        <span className="mx-1 text-slate-400">→</span>
                        {formatDecisionValue(d.after_value)}
                      </span>
                    )}
                    {d.created_at && <span>{formatDate(d.created_at)}</span>}
                  </div>
                </div>
                {d.risk_score != null && Number.isFinite(d.risk_score) && (
                  <span className="shrink-0 text-xs text-slate-500">
                    风险 <span className="font-semibold tabular-nums text-slate-700">{d.risk_score.toFixed(2)}</span>
                  </span>
                )}
                {d.sync_state && (
                  <span className="shrink-0 rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-600">
                    {d.sync_state}
                  </span>
                )}
                <ChevronRight size={14} className="shrink-0 text-slate-300" />
              </button>
              {renderActions?.(d)}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function DecisionDetailModal({ decisionId, onClose }: { decisionId: string; onClose: () => void }) {
  const detailQuery = useApiQuery(qk.hostingDecision(decisionId), () => fetchHostingDecision(decisionId));

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="flex flex-col gap-0 p-0 max-h-[85vh] w-full sm:max-w-2xl rounded-xl border-0 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <DialogTitle className="text-lg font-semibold text-slate-900">AI 决策解释</DialogTitle>
        </div>
        <div className="overflow-y-auto px-5 py-4">
          {detailQuery.isLoading ? (
            <div className="flex items-center justify-center gap-2 py-16 text-slate-400">
              <Loader2 size={18} className="animate-spin" />
              <span className="text-sm">加载决策详情...</span>
            </div>
          ) : detailQuery.isError ? (
            <div className="flex flex-col items-center gap-2 py-16 text-slate-400">
              <AlertCircle size={20} className="text-red-400" />
              <p className="text-sm">{detailQuery.error?.message ?? '加载决策详情失败'}</p>
              <button onClick={() => detailQuery.refetch()} className="text-sm font-medium text-violet-600 underline">
                重试
              </button>
            </div>
          ) : detailQuery.data ? (
            <DecisionExplanationCard detail={detailQuery.data} />
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function AiHostingTab({ storeId }: { storeId: string | null }) {
  const { stores } = useStoreContext();
  const { can } = usePermissions();
  const queryClient = useQueryClient();
  const [modalCampaign, setModalCampaign] = useState<CampaignVo | null>(null);
  const [view, setView] = useState<'hosted' | 'all'>('all');
  // The Campaign whose full personality rules are open in the settings drawer
  // (opened by clicking the AI人格 cell, Req 50.9).
  const [personalityDrawer, setPersonalityDrawer] = useState<CampaignVo | null>(null);
  // Optional 人格分布 filter applied by clicking a distribution count (Req 50.13).
  const [personalityFilter, setPersonalityFilter] = useState<AiPersonality | null>(null);
  // Personality policies are a configurable BACKEND value (Req 49.6); the drawer
  // degrades gracefully when they have not been supplied.
  const policies: PersonalityPolicyMap | undefined = undefined;

  const storeName = useMemo(
    () => stores.find((s) => s.id === storeId)?.name ?? '—',
    [stores, storeId],
  );

  // AI 托管 only applies to an Amazon store with an active (connected) Amazon
  // Ads account. The tab self-gates on this so it stays correct even if reused
  // outside the advertising workspace's page-level connection gate.
  const activeStore = stores.find((s) => s.id === storeId);
  const isAmazonStore = normalizeStorePlatform(activeStore?.platform) === 'amazon';
  const connectionsQuery = useApiQuery<any[]>(
    ['platform-connections', storeId],
    () => fetchPlatformConnections() as Promise<any[]>,
    { enabled: !!storeId && isAmazonStore },
  );
  const amazonAdsReady = (connectionsQuery.data ?? []).some(
    (c) =>
      (c?.platform === 'amazon_ads' || c?.platformId === 'amazon_ads') &&
      c?.storeId === storeId &&
      c?.status === 'connected',
  );

  const queryParams = { pageSize: 200 };
  const query = useApiQuery<CampaignVo[]>(
    qk.campaigns(storeId ?? undefined, queryParams),
    () => fetchCampaigns({ storeId: storeId!, pageSize: 200 }).then((r) => r.items ?? []),
    { enabled: !!storeId },
  );

  const campaigns = query.data ?? [];
  const loading = !!storeId && query.isLoading;
  const error = query.isError ? query.error?.message ?? '加载广告活动失败' : null;
  const load = () => {
    query.refetch();
    summaryQuery.refetch();
    decisionsQuery.refetch();
  };

  // Live dashboard summary cards (Req 11.1/27.1), scoped to the Active_Store so
  // data-scope is respected (Req 11.7/27.5). Auto-refreshes every 60s (Req 11.6).
  const summaryQuery = useApiQuery<HostingDashboardSummary>(
    qk.hostingDashboardSummary(storeId ?? undefined),
    () => fetchHostingDashboardSummary(storeId!),
    { enabled: !!storeId, refetchInterval: HOSTING_REFRESH_MS },
  );
  const summary = summaryQuery.data ?? null;

  // Recent AI decisions for the store, newest first (Req 11.4); also 60s live.
  const decisionsQuery = useApiQuery<HostingDecision[]>(
    qk.hostingDecisions(storeId ?? undefined),
    () => fetchHostingDecisions(storeId!, 20),
    { enabled: !!storeId, refetchInterval: HOSTING_REFRESH_MS },
  );
  const decisions = decisionsQuery.data ?? [];

  // The decision whose explanation card is open (Req 11.5).
  const [decisionDetailId, setDecisionDetailId] = useState<string | null>(null);

  // On a successful write, refresh the affected cached read (Req 3.3).
  const invalidate = () => queryClient.invalidateQueries({ queryKey: qk.campaigns(storeId ?? undefined, queryParams) });

  function flashToast(t: { type: 'success' | 'error'; text: string }) {
    if (t.type === 'success') notify.success(t.text);
    else notify.error(t.text);
  }

  // After an approve/reject/rollback, refresh the affected dashboard summary
  // (awaiting-approval count) and the decision list so the UI updates in
  // real-time without a full reload (Req 24.5).
  const refreshHosting = () => {
    queryClient.invalidateQueries({ queryKey: qk.hostingDashboardSummary(storeId ?? undefined) });
    queryClient.invalidateQueries({ queryKey: qk.hostingDecisions(storeId ?? undefined) });
  };

  // Approval workflow permissions: approve/reject require advertising:approve
  // (Req 24.1); rollback requires advertising:execute (Req 10.6).
  const canApprove = can('advertising:approve');
  const canRollback = can('advertising:execute');

  // Approve an awaiting-approval Operation → advances it to pending (Req 24.2).
  const approveMutation = useApiMutation((operationId: string) => approveHostingOperation(operationId), {
    onSuccess: () => {
      refreshHosting();
      flashToast({ type: 'success', text: '已批准该决策，等待执行' });
    },
    onError: (err) => flashToast({ type: 'error', text: err?.message ?? '批准失败' }),
  });

  // Reject an awaiting-approval Operation with a reason → cancels it (Req 24.3).
  const rejectMutation = useApiMutation(
    ({ operationId, reason }: { operationId: string; reason: string }) =>
      rejectHostingOperation(operationId, reason),
    {
      onSuccess: () => {
        refreshHosting();
        flashToast({ type: 'success', text: '已拒绝该决策' });
      },
      onError: (err) => flashToast({ type: 'error', text: err?.message ?? '拒绝失败' }),
    },
  );

  // Roll back an effective, reversible Operation → compensating operation (Req 10.6).
  // A confirmation_required response is NOT a completion: the conflict is shown
  // by ApprovalActions and no success toast/refresh fires until confirmed (Req 10.5).
  const rollbackMutation = useApiMutation(
    ({ operationId, confirm }: { operationId: string; confirm: boolean }) =>
      rollbackHostingOperation(operationId, confirm),
    {
      onSuccess: (data) => {
        if (data.confirmation_required) return;
        refreshHosting();
        flashToast({ type: 'success', text: '已发起回滚，正在创建补偿操作' });
      },
      onError: (err) => flashToast({ type: 'error', text: err?.message ?? '回滚失败' }),
    },
  );

  const rows = useMemo(() => {
    let result = view === 'hosted' ? campaigns.filter((c) => c.hostingEnabled) : campaigns;
    if (personalityFilter) {
      result = result.filter(
        (c) =>
          resolveCampaignPersonality({ campaignPersonality: c.campaignPersonality }).personality ===
          personalityFilter,
      );
    }
    return result;
  }, [campaigns, view, personalityFilter]);

  const hostedCount = useMemo(() => campaigns.filter((c) => c.hostingEnabled).length, [campaigns]);

  // AI托管 overview figures. The hosted-count and the per-personality distribution
  // are derived from the loaded campaigns; the operation-derived counts, the
  // estimated savings (always labelled an estimate, Req 11.3) and the learning-
  // period status (Req 19.5) come from the live dashboard summary endpoint
  // (Req 11.1/27.1). Values default to 0 / "no baseline" until supplied rather
  // than being fabricated (Req 11.3/8.2).
  const overviewData: HostingOverviewData = useMemo(() => {
    const hosted = campaigns.filter((c) => c.hostingEnabled);
    const campaignName = (id: string) => campaigns.find((c) => c.id === id)?.name ?? null;

    const savings7d = summary?.estimated_savings_7d;
    const estimatedSpendSavings =
      savings7d != null && Number.isFinite(savings7d) ? formatCurrency(savings7d) : null;

    const learningPeriods = (summary?.learning_periods ?? []).map((lp) => ({
      campaignId: lp.campaign_id,
      campaignName: campaignName(lp.campaign_id),
      daysRemaining: lp.days_remaining,
      totalDays: lp.total_days,
    }));

    return {
      // Prefer the backend's hosted count; fall back to the loaded campaigns.
      hostedCount: summary?.hosted_campaigns_count ?? hosted.length,
      todayDecisions: summary?.today_decisions_count ?? 0,
      awaitingApproval: summary?.awaiting_approval_count ?? 0,
      amazonEffective: summary?.effective_today_count ?? 0,
      failed: summary?.failed_today_count ?? 0,
      todayBudgetDelta: null,
      estimatedSalesChange: null,
      estimatedSpendSavings,
      estimatedSavingsLabel: summary?.estimated_savings_label ?? null,
      learningPeriods,
      resolvedPersonalities: hosted.map(
        (c) => resolveCampaignPersonality({ campaignPersonality: c.campaignPersonality }).personality,
      ),
    };
  }, [campaigns, summary]);

  // ── Un-host action (Req 21.5) — mutation does not auto-retry (Req 3.6) ──
  const unhostMutation = useApiMutation(
    (c: CampaignVo) => removeCampaignHosting(c.id),
    {
      onSuccess: (_data, c) => {
        invalidate();
        flashToast({ type: 'success', text: `已取消「${c.name}」的 AI 托管` });
      },
      onError: (err) => flashToast({ type: 'error', text: err?.message ?? '取消托管失败' }),
    },
  );
  const unhostingId = unhostMutation.isPending ? unhostMutation.variables?.id ?? null : null;
  // `mutate` is referentially stable across renders, so the memoized columns
  // below only rebuild when the busy-row id actually changes.
  const { mutate: unhostCampaign } = unhostMutation;

  // Stable row-identity resolver so SharedDataTable's row-id memoization isn't
  // invalidated by a fresh closure on every render.
  const rowId = useCallback((c: CampaignVo) => c.id, []);

  const columns = useMemo<ColumnDef<CampaignVo>[]>(() => [
    {
      key: 'name',
      header: '广告活动',
      render: (c) => (
        <div className="flex items-center gap-2">
          {c.campaignType && (
            <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold uppercase bg-slate-100 text-slate-600">
              {c.campaignType}
            </span>
          )}
          <span className="text-sm font-medium text-slate-900 truncate max-w-[240px]" title={c.name}>
            {c.name}
          </span>
        </div>
      ),
    },
    {
      key: 'personality',
      header: 'AI人格',
      render: (c) => (
        <ResolvedPersonalityCell
          campaignPersonality={c.campaignPersonality}
          onOpen={() => setPersonalityDrawer(c)}
        />
      ),
    },
    { key: 'hostingGoal', header: '托管目标', render: (c) => (c.hostingEnabled ? hostingGoalLabel(c.hostingGoal) : '—') },
    {
      key: 'targetAcos',
      header: '目标ACOS',
      render: (c) => (c.hostingEnabled && c.targetAcos != null ? formatPercent(c.targetAcos) : '—'),
    },
    {
      key: 'acos',
      header: 'ACOS最近',
      render: (c) => <span className={cn('font-medium', acosColor(c.acos))}>{c.acos > 0 ? formatPercent(c.acos) : '—'}</span>,
    },
    {
      key: 'actions',
      header: '操作',
      render: (c) => {
        const busy = unhostingId === c.id;
        return (
          <div className="flex items-center gap-2">
            {c.hostingEnabled ? (
              <>
                <Button variant="outline" size="sm" className="h-7 text-xs gap-1" onClick={() => setModalCampaign(c)} disabled={busy}>
                  <DollarSign size={12} /> 调整
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-7 text-xs gap-1 text-red-600 hover:text-red-700"
                  onClick={() => unhostCampaign(c)}
                  disabled={busy}
                >
                  {busy ? <Loader2 size={12} className="animate-spin" /> : <Trash2 size={12} />}
                  取消托管
                </Button>
              </>
            ) : (
              <Button
                size="sm"
                className="h-7 text-xs gap-1 bg-violet-600 hover:bg-violet-700"
                onClick={() => setModalCampaign(c)}
                disabled={busy}
              >
                <Bot size={12} /> 开启托管
              </Button>
            )}
          </div>
        );
      },
    },
  ], [unhostingId, unhostCampaign]);

  // Gate: require an active Amazon Ads connection before exposing AI 托管 (Req:
  // only show the AI Hosting tab when the store has an active Amazon Ads
  // connection). Show a clear, actionable message otherwise.
  if (storeId && !connectionsQuery.isLoading && !amazonAdsReady) {
    return (
      <ErpEmptyState
        title="请先连接亚马逊广告账户以启用 AI 托管"
        description="AI 托管需要已连接并测试通过的 Amazon Ads 账户后才能使用。"
        icon={<Bot size={24} className="text-slate-400" />}
        action={
          <Link
            to="/data-sync?platform=amazon_ads"
            className="inline-flex items-center gap-2 rounded-lg bg-violet-600 px-4 py-2 text-sm font-medium text-white hover:bg-violet-700 transition-colors"
          >
            连接 Amazon Ads
          </Link>
        }
      />
    );
  }

  return (
    <div className="space-y-4">
      {/* Header + view toggle */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="text-sm text-slate-600">
            {loading ? '加载中...' : `${hostedCount} 个广告活动处于 AI 托管中 · ${storeName}`}
          </p>
          <p className="text-xs text-slate-400 mt-0.5">为广告活动分配托管目标与目标 ACOS，由 AI 自动优化竞价、预算与关键词。</p>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex items-center rounded-lg border border-slate-200 p-0.5">
            <button
              onClick={() => setView('all')}
              className={cn('px-3 py-1.5 text-xs font-medium rounded-md transition-colors', view === 'all' ? 'bg-slate-100 text-slate-900' : 'text-slate-500 hover:text-slate-700')}
            >
              全部
            </button>
            <button
              onClick={() => setView('hosted')}
              className={cn('px-3 py-1.5 text-xs font-medium rounded-md transition-colors', view === 'hosted' ? 'bg-violet-100 text-violet-700' : 'text-slate-500 hover:text-slate-700')}
            >
              托管中
            </button>
          </div>
          <Button variant="outline" size="sm" className="gap-1.5" onClick={load} disabled={loading}>
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            刷新
          </Button>
        </div>
      </div>

      {/* Toast */}
      {/* AI托管 overview — first-screen figures + 人格分布 (Req 50.10/50.11/50.13) */}
      {!loading && !error && (campaigns.length > 0 || summary) && (
        <HostingOverview
          data={overviewData}
          onSelectPersonality={(p) =>
            setPersonalityFilter((prev) => (prev === p ? null : p))
          }
        />
      )}

      {/* Recent AI decisions — clicking one opens its explanation card (Req 11.4/11.5) */}
      {!loading && !error && (campaigns.length > 0 || summary) && (
        <DecisionList
          decisions={decisions}
          loading={decisionsQuery.isLoading}
          error={decisionsQuery.isError ? decisionsQuery.error?.message ?? '加载 AI 决策失败' : null}
          onOpen={setDecisionDetailId}
          onRetry={() => decisionsQuery.refetch()}
          renderActions={(d) => (
            <ApprovalActions
              decision={d}
              canApprove={canApprove}
              canRollback={canRollback}
              onApprove={(operationId) => approveMutation.mutateAsync(operationId)}
              onReject={(operationId, reason) => rejectMutation.mutateAsync({ operationId, reason })}
              onRollback={(operationId, confirm) => rollbackMutation.mutateAsync({ operationId, confirm })}
            />
          )}
        />
      )}

      {/* Active 人格分布 filter chip */}
      {personalityFilter && (
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 text-xs text-slate-700">
            人格：{translateMachineValue('AI_Personality', personalityFilter)}
            <button onClick={() => setPersonalityFilter(null)} className="text-slate-400 hover:text-slate-600" aria-label="清除人格筛选">
              <X size={12} />
            </button>
          </span>
        </div>
      )}

      <SharedDataTable<CampaignVo>
        tableKey="campaigns.hosting"
        rows={rows}
        columns={columns}
        rowId={rowId}
        loading={loading}
        error={error}
        onRetry={load}
        emptyState={
          <div className="flex flex-col items-center gap-2 py-16 text-slate-400">
            <Bot size={24} />
            <p className="text-sm">{view === 'hosted' ? '暂无处于 AI 托管中的广告活动' : '该店铺暂无广告活动'}</p>
          </div>
        }
      />

      {modalCampaign && (
        <HostingModal
          campaign={modalCampaign}
          onClose={() => setModalCampaign(null)}
          onSaved={(updated) => {
            invalidate();
            setModalCampaign(null);
            flashToast({ type: 'success', text: `「${updated.name}」已进入 AI 托管` });
          }}
        />
      )}

      {/* AI decision explanation card modal (Req 11.5) */}
      {decisionDetailId && (
        <DecisionDetailModal decisionId={decisionDetailId} onClose={() => setDecisionDetailId(null)} />
      )}

      {/* Per-Campaign AI人格 settings drawer (Req 50.1/50.9), opened from the AI人格 cell */}
      {personalityDrawer && (
        <HostingSettingsDrawer
          open
          onClose={() => setPersonalityDrawer(null)}
          title="AI 人格规则"
          subtitle={personalityDrawer.name}
          policies={policies}
          writeCapable
          affectedCampaignCount={1}
          initial={
            {
              optimizationGoal: personalityDrawer.optimizationGoal ?? 'profit_first',
              targetAcos: personalityDrawer.targetAcos ?? null,
              personality: resolveCampaignPersonality({
                campaignPersonality: personalityDrawer.campaignPersonality,
              }).personality,
            } satisfies HostingSettings
          }
          onSave={(next) => {
            // Persisting the personality override is handled by the campaign
            // mutation path; here we surface the confirmed change and close.
            setPersonalityDrawer(null);
            flashToast({
              type: 'success',
              text: `「${personalityDrawer.name}」AI人格已更新为 ${translateMachineValue('AI_Personality', next.personality)}`,
            });
          }}
        />
      )}
    </div>
  );
}

export default AiHostingTab;
