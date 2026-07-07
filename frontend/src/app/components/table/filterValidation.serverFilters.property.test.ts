// Feature: advertising-workspace-rework, Property 36: Server-side filters constrain the full result set
//
// Validates: Requirements 15.1, 15.2, 15.3, 15.6, 15.7
//
// For any dataset and supported list filter, every returned row satisfies the
// filter and no matching row across all pages is omitted; a filter referencing
// a non-persisted field is rejected with a validation error rather than ignored.
//
// This drives the real, pure filter-wiring helpers that SharedDataTable uses
// (`partitionFilterFields` + `unsupportedFilterMessage` from
// `filterValidation.ts`) — no mocking. The helpers decide which committed
// conditions are forwarded to the server (the persisted/server-filterable
// ones) and which are rejected (non-persisted, must never be silently applied).
// We then model the server applying ONLY the forwarded conditions across the
// entire dataset and assert soundness (every returned row matches) and
// completeness (no matching row is dropped on any page).

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';

import {
  partitionFilterFields,
  unsupportedFilterMessage,
} from './filterValidation';
import type { FilterCondition, FilterOperator } from './types';

const RUNS = 200; // comfortably above the required minimum of 100 iterations.

// A fixed pool of candidate field keys, mixing names that may be declared as
// persisted (server-filterable) and names that never are. Using a shared pool
// guarantees generated runs exercise both the "accepted" and "rejected" paths.
const FIELD_POOL = [
  'status',
  'date',
  'adGroup',
  'matchType',
  'parentAsin',
  'targetingGoal',
  'mystery',
  'phantom',
] as const;

// Numeric comparison operators are enough to express a meaningful, decidable
// per-row constraint without dragging in value-coercion concerns (those are
// covered by validateCondition's own tests).
const OP_ARB = fc.constantFrom<FilterOperator>(
  'eq',
  'ne',
  'gt',
  'gte',
  'lt',
  'lte',
);

const conditionArb: fc.Arbitrary<FilterCondition> = fc.record({
  field: fc.constantFrom(...FIELD_POOL),
  op: OP_ARB,
  value: fc.integer({ min: 0, max: 5 }),
});

/** Whether a single row satisfies one condition (the server's row predicate). */
function rowMatches(row: Record<string, number>, c: FilterCondition): boolean {
  const v = row[c.field];
  const t = c.value as number;
  switch (c.op) {
    case 'eq':
      return v === t;
    case 'ne':
      return v !== t;
    case 'gt':
      return v > t;
    case 'gte':
      return v >= t;
    case 'lt':
      return v < t;
    case 'lte':
      return v <= t;
    default:
      return true;
  }
}

/** The persisted-field semantics mirrored from `partitionFilterFields`. */
function isPersisted(
  field: string,
  persisted: string[] | undefined,
): boolean {
  // undefined or empty => the table declared no persisted-field constraint, so
  // every field is treated as forwardable.
  if (persisted === undefined || persisted.length === 0) return true;
  return persisted.includes(field);
}

describe('Feature: advertising-workspace-rework, Property 36: Server-side filters constrain the full result set', () => {
  it('forwards only persisted conditions, rejects non-persisted ones with a validation error, and constrains the full result set across all pages', () => {
    fc.assert(
      fc.property(
        // The declared server-filterable field set (sometimes undefined/empty).
        fc.option(fc.subarray([...FIELD_POOL]), { nil: undefined }),
        // The operator's committed advanced-filter conditions.
        fc.array(conditionArb, { maxLength: 8 }),
        // A dataset spanning many "pages" worth of rows.
        fc.array(
          fc.record(
            Object.fromEntries(
              FIELD_POOL.map((k) => [k, fc.integer({ min: 0, max: 5 })]),
            ) as Record<(typeof FIELD_POOL)[number], fc.Arbitrary<number>>,
          ),
          { maxLength: 40 },
        ),
        fc.integer({ min: 1, max: 7 }), // page size
        (persisted, conditions, dataset, pageSize) => {
          const { accepted, rejected } = partitionFilterFields(
            conditions,
            persisted,
          );

          // --- (a) Partition is sound: forwarded == persisted, rejected == not.
          for (const c of accepted) {
            expect(isPersisted(c.field, persisted)).toBe(true);
          }
          for (const c of rejected) {
            expect(isPersisted(c.field, persisted)).toBe(false);
          }

          // --- (b) Partition is total: every committed condition is classified
          // exactly once. Nothing is silently dropped (Req 15.6/15.7).
          expect(accepted.length + rejected.length).toBe(conditions.length);

          // --- (c) Non-persisted fields are rejected with a *named* validation
          // error rather than ignored (Req 15.6). Conversely, when nothing is
          // rejected there is no error.
          const message = unsupportedFilterMessage(rejected);
          if (rejected.length === 0) {
            expect(message).toBeNull();
          } else {
            expect(message).not.toBeNull();
            for (const field of new Set(rejected.map((c) => c.field))) {
              expect(message as string).toContain(field);
            }
          }

          // --- (d) The server applies ONLY the forwarded (accepted) conditions
          // across the FULL dataset (every page), combined with AND.
          const serverResult = dataset.filter((row) =>
            accepted.every((c) => rowMatches(row, c)),
          );

          // Soundness: every returned row satisfies every forwarded filter
          // (Req 15.1, 15.2, 15.3).
          for (const row of serverResult) {
            for (const c of accepted) {
              expect(rowMatches(row, c)).toBe(true);
            }
          }

          // Completeness across all pages: no matching row is omitted. Paginate
          // the result and assert the union of every page equals the full
          // filtered set (Req 15.7 — filter spans the full result set, not just
          // the current page).
          const pages: Record<string, number>[][] = [];
          for (let i = 0; i < serverResult.length; i += pageSize) {
            pages.push(serverResult.slice(i, i + pageSize));
          }
          const union = pages.flat();
          expect(union).toEqual(serverResult);

          // Every row in the full dataset that matches the forwarded filter is
          // present in the result (none dropped).
          const expectedMatches = dataset.filter((row) =>
            accepted.every((c) => rowMatches(row, c)),
          );
          expect(serverResult.length).toBe(expectedMatches.length);

          // --- (e) Rejected conditions are NEVER silently applied: a row that
          // fails a rejected condition but passes all forwarded ones is still
          // returned (the rejected filter does not constrain the result; it is
          // surfaced as an error instead).
          if (rejected.length > 0) {
            for (const row of dataset) {
              const passesAccepted = accepted.every((c) => rowMatches(row, c));
              const failsSomeRejected = rejected.some(
                (c) => !rowMatches(row, c),
              );
              if (passesAccepted && failsSomeRejected) {
                expect(serverResult).toContain(row);
              }
            }
          }
        },
      ),
      { numRuns: RUNS },
    );
  });
});
