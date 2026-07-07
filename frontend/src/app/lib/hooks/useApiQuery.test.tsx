// Unit/example tests for the react-query data-fetching layer (Task 18.5).
//
// These are example-based Vitest tests (not property tests) covering the
// observable behaviors of `useApiQuery` / `useApiMutation` and the app-level
// `queryClient` defaults:
//
//   1. Loading state + ApiResponse-unwrapped data        (Req 3.2)
//   2. Write invalidation/refresh of affected reads        (Req 3.3)
//   3. Read auto-retry up to 3 times on transient failure  (Req 3.5)
//   4. Mutation manual-retry (no auto-retry; re-mutate)     (Req 3.6)
//   5. Migrated-page behavior equivalence                   (Req 3.7)
//
// The real hooks, a real `QueryClient`, and a real `QueryClientProvider` do the
// work — no network and no fabricated react-query behavior. Fetchers stand in
// for `lib/api.ts` calls, which already unwrap the `ApiResponse<T>` envelope and
// throw an `Error` carrying the backend's readable message on failure.

import { describe, it, expect } from 'vitest';
import type { ReactNode } from 'react';
import React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useApiQuery, useApiMutation } from './useApiQuery';

// Build a fresh provider wrapper around a caller-supplied client so each test
// gets an isolated cache.
function makeWrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

// A client that mirrors the app defaults (retry 3 for reads, 0 for mutations)
// but with no window-focus refetch, suitable for the jsdom test runtime.
function appLikeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: 30_000, gcTime: 300_000, retry: 3, refetchOnWindowFocus: false },
      mutations: { retry: 0 },
    },
  });
}

describe('useApiQuery — loading state and unwrapped data (Req 3.2)', () => {
  it('exposes isLoading while in flight, then resolves to the unwrapped data', async () => {
    const client = appLikeClient();

    // A deferred fetcher so we can observe the in-flight state deterministically.
    let resolveFetch!: (value: { id: string; name: string }) => void;
    const fetcher = () =>
      new Promise<{ id: string; name: string }>((resolve) => {
        resolveFetch = resolve;
      });

    const { result, unmount } = renderHook(
      () => useApiQuery(['campaigns', 'store-1'], fetcher),
      { wrapper: makeWrapper(client) },
    );

    // While the request is in flight, loading is true and there is no data/error.
    expect(result.current.isLoading).toBe(true);
    expect(result.current.data).toBeUndefined();
    expect(result.current.isError).toBe(false);

    // Resolve the request: data is the value already unwrapped from the envelope.
    const payload = { id: 'c1', name: 'Spring Sale' };
    resolveFetch(payload);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.isLoading).toBe(false);
    expect(result.current.data).toEqual(payload);

    unmount();
    client.clear();
  });
});

describe('useApiMutation — write invalidation refreshes affected reads (Req 3.3)', () => {
  it('a successful mutation invalidates the cached read so it refetches fresh data', async () => {
    const client = appLikeClient();
    const wrapper = makeWrapper(client);
    const queryKey = ['campaigns', 'store-1'] as const;

    // The read returns whatever the backing store currently holds.
    let backingValue = 'before';
    const readFetcher = () => Promise.resolve(backingValue);

    const { result: query, unmount: unmountQuery } = renderHook(
      () => useApiQuery(queryKey, readFetcher),
      { wrapper },
    );

    await waitFor(() => expect(query.current.isSuccess).toBe(true));
    expect(query.current.data).toBe('before');

    // The mutation writes to the backing store, then invalidates the read on
    // success — the mechanism a migrated host uses to refresh without reload.
    const writeFn = (next: string) => {
      backingValue = next;
      return Promise.resolve({ ok: true });
    };
    const { result: mutation, unmount: unmountMutation } = renderHook(
      () =>
        useApiMutation(writeFn, {
          onSuccess: () => client.invalidateQueries({ queryKey }),
        }),
      { wrapper },
    );

    await mutation.current.mutateAsync('after');

    // The affected read refreshes to reflect the write, with no full reload.
    await waitFor(() => expect(query.current.data).toBe('after'));

    unmountMutation();
    unmountQuery();
    client.clear();
  });
});

