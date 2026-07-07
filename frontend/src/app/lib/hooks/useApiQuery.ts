// AdPilot AI — Data-fetching hooks (Req 3.2, 3.3, 3.4, 3.6)
//
// Thin wrappers over react-query's `useQuery` / `useMutation`. The fetchers and
// mutation functions are the `lib/api.ts` helpers, which already unwrap the
// `ApiResponse<T>` envelope and throw an `Error` carrying the backend's
// human-readable `error.message` on failure. As a result:
//
// - `useApiQuery` exposes `isLoading` (true while in flight), `isError`/`error`
//   (the readable message), and `data` already unwrapped from the envelope
//   (Req 3.2, 3.4).
// - Failed reads keep the last successfully cached data (react-query retains the
//   prior `data` on a background refetch error), so pages can show an error
//   indicator without clearing the table (Req 3.4).
// - Reads inherit the app-level defaults from `queryClient` (staleTime 30s,
//   gcTime 300s, retry 3) unless a caller overrides them (Req 3.5, 3.9).
// - Mutations never auto-retry (mutation retry 0 in `queryClient`); the manual
//   retry is simply re-invoking the returned `mutate`/`mutateAsync` (Req 3.6).
// - On write success, hosts call `queryClient.invalidateQueries({ queryKey:
//   [resource, storeId] })` to refresh affected reads (Req 3.3). Hosts may pass
//   an `onSuccess` to do this, or read `queryClient` from `useQueryClient()`.
//
// These hooks coexist with not-yet-migrated `useEffect`-based pages (Req 3.7, 3.10).

import {
  useMutation,
  useQuery,
  type QueryKey,
  type UseMutationOptions,
  type UseMutationResult,
  type UseQueryOptions,
  type UseQueryResult,
} from '@tanstack/react-query';

/**
 * Read a backend resource through react-query.
 *
 * @param key     A `[resource, storeId, ...params]` tuple from `qk` (Req 3.8).
 * @param fetcher An `lib/api.ts` call returning the already-unwrapped data.
 * @param options Optional react-query overrides (e.g. `enabled`, `staleTime`).
 *                `queryKey`/`queryFn` are supplied by this wrapper.
 */
export function useApiQuery<T>(
  key: QueryKey,
  fetcher: () => Promise<T>,
  options?: Omit<UseQueryOptions<T, Error, T, QueryKey>, 'queryKey' | 'queryFn'>,
): UseQueryResult<T, Error> {
  return useQuery<T, Error, T, QueryKey>({
    queryKey: key,
    queryFn: fetcher,
    ...options,
  });
}

/**
 * Perform a write through react-query. Mutations do not auto-retry (Req 3.6);
 * the manual retry is re-invoking the returned `mutate`/`mutateAsync`. Hosts
 * typically pass an `onSuccess` that calls
 * `queryClient.invalidateQueries({ queryKey: [resource, storeId] })` to refresh
 * the affected reads after a successful write (Req 3.3).
 *
 * @param mutationFn The `lib/api.ts` write call (returns unwrapped data, throws on error).
 * @param options    Optional react-query overrides (e.g. `onSuccess`, `onError`).
 */
export function useApiMutation<TData, TVars = void>(
  mutationFn: (vars: TVars) => Promise<TData>,
  options?: Omit<UseMutationOptions<TData, Error, TVars>, 'mutationFn'>,
): UseMutationResult<TData, Error, TVars> {
  return useMutation<TData, Error, TVars>({
    mutationFn,
    ...options,
  });
}
