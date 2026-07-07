// AI托管 overview (Req 50.10 / 50.11 / 50.12 / 50.13).
//
// First screen shows EXACTLY the five figures of Req 50.10 (托管活动数, 今日 AI
// 决策数, 等待审批数, Amazon 生效数, 失败数). The budget delta and the two AI
// ESTIMATES live in the trend area, not the first screen (Req 50.11). The
// estimates are labelled "（估算）" and, when there is no valid baseline, show the
// fixed "暂不可估算：缺少有效基线" signal instead of a fabricated number (Req 50.12).
// The "AI人格分布" breakdown counts Campaigns per resolved AI_Personality and each
// count filters the list to that personality (Req 50.11 / 50.13).

import { Bot, CheckCircle2, Clock, XCircle, Sparkles, GraduationCap } from 'lucide-react';

import { cn } from '../../lib/utils';
import {
  computePersonalityDistribution,
  PERSONALITY_META,
  type AiPersonality,
} from '../../lib/aiPersonality';
import { PersonalityIcon } from './PersonalityIcon';

/** The honest "cannot estimate" signal mandated by Req 50.12. */
export const NO_BASELINE_SIGNAL = '暂不可估算：缺少有效基线';

/** Fallback label used when the backend supplies no `estimated_savings_label`. */
export const DEFAULT_SAVINGS_LABEL = 'AI 节省花费（估算）';

/**
 * The display decision for an estimate card, derived purely from the raw
 * estimate value. This is the single source of truth for whether a card shows
 * the estimate or the honest no-baseline signal (Req 11.3 / 27.3 / 50.12).
 *
 * - `hasBaseline` is true only for a non-empty string value.
 * - `text` is the value to render: the estimate when a baseline exists, the
 *   fixed NO_BASELINE_SIGNAL otherwise. A number is NEVER fabricated.
 *
 * Pure and deterministic: identical input always yields identical output.
 */
export interface EstimateDisplay {
  hasBaseline: boolean;
  text: string;
}

export function selectEstimateDisplay(value?: string | null): EstimateDisplay {
  const hasBaseline = value != null && value !== '';
  return {
    hasBaseline,
    text: hasBaseline ? value : NO_BASELINE_SIGNAL,
  };
}

/**
 * The label for an estimate card. Always falls back to an estimate-flavoured
 * label so the figure is never presented as a definitive/proven number
 * (Req 11.3 / 27.3).
 */
export function resolveSavingsLabel(label?: string | null): string {
  return label != null && label !== '' ? label : DEFAULT_SAVINGS_LABEL;
}

/**
 * One campaign currently inside its learning period (Req 19.5). `daysRemaining`
 * and `totalDays` come from the backend; `campaignName` is resolved client-side
 * from the loaded campaigns when available (falls back to the id).
 */
export interface HostingLearningPeriodItem {
  campaignId: string;
  campaignName?: string | null;
  daysRemaining: number;
  totalDays: number;
}

export interface HostingOverviewData {
  /** 托管活动数 — number of hosted Campaigns. */
  hostedCount: number;
  /** 今日 AI 决策数 — AI decisions made today. */
  todayDecisions: number;
  /** 等待审批数 — Operations awaiting approval. */
  awaitingApproval: number;
  /** Amazon 生效数 — Operations that reached `effective` on Amazon. */
  amazonEffective: number;
  /** 失败数 — failed Operations. */
  failed: number;

  /** 今日预算增减 — net budget change today, pre-formatted (e.g. "+$120"). */
  todayBudgetDelta?: string | null;
  /**
   * AI 带来的销售变化（估算） — pre-formatted estimate, or null when there is no
   * valid baseline (renders the NO_BASELINE_SIGNAL).
   */
  estimatedSalesChange?: string | null;
  /**
   * AI 节省花费（估算） — pre-formatted estimate, or null when there is no valid
   * baseline (renders the NO_BASELINE_SIGNAL).
   */
  estimatedSpendSavings?: string | null;
  /**
   * Label for the estimated-savings card, supplied by the backend
   * (`estimated_savings_label`) so the figure is always presented as an estimate
   * (Req 11.3 / 27.3). Falls back to a fixed estimate label when absent.
   */
  estimatedSavingsLabel?: string | null;

  /**
   * Campaigns currently inside their learning period, with days remaining
   * (Req 19.5). Empty/undefined hides the learning-period section.
   */
  learningPeriods?: readonly HostingLearningPeriodItem[];

  /** Resolved AI_Personality machine value for each hosted Campaign (Req 50.11). */
  resolvedPersonalities: readonly (AiPersonality | string)[];
}

function Figure({
  icon,
  label,
  value,
  tone,
}: {
  icon: React.ReactNode;
  label: string;
  value: number;
  tone?: string;
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white px-4 py-3">
      <div className="flex items-center gap-1.5 text-slate-400">
        {icon}
        <span className="text-xs font-medium">{label}</span>
      </div>
      <p className={cn('mt-1.5 text-2xl font-bold tabular-nums', tone ?? 'text-slate-900')}>{value}</p>
    </div>
  );
}

