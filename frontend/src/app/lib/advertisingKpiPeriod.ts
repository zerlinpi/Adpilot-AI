// AdPilot AI — KPI period-comparison helper (advertising-workspace-rework)
// Requirements 30.1, 30.3, 30.4 (and the day-boundary rule of 51.13/51.14).
//
// This module owns the PURE, side-effect-free computation that resolves the
// KpiPanel's default comparison: the "last N days" current window and the
// "prior N days" comparison window, with every date boundary computed in the
// Active_Store's Marketplace_Timezone (Req 30.4).
//
// Why a dedicated module: the KpiPanel renders these dates, but the date math
// must be testable in isolation and must NEVER silently fall back to the
// server/browser timezone (Req 30.4, 51.14). Keeping it pure lets the
// round-trip / boundary behavior be property-tested without React or a router.
//
// Day boundaries are calendar dates (`YYYY-MM-DD`, inclusive on both ends),
// matching how the backend scopes a day in the Marketplace_Timezone. We derive
// "today" by formatting `now` in the target IANA zone, then do the windowing
// with plain calendar arithmetic so DST transitions never shift a boundary.

/** A closed (inclusive) calendar-date range expressed as `YYYY-MM-DD`. */
export interface KpiDateRange {
  start: string;
  end: string;
}

/**
 * A current window and the equal-length window immediately preceding it.
 * `current` is the last `windowDays` days; `prior` is the `windowDays` days
 * directly before `current` (Req 19.4 immediately-preceding equal-length
 * period, Req 30.3 default 7-vs-7).
 */
export interface KpiPeriodComparison {
  current: KpiDateRange;
  prior: KpiDateRange;
  /** The length, in days, of each window (current and prior are equal). */
  windowDays: number;
}

/** The default KPI window length: last 7 days vs prior 7 days (Req 30.3). */
export const DEFAULT_KPI_WINDOW_DAYS = 7;

/**
 * Thrown when a timezone-dependent date computation is requested without a
 * usable Marketplace_Timezone. The system MUST surface a configuration error
 * rather than defaulting to the server/browser timezone (Req 30.4, 51.14).
 */
export class MarketplaceTimezoneError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MarketplaceTimezoneError';
  }
}

// ── internal calendar arithmetic ─────────────────────────────────────────────
//
// We represent a calendar date as a UTC instant at noon. Anchoring at noon (not
// midnight) keeps a date stable under +/-1 day DST math, and because we only
// ever read the UTC Y/M/D back out, no zone offset leaks into the result.

function ymdToUtcNoon(ymd: string): Date {
  const [y, m, d] = ymd.split('-').map((p) => Number.parseInt(p, 10));
  return new Date(Date.UTC(y, m - 1, d, 12, 0, 0, 0));
}

function utcToYmd(date: Date): string {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** Add (or subtract, when negative) whole calendar days to a `YYYY-MM-DD`. */
export function addCalendarDays(ymd: string, days: number): string {
  const date = ymdToUtcNoon(ymd);
  date.setUTCDate(date.getUTCDate() + days);
  return utcToYmd(date);
}

/**
 * The number of inclusive days in a range, e.g. `2024-01-01..2024-01-07` -> 7.
 * Returns 0 when `end` precedes `start`.
 */
export function inclusiveDayCount(range: KpiDateRange): number {
  const start = ymdToUtcNoon(range.start).getTime();
  const end = ymdToUtcNoon(range.end).getTime();
  if (end < start) return 0;
  return Math.round((end - start) / 86_400_000) + 1;
}

function isUsableTimezone(tz: string | null | undefined): tz is string {
  return typeof tz === 'string' && tz.trim() !== '';
}

// ── timezone-aware "today" ───────────────────────────────────────────────────

/**
 * The current calendar date (`YYYY-MM-DD`) in the given IANA timezone.
 *
 * @throws {MarketplaceTimezoneError} when `timeZone` is empty/unset (never
 *   defaults to the server timezone, Req 30.4) or is not a valid IANA zone.
 */
export function todayInTimezone(
  timeZone: string | null | undefined,
  now: Date = new Date(),
): string {
  if (!isUsableTimezone(timeZone)) {
    throw new MarketplaceTimezoneError(
      'Marketplace_Timezone is not configured for the Active_Store; ' +
      'refusing to compute day boundaries in the server timezone.',
    );
  }
  let parts: Intl.DateTimeFormatPart[];
  try {
    const fmt = new Intl.DateTimeFormat('en-US', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    });
    parts = fmt.formatToParts(now);
  } catch {
    throw new MarketplaceTimezoneError(
      `Marketplace_Timezone "${timeZone}" is not a valid IANA timezone.`,
    );
  }
  const get = (type: string) =>
    parts.find((p) => p.type === type)?.value ?? '';
  const year = get('year');
  const month = get('month');
  const day = get('day');
  return `${year}-${month}-${day}`;
}

// ── period resolution ────────────────────────────────────────────────────────

/** Options for {@link resolveDefaultPeriodComparison}. */
export interface ResolvePeriodOptions {
  /** The instant to anchor "today" on; defaults to the current time. */
  now?: Date;
  /** Window length in days; defaults to {@link DEFAULT_KPI_WINDOW_DAYS} (7). */
  windowDays?: number;
}

/**
 * Build a current/prior comparison from an explicit anchor date and window.
 * Pure calendar math, no timezone involved — `anchorEnd` is already a resolved
 * `YYYY-MM-DD`. The current window is the `windowDays` days ending on
 * `anchorEnd` (inclusive); the prior window is the equal-length window directly
 * before it.
 */
export function periodComparisonEndingOn(
  anchorEnd: string,
  windowDays: number = DEFAULT_KPI_WINDOW_DAYS,
): KpiPeriodComparison {
  const days = Number.isInteger(windowDays) && windowDays >= 1 ? windowDays : DEFAULT_KPI_WINDOW_DAYS;
  const currentStart = addCalendarDays(anchorEnd, -(days - 1));
  const priorEnd = addCalendarDays(currentStart, -1);
  const priorStart = addCalendarDays(priorEnd, -(days - 1));
  return {
    current: { start: currentStart, end: anchorEnd },
    prior: { start: priorStart, end: priorEnd },
    windowDays: days,
  };
}

/**
 * Resolve the KpiPanel's default comparison — last `windowDays` days vs the
 * prior `windowDays` days — with all boundaries computed in `timeZone`
 * (Req 30.3, 30.4). "Today" (the inclusive end of the current window) is the
 * current date in the Marketplace_Timezone.
 *
 * @throws {MarketplaceTimezoneError} when `timeZone` is unset/invalid.
 */
export function resolveDefaultPeriodComparison(
  timeZone: string | null | undefined,
  options: ResolvePeriodOptions = {},
): KpiPeriodComparison {
  const { now = new Date(), windowDays = DEFAULT_KPI_WINDOW_DAYS } = options;
  const today = todayInTimezone(timeZone, now);
  return periodComparisonEndingOn(today, windowDays);
}

// ── period-over-period delta (Req 30.6 / 19) ─────────────────────────────────

/**
 * The signed period-over-period change ratio of `current` vs `prior`, e.g.
 * `0.25` for +25%. Returns `null` when no honest growth can be computed (no
 * prior baseline), matching the "not-available when no baseline" rule of
 * Requirement 19.4/19.5 — never fabricates a number from a zero baseline.
 */
export function periodOverPeriodChange(
  current: number,
  prior: number,
): number | null {
  if (!Number.isFinite(current) || !Number.isFinite(prior)) return null;
  if (prior === 0) return null;
  return (current - prior) / Math.abs(prior);
}
