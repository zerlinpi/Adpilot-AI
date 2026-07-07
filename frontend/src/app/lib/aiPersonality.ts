// AdPilot AI — AI_Personality UI logic (pure, render-independent)
//
// Pure helpers backing the AI hosting UI (Req 49/50): the three-segment
// personality control, its 人格影响预览 (impact preview), the advertising-list
// RESOLVED-personality column with its inherited/override indicator, and the
// AI人格分布 (personality distribution) breakdown on the overview.
//
// IMPORTANT (Req 49.6): the concrete per-personality numeric controls
// (Personality_Policy) are a CONFIGURABLE BACKEND value and are NEVER hardcoded
// in the frontend. Every numeric figure rendered by the preview is derived from
// a {@link PersonalityPolicy} object supplied by the backend; this module only
// owns the qualitative, descriptive copy (core goal, tone, icon, labels) and
// the pure derivation/resolution functions. When no policy is supplied the
// preview degrades to the descriptive copy and an explicit "loading" note
// rather than inventing magnitudes.

import {
  AI_PERSONALITY_VALUES,
  AI_PERSONALITY_DISPLAY,
  type AiPersonality,
} from './translateMachineValue';

export { AI_PERSONALITY_VALUES, AI_PERSONALITY_DISPLAY };
export type { AiPersonality };

/** Set membership test for a canonical AI_Personality machine value. */
export function isAiPersonality(value: unknown): value is AiPersonality {
  return typeof value === 'string' && (AI_PERSONALITY_VALUES as readonly string[]).includes(value);
}

// ─── Personality_Policy (mirrors the backend personality_policies row) ───────
//
// Mirrors the configurable backend Personality_Policy table of Req 49.5. All
// ratio fields are decimal ratios (0.10 == 10%), matching the backend contract.

export interface PersonalityPolicy {
  personality: AiPersonality;
  ruleVersion?: string;
  minClicks?: number;
  minOrders?: number;
  lookbackDays?: number;
  minConversionRate?: number;
  negativeConfidenceThreshold?: number;
  acosToleranceRatio?: number;
  approvalBidChangeRatio?: number;
  approvalBudgetChangeRatio?: number;
  maxBidIncreaseRatio?: number;
  maxBidDecreaseRatio?: number;
  maxDailyBudgetIncreaseRatio?: number;
  adjustmentCooldownHours?: number;
  exploreBudgetRatioMin?: number;
  exploreBudgetRatioMax?: number;
  keywordExpansionMode?: string;
  negativeKeywordMode?: string;
  keywordConfidenceThreshold?: number;
  maxKeywordsAddedPerDay?: number;
  maxNegativesAddedPerDay?: number;
}

/** Backend-supplied policies keyed by personality machine value. */
export type PersonalityPolicyMap = Partial<Record<AiPersonality, PersonalityPolicy>>;

// ─── Descriptive (qualitative) per-segment meta ──────────────────────────────
//
// This copy describes WHAT each personality is for; it never claims any concrete
// numeric limit (those come from the backend policy). The icon kind drives a
// shape — not color alone — so the AI人格 column is legible without color
// (Req 50.8): shield = conservative, scale = balanced, trend = aggressive.

export type PersonalityIconKind = 'shield' | 'scale' | 'trend';

export const PERSONALITY_ICON: Record<AiPersonality, PersonalityIconKind> = {
  conservative: 'shield',
  balanced: 'scale',
  aggressive: 'trend',
};

export interface PersonalityMeta {
  personality: AiPersonality;
  /** Display name (常规型 / 平衡型 / 激进型). */
  display: string;
  icon: PersonalityIconKind;
  /** The personality's core goal (Req 50.4 "core goal"). */
  coreGoal: string;
  /** A one-line tone summary shown under the segment label. */
  tagline: string;
  /** Tailwind classes for the segment accent (paired with the icon, never color-only). */
  accentClass: string;
  /** Subtle background used for the resolved-personality chip. */
  chipClass: string;
}

