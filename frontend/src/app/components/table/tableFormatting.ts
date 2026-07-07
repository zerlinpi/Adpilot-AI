// AdPilot AI — Pinned-column offset resolution + currency-aware formatting for
// the Shared_Data_Table (advertising-workspace-rework Req 33.2, 38.1, 38.2, 38.3)
//
// Everything here is PURE (no React, no DOM) so the cumulative-offset math and
// the per-value currency formatting can be unit-/property-tested in isolation
// (see tasks 19.12 / 19.13, design Properties 66 and 67) and reused by any table.
//
// Two concerns live here:
//
//   1. resolvePinnedOffsets — given the ordered list of pinned columns and their
//      widths, compute each pinned column's horizontal offset as the CUMULATIVE
//      width of the pinned columns preceding it. Offsets are therefore
//      non-overlapping (the start of column i+1 is exactly the end of column i)
//      and strictly increasing whenever widths are positive (Req 33.2, 38.1).
//
//   2. formatMonetaryValue — format a monetary advertising value using ITS OWN
//      currency, never a single assumed currency, so a multi-currency view shows
//      each value with the correct currency (Req 38.2, 38.3).

/** Fallback width (px) used when a pinned column declares no explicit width. */
export const DEFAULT_PINNED_COLUMN_WIDTH = 150;

/** A pinned column as supplied to {@link resolvePinnedOffsets}. */
export interface PinnedColumnInput {
  /** Stable column key. */
  key: string;
  /** The column's rendered width in pixels. */
  width: number;
}

/** A pinned column with its resolved horizontal offset. */
export interface ResolvedPinnedOffset {
  /** Stable column key. */
  key: string;
  /** The column's own width in pixels (non-negative, finite). */
  width: number;
  /**
   * Horizontal offset (px) from the start of the table: the cumulative width of
   * every pinned column preceding this one. The first pinned column is at 0.
   */
  offset: number;
}

/**
 * Coerce a width into a safe, non-negative, finite pixel value. A
 * missing / NaN / Infinity / negative width collapses to 0 so it can never
 * push later columns to a bogus (e.g. negative or non-finite) offset.
 */
function safeWidth(width: number): number {
  return Number.isFinite(width) && width > 0 ? width : 0;
}

/**
 * Resolve the cumulative, non-overlapping horizontal offsets for an ordered
 * list of pinned columns.
 *
 * The offset of the column at index `i` is the sum of the widths of the columns
 * at indices `0..i-1` (the first column is at offset 0). Consequently:
 *
 *   - offsets are non-decreasing, and strictly increasing across any column
 *     whose predecessor has a positive width (so positive-width pinned columns
 *     never overlap);
 *   - `offset[i] + width[i] === offset[i+1]` for every adjacent pair (columns
 *     are laid out contiguously with no gap and no overlap).
 *
 * Pure and order-preserving: the returned array is in the same order as the
 * input (the input order is the visible left-to-right pin order).
 *
 * Validates: Requirements 33.2, 38.1
 */
export function resolvePinnedOffsets(
  pinned: ReadonlyArray<PinnedColumnInput>,
): ResolvedPinnedOffset[] {
  let cumulative = 0;
  const resolved: ResolvedPinnedOffset[] = [];
  for (const col of pinned) {
    const width = safeWidth(col.width);
    resolved.push({ key: col.key, width, offset: cumulative });
    cumulative += width;
  }
  return resolved;
}

/** A monetary advertising value paired with its own currency. */
export interface MonetaryValue {
  /** The numeric amount. */
  amount: number;
  /** ISO-4217 currency code (e.g. `USD`, `GBP`, `JPY`). */
  currency: string;
}

/** Default locale used for monetary formatting when the host supplies none. */
export const DEFAULT_MONETARY_LOCALE = 'en-US';

/** Whether a string looks like a well-formed ISO-4217 currency code. */
function isValidCurrencyCode(currency: string): boolean {
  return /^[A-Za-z]{3}$/.test(currency);
}

/**
 * Format a single monetary advertising value using ITS OWN currency.
 *
 * Each value carries its own currency, so a view holding values in more than
 * one currency formats each value with the correct currency rather than
 * assuming a single one (Req 38.2, 38.3). The currency code is normalized to
 * upper-case; an unparseable / unknown code degrades gracefully to a
 * `"<CODE> <amount>"` form rather than throwing, so a bad code can never blank
 * out the whole table.
 *
 * Validates: Requirements 38.2, 38.3
 */
export function formatMonetaryValue(
  amount: number,
  currency: string,
  locale: string = DEFAULT_MONETARY_LOCALE,
): string {
  const code = (currency ?? '').trim().toUpperCase();
  const safeAmount = Number.isFinite(amount) ? amount : 0;

  if (!isValidCurrencyCode(code)) {
    return `${code} ${safeAmount}`.trim();
  }

  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: code,
    }).format(safeAmount);
  } catch {
    // Intl throws on an unknown (but well-formed) code; degrade honestly.
    return `${code} ${safeAmount}`;
  }
}

/**
 * Convenience wrapper to format a {@link MonetaryValue}. Equivalent to
 * `formatMonetaryValue(value.amount, value.currency, locale)`. Useful when a
 * table cell holds a `{ amount, currency }` pair and a multi-currency column
 * maps each row's value through its own currency.
 *
 * Validates: Requirements 38.2, 38.3
 */
export function formatMoney(value: MonetaryValue, locale?: string): string {
  return formatMonetaryValue(value.amount, value.currency, locale);
}
