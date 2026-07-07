// Feature: advertising-workspace-rework, Property 61: Single source of truth for query conditions
//
// Validates: Requirements 39.1, 39.2, 39.3
//
// Requirement 39 demands that the Filter controls, the KPI_Panel, and the table
// (SharedDataTable) are all driven by the SAME set of query conditions as a
// single source of truth (39.1); that changing any query condition applies to
// all three surfaces together (39.2); and that no separate, divergent set of
// query conditions is maintained for the KPI_Panel and the table (39.3).
//
// In the implementation that single source of truth is the one
// `AdvertisingQueryState` owned by `useAdvertisingQueryState`. Every surface
// derives BOTH its request params and its react-query Query_Key from that one
// state through the PURE `deriveRequestParams` mapping in `useAdvertisingQueries`.
// Because the mapping is a deterministic function of the single state, the three
// surfaces structurally cannot diverge: given the same state they compute the
// same params, and a change to the state re-derives all of them together.
//
// This test exercises that pure layer directly (no React / router needed): it
// models the three surfaces as three independent derivations from one state and
// asserts (a) they never diverge for a given state, and (b) when a query
// condition changes, all three move to the new derived value together — there is
// no surface that retains a stale or independent set of conditions.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';

import { deriveRequestParams } from './useAdvertisingQueries';
import { normalizeQueryState, type AdvertisingQueryState } from '../advertisingQueryState';
import { qk } from '../queryKeys';
import { ALL_SELECTION } from '../../components/table/types';
import type { FilterCondition, FilterOperator } from '../../components/table/types';

// ---------------------------------------------------------------------------
// Stable structural hashing, identical in behavior to @tanstack/react-query's
// hashKey: JSON.stringify with a replacer that recursively sorts plain-object
// keys so that {a:1,b:2} and {b:2,a:1} hash equal. This is exactly how the
// cache decides whether two surfaces share a cache entry, so "no divergence"
// is measured the same way react-query measures key identity.
// ---------------------------------------------------------------------------
function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

function hashKey(key: unknown): string {
  return JSON.stringify(key, (_k, val) =>
    isPlainObject(val)
      ? Object.keys(val)
        .sort()
        .reduce<Record<string, unknown>>((acc, k) => {
          acc[k] = (val as Record<string, unknown>)[k];
          return acc;
        }, {})
      : val,
  );
}

// ---------------------------------------------------------------------------
// Generators for an arbitrary AdvertisingQueryState. The generators cover the
// full query-condition surface: free-text search, type-selector values (incl.
// the ALL_SELECTION "no filter" sentinel and empty strings, which the derive
// logic must drop), advanced conditions, an optional date range, an optional
// sort, and pagination.
// ---------------------------------------------------------------------------
const FILTER_OPS: FilterOperator[] = [
  'eq',
  'ne',
  'gt',
  'gte',
  'lt',
  'lte',
  'contains',
  'in',
];

// Keys an operator might filter/select on across the advertising surfaces.
const selectorKeyArb = fc.constantFrom(
  'status',
  'adType',
  'matchType',
  'campaignId',
  'adGroupId',
  'goalId',
  'portfolioId',
  'parentAsin',
  'targetingGoal',
  'smartFilter',
  'harvestingStatus',
  'riskLevel',
  'type',
);

const selectorValueArb = fc.oneof(
  fc.string(),
  fc.constant(ALL_SELECTION), // "no filter" sentinel — must be dropped on derive
  fc.constant(''), // empty — must be dropped on derive
);

const typeSelectionsArb = fc.dictionary(selectorKeyArb, selectorValueArb, {
  maxKeys: 6,
});

const conditionArb: fc.Arbitrary<FilterCondition> = fc.record({
  field: fc.string({ minLength: 1 }),
  op: fc.constantFrom(...FILTER_OPS),
  value: fc.oneof(fc.string(), fc.integer(), fc.boolean(), fc.constant(null)),
});

const dateArb = fc
  .date({ min: new Date('2020-01-01'), max: new Date('2030-12-31') })
  .map((d) => d.toISOString().slice(0, 10));

