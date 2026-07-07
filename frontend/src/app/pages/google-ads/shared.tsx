// Shared UI primitives for the 独立站 Google Ads module
// (platform-workspace-rbac Req 6, 7, 8).
//
// These small building blocks keep the six Google Ads views consistent and
// encode the three states the GoogleAds_Module must surface:
//   - connect prompt when the Store has no active Google Ads connection (Req 6.6)
//   - an error indicator with a retry control on a failed read/request (Req 6.5)
//   - a pending badge for an unsettled write Operation (Req 7.6)

import { ReactNode } from 'react';
import { Link } from 'react-router';
import { AlertTriangle, Link2, Loader2, Megaphone } from 'lucide-react';

/** Page header with a title and optional subtitle + right-aligned actions. */
export function GaPageHeader({
  title,
  subtitle,
  actions,
}: {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">{title}</h1>
        {subtitle && <p className="text-sm text-slate-500 mt-1">{subtitle}</p>}
      </div>
      {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
    </div>
  );
}

/** Centered loading spinner used while a read is in flight. */
export function GaLoading({ label = '加载中…' }: { label?: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <Loader2 size={32} className="text-blue-400 mb-3 animate-spin" />
      <p className="text-sm text-slate-500">{label}</p>
    </div>
  );
}

/**
 * Connect prompt shown when the selected Store has no active Google Ads
 * connection (read state CONNECT_PROMPT, Req 6.6). It is NOT an error: it routes
 * the operator to the 连接 view to bind a Google Ads account.
 */
export function GaConnectPrompt({ message }: { message?: string | null }) {
  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <div className="w-14 h-14 rounded-full bg-blue-50 flex items-center justify-center mb-4">
        <Megaphone size={26} className="text-blue-500" />
      </div>
      <p className="text-lg font-medium text-slate-700">未连接 Google Ads</p>
      <p className="text-sm text-slate-500 mt-1 max-w-md">
        {message || '请先为该独立站店铺绑定 Google Ads 账号，才能查看广告系列与报告。'}
      </p>
      <Link
        to="/google-ads/connect"
        className="mt-4 inline-flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
      >
        <Link2 size={16} />
        前往连接
      </Link>
    </div>
  );
}

/**
 * Error indicator with a retry control (read state ERROR or a thrown request
 * failure, Req 6.5). Previously displayed data is left untouched by the caller.
 */
export function GaErrorRetry({ message, onRetry }: { message?: string | null; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <AlertTriangle size={40} className="text-red-300 mb-4" />
      <p className="text-lg font-medium text-slate-700">出错了</p>
      <p className="text-sm text-slate-500 mt-1 max-w-md">{message || '请求失败，请重试。'}</p>
      <button
        onClick={onRetry}
        className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
      >
        重试
      </button>
    </div>
  );
}

/** Empty-state placeholder for a view that loaded with no rows. */
export function GaEmpty({ icon, title, hint }: { icon: ReactNode; title: string; hint?: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <div className="mb-4 text-slate-300">{icon}</div>
      <p className="text-lg font-medium text-slate-500">{title}</p>
      {hint && <p className="text-sm text-slate-400 mt-1">{hint}</p>}
    </div>
  );
}

/** Prompt shown when no store is selected in the global switcher. */
export function GaNoStore() {
  return (
    <GaEmpty
      icon={<Megaphone size={48} />}
      title="请先选择一个店铺"
      hint="使用顶部的店铺切换器选择一个独立站店铺。"
    />
  );
}

/** A small "待确认 / 待生效" pending badge for an unsettled change (Req 7.6). */
export function GaPendingBadge({ label = '待生效' }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border bg-amber-50 text-amber-700 border-amber-200">
      <Loader2 size={11} className="animate-spin" />
      {label}
    </span>
  );
}

/** Format a possibly-null numeric metric, with optional currency/percent. */
export function fmtNum(v: number | null | undefined, opts?: { currency?: boolean; pct?: boolean; digits?: number }): string {
  if (v == null || Number.isNaN(v)) return '-';
  const digits = opts?.digits ?? (opts?.currency ? 2 : 0);
  const n = Number(v).toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
  if (opts?.currency) return `$${n}`;
  if (opts?.pct) return `${n}%`;
  return n;
}

/** Translate a Sync_State machine value into Chinese display copy (Req 7.6). */
export function syncStateLabel(state?: string | null): string {
  if (!state) return '未知';
  const map: Record<string, string> = {
    PENDING: '待提交',
    SUBMITTING: '提交中',
    SUBMITTED: '已提交',
    CONFIRMED: '已生效',
    FAILED: '失败',
    pending: '待提交',
    submitting: '提交中',
    submitted: '已提交',
    confirmed: '已生效',
    failed: '失败',
  };
  return map[state] ?? state;
}

/** True while a write Operation is unsettled (not yet platform-confirmed). */
export function isUnsettled(state?: string | null): boolean {
  if (!state) return false;
  const s = state.toUpperCase();
  return s === 'PENDING' || s === 'SUBMITTING' || s === 'SUBMITTED';
}