function EstimateCard({ label, value }: { label: string; value?: string | null }) {
  const { hasBaseline, text } = selectEstimateDisplay(value);
  return (
    <div className="rounded-xl border border-slate-200 bg-white px-4 py-3">
      <p className="text-xs font-medium text-slate-500">{label}</p>
      {hasBaseline ? (
        <p className="mt-1 text-lg font-semibold text-slate-900">{text}</p>
      ) : (
        <p className="mt-1 text-sm text-slate-400">{text}</p>
      )}
    </div>
  );
}

export function HostingOverview({
  data,
  onSelectPersonality,
  className,
}: {
  data: HostingOverviewData;
  /** Clicking a 人格分布 count filters the list to that personality (Req 50.13). */
  onSelectPersonality?: (personality: AiPersonality) => void;
  className?: string;
}) {
  const distribution = computePersonalityDistribution(data.resolvedPersonalities);

  return (
    <div className={cn('space-y-4', className)}>
      {/* First screen — exactly the five Req 50.10 figures */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        <Figure icon={<Bot size={13} />} label="托管活动" value={data.hostedCount} />
        <Figure icon={<Sparkles size={13} />} label="今日决策" value={data.todayDecisions} />
        <Figure
          icon={<Clock size={13} />}
          label="等待审批"
          value={data.awaitingApproval}
          tone={data.awaitingApproval > 0 ? 'text-amber-600' : undefined}
        />
        <Figure
          icon={<CheckCircle2 size={13} />}
          label="Amazon生效"
          value={data.amazonEffective}
          tone="text-emerald-600"
        />
        <Figure
          icon={<XCircle size={13} />}
          label="失败"
          value={data.failed}
          tone={data.failed > 0 ? 'text-red-600' : undefined}
        />
      </div>

      {/* Trend area — budget delta + the two honest estimates (Req 50.11/50.12) */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="rounded-xl border border-slate-200 bg-white px-4 py-3">
          <p className="text-xs font-medium text-slate-500">今日预算增减</p>
          <p className="mt-1 text-lg font-semibold text-slate-900">{data.todayBudgetDelta ?? '—'}</p>
        </div>
        <EstimateCard label="AI 带来的销售变化（估算）" value={data.estimatedSalesChange} />
        <EstimateCard
          label={resolveSavingsLabel(data.estimatedSavingsLabel)}
          value={data.estimatedSpendSavings}
        />
      </div>

      {/* Learning-period status — campaigns still accumulating data (Req 19.5) */}
      {data.learningPeriods && data.learningPeriods.length > 0 && (
        <div className="rounded-xl border border-slate-200 bg-white p-4">
          <div className="mb-3 flex items-center gap-1.5">
            <GraduationCap size={14} className="text-violet-500" />
            <p className="text-sm font-semibold text-slate-900">学习期</p>
            <span className="text-xs text-slate-400">
              （{data.learningPeriods.length} 个广告活动学习中）
            </span>
          </div>
          <ul className="space-y-2">
            {data.learningPeriods.map((lp) => {
              const total = lp.totalDays > 0 ? lp.totalDays : 1;
              const remaining = Math.max(0, Math.min(lp.daysRemaining, total));
              const pct = Math.round(((total - remaining) / total) * 100);
              return (
                <li key={lp.campaignId} className="space-y-1">
                  <div className="flex items-center justify-between gap-3">
                    <span
                      className="truncate text-sm text-slate-700"
                      title={lp.campaignName ?? lp.campaignId}
                    >
                      {lp.campaignName || lp.campaignId}
                    </span>
                    <span className="shrink-0 text-xs font-medium tabular-nums text-slate-500">
                      剩余 {remaining} / {lp.totalDays} 天
                    </span>
                  </div>
                  <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
                    <div
                      className="h-full rounded-full bg-violet-400"
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      {/* AI人格分布 (Req 50.11/50.13) */}
      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <p className="mb-3 text-sm font-semibold text-slate-900">AI人格分布</p>
        <div className="grid grid-cols-3 gap-2">
          {distribution.map((entry) => {
            const meta = PERSONALITY_META[entry.personality];
            const interactive = !!onSelectPersonality;
            const inner = (
              <>
                <span className={cn('inline-flex h-7 w-7 items-center justify-center rounded-lg border', meta.accentClass)}>
                  <PersonalityIcon personality={entry.personality} size={14} />
                </span>
                <span className="text-2xl font-bold tabular-nums text-slate-900">{entry.count}</span>
                <span className="text-xs text-slate-500">{entry.display}</span>
              </>
            );
            return interactive ? (
              <button
                key={entry.personality}
                type="button"
                onClick={() => onSelectPersonality!(entry.personality)}
                aria-label={`筛选 ${entry.display} 的广告活动（${entry.count} 个）`}
                className="flex flex-col items-center gap-1 rounded-lg border border-slate-200 py-3 transition-colors hover:bg-slate-50"
              >
                {inner}
              </button>
            ) : (
              <div
                key={entry.personality}
                className="flex flex-col items-center gap-1 rounded-lg border border-slate-200 py-3"
              >
                {inner}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export default HostingOverview;