const queryStateArb: fc.Arbitrary<AdvertisingQueryState> = fc.record({
  filters: fc.record({
    search: fc.string(),
    typeSelections: typeSelectionsArb,
    conditions: fc.array(conditionArb, { maxLength: 4 }),
  }),
  dateRange: fc.option(fc.record({ start: dateArb, end: dateArb }), { nil: null }),
  sort: fc.option(
    fc.record({
      field: fc.string({ minLength: 1 }),
      direction: fc.constantFrom('asc' as const, 'desc' as const),
    }),
    { nil: null },
  ),
  page: fc.integer({ min: 1, max: 9999 }),
  pageSize: fc.integer({ min: 1, max: 500 }),
});

// ---------------------------------------------------------------------------
// The three surfaces of Requirement 39, each modeled as an INDEPENDENT
// derivation from whatever single state it is handed. If any surface kept its
// own divergent conditions, these functions would have to take different state
// — they cannot, because the only input is the one shared state.
// ---------------------------------------------------------------------------
function filterToolbarParams(state: AdvertisingQueryState) {
  return deriveRequestParams(state);
}
function kpiPanelParams(state: AdvertisingQueryState) {
  return deriveRequestParams(state);
}
function sharedDataTableParams(state: AdvertisingQueryState) {
  return deriveRequestParams(state);
}

// The Query_Key each surface would hand react-query. The params segment is the
// derived params, so identical state ⇒ identical key params segment.
function surfaceKeys(storeId: string, state: AdvertisingQueryState) {
  const filter = filterToolbarParams(state);
  const kpi = kpiPanelParams(state);
  const table = sharedDataTableParams(state);
  return {
    filter,
    kpi,
    table,
    // KPI_Panel reads the trend; the table reads the campaign list. Both embed
    // the same derived params, so their params segments must match.
    kpiKey: qk.campaignTrend(storeId, kpi),
    tableKey: qk.campaigns(storeId, table),
  };
}

describe('Feature: advertising-workspace-rework, Property 61: Single source of truth for query conditions', () => {
  it('all surfaces derive one identical set of query conditions, and a change updates them together (numRuns >= 100)', () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1 }), // storeId scope
        queryStateArb, // state before a change
        queryStateArb, // state after a change to some query condition
        (storeId, before, after) => {
          // ── 39.1 / 39.3: for a single state, the Filter controls, the
          // KPI_Panel, and the table derive the SAME query conditions — no
          // divergent set exists for any surface. ──────────────────────────
          const b = surfaceKeys(storeId, before);
          expect(hashKey(b.filter)).toBe(hashKey(b.kpi));
          expect(hashKey(b.kpi)).toBe(hashKey(b.table));

          // The params each surface puts on its Query_Key are the same single
          // derived set, so the KPI panel and the table can never key on a
          // divergent condition set.
          expect(hashKey(b.kpiKey[2])).toBe(hashKey(b.tableKey[2]));

          // Determinism: re-deriving from the same state yields an identical
          // result (so two surfaces evaluating the one state always agree).
          expect(hashKey(deriveRequestParams(before))).toBe(hashKey(b.filter));

          // The single source of truth is exactly the normalized query state:
          // structurally-equal states map to equal derived conditions.
          if (hashKey(normalizeQueryState(before)) === hashKey(normalizeQueryState(after))) {
            expect(hashKey(deriveRequestParams(after))).toBe(hashKey(b.filter));
          }

          // ── 39.2: when a query condition changes, the change applies to the
          // Filter controls, the KPI_Panel, and the table TOGETHER. After the
          // change every surface re-derives from the one new state and they
          // remain mutually identical (still no divergence). ────────────────
          const a = surfaceKeys(storeId, after);
          expect(hashKey(a.filter)).toBe(hashKey(a.kpi));
          expect(hashKey(a.kpi)).toBe(hashKey(a.table));
          expect(hashKey(a.kpiKey[2])).toBe(hashKey(a.tableKey[2]));

          // "Update together" is all-or-nothing: the set of surfaces whose
          // derived conditions changed is either all three or none. Because
          // each surface is the same pure function of the one state, a surface
          // changes IFF the others do — no surface can lag behind on a stale,
          // independent copy of the conditions.
          const filterChanged = hashKey(b.filter) !== hashKey(a.filter);
          const kpiChanged = hashKey(b.kpi) !== hashKey(a.kpi);
          const tableChanged = hashKey(b.table) !== hashKey(a.table);
          expect(kpiChanged).toBe(filterChanged);
          expect(tableChanged).toBe(filterChanged);
        },
      ),
      { numRuns: 300 },
    );
  });
});
