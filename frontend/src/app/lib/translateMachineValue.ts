// AdPilot AI — Machine-value → display-copy translation
//
// The backend contract returns only STABLE MACHINE values for the four
// advertising enums (AI_Hosting_Status, AI_Personality, Optimization_Goal,
// Object_Status) and never Chinese display strings (Req 48.4, 14.7). The
// frontend is solely responsible for translating those machine values into
// the fixed display copy (Req 48.5, 3.6).
//
// `translateMachineValue` is a PURE, TOTAL function: every canonical machine
// value emitted by the backend (tasks 15.2 / 15.5) maps to a defined display
// string, and the mapping is total over the enum. To stay total over ALL
// inputs — including unexpected or future machine values — an unknown value is
// echoed back verbatim as a safe fallback rather than throwing or returning
// an empty/undefined string, so the UI never renders a blank cell and a new
// backend value degrades to its raw (still human-legible) machine token.
//
// Validates: Requirements 48.5, 3.6 (see Property 35, exercised by task 21.5).

/** The four advertising machine-value enums the backend can return. */
export type MachineEnumKind =
  | 'AI_Hosting_Status'
  | 'AI_Personality'
  | 'Optimization_Goal'
  | 'Object_Status';

/** Canonical AI_Hosting_Status machine values (Req 48.4). */
export const AI_HOSTING_STATUS_VALUES = ['hosted', 'not_hosted'] as const;
export type AiHostingStatus = (typeof AI_HOSTING_STATUS_VALUES)[number];

/** Canonical AI_Personality machine values (Req 48.4, 49.1). */
export const AI_PERSONALITY_VALUES = ['conservative', 'balanced', 'aggressive'] as const;
export type AiPersonality = (typeof AI_PERSONALITY_VALUES)[number];

/** Canonical Optimization_Goal machine values (Req 48.4). */
export const OPTIMIZATION_GOAL_VALUES = [
  'profit_first',
  'sales_growth',
  'rank',
  'clearance',
] as const;
export type OptimizationGoal = (typeof OPTIMIZATION_GOAL_VALUES)[number];

/** Canonical Object_Status machine values (Req 48.4). */
export const OBJECT_STATUS_VALUES = ['enabled', 'paused', 'archived'] as const;
export type ObjectStatus = (typeof OBJECT_STATUS_VALUES)[number];

/**
 * AI_Hosting_Status → 托管中 / 未托管 (AI托管状态, Req 48.2/48.5). The retired
 * "入格 / 已入格" vocabulary is intentionally never used here (Req 48.1).
 */
export const AI_HOSTING_STATUS_DISPLAY: Record<AiHostingStatus, string> = {
  hosted: '托管中',
  not_hosted: '未托管',
};

/**
 * AI_Personality → fixed display names (Req 48.5, 49.1). The alternate name
 * 稳健型 is intentionally NOT used for `conservative`.
 */
export const AI_PERSONALITY_DISPLAY: Record<AiPersonality, string> = {
  conservative: '常规型',
  balanced: '平衡型',
  aggressive: '激进型',
};

/** Optimization_Goal → display copy (优化目标, Req 48.2/48.5). */
export const OPTIMIZATION_GOAL_DISPLAY: Record<OptimizationGoal, string> = {
  profit_first: '利润优先',
  sales_growth: '销量增长',
  rank: '排名提升',
  clearance: '清仓甩货',
};

/** Object_Status → display copy for the canonical enabled/paused/archived vocabulary. */
export const OBJECT_STATUS_DISPLAY: Record<ObjectStatus, string> = {
  enabled: '启用',
  paused: '暂停',
  archived: '已归档',
};

/**
 * The complete per-enum machine-value → display-copy tables. Exported so the
 * totality property test (task 21.5) can enumerate every canonical value of
 * every enum and assert each maps to a defined display string.
 */
export const MACHINE_VALUE_DISPLAY: Record<MachineEnumKind, Record<string, string>> = {
  AI_Hosting_Status: AI_HOSTING_STATUS_DISPLAY,
  AI_Personality: AI_PERSONALITY_DISPLAY,
  Optimization_Goal: OPTIMIZATION_GOAL_DISPLAY,
  Object_Status: OBJECT_STATUS_DISPLAY,
};

/**
 * The canonical machine-value set for each enum, exported so the totality
 * property test can generate inputs over exactly the values the backend emits.
 */
export const MACHINE_VALUE_SETS: Record<MachineEnumKind, readonly string[]> = {
  AI_Hosting_Status: AI_HOSTING_STATUS_VALUES,
  AI_Personality: AI_PERSONALITY_VALUES,
  Optimization_Goal: OPTIMIZATION_GOAL_VALUES,
  Object_Status: OBJECT_STATUS_VALUES,
};

/**
 * Translate a backend machine value into its display copy for the given enum.
 *
 * PURE and TOTAL: for every canonical machine value of `kind` a defined,
 * non-empty display string is returned. For an unknown/unexpected value
 * (including `null`/`undefined`) the input is echoed back verbatim — for
 * `null`/`undefined` an empty string — as a safe, never-throwing fallback so
 * the mapping is total over all inputs and the UI never breaks on a future
 * backend value.
 *
 * @param kind  Which advertising enum the value belongs to.
 * @param value The machine value returned by the backend.
 * @returns The display copy, or the raw value as a fallback for unknown input.
 */
export function translateMachineValue(
  kind: MachineEnumKind,
  value: string | null | undefined,
): string {
  if (value == null) {
    return '';
  }
  const table = MACHINE_VALUE_DISPLAY[kind];
  const display = Object.prototype.hasOwnProperty.call(table, value)
    ? table[value]
    : undefined;
  return display !== undefined ? display : value;
}
