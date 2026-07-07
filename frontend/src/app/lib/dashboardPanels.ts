// AI advertising dashboard — pure panel helpers (Req 18.1, 18.2, 18.6)
//
// These functions hold the dashboard's pure presentation logic (date-range
// resolution, Sales Overview tile derivation, delta-trend direction, and
// per-panel empty detection) so they can be unit-tested without rendering the
// React page. The DashboardPage component formats and renders the shapes
// produced here.

// ─── Date range ──────────────────────────────────────────────────────

export const DATE_RANGE_OPTIONS = ['今天', '昨天', '近7天', '近30天'] as const;
export type DateRangeLabel = (typeof DATE_RANGE_OPTIONS)[number];

const DAY_MS = 86_400_000;

function toIsoDate(d: Date): string {
  return d.toISOString().split('T')[0];
}

/**
 * Resolves a header date-range label to an inclusive {startDate, endDate}
 * pair (yyyy-MM-dd). `now` is injectable for deterministic tests.
 */
export function getDashboardDateRange(
  range: string,
  now: Date = new Date(),
): { startDate: string; endDate: string } {
  const end = toIsoDate(now);
  switch (range) {
    case '今天':
      return { startDate: end, endDate: end };
    case '昨天': {
      const d = toIsoDate(new Date(now.getTime() - DAY_MS));
      return { startDate: d, endDate: d };
    }
    case '近30天':
      return { startDate: toIsoDate(new Date(now.getTime() - 30 * DAY_MS)), endDate: end };
    case '近7天':
    default:
      return { startDate: toIsoDate(new Date(now.getTime() - 7 * DAY_MS)), endDate: end };
  }
}

// ─── Granularity ─────────────────────────────────────────────────────

export const GRANULARITY_OPTIONS = [
  { key: 'day', label: '日' },
  { key: 'week', label: '周' },
  { key: 'month', label: '月' },
] as const;
export type Granularity = (typeof GRANULARITY_OPTIONS)[number]['key'];

// ─── Delta trend direction ───────────────────────────────────────────

export type Trend = 'up' | 'down' | 'flat';

/** Maps a signed percentage delta to a trend direction. */
export function deltaTrend(deltaPct: number | null | undefined): Trend {
  const v = Number(deltaPct ?? 0);
  if (!Number.isFinite(v) || v === 0) return 'flat';
  return v > 0 ? 'up' : 'down';
}

// ─── Sales Overview tiles (Req 18.1) ─────────────────────────────────

export type MetricFormat = 'currency' | 'number' | 'percent';

export interface MetricDelta {
  value?: number | null;
  previous?: number | null;
  delta?: number | null;
  deltaPct?: number | null;
}

export interface SalesOverviewVo {
  currency?: string | null;
  totalSales?: MetricDelta | null;
  adSpend?: MetricDelta | null;
  adSales?: MetricDelta | null;
  adOrders?: MetricDelta | null;
  tacos?: MetricDelta | null;
  acos?: MetricDelta | null;
}

export interface OverviewTile {
  key: string;
  label: string;
  rawValue: number;
  previousValue: number;
  format: MetricFormat;
  deltaPct: number;
  trend: Trend;
  /** Lower-is-better metrics (spend / TACoS / ACoS) invert delta coloring. */
  inverse: boolean;
}

const num = (v: number | null | undefined): number => {
  const n = Number(v ?? 0);
  return Number.isFinite(n) ? n : 0;
};

/**
 * Builds the six Sales Overview tiles in the order required by Req 18.1:
 * 总销售额, 广告花费, 广告销售额, 广告订单数, TACoS, ACoS — each with its
 * value, period-over-period delta percentage, and trend direction.
 */
