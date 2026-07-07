// Approve / Reject / Rollback actions for a hosting decision row (Req 24, 10.6).
//
// Rendered inline in the dashboard decision list. The component is purely
// presentational + local-interaction: it decides which controls apply to the
// decision's current Sync_State, manages the reject-reason input and the
// rollback conflict-confirmation flow, and delegates the actual writes to the
// async callbacks supplied by the host (which own react-query mutations, query
// invalidation and toast feedback).
//
//  - awaiting_approval  → Approve / Reject       (gated on advertising:approve, Req 24.1)
//  - effective + reversible → Rollback           (gated on advertising:execute, Req 10.6)
//
// On a confirmation_required rollback response (Req 10.5) the component surfaces
// the conflicting operation ids + warning and offers a "confirm anyway" action.

import { useState } from 'react';
import { AlertTriangle, Check, Loader2, RotateCcw, X } from 'lucide-react';

import { Button } from '../ui/button';
import type { HostingDecision, HostingRollbackResult } from '../../lib/api';

/**
 * Decision types whose Operations are reversible (Req 10.3): keyword bid changes
 * and campaign budget changes. Keyword / negative-keyword additions are not.
 */
const REVERSIBLE_DECISION_TYPES = new Set(['bid_adjustment', 'budget_adjustment']);

export interface ApprovalActionsProps {
  decision: HostingDecision;
  /** True when the current user holds `advertising:approve` (Req 24.1). */
  canApprove: boolean;
  /** True when the current user holds `advertising:execute` (Req 10.6). */
  canRollback: boolean;
  /** Approve the awaiting-approval Operation (Req 24.2). */
  onApprove: (operationId: string) => Promise<unknown>;
  /** Reject the awaiting-approval Operation with a reason (Req 24.3). */
  onReject: (operationId: string, reason: string) => Promise<unknown>;
  /**
   * Roll back the effective Operation (Req 10.6). Resolves with the backend
   * result so a `confirmation_required` response can be surfaced before a
   * confirmed re-issue with `confirm=true` (Req 10.5).
   */
  onRollback: (operationId: string, confirm: boolean) => Promise<HostingRollbackResult>;
}

/** Whether a decision's promoted Operation is reversible (Req 10.3). */
export function isReversibleDecision(decision: HostingDecision): boolean {
  return !!decision.decision_type && REVERSIBLE_DECISION_TYPES.has(decision.decision_type);
}

