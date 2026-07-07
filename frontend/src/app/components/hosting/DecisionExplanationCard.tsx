// AI 决策解释卡片 (Decision Explanation Card) — Req 13.1–13.6, 8.6.
//
// Renders the COMPLETE, immutable explanation for a single AI hosting decision,
// backed entirely by the immutable Decision_Snapshot persisted at decision time
// (Req 13.6 / 34). Nothing here is reconstructed or recomputed on the client —
// the card only translates and lays out the snapshot + attribution data returned
// by `GET /advertising/hosting/decisions/{id}` (HostingDecisionDetail).
//
// Sections, mapped to the acceptance criteria:
//   • 触发数据   — triggering metric values + data window/DQ gate (Req 13.1)
//   • 规则与人格 — personality, rule_version, source level (Req 13.1 / 13.4)
//   • 安全边界   — boundaries checked, each with its source level (Req 13.1)
//   • 决策与预测 — rule that fired, proposed action, predicted impact / risk (Req 13.2)
//   • 审计轨迹   — creation / expiry / sync state audit trail (Req 13.3)
//   • 效果归因   — attribution once the measurement window completes, with an
//                  explicit "pending" state while it is still open (Req 8.6 / 13.3)
//
// Honesty rule (Req 8.2 / 8.4 / 8.6): the estimated incremental impact is ALWAYS
// labelled as an estimate (（估算）). When the backend reports it as null — i.e.
// there is no reliable baseline — the card shows the fixed NO_BASELINE_SIGNAL
// instead of fabricating a number, and the full observed change is never
// presented as proven AI contribution.

import {
  Database,
  Sliders,
  ShieldCheck,
  Gauge,
  History,
  BarChart3,
  Clock,
  CheckCircle2,
  XCircle,
} from 'lucide-react';

import { cn } from '../../lib/utils';
import { formatDate } from '../../lib/utils';
import { translateMachineValue } from '../../lib/translateMachineValue';
import { isAiPersonality } from '../../lib/aiPersonality';
import type {
  HostingDecisionDetail,
  HostingDecision,
  HostingDecisionSnapshot,
  HostingEffectAttribution,
} from '../../lib/api';
import { PersonalityIcon } from './PersonalityIcon';

/** Honest "no reliable baseline" signal for a null incremental estimate (Req 8.2). */
export const NO_BASELINE_SIGNAL = '暂无可靠基线';

// ─── Display label maps (machine value → fixed Chinese copy) ─────────────────

/** Source-level machine value → display copy (campaign/goal/store/system). */
const SOURCE_LEVEL_LABEL: Record<string, string> = {
  campaign: '活动级',
  goal: '目标级',
  store: '店铺级',
  system: '系统默认',
  fallback: '系统默认',
};

/** Known metric keys (snapshot metricInputs & attribution metric_type) → copy. */
const METRIC_LABEL: Record<string, string> = {
  acos: 'ACoS',
  currentAcos: '当前 ACoS',
  current_acos: '当前 ACoS',
  targetAcos: '目标 ACoS',
  target_acos: '目标 ACoS',
  spend: '花费',
  sales: '销售额',
  clicks: '点击',
  orders: '订单',
  impressions: '曝光',
  ctr: 'CTR',
  cvr: '转化率',
  conversionRate: '转化率',
};

function sourceLevelLabel(level?: string | null): string {
  if (!level) return '—';
  return SOURCE_LEVEL_LABEL[level] ?? level;
}

function metricLabel(metric: string): string {
  return METRIC_LABEL[metric] ?? metric;
}

/** Format an arbitrary metric-input number for display (never throws). */
function formatMetricValue(value: number): string {
  if (!Number.isFinite(value)) return '—';
  return value.toLocaleString('en-US', { maximumFractionDigits: 4 });
}

/** Format a before/after value (which the API types as `any`) defensively. */
function formatRawValue(value: unknown): string {
  if (value == null || value === '') return '—';
  if (typeof value === 'number') return formatMetricValue(value);
  return String(value);
}