export function buildSalesOverviewTiles(vo: SalesOverviewVo | null | undefined): OverviewTile[] {
  const o = vo ?? {};
  const tile = (
    key: string,
    label: string,
    metric: MetricDelta | null | undefined,
    format: MetricFormat,
    inverse: boolean,
  ): OverviewTile => {
    const deltaPct = num(metric?.deltaPct);
    return {
      key,
      label,
      rawValue: num(metric?.value),
      previousValue: num(metric?.previous),
      format,
      deltaPct,
      trend: deltaTrend(deltaPct),
      inverse,
    };
  };

  return [
    tile('totalSales', '总销售额', o.totalSales, 'currency', false),
    tile('adSpend', '广告花费', o.adSpend, 'currency', true),
    tile('adSales', '广告销售额', o.adSales, 'currency', false),
    tile('adOrders', '广告订单数', o.adOrders, 'number', false),
    tile('tacos', 'TACoS', o.tacos, 'percent', true),
    tile('acos', 'ACoS', o.acos, 'percent', true),
  ];
}

/** True when the Sales Overview carries no non-zero metric (empty-state, Req 18.7). */
export function isSalesOverviewEmpty(vo: SalesOverviewVo | null | undefined): boolean {
  if (!vo) return true;
  return buildSalesOverviewTiles(vo).every((t) => t.rawValue === 0 && t.deltaPct === 0);
}

// ─── Sales trend (Req 18.2) ──────────────────────────────────────────

export interface TrendPoint {
  period: string;
  spend?: number | null;
  sales?: number | null;
  totalSales?: number | null;
}

export interface SalesTrendVo {
  granularity?: string | null;
  currency?: string | null;
  points?: TrendPoint[] | null;
}

/** True when the trend has no points or every point is all-zero (empty-state). */
export function isTrendEmpty(vo: SalesTrendVo | null | undefined): boolean {
  const points = vo?.points;
  if (!Array.isArray(points) || points.length === 0) return true;
  return points.every((p) => num(p.spend) === 0 && num(p.sales) === 0);
}

// ─── AI Actions (Req 18.3) ───────────────────────────────────────────

export interface AiActionVo {
  key: string;
  label: string;
  count?: number | null;
  affectedCampaigns?: number | null;
  impactValue?: number | null;
  impactLabel?: string | null;
}

export interface AiActionsVo {
  currency?: string | null;
  actions?: AiActionVo[] | null;
}

/** True when no AI action ran over the period (every count is 0). */
export function isAiActionsEmpty(vo: AiActionsVo | null | undefined): boolean {
  const actions = vo?.actions;
  if (!Array.isArray(actions) || actions.length === 0) return true;
  return actions.every((a) => num(a.count) === 0);
}

// ─── AI Usage (Req 18.4) ─────────────────────────────────────────────

export interface AiUsageVo {
  currency?: string | null;
  coveragePercent?: number | null;
  aiAdSpend?: number | null;
  aiAdSales?: number | null;
}

/** True when AI usage carries no coverage, spend, or sales. */
export function isAiUsageEmpty(vo: AiUsageVo | null | undefined): boolean {
  if (!vo) return true;
  return num(vo.coveragePercent) === 0 && num(vo.aiAdSpend) === 0 && num(vo.aiAdSales) === 0;
}

// ─── AI Notifications summary (Req 18.5) ─────────────────────────────

export interface AiNotificationCategoryVo {
  key: string;
  label: string;
  pendingCount?: number | null;
}

export interface AiNotificationsSummaryVo {
  available?: boolean;
  categories?: AiNotificationCategoryVo[] | null;
}

/** The four standard notification categories shown even when counts are absent. */
export const NOTIFICATION_CATEGORY_FALLBACK: AiNotificationCategoryVo[] = [
  { key: 'core_ops_attention', label: '广告运营核心关注', pendingCount: 0 },
  { key: 'one_click_optimization', label: '广告活动一键优化', pendingCount: 0 },
  { key: 'discover_high_potential', label: '发现高潜广告活动', pendingCount: 0 },
  { key: 'target_correction_pending', label: 'AI目标修正待确认', pendingCount: 0 },
];

/**
 * Always yields four categories so the panel renders a complete summary even
 * before the ai_notifications table is provisioned (Req 18.5).
 */
export function resolveNotificationCategories(
  vo: AiNotificationsSummaryVo | null | undefined,
): AiNotificationCategoryVo[] {
  const cats = vo?.categories;
  if (Array.isArray(cats) && cats.length > 0) return cats;
  return NOTIFICATION_CATEGORY_FALLBACK;
}
