// Property-based test for the pure, currency-aware monetary formatting helpers
// in ./tableFormatting (advertising-workspace-rework Req 38.2, 38.3).
//
// Feature: advertising-workspace-rework, Property 67
//
// Property 67 — Monetary values are formatted in their own currency:
//   When the advertising tables render money, each value is formatted using ITS
//   OWN currency, never a single assumed currency. In a multi-currency view
//   (different currencies in the same set) every value renders with the currency
//   it carries, so two values of the same amount but different currency render
//   differently (Req 38.3). An invalid / unknown currency code degrades
//   gracefully — it returns a string and never throws — so one bad code can
//   never blank out the whole table (Req 38.2, 38.3).

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  formatMonetaryValue,
  formatMoney,
  type MonetaryValue,
} from './tableFormatting';

const NUM_RUNS = 300;

// Curated set of real ISO-4217 codes whose currency indicators are mutually
// distinct in every locale exercised below, so "different currency ⇒ different
// rendering" is unambiguous (each has its own symbol/code marker).
const CURRENCY_CODES = [
  'USD',
  'EUR',
  'GBP',
  'JPY',
  'CNY',
  'INR',
  'BRL',
  'KRW',
  'CHF',
  'SEK',
] as const;

const localeArb = fc.constantFrom('en-US', 'en-GB', 'de-DE', 'ja-JP');

// Amounts deliberately include non-finite values: the helper coerces NaN /
// ±Infinity to 0 rather than throwing, so they must still format cleanly.
const amountArb = fc.oneof(
  { weight: 8, arbitrary: fc.double({ min: -1e9, max: 1e9, noNaN: true }) },
  { weight: 1, arbitrary: fc.constantFrom(NaN, Infinity, -Infinity) },
);

const currencyArb = fc.constantFrom(...CURRENCY_CODES);

// A multi-currency view: a set of {amount, currency} values that mixes
// currencies. Index pairs let us probe "own currency, not a single assumed one".
const valueArb: fc.Arbitrary<MonetaryValue> = fc.record({
  amount: amountArb,
  currency: currencyArb,
});

// Malformed / unknown currency codes that must degrade gracefully.
const badCurrencyArb = fc.oneof(
  fc.constant(''),
  fc.constant('US'),
  fc.constant('DOLLARS'),
  fc.constant('12'),
  fc.constant('$$$'),
  fc.constant('  '),
  fc.string({ maxLength: 6 }),
  fc.constantFrom('ZZZ', 'QQQ', 'XBT'), // well-formed but not real ISO codes
);

describe('Feature: advertising-workspace-rework, Property 67 — Monetary values are formatted in their own currency', () => {
  it('each value renders in its own currency (multi-currency views), and bad codes degrade without throwing', () => {
    fc.assert(
      fc.property(
        fc.record({
          values: fc.array(valueArb, { minLength: 1, maxLength: 8 }),
          locale: localeArb,
          otherPick: fc.nat(),
          bad: fc.record({ amount: amountArb, currency: badCurrencyArb }),
        }),
        ({ values, locale, otherPick, bad }) => {
          // ── Render the whole multi-currency set with one shared locale ──
          const rendered = values.map((v) => formatMoney(v, locale));

          values.forEach((value, i) => {
            // (1) The {amount,currency} wrapper formats using the value's OWN
            //     currency — identical to calling the primitive with that
            //     value's currency. No single assumed currency is involved.
            expect(rendered[i]).toBe(
              formatMonetaryValue(value.amount, value.currency, locale),
            );

            // (2) Every formatted value is a non-empty string (a real cell).
            expect(typeof rendered[i]).toBe('string');
            expect(rendered[i].length).toBeGreaterThan(0);

            // (3) Own currency vs. a DIFFERENT currency: formatting this value's
            //     amount under any other currency from the view changes the
            //     output. This is exactly the multi-currency guarantee — were
            //     the table to assume a single currency, the differing-currency
            //     values would be misrendered.
            const other =
              CURRENCY_CODES[otherPick % CURRENCY_CODES.length] === value.currency
                ? CURRENCY_CODES[(otherPick + 1) % CURRENCY_CODES.length]
                : CURRENCY_CODES[otherPick % CURRENCY_CODES.length];

            const ownFormatting = formatMonetaryValue(
              value.amount,
              value.currency,
              locale,
            );
            const otherFormatting = formatMonetaryValue(
              value.amount,
              other,
              locale,
            );

            if (other !== value.currency) {
              expect(ownFormatting).not.toBe(otherFormatting);
            }

            // (4) Currency code is case-insensitive: a lower-cased code yields
            //     the same rendering as its upper-cased form (own currency is
            //     identified by code, not by literal casing).
            expect(
              formatMonetaryValue(
                value.amount,
                value.currency.toLowerCase(),
                locale,
              ),
            ).toBe(ownFormatting);
          });

          // ── Graceful degradation: an invalid/unknown code never throws ──
          let degraded: string | undefined;
          expect(() => {
            degraded = formatMonetaryValue(bad.amount, bad.currency, locale);
          }).not.toThrow();
          expect(typeof degraded).toBe('string');
          // The wrapper path degrades just as gracefully.
          expect(() =>
            formatMoney({ amount: bad.amount, currency: bad.currency }, locale),
          ).not.toThrow();
        },
      ),
      { numRuns: NUM_RUNS },
    );
  });
});
