// AdPilot AI — First-run onboarding wizard
//
// Pure step-status computation for the guided getting-started wizard. Kept free
// of React/DOM so the "which steps are done / which is current" logic is unit
// testable in isolation from live data (connections / products / sync records /
// goals / hosted campaigns).
//
// The wizard walks a new user through three steps, each computed from REAL data
// (never hardcoded):
//   1. connect — 连接店铺与广告账户 (done when ≥1 platform connection is connected)
//   2. sync    — 同步数据 (done when ≥1 product OR sync record exists)
//   3. goal    — 创建广告目标 / 启用 AI 托管 (done when ≥1 goal OR hosted campaign)

/** The three onboarding step identities, in order. */
export type OnboardingStepId = 'connect' | 'sync' | 'goal';

/** A step is exactly one of: completed, the current focus, or not yet reached. */
export type OnboardingStepStatus = 'done' | 'current' | 'pending';

/** The live data the step-status computation reads (all optional/defensive). */
export interface OnboardingData {
  /** Platform connections for the user/store (from `fetchPlatformConnections`). */
  connections?: unknown[];
  /** Products for the active store (from `fetchProducts`). */
  products?: unknown[];
  /** Sync/performance records for the active store (from sync jobs), optional. */
  syncRecords?: unknown[];
  /** Advertising goals for the active store (from `fetchGoals`). */
  goals?: unknown[];
  /** Hosted/AI-managed campaigns, optional. */
  hostedCampaigns?: unknown[];
}

/** A fully-resolved step ready to render. */
export interface OnboardingStep {
  id: OnboardingStepId;
  title: string;
  description: string;
  /** Route the primary action navigates to. */
  href: string;
  /** Label for the primary action button. */
  actionLabel: string;
  done: boolean;
  status: OnboardingStepStatus;
}

/** Connection status values the backend reports as "ready / connected". */
const CONNECTED_STATUSES = ['active', 'connected', 'authorized', 'ok', 'healthy'];

function normalizeStatus(value: unknown): string {
  return String(value ?? '').trim().toLowerCase();
}

function asArray(value: unknown[] | undefined): unknown[] {
  return Array.isArray(value) ? value : [];
}

/** True when a single platform connection is in a connected/ready state. */
export function isConnectionConnected(connection: any): boolean {
  const status = normalizeStatus(
    connection?.status ?? connection?.connectionStatus ?? connection?.state,
  );
  return CONNECTED_STATUSES.includes(status);
}

/** Static metadata (copy + navigation) for each step, in display order. */
export const ONBOARDING_STEP_META: Record<
  OnboardingStepId,
  Pick<OnboardingStep, 'id' | 'title' | 'description' | 'href' | 'actionLabel'>
> = {
  connect: {
    id: 'connect',
    title: '连接店铺与广告账户',
    description: '连接亚马逊店铺与广告账户，AdPilot 才能读取真实的广告、订单与商品数据。',
    href: '/data-sync',
    actionLabel: '去连接',
  },
  sync: {
    id: 'sync',
    title: '同步数据',
    description: '同步商品与广告表现数据，作为后续分析、托管与优化的基础。',
    href: '/platform-sync',
    actionLabel: '去同步',
  },
  goal: {
    id: 'goal',
    title: '创建广告目标 / 启用 AI 托管',
    description: '设定广告目标或开启 AI 托管，让系统按目标自动优化广告投放。',
    href: '/goals',
    actionLabel: '创建目标',
  },
};

/** Ordered list of step ids. */
export const ONBOARDING_STEP_ORDER: OnboardingStepId[] = ['connect', 'sync', 'goal'];

/**
 * Compute the per-step completion booleans from live data. A step is "done" only
 * when the corresponding real data exists — nothing is hardcoded.
 */
export function computeStepCompletion(
  data: OnboardingData,
): Record<OnboardingStepId, boolean> {
  const connect = asArray(data.connections).some((c) => isConnectionConnected(c));
  const sync = asArray(data.products).length > 0 || asArray(data.syncRecords).length > 0;
  const goal =
    asArray(data.goals).length > 0 || asArray(data.hostedCampaigns).length > 0;
  return { connect, sync, goal };
}

/**
 * Resolve the ordered steps with their status. The "current" pointer is the
 * FIRST incomplete step; every step before it that is done shows "done", and
 * later incomplete steps show "pending". When all steps are done there is no
 * current step (all "done").
 */
export function computeOnboardingSteps(data: OnboardingData): OnboardingStep[] {
  const completion = computeStepCompletion(data);
  const firstIncomplete = ONBOARDING_STEP_ORDER.find((id) => !completion[id]) ?? null;

  return ONBOARDING_STEP_ORDER.map((id) => {
    const done = completion[id];
    const status: OnboardingStepStatus = done
      ? 'done'
      : id === firstIncomplete
        ? 'current'
        : 'pending';
    return { ...ONBOARDING_STEP_META[id], done, status };
  });
}

/** True when every onboarding step is complete. */
export function isOnboardingComplete(data: OnboardingData): boolean {
  const completion = computeStepCompletion(data);
  return ONBOARDING_STEP_ORDER.every((id) => completion[id]);
}

/**
 * The id of the current (first incomplete) step, or null when all are complete.
 * Handy for advancing the wizard's focus pointer.
 */
export function currentOnboardingStepId(data: OnboardingData): OnboardingStepId | null {
  const completion = computeStepCompletion(data);
  return ONBOARDING_STEP_ORDER.find((id) => !completion[id]) ?? null;
}