describe('useApiQuery — read auto-retry on transient failure (Req 3.5)', () => {
  it('automatically retries a transient read failure up to 3 times then succeeds', async () => {
    // Use a client with no retry delay so the 3 retries settle quickly.
    const client = new QueryClient({
      defaultOptions: {
        queries: { retry: 3, retryDelay: 0, refetchOnWindowFocus: false },
      },
    });

    // Fail the first 3 attempts (transient), succeed on the 4th — exercising the
    // initial attempt plus 3 retries.
    let attempts = 0;
    const fetcher = () => {
      attempts += 1;
      if (attempts <= 3) {
        return Promise.reject(new Error('Network request failed'));
      }
      return Promise.resolve('recovered');
    };

    const { result, unmount } = renderHook(
      () => useApiQuery(['shipments', 'store-1'], fetcher),
      { wrapper: makeWrapper(client) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true), { timeout: 5_000 });
    expect(result.current.data).toBe('recovered');
    expect(attempts).toBe(4); // 1 initial + 3 retries

    unmount();
    client.clear();
  });

  it('surfaces an error after exhausting the 3 read retries', async () => {
    const client = new QueryClient({
      defaultOptions: {
        queries: { retry: 3, retryDelay: 0, refetchOnWindowFocus: false },
      },
    });

    let attempts = 0;
    const fetcher = () => {
      attempts += 1;
      return Promise.reject(new Error('Service Unavailable'));
    };

    const { result, unmount } = renderHook(
      () => useApiQuery(['shipments', 'store-2'], fetcher),
      { wrapper: makeWrapper(client) },
    );

    await waitFor(() => expect(result.current.isError).toBe(true), { timeout: 5_000 });
    expect(attempts).toBe(4); // 1 initial + 3 retries, then gives up
    expect(result.current.error?.message).toBe('Service Unavailable');

    unmount();
    client.clear();
  });
});

describe('useApiMutation — manual retry only, never auto-retry (Req 3.6)', () => {
  it('does not auto-retry a failed mutation and re-issues only when mutate is called again', async () => {
    const client = appLikeClient();

    // Fail the first invocation, succeed on the second (the manual retry).
    let calls = 0;
    const writeFn = () => {
      calls += 1;
      if (calls === 1) {
        return Promise.reject(new Error('Conflict'));
      }
      return Promise.resolve({ ok: true });
    };

    const { result, unmount } = renderHook(() => useApiMutation(writeFn), {
      wrapper: makeWrapper(client),
    });

    // First attempt fails — and is NOT auto-retried.
    await expect(result.current.mutateAsync()).rejects.toThrow('Conflict');
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(calls).toBe(1); // exactly one call: no automatic re-issue

    // Caller invokes the manual retry by calling mutate again.
    await result.current.mutateAsync();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(calls).toBe(2); // re-issued exactly once, only when invoked

    unmount();
    client.clear();
  });
});

describe('migrated-page behavior equivalence (Req 3.7)', () => {
  // A migrated page must preserve the same observable loading / empty / error
  // states it had under hand-rolled useEffect fetching. These focused cases
  // assert each observable state is reachable through the data-fetching layer.

  it('preserves the loading state observable to a migrated page', async () => {
    const client = appLikeClient();
    let resolveFetch!: (value: string[]) => void;
    const fetcher = () =>
      new Promise<string[]>((resolve) => {
        resolveFetch = resolve;
      });

    const { result, unmount } = renderHook(
      () => useApiQuery(['shipments', 'store-1'], fetcher),
      { wrapper: makeWrapper(client) },
    );

    // Equivalent to the old `loading === true` before data arrives.
    expect(result.current.isLoading).toBe(true);
    resolveFetch(['s1']);
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    unmount();
    client.clear();
  });

  it('preserves the empty-state observable (success with an empty result)', async () => {
    const client = appLikeClient();
    const fetcher = () => Promise.resolve<string[]>([]);

    const { result, unmount } = renderHook(
      () => useApiQuery(['shipments', 'store-empty'], fetcher),
      { wrapper: makeWrapper(client) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // A migrated page distinguishes empty-state from error: data is an empty
    // array, not an error.
    expect(result.current.data).toEqual([]);
    expect(result.current.isError).toBe(false);

    unmount();
    client.clear();
  });

  it('preserves the error-state observable with a human-readable message', async () => {
    // retry: false to settle immediately on the error path for this assertion.
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
    });
    const fetcher = () => Promise.reject(new Error('加载失败'));

    const { result, unmount } = renderHook(
      () => useApiQuery(['shipments', 'store-err'], fetcher),
      { wrapper: makeWrapper(client) },
    );

    await waitFor(() => expect(result.current.isError).toBe(true));
    // Equivalent to the old `error` string the page rendered.
    expect(result.current.error).toBeInstanceOf(Error);
    expect(result.current.error?.message).toBe('加载失败');

    unmount();
    client.clear();
  });
});
