// AI decision explanation (Req 50.14).
//
// Renders, for one AI optimizer decision, the trigger metric, the resolved
// AI_Personality used, the decision reason, the before/after values, the
// personality-allowed maximum magnitude, the actual magnitude, the predicted
// impact, the approval-required flag, and the Amazon sync result. The decision
// data originates from the Operation_Record (Req 8 / 49.9); the backend returns
// machine values, the frontend translates them here.

import { cn } from '../../lib/utils';
import { translateMachineValue } from '../../lib/translateMachineValue';
import { formatRatioPercent, isAiPersonality } from '../../lib/aiPersonality';
import { PersonalityIcon } from './PersonalityIcon';

/**
 * One AI decision as surfaced from the Operation_Record (Req 49.9). All fields
 * are optional so a partially-populated decision still renders the rows it has.
 */
export interface AiDecisionExplanation {
  /** The trigger metric name (e.g. "近7天 ACoS"). */
  triggerMetric?: string | null;
  /** The trigger metric value, pre-formatted for display (e.g. "18.4%"). */
  triggerValue?: string | null;
  /** Resolved AI_Personality machine value (conservative/balanced/aggressive). */
  resolvedPersonality?: string | null;
  /** The decision reason in plain language. */
  reason?: string | null;
  /** Before value, pre-formatted (e.g. "$1.00"). */
  beforeValue?: string | null;
  /** After value, pre-formatted (e.g. "$1.08"). */
  afterValue?: string | null;
  /** Personality-allowed maximum change magnitude as a decimal ratio (0.10). */
  personalityAllowedMagnitude?: number | null;
  /** Actual applied change magnitude as a decimal ratio (0.08). */
  actualMagnitude?: number | null;
  /** The predicted impact in plain language. */
  predictedImpact?: string | null;
  /** Whether the change requires approval. */
  approvalRequired?: boolean | null;
  /** Amazon sync result machine value (Sync_State) or status string. */
  amazonSyncResult?: string | null;
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[5.5rem_1fr] gap-2 py-1">
      <dt className="text-xs font-medium text-slate-500">{label}</dt>
      <dd className="text-sm text-slate-800">{children}</dd>
    </div>
  );
}

export function DecisionExplanation({
  decision,
  className,
}: {
  decision: AiDecisionExplanation;
  className?: string;
}) {
  const personality = isAiPersonality(decision.resolvedPersonality)
    ? decision.resolvedPersonality
    : null;
  const personalityDisplay = personality
    ? translateMachineValue('AI_Personality', personality)
    : null;

  return (
    <dl className={cn('rounded-xl border border-slate-200 bg-white p-4', className)}>
      {(decision.triggerMetric || decision.triggerValue) && (
        <Row label="触发指标">
          {decision.triggerMetric}
          {decision.triggerValue ? <span className="font-medium"> {decision.triggerValue}</span> : null}
        </Row>
      )}

      {personalityDisplay && (
        <Row label="使用人格">
          <span className="inline-flex items-center gap-1">
            {personality && <PersonalityIcon personality={personality} size={13} />}
            {personalityDisplay}
          </span>
        </Row>
      )}

      {decision.reason && <Row label="决策原因">{decision.reason}</Row>}

      {(decision.beforeValue != null || decision.afterValue != null) && (
        <Row label="调整">
          <span className="text-slate-500">{decision.beforeValue ?? '—'}</span>
          <span className="mx-1 text-slate-400">→</span>
          <span className="font-semibold text-slate-900">{decision.afterValue ?? '—'}</span>
        </Row>
      )}

      {(decision.personalityAllowedMagnitude != null || decision.actualMagnitude != null) && (
        <Row label="调整幅度">
          本次 {formatRatioPercent(decision.actualMagnitude)}
          <span className="text-slate-400">
            {' '}
            / 人格允许上限 {formatRatioPercent(decision.personalityAllowedMagnitude)}
          </span>
        </Row>
      )}

      {decision.predictedImpact && <Row label="预计影响">{decision.predictedImpact}</Row>}

      <Row label="是否需审批">
        {decision.approvalRequired == null ? (
          '—'
        ) : decision.approvalRequired ? (
          <span className="font-medium text-amber-600">需人工审批</span>
        ) : (
          <span className="text-slate-600">无需审批</span>
        )}
      </Row>

      {decision.amazonSyncResult && (
        <Row label="Amazon 同步">{decision.amazonSyncResult}</Row>
      )}
    </dl>
  );
}

export default DecisionExplanation;
