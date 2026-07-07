// Unit + property tests for the pure KPI period-comparison helper
// (advertising-workspace-rework Req 30.1, 30.3, 30.4).

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  addCalendarDays,
  DEFAULT_KPI_WINDOW_DAYS,
  inclusiveDayCount,
  MarketplaceTimezoneError,
  periodComparisonEndingOn,
  periodOverPeriodChange,
  resolveDefaultPeriodComparison,
  todayInTimezone,
} from './advertisingKpiPeriod';

describe('addCalendarDays', () => {
  it('adds and subtracts whole days', () => {
    expect(addCalendarDays('2024-01-10', 5)).toBe('2024-01-15');
    expect(addCalendarDays('2024-01-10', -5)).toBe('2024-01-05');
  });

  it('crosses month and year boundaries', () => {
    expect(addCalendarDays('2024-01-31', 1)).toBe('2024-02-01');
    expect(addCalendarDays('2024-12-31', 1)).toBe('2025-01-01');
    expect(addCalendarDays('2024-03-01', -1)).toBe('2024-02-29'); // leap year
  });
});

describe('inclusiveDayCount', () => {
  it('counts both ends inclusively', () => {
    expect(inclusiveDayCount({ start: '2024-01-01', end: '2024-01-07' })).toBe(7);
    expect(inclusiveDayCount({ start: '2024-01-01', end: '2024-01-01' })).toBe(1);
  });

  it('returns 0 for an inverted range', () => {
    expect(inclusiveDayCount({ start: '2024-01-07', end: '2024-01-01' })).toBe(0);
  });
});

describe('periodComparisonEndingOn', () => {
  it('builds last-7 vs prior-7 by default', () => {
    const c = periodComparisonEndingOn('2024-01-10');
    expect(c.windowDays).toBe(DEFAULT_KPI_WINDOW_DAYS);
    expect(c.current).toEqual({ start: '2024-01-04', end: '2024-01-10' });
    expect(c.prior).toEqual({ start: '2023-12-28', end: '2024-01-03' });
  });

  it('falls back to the default window for invalid window sizes', () => {
    expect(periodComparisonEndingOn('2024-01-10', 0).windowDays).toBe(7);
    expect(periodComparisonEndingOn('2024-01-10', -3).windowDays).toBe(7);
    expect(periodComparisonEndingOn('2024-01-10', 2.5).windowDays).toBe(7);
  });
});

describe('todayInTimezone', () => {
  it('computes the local calendar date in the given zone', () => {
    // 2024-01-10T02:00Z is still 2024-01-09 in Los Angeles (UTC-8).
    const now = new Date('2024-01-10T02:00:00Z');
    expect(todayInTimezone('America/Los_Angeles', now)).toBe('2024-01-09');
    // ...but already 2024-01-10 in Tokyo (UTC+9).
    expect(todayInTimezone('Asia/Tokyo', now)).toBe('2024-01-10');
    expect(todayInTimezone('UTC', now)).toBe('2024-01-10');
  });

  it('throws (never defaults to server tz) when the timezone is unset', () => {
    const now = new Date('2024-01-10T02:00:00Z');
    expect(() => todayInTimezone(null, now)).toThrow(MarketplaceTimezoneError);
    expect(() => todayInTimezone(undefined, now)).toThrow(MarketplaceTimezoneError);
    expect(() => todayInTimezone('', now)).toThrow(MarketplaceTimezoneError);
    expect(() => todayInTimezone('   ', now)).toThrow(MarketplaceTimezoneError);
  });

  it('throws for an invalid IANA timezone', () => {
    expect(() => todayInTimezone('Not/AZone')).toThrow(MarketplaceTimezoneError);
  });
});

describe('resolveDefaultPeriodComparison', () => {
  it('defaults to last-7 vs prior-7 anchored on today in the marketplace tz', () => {
    const now = new Date('2024-01-10T23:30:00Z'); // 2024-01-11 in Tokyo
    const c = resolveDefaultPeriodComparison('Asia/Tokyo', { now });
    expect(c.current).toEqual({ start: '2024-01-05', end: '2024-01-11' });
    expect(c.prior).toEqual({ start: '2023-12-29', end: '2024-01-04' });
  });

  it('propagates the timezone error when unset', () => {
    expect(() => resolveDefaultPeriodComparison(null)).toThrow(
      MarketplaceTimezoneError,
    );
  });
});

describe('periodOverPeriodChange', () => {
  it('computes a signed ratio against the prior baseline', () => {
    expect(periodOverPeriodChange(125, 100)).toBeCloseTo(0.25);
    expect(periodOverPeriodChange(75, 100)).toBeCloseTo(-0.25);
  });

  it('returns null with no honest baseline', () => {
    expect(periodOverPeriodChange(10, 0)).toBeNull();
    expect(periodOverPeriodChange(Number.NaN, 100)).toBeNull();
    expect(periodOverPeriodChange(100, Number.POSITIVE_INFINITY)).toBeNull();
  });
});

// ── Property tests (fast-check, >=100 runs) ──────────────────────────────────

const IANA_ZONES = [
  'UTC',
  'America/Los_Angeles',
  'America/New_York',
  'Europe/London',
  'Europe/Berlin',
  'Asia/Tokyo',
  'Asia/Shanghai',
  'Asia/Kolkata',
  'Australia/Sydney',
  'Pacific/Auckland',
];

describe('KpiPeriod properties', () => {
  // The two windows are adjacent, equal-length, and non-overlapping.
  it('current and prior windows are equal-length, adjacent, and non-overlapping', () => {
    fc.assert(
      fc.property(
        fc.date({ min: new Date('2000-01-01T00:00:00Z'), max: new Date('2100-01-01T00:00:00Z') }),
        fc.constantFrom(...IANA_ZONES),
        fc.integer({ min: 1, max: 90 }),
        (now, tz, windowDays) => {
          const c = resolveDefaultPeriodComparison(tz, { now, windowDays });
          // Equal length, exactly windowDays each.
          expect(inclusiveDayCount(c.current)).toBe(windowDays);
          expect(inclusiveDayCount(c.prior)).toBe(windowDays);
          // Adjacent: prior.end is the day immediately before current.start.
          expect(addCalendarDays(c.prior.end, 1)).toBe(c.current.start);
          // Non-overlapping and ordered.
          expect(c.prior.start <= c.prior.end).toBe(true);
          expect(c.current.start <= c.current.end).toBe(true);
          expect(c.prior.end < c.current.start).toBe(true);
        },
      ),
      { numRuns: 200 },
    );
  });

  // The current window always ends on "today" in the marketplace timezone.
  it('current window ends on today-in-timezone, never the server timezone', () => {
    fc.assert(
      fc.property(
        fc.date({ min: new Date('2000-01-01T00:00:00Z'), max: new Date('2100-01-01T00:00:00Z') }),
        fc.constantFrom(...IANA_ZONES),
        (now, tz) => {
          const c = resolveDefaultPeriodComparison(tz, { now });
          expect(c.current.end).toBe(todayInTimezone(tz, now));
        },
      ),
      { numRuns: 200 },
    );
  });

  // An unset timezone always errors — it never silently resolves.
  it('always throws for an unset timezone', () => {
    fc.assert(
      fc.property(
        fc.date(),
        fc.constantFrom('', '   ', null, undefined),
        (now, tz) => {
          expect(() => resolveDefaultPeriodComparison(tz, { now })).toThrow(
            MarketplaceTimezoneError,
          );
        },
      ),
      { numRuns: 100 },
    );
  });
});