export const PERSONALITY_META: Record<AiPersonality, PersonalityMeta> = {
  conservative: {
    personality: 'conservative',
    display: AI_PERSONALITY_DISPLAY.conservative,
    icon: 'shield',
    coreGoal: '稳健控制 ACoS，优先保护利润',
    tagline: '小步、低频调整，更多决策需人工审批',
    accentClass: 'border-emerald-500 bg-emerald-50 text-emerald-700',
    chipClass: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  },
  balanced: {
    personality: 'balanced',
    display: AI_PERSONALITY_DISPLAY.balanced,
    icon: 'scale',
    coreGoal: '在目标 ACoS 与增长之间取得平衡',
    tagline: '中等幅度与频率，关键变动需审批',
    accentClass: 'border-blue-500 bg-blue-50 text-blue-700',
    chipClass: 'bg-blue-50 text-blue-700 border-blue-200',
  },
  aggressive: {
    personality: 'aggressive',
    display: AI_PERSONALITY_DISPLAY.aggressive,
    icon: 'trend',
    coreGoal: '积极抢量与抢排名，容忍更高 ACoS',
    tagline: '更大幅度、更高频率，自动化程度最高',
    accentClass: 'border-rose-500 bg-rose-50 text-rose-700',
    chipClass: 'bg-rose-50 text-rose-700 border-rose-200',
  },
};

// ─── Mode labels (keyword expansion / negative keyword behavior) ─────────────

const KEYWORD_MODE_LABEL: Record<string, string> = {
  off: '不自动加词',
  suggest: '仅建议加词',
  auto: '自动加词',
};

const NEGATIVE_MODE_LABEL: Record<string, string> = {
  suggest: '仅建议否定词',
  approval: '否定词需审批',
  auto: '自动添加否定词',
};

/** Format a decimal ratio (0.10) as a percentage string ("10%"). */
export function formatRatioPercent(ratio: number | null | undefined, decimals = 0): string {
  if (ratio == null || !Number.isFinite(ratio)) return '—';
  return `${(ratio * 100).toFixed(decimals)}%`;
}

// ─── 人格影响预览 (impact preview) derivation (Req 50.4 / 50.5) ───────────────

export interface PreviewItem {
  /** Stable key for React lists. */
  key: string;
  label: string;
  value: string;
  /** When true the value is a fallback/loading note rather than a concrete figure. */
  pending?: boolean;
}

/**
 * Derive the concrete "人格影响预览" rows for a personality. Every numeric figure
 * is taken from the backend-supplied {@link PersonalityPolicy}; when no policy is
 * available the magnitude/frequency/keyword/approval rows degrade to an explicit
 * "loading from backend" note so the frontend never fabricates a limit (Req 49.6).
 *
 * Always returns, in a stable order, the five facets called out by Req 50.4:
 * core goal, expected action frequency, maximum adjustment magnitude, auto
 * keyword & negative behavior, and the approval requirement.
 */
export function derivePersonalityPreview(
  personality: AiPersonality,
  policy?: PersonalityPolicy | null,
): PreviewItem[] {
  const meta = PERSONALITY_META[personality];
  const pendingNote = '具体数值由后端策略提供，加载中…';

  // Expected action frequency from the adjustment cooldown.
  const frequency =
    policy?.adjustmentCooldownHours != null && Number.isFinite(policy.adjustmentCooldownHours)
      ? `每 ${policy.adjustmentCooldownHours} 小时最多调整一次`
      : pendingNote;

  // Maximum adjustment magnitude from the bid/budget increase limits.
  let magnitude = pendingNote;
  if (policy?.maxBidIncreaseRatio != null || policy?.maxDailyBudgetIncreaseRatio != null) {
    const parts: string[] = [];
    if (policy.maxBidIncreaseRatio != null) {
      parts.push(`竞价单次最高 ±${formatRatioPercent(policy.maxBidIncreaseRatio)}`);
    }
    if (policy.maxDailyBudgetIncreaseRatio != null) {
      parts.push(`日预算单次最高 +${formatRatioPercent(policy.maxDailyBudgetIncreaseRatio)}`);
    }
    magnitude = parts.join('，');
  }

  // Auto keyword / negative behavior from the V3 modes.
  let keywordBehavior = pendingNote;
  if (policy?.keywordExpansionMode || policy?.negativeKeywordMode) {
    const kw = policy.keywordExpansionMode
      ? KEYWORD_MODE_LABEL[policy.keywordExpansionMode] ?? policy.keywordExpansionMode
      : null;
    const neg = policy.negativeKeywordMode
      ? NEGATIVE_MODE_LABEL[policy.negativeKeywordMode] ?? policy.negativeKeywordMode
      : null;
    keywordBehavior = [kw, neg].filter(Boolean).join(' · ');
  }

  // Approval requirement from the approval ratios.
  let approval = pendingNote;
  if (policy?.approvalBidChangeRatio != null || policy?.approvalBudgetChangeRatio != null) {
    const parts: string[] = [];
    if (policy.approvalBidChangeRatio != null) {
      parts.push(`竞价变动 ≥ ${formatRatioPercent(policy.approvalBidChangeRatio)}`);
    }
    if (policy.approvalBudgetChangeRatio != null) {
      parts.push(`预算变动 ≥ ${formatRatioPercent(policy.approvalBudgetChangeRatio)}`);
    }
    approval = `${parts.join(' 或 ')} 时需人工审批`;
  }

  return [
    { key: 'coreGoal', label: '核心目标', value: meta.coreGoal },
    { key: 'frequency', label: '预期调整频率', value: frequency, pending: frequency === pendingNote },
    { key: 'magnitude', label: '单次最大调整幅度', value: magnitude, pending: magnitude === pendingNote },
    { key: 'keyword', label: '自动加词 / 否定', value: keywordBehavior, pending: keywordBehavior === pendingNote },
    { key: 'approval', label: '审批要求', value: approval, pending: approval === pendingNote },
  ];
}

