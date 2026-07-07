// Feature: platform-ux-logistics-enhancements, Property 31: Query keys are unique per resource and store scope
//
// Validates: Requirements 3.8
//
// The Data_Fetching_Layer assigns each read a Query_Key that is unique per
// backend resource and per store scope, expressed as `[resource, storeId, ...params]`.
// This test verifies the universal property that keys produced by the `qk`
// convention are equal IFF their conceptual inputs (which helper / resource +
// the arguments, i.e. store scope and params) are equal. In other words:
// different resources, different store scopes, or different params NEVER collide
// onto the same cache entry, and identical inputs ALWAYS produce identical keys.
//
// Equality is measured with a stable serialization that mirrors how react-query
// hashes query keys (structural equality with recursively sorted object keys),
// so the comparison is consistent with the cache-matching the app relies on.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import { qk } from './queryKeys';

// ---------------------------------------------------------------------------
// Stable key hashing, identical in behavior to @tanstack/react-query's hashKey:
// JSON.stringify with a replacer that sorts plain-object keys recursively so
// that {a:1,b:2} and {b:2,a:1} hash to the same string (structural equality),
// while arrays and primitives are preserved positionally.
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
// A Descriptor models a single read: which helper to call (the resource) plus
// its arguments (store scope / id / params). Two descriptors are "input-equal"
// when they target the same helper with structurally-equal arguments. Input
// equality is computed independently of the produced key, so a buggy helper
// that dropped a param or ignored the store scope would break the IFF.
// ---------------------------------------------------------------------------
type Descriptor =
  | { name: 'campaigns'; args: [string | undefined, unknown] }
  | { name: 'campaignDetail'; args: [string] }
  | { name: 'shipments'; args: [string | undefined, unknown] }
  | { name: 'shipmentDetail'; args: [string] }
  | { name: 'carriers'; args: [string | undefined] }
  | { name: 'savedViews'; args: [string] };

function buildKey(d: Descriptor): readonly unknown[] {
  switch (d.name) {
    case 'campaigns':
      return qk.campaigns(d.args[0], d.args[1]);
    case 'campaignDetail':
      return qk.campaignDetail(d.args[0]);
    case 'shipments':
      return qk.shipments(d.args[0], d.args[1]);
    case 'shipmentDetail':
      return qk.shipmentDetail(d.args[0]);
    case 'carriers':
      return qk.carriers(d.args[0]);
    case 'savedViews':
      return qk.savedViews(d.args[0]);
  }
}

// Independent stable serialization of a descriptor's *inputs* (resource + args).
function hashInput(d: Descriptor): string {
  return hashKey([d.name, ...d.args]);
}

function inputsEqual(a: Descriptor, b: Descriptor): boolean {
  return hashInput(a) === hashInput(b);
}

// ---------------------------------------------------------------------------
// Generators
// ---------------------------------------------------------------------------
const storeIdArb = fc.option(fc.string(), { nil: undefined });
const idArb = fc.string();
// Params is `unknown`; exercise the realistic JSON-serializable filter space
// (objects with varying key order, arrays, primitives, null, and undefined).
const paramsArb = fc.oneof(fc.constant(undefined), fc.jsonValue());

const descriptorArb: fc.Arbitrary<Descriptor> = fc.oneof(
  fc.record({ name: fc.constant('campaigns' as const), args: fc.tuple(storeIdArb, paramsArb) }),
  fc.record({ name: fc.constant('campaignDetail' as const), args: fc.tuple(idArb) }),
  fc.record({ name: fc.constant('shipments' as const), args: fc.tuple(storeIdArb, paramsArb) }),
  fc.record({ name: fc.constant('shipmentDetail' as const), args: fc.tuple(idArb) }),
  fc.record({ name: fc.constant('carriers' as const), args: fc.tuple(storeIdArb) }),
  fc.record({ name: fc.constant('savedViews' as const), args: fc.tuple(idArb) }),
) as fc.Arbitrary<Descriptor>;

// Deep clone so the "identical inputs" arm uses a distinct-but-equal value,
// proving equality is structural and not reference-based.
function cloneDescriptor(d: Descriptor): Descriptor {
  return JSON.parse(JSON.stringify(d, (_k, v) => (v === undefined ? '__undef__' : v)), (_k, v) =>
    v === '__undef__' ? undefined : v,
  ) as Descriptor;
}

describe('Property 31: Query keys are unique per resource and store scope', () => {
  it('keys are equal IFF their (resource, storeId, ...params) inputs are equal (numRuns >= 100)', () => {
    fc.assert(
      fc.property(descriptorArb, descriptorArb, (a, b) => {
        const keyEqual = hashKey(buildKey(a)) === hashKey(buildKey(b));
        const sameInputs = inputsEqual(a, b);
        // The IFF: cache collision happens exactly when the inputs are equal.
        expect(keyEqual).toBe(sameInputs);
      }),
      { numRuns: 300 },
    );
  });

  it('identical inputs always produce identical keys (reflexivity / determinism)', () => {
    fc.assert(
      fc.property(descriptorArb, (d) => {
        const clone = cloneDescriptor(d);
        expect(hashKey(buildKey(clone))).toBe(hashKey(buildKey(d)));
      }),
      { numRuns: 100 },
    );
  });

  it('different resources never collide for the same store scope and params', () => {
    fc.assert(
      fc.property(storeIdArb, paramsArb, (storeId, params) => {
        // campaigns vs shipments share storeId + params but must differ by resource.
        expect(hashKey(qk.campaigns(storeId, params))).not.toBe(
          hashKey(qk.shipments(storeId, params)),
        );
      }),
      { numRuns: 100 },
    );
  });

  it('different store scopes never collide for the same resource', () => {
    fc.assert(
      fc.property(
        fc.string(),
        fc.string(),
        paramsArb,
        (storeA, storeB, params) => {
          fc.pre(storeA !== storeB);
          expect(hashKey(qk.campaigns(storeA, params))).not.toBe(
            hashKey(qk.campaigns(storeB, params)),
          );
        },
      ),
      { numRuns: 100 },
    );
  });
});