export function ApprovalActions({
  decision,
  canApprove,
  canRollback,
  onApprove,
  onReject,
  onRollback,
}: ApprovalActionsProps) {
  const operationId = decision.promoted_operation_id ?? null;
  const isAwaitingApproval = decision.sync_state === 'awaiting_approval';
  const isReversibleEffective =
    decision.sync_state === 'effective' && isReversibleDecision(decision);

  const [busy, setBusy] = useState<null | 'approve' | 'reject' | 'rollback'>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [conflict, setConflict] = useState<HostingRollbackResult | null>(null);

  // No promoted Operation, or no applicable action for this state/permission set.
  if (!operationId) return null;
  const showApproval = isAwaitingApproval && canApprove;
  const showRollback = isReversibleEffective && canRollback;
  if (!showApproval && !showRollback) return null;

  async function runApprove() {
    if (!operationId) return;
    setBusy('approve');
    try {
      await onApprove(operationId);
    } finally {
      setBusy(null);
    }
  }

  async function runReject() {
    if (!operationId) return;
    const trimmed = reason.trim();
    if (!trimmed) return;
    setBusy('reject');
    try {
      await onReject(operationId, trimmed);
      setRejecting(false);
      setReason('');
    } finally {
      setBusy(null);
    }
  }

  async function runRollback(confirm: boolean) {
    if (!operationId) return;
    setBusy('rollback');
    try {
      const result = await onRollback(operationId, confirm);
      // A confirmation_required response means overlapping subsequent operations
      // were detected; surface them and wait for an explicit confirm (Req 10.5).
      if (result?.confirmation_required) {
        setConflict(result);
      } else {
        setConflict(null);
      }
    } finally {
      setBusy(null);
    }
  }

  // ── Reject reason input (Req 24.3) ──
  if (rejecting) {
    return (
      <div className="flex shrink-0 items-center gap-1.5">
        <input
          type="text"
          autoFocus
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="拒绝原因"
          aria-label="拒绝原因"
          className="h-7 w-32 rounded-md border border-slate-200 px-2 text-xs outline-none focus:border-violet-400 focus:ring-2 focus:ring-violet-100"
          onKeyDown={(e) => {
            if (e.key === 'Enter') void runReject();
            if (e.key === 'Escape') {
              setRejecting(false);
              setReason('');
            }
          }}
        />
        <Button
          variant="outline"
          size="sm"
          className="h-7 px-2 text-xs text-red-600 hover:text-red-700"
          onClick={() => void runReject()}
          disabled={busy === 'reject' || !reason.trim()}
        >
          {busy === 'reject' ? <Loader2 size={12} className="animate-spin" /> : '确认拒绝'}
        </Button>
        <Button
          variant="ghost"
          size="sm"
          className="h-7 px-2 text-xs text-slate-500"
          onClick={() => {
            setRejecting(false);
            setReason('');
          }}
          disabled={busy === 'reject'}
        >
          取消
        </Button>
      </div>
    );
  }

  // ── Rollback conflict confirmation (Req 10.5) ──
  if (conflict) {
    return (
      <div className="flex shrink-0 flex-col items-end gap-1.5">
        <div className="flex items-center gap-1.5 text-xs text-amber-600">
          <AlertTriangle size={12} className="shrink-0" />
          <span className="max-w-[220px] text-right">
            {conflict.warning_message ?? '该操作之后存在针对同一字段的后续变更，回滚可能产生冲突。'}
          </span>
        </div>
        {conflict.conflicting_operation_ids && conflict.conflicting_operation_ids.length > 0 && (
          <p className="max-w-[220px] text-right text-[10px] text-slate-400">
            冲突操作：{conflict.conflicting_operation_ids.join('、')}
          </p>
        )}
        <div className="flex items-center gap-1.5">
          <Button
            variant="outline"
            size="sm"
            className="h-7 px-2 text-xs text-amber-700 hover:text-amber-800"
            onClick={() => void runRollback(true)}
            disabled={busy === 'rollback'}
          >
            {busy === 'rollback' ? <Loader2 size={12} className="animate-spin" /> : '仍要回滚'}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 px-2 text-xs text-slate-500"
            onClick={() => setConflict(null)}
            disabled={busy === 'rollback'}
          >
            取消
          </Button>
        </div>
      </div>
    );
  }

  // ── Default action buttons ──
  return (
    <div className="flex shrink-0 items-center gap-1.5">
      {showApproval && (
        <>
          <Button
            size="sm"
            className="h-7 gap-1 bg-emerald-600 px-2 text-xs hover:bg-emerald-700"
            onClick={() => void runApprove()}
            disabled={busy !== null}
          >
            {busy === 'approve' ? <Loader2 size={12} className="animate-spin" /> : <Check size={12} />}
            批准
          </Button>
          <Button
            variant="outline"
            size="sm"
            className="h-7 gap-1 px-2 text-xs text-red-600 hover:text-red-700"
            onClick={() => setRejecting(true)}
            disabled={busy !== null}
          >
            <X size={12} />
            拒绝
          </Button>
        </>
      )}
      {showRollback && (
        <Button
          variant="outline"
          size="sm"
          className="h-7 gap-1 px-2 text-xs text-amber-600 hover:text-amber-700"
          onClick={() => void runRollback(false)}
          disabled={busy !== null}
        >
          {busy === 'rollback' ? <Loader2 size={12} className="animate-spin" /> : <RotateCcw size={12} />}
          回滚
        </Button>
      )}
    </div>
  );
}

export default ApprovalActions;