// ─── Resolved AI_Personality + inheritance source (Req 49.2/49.3, 50.7) ──────

export type PersonalitySource = 'override' | 'goal' | 'store' | 'fallback';

export interface ResolvedPersonality {
  personality: AiPersonality;
  source: PersonalitySource;
}

/** The system fallback personality used when nothing else resolves (Req 49.3). */
export const FALLBACK_PERSONALITY: AiPersonality = 'balanced';

export interface ResolvePersonalityInput {
  /** Campaign-level override (Campaign_Personality), if any. */
  campaignPersonality?: string | null;
  /** The associated Goal's default personality, if any. */
  goalPersonality?: string | null;
  /** The Store_Default_Personality, if any. */
  storePersonality?: string | null;
}

/**
 * Resolve a Campaign's effective AI_Personality with the Req 49.2/49.3
 * precedence — Campaign override > Goal default > Store default > the system
 * fallback `balanced` — and report WHICH level the value came from so the list
 * column can render the inherited-vs-override indicator (Req 50.7). A value that
 * is not one of the three canonical machine values at any level is treated as
 * "not set" and falls through to the next level (never guessed).
 */
export function resolveCampaignPersonality(input: ResolvePersonalityInput): ResolvedPersonality {
  if (isAiPersonality(input.campaignPersonality)) {
    return { personality: input.campaignPersonality, source: 'override' };
  }
  if (isAiPersonality(input.goalPersonality)) {
    return { personality: input.goalPersonality, source: 'goal' };
  }
  if (isAiPersonality(input.storePersonality)) {
    return { personality: input.storePersonality, source: 'store' };
  }
  return { personality: FALLBACK_PERSONALITY, source: 'fallback' };
}

/** Whether a resolved personality is a Campaign-level override (vs inherited). */
export function isOverride(source: PersonalitySource): boolean {
  return source === 'override';
}

/** Short human label for where a resolved personality was inherited from. */
export function inheritanceLabel(source: PersonalitySource): string {
  switch (source) {
    case 'override':
      return '活动级覆盖';
    case 'goal':
      return '继承自目标';
    case 'store':
      return '继承自店铺默认';
    case 'fallback':
      return '系统默认';
    default: {
      const _exhaustive: never = source;
      return _exhaustive;
    }
  }
}

// ─── AI人格分布 (personality distribution, Req 50.11) ─────────────────────────

export interface PersonalityDistributionEntry {
  personality: AiPersonality;
  display: string;
  count: number;
}

/**
 * Count Campaigns per resolved AI_Personality, returned in the canonical
 * conservative → balanced → aggressive order so the overview "AI人格分布"
 * breakdown is stable. Accepts the resolved personality machine value of each
 * Campaign; non-canonical values are ignored (they cannot occur for a resolved
 * value, which always falls back to `balanced`).
 */
export function computePersonalityDistribution(
  resolvedPersonalities: readonly (AiPersonality | string)[],
): PersonalityDistributionEntry[] {
  const counts: Record<AiPersonality, number> = {
    conservative: 0,
    balanced: 0,
    aggressive: 0,
  };
  for (const value of resolvedPersonalities) {
    if (isAiPersonality(value)) counts[value] += 1;
  }
  return AI_PERSONALITY_VALUES.map((personality) => ({
    personality,
    display: AI_PERSONALITY_DISPLAY[personality],
    count: counts[personality],
  }));
}
