// Feature: platform-ux-logistics-enhancements, Property 30: Read failures preserve the last cached data and surface a message
//
// For any read issued through the data-fetching layer that has previously cached
// data, a subsequent failed request retains the last successfully cached data
// rather than clearing it and surfaces a human-readable error message derived
// from the response envelope.
//
// Validates: Requirements 3.4
//
// We drive the real `useApiQuery` hook with a fetcher that succeeds on its first
// call (seeding the cache) and then fails on a subsequent refetch — mirroring a
// transient backend failure after a successful load. `lib/api.ts` already throws
// an `Error` carrying the backend's readable message, so here the failing fetcher
// rejects with `new Error(message)` to stand in for that surfaced message. No
// network and no fabricated react-query behavior: the hook, a real `QueryClient`,
// and a real `QueryClientProvider` do the work.

import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import type { ReactNode } from 'react';
import React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useApiQuery } from './useApiQuery';

const RUNS = 100; // minimum 100 iterations as required for property tests.

// Any successfully-cached value a backend read might return once unwrapped from
// the ApiResponse envelope: objects, arrays, strings, numbers, booleans, null.
// `jsonValue` never yields `undefined` (which react-query forbids as query data).
const cachedDataArb = fc.jsonValue();

// Any non-empty, human-readable error message derived from the envelope. We
// require at least one non-whitespace character so "non-empty message" is
// meaningful.
const messageArb = fc
  .oneof(
    fc.constantFrom(
      'Network request failed',
      'API error: 500 Internal Server Error',
      'Service Unavailable',
      'Request timed out',
      '请求失败', // non-ASCII readable message
    ),
    fc.string({ minLength: 1 }),
  )
  .filter((s) => s.trim().length > 0);

describe('Feature: platform-ux-logistics-enhancements, Property 30: Read failures preserve the last cached data and surface a message', () => {
  it('retains the last cached data and exposes a readable error after a failing refetch', async () => {
    let iteration = 0;

    await fc.assert(
      fc.asyncProperty(cachedDataArb, messageArb, async (cachedData, message) => {
        iteration += 1;

        // Fresh client per iteration so caches never bleed across runs. Disable
        // retry so the failing refetch settles immediately into an error state
        // (the auto-retry behavior is covered by its own test for Req 3.5).
        const client = new QueryClient({
          defaultOptions: {
            queries: { retry: false, gcTime: Infinity, staleTime: 0 },
          },
        });

        const wrapper = ({ children }: { children: ReactNode }) => (
          <QueryClientProvider client={client}>{children}</QueryClientProvider>
        );

        // Succeed first (seed the cache), then fail on the refetch.
        let callCount = 0;
        const fetcher = () => {
          callCount += 1;
          if (callCount === 1) {
            return Promise.resolve(cachedData);
          }
          return Promise.reject(new Error(message));
        };

        const key = ['property30-read', iteration] as const;
        const { result, unmount } = renderHook(() => useApiQuery(key, fetcher), {
          wrapper,
        });

        try {
          // First load succeeds and populates the cache.
          await waitFor(() => expect(result.current.isSuccess).toBe(true));
          expect(result.current.data).toEqual(cachedData);

          // A subsequent read fails.
          await result.current.refetch();
          await waitFor(() => expect(result.current.isError).toBe(true));

          // The last successfully cached data is retained, not cleared.
          expect(result.current.data).toEqual(cachedData);

          // A human-readable, non-empty error message is surfaced.
          expect(result.current.error).toBeInstanceOf(Error);
          const surfaced = result.current.error?.message ?? '';
          expect(surfaced.trim().length).toBeGreaterThan(0);
          expect(surfaced).toBe(message);
        } finally {
          unmount();
          client.clear();
        }
      }),
      { numRuns: RUNS },
    );
  }, 60_000); // generous timeout: 100 iterations each mount/refetch a real hook.
});
