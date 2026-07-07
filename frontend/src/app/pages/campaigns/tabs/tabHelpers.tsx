// Shared display helpers for the decomposed Campaigns_Workspace tab modules.
//
// These were extracted verbatim from the original monolithic `CampaignsPage.tsx`
// so the per-tab modules under `tabs/` preserve the exact labels, badge styling,
// and formatting behavior of the original inline tab content (Req 1.2, 1.3, 1.4).

import { cn } from '../../../lib/utils';

// ─── Status display (Req 19.3: 投放状态 投放中/已暂停/已投放/审核中) ──────
const RUNNING_STATUSES = new Set(['active', 'enabled', 'running']);

export function statusLabel(status: string): string {
  switch ((status ?? '').toLowerCase()) {
    case 'active':
    case 'enabled':
    case 'running':
      return '投放中';
    case 'paused':
      return '已暂停';
    case 'delivered':
    case 'served':
    case 'completed':
      return '已投放';
    case 'needs_review':
    case 'pending_review':
    case 'in_review':
      return '审核中';
    default:
      return status || '—';
  }
}

export function statusBadgeClass(status: string): string {
  switch ((status ?? '').toLowerCase()) {
    case 'active':
    case 'enabled':
    case 'running':
      return 'bg-emerald-100 text-emerald-700';
    case 'paused':
      return 'bg-slate-100 text-slate-500';
    case 'needs_review':
    case 'pending_review':
    case 'in_review':
      return 'bg-amber-100 text-amber-700';
    default:
      return 'bg-blue-100 text-blue-700';
  }
}

export function isRunning(status: string): boolean {
  return RUNNING_STATUSES.has((status ?? '').toLowerCase());
}

/** A status pill rendered inside a SharedDataTable cell. */
export function StatusBadge({ status }: { status: string }) {
  return (
    <span
      className={cn(
        'inline-flex px-2 py-0.5 rounded-md text-[11px] font-semibold',
        statusBadgeClass(status),
      )}
    >
      {statusLabel(status)}
    </span>
  );
}

// ─── Targeting type label (投放类型: 自动 / 手动) ──────────────────────
export function targetingTypeLabel(t?: string): string {
  switch ((t ?? '').toLowerCase()) {
    case 'auto':
    case 'automatic':
      return '自动';
    case 'manual':
    case 'manual_keyword':
      return '手动';
    case '':
      return '—';
    default:
      return t || '—';
  }
}

// ─── ACoS color helper ────────────────────────────────────────────────
export function acosColor(acos: number): string {
  if (acos > 40) return 'text-red-600';
  if (acos > 25) return 'text-orange-600';
  return 'text-emerald-600';
}

// ─── Ad type / smart filter options (SP / SB / SD) ────────────────────
export const adTypeOptions = [
  { value: 'all', label: '全部类型' },
  { value: 'SP', label: 'SP' },
  { value: 'SB', label: 'SB' },
  { value: 'SD', label: 'SD' },
];

export const smartFilterOptions = [
  { value: 'all', label: '智能筛选' },
  { value: 'AI_MANAGED', label: 'AI入格' },
  { value: 'HOSTED', label: 'AI托管中' },
  { value: 'UNHOSTED', label: '未托管' },
  { value: 'OVER_TARGET', label: '超出目标ACOS' },
  { value: 'UNDER_TARGET', label: '达成目标ACOS' },
];

// ─── Hosting goal presets (托管目标) ───────────────────────────────────
export const HOSTING_GOAL_OPTIONS = [
  { value: 'maximize_sales_at_target', label: '在目标ACOS下最大化销售' },
  { value: 'reduce_acos', label: '降低 ACOS' },
  { value: 'grow_sales', label: '提升销售额' },
  { value: 'maintain_efficiency', label: '维持广告效率' },
];

export function hostingGoalLabel(goal?: string | null): string {
  if (!goal) return '—';
  return HOSTING_GOAL_OPTIONS.find((o) => o.value === goal)?.label ?? goal;
}

// ─── 否定投放 scope label ──────────────────────────────────────────────
export function negativeScopeLabel(level?: string | null): string {
  switch ((level ?? '').toLowerCase()) {
    case 'campaign':
      return '广告活动';
    case 'adgroup':
    case 'ad_group':
      return '广告组';
    default:
      return level || '—';
  }
}

// ─── SP预算上限 budget type label ──────────────────────────────────────
export function budgetTypeLabel(t?: string): string {
  switch ((t ?? '').toLowerCase()) {
    case 'daily':
      return '每日预算';
    case 'lifetime':
      return '总预算';
    default:
      return t || '—';
  }
}

/**
 * 操作日志 status badge color helper.
 */
export function operationStatusBadge(status?: string | null): string {
  switch ((status ?? '').toLowerCase()) {
    case 'success':
    case 'applied':
    case 'completed':
      return 'bg-emerald-100 text-emerald-700';
    case 'failed':
    case 'error':
      return 'bg-red-100 text-red-600';
    case 'pending':
    case 'running':
      return 'bg-amber-100 text-amber-700';
    case 'rolled_back':
      return 'bg-slate-100 text-slate-500';
    default:
      return 'bg-blue-100 text-blue-700';
  }
}