/** Format an attribution change/confidence figure with sign awareness. */
function formatSigned(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toLocaleString('en-US', { maximumFractionDigits: 4 })}`;
}

/** Format an attribution_confidence (0.0–1.0) as a percent. */
function formatConfidence(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return `${(value * 100).toFixed(0)}%`;
}

// ─── Layout primitives (match DecisionExplanation / HostingOverview style) ───

function Section({
  icon,
  title,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4">
      <header className="mb-2 flex items-center gap-1.5 text-slate-400">
        {icon}
        <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</h4>
      </header>
      {children}
    </section>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[7rem_1fr] gap-2 py-1">
      <dt className="text-xs font-medium text-slate-500">{label}</dt>
      <dd className="text-sm text-slate-800">{children}</dd>
    </div>
  );
}

// ─── Sub-sections ────────────────────────────────────────────────────────────

function TriggerDataSection({ snapshot }: { snapshot: HostingDecisionSnapshot }) {
  const metricEntries = Object.entries(snapshot.metricInputs ?? {});
  return (
    <Section icon={<Database size={13} />} title="触发数据">
      <dl>
        {metricEntries.length > 0 ? (
          metricEntries.map(([key, value]) => (
            <Row key={key} label={metricLabel(key)}>
              <span className="font-medium tabular-nums">{formatMetricValue(value)}</span>
            </Row>
          ))
        ) : (
          <p className="py-1 text-sm text-slate-400">无触发指标数据</p>
        )}

        {snapshot.lookbackDays != null && (
          <Row label="回看窗口">{snapshot.lookbackDays} 天</Row>
        )}
        {snapshot.dataCutoff && <Row label="数据截止">{formatDate(snapshot.dataCutoff)}</Row>}
        {snapshot.dqGateResult && (
          <Row label="数据质量">
            {snapshot.dqGateResult.passed ? (
              <span className="inline-flex items-center gap-1 text-emerald-600">
                <CheckCircle2 size={13} /> 通过
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 text-red-600">
                <XCircle size={13} /> 未通过
                {snapshot.dqGateResult.reason ? `（${snapshot.dqGateResult.reason}）` : null}
              </span>
            )}
          </Row>
        )}
      </dl>
    </Section>
  );
}

function PersonalityRuleSection({ snapshot }: { snapshot: HostingDecisionSnapshot }) {
  const personality = isAiPersonality(snapshot.personality) ? snapshot.personality : null;
  const personalityDisplay = personality
    ? translateMachineValue('AI_Personality', personality)
    : null;
  // The inheritance chain records the resolution path; its first hop is the
  // level that actually determined the personality (Req 13.4 source level).
  const chain = snapshot.inheritanceChain ?? [];
  const sourceLevel = chain.length > 0 ? chain[0] : null;

  return (
    <Section icon={<Sliders size={13} />} title="规则与人格">
      <dl>
        {personalityDisplay ? (
          <Row label="AI 人格">
            <span className="inline-flex items-center gap-1">
              {personality && <PersonalityIcon personality={personality} size={13} />}
              {personalityDisplay}
            </span>
          </Row>
        ) : (
          <Row label="AI 人格">—</Row>
        )}
        {sourceLevel && <Row label="人格来源">{sourceLevelLabel(sourceLevel)}</Row>}
        {chain.length > 0 && (
          <Row label="继承链">
            <span className="text-slate-500">
              {chain.map((level) => sourceLevelLabel(level)).join(' → ')}
            </span>
          </Row>
        )}
        {snapshot.ruleVersion && <Row label="规则版本">{snapshot.ruleVersion}</Row>}
        {snapshot.executionMode && <Row label="执行模式">{snapshot.executionMode}</Row>}
      </dl>
    </Section>
  );
}

function BoundariesSection({ snapshot }: { snapshot: HostingDecisionSnapshot }) {
  const boundaries = snapshot.effectiveBoundaries ?? [];
  return (
    <Section icon={<ShieldCheck size={13} />} title="安全边界">
      {boundaries.length > 0 ? (
        <ul className="divide-y divide-slate-100">
          {boundaries.map((b) => (
            <li key={b.limitName} className="flex items-center justify-between gap-2 py-1.5">
              <span className="text-xs font-medium text-slate-500">{b.limitName}</span>
              <span className="flex items-center gap-2">
                <span className="text-sm font-medium text-slate-800 tabular-nums">{b.value}</span>
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-500">
                  {sourceLevelLabel(b.sourceLevel)}
                </span>
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="py-1 text-sm text-slate-400">无适用的安全边界</p>
      )}
    </Section>
  );
}

function DecisionImpactSection({
  decision,
  snapshot,
}: {
  decision: HostingDecision;
  snapshot: HostingDecisionSnapshot;
}) {
  const riskScore = snapshot.riskScore ?? decision.risk_score ?? null;
  const formulaVersion = snapshot.riskFormulaVersion ?? null;
  const hasAction = decision.before_value != null || decision.after_value != null;

  return (
    <Section icon={<Gauge size={13} />} title="决策与预测">
      <dl>
        {decision.decision_type && (
          <Row label="决策类型">{decision.decision_type}</Row>
        )}
        {decision.field && <Row label="调整字段">{decision.field}</Row>}
        {hasAction && (
          <Row label="拟执行动作">
            <span className="text-slate-500">{formatRawValue(decision.before_value)}</span>
            <span className="mx-1 text-slate-400">→</span>
            <span className="font-semibold text-slate-900">{formatRawValue(decision.after_value)}</span>
          </Row>
        )}
        {riskScore != null && Number.isFinite(riskScore) && (
          <Row label="预测风险评分">
            <span className="font-semibold tabular-nums text-slate-900">{riskScore.toFixed(2)}</span>
            {formulaVersion ? (
              <span className="ml-1 text-xs text-slate-400">（公式 {formulaVersion}）</span>
            ) : null}
          </Row>
        )}
        {decision.routing_outcome && (
          <Row label="路由结果">{decision.routing_outcome}</Row>
        )}
      </dl>
    </Section>
  );
}

function AuditTrailSection({ decision }: { decision: HostingDecision }) {
  return (
    <Section icon={<History size={13} />} title="审计轨迹">
      <dl>
        {decision.created_at && <Row label="创建时间">{formatDate(decision.created_at)}</Row>}
        {decision.sync_state && <Row label="同步状态">{decision.sync_state}</Row>}
        {decision.promoted_operation_id && (
          <Row label="关联操作">{decision.promoted_operation_id}</Row>
        )}
        {decision.expires_at && <Row label="决策过期">{formatDate(decision.expires_at)}</Row>}
      </dl>
    </Section>
  );
}

function AttributionSection({
  attributions,
}: {
  attributions: HostingEffectAttribution[] | null | undefined;
}) {
  const rows = attributions ?? [];
  const windowComplete = rows.length > 0;

  return (
    <Section icon={<BarChart3 size={13} />} title="效果归因">
      {windowComplete ? (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-[11px] uppercase tracking-wide text-slate-400">
                <th className="py-1.5 pr-3 font-medium">指标</th>
                <th className="py-1.5 pr-3 font-medium">观测变化</th>
                <th className="py-1.5 pr-3 font-medium">增量影响（估算）</th>
                <th className="py-1.5 font-medium">置信度</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, idx) => {
                const noBaseline = row.estimated_incremental_impact == null;
                return (
                  <tr key={`${row.metric_type}-${idx}`} className="border-b border-slate-100 last:border-0">
                    <td className="py-1.5 pr-3 text-slate-700">{metricLabel(row.metric_type)}</td>
                    <td className="py-1.5 pr-3 tabular-nums text-slate-800">
                      {formatSigned(row.observed_change)}
                    </td>
                    <td className="py-1.5 pr-3 tabular-nums">
                      {noBaseline ? (
                        <span className="text-slate-400">{NO_BASELINE_SIGNAL}</span>
                      ) : (
                        <span className="font-medium text-slate-900">
                          {formatSigned(row.estimated_incremental_impact)}
                        </span>
                      )}
                    </td>
                    <td className="py-1.5 tabular-nums text-slate-600">
                      {formatConfidence(row.attribution_confidence)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="mt-2 text-[11px] text-slate-400">
            “增量影响”为基于基线的估算值，并非已证实的 AI 贡献；观测变化为前后窗口的原始差值。
          </p>
        </div>
      ) : (
        <div className="flex items-center gap-2 py-1 text-sm text-slate-400">
          <Clock size={14} className="shrink-0" />
          <span>测量窗口进行中，效果归因将在窗口结束后生成。</span>
        </div>
      )}
    </Section>
  );
}

// ─── Main card ───────────────────────────────────────────────────────────────

export function DecisionExplanationCard({
  detail,
  className,
}: {
  detail: HostingDecisionDetail;
  className?: string;
}) {
  const { decision, snapshot, attributions } = detail;

  return (
    <article
      aria-label="AI 决策解释"
      className={cn('space-y-3', className)}
    >
      {/* Header — decision identity */}
      <header className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-slate-200 bg-slate-50/60 px-4 py-3">
        <div>
          <p className="text-sm font-semibold text-slate-900">
            {decision.campaign_name || decision.campaign_id || '未命名广告活动'}
          </p>
          <p className="text-xs text-slate-500">
            {[decision.engine, decision.decision_type].filter(Boolean).join(' · ') || 'AI 决策'}
          </p>
        </div>
        {decision.execution_mode && (
          <span className="rounded-full bg-white px-2.5 py-1 text-xs font-medium text-slate-600 ring-1 ring-slate-200">
            {decision.execution_mode}
          </span>
        )}
      </header>

      {snapshot ? (
        <>
          <TriggerDataSection snapshot={snapshot} />
          <PersonalityRuleSection snapshot={snapshot} />
          <BoundariesSection snapshot={snapshot} />
          <DecisionImpactSection decision={decision} snapshot={snapshot} />
        </>
      ) : (
        <p className="rounded-xl border border-dashed border-slate-200 bg-white p-4 text-sm text-slate-400">
          该决策没有可用的不可变快照数据。
        </p>
      )}

      <AuditTrailSection decision={decision} />
      <AttributionSection attributions={attributions} />
    </article>
  );
}

export default DecisionExplanationCard;
