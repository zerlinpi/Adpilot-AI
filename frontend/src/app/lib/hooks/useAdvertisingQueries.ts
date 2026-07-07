// AdPilot AI — Advertising react-query hooks (Data_Fetching_Layer)
// (advertising-workspace-rework Req 39.1, 39.2, 39.3)
//
// These hooks wire the advertising list/detail reads to react-query so that the
// FilterToolbar, the KpiPanel, and the SharedDataTable all fetch from ONE set of
// query conditions — the state owned by `useAdvertisingQueryState` and encoded in
// the page URL (Req 39.1). Because every list hook derives BOTH its Query_Key and
// its request params from that single state, any change to a filter / sort / page
// / date-range re-keys every dependent read together (Req 39.2) and no surface
// maintains a divergent copy of the conditions (Req 39.3): the URL drives fetching.
//
// The state→params mapping (`deriveRequestParams` and the per-endpoint adapters)
// is PURE so it can be unit-/property-tested without React, and so the produced
// params double as a stable, structural Query_Key segment (Req 3.8). Each hook is
// a thin wrapper over `useApiQuery`, inheriting the app-level read defaults and
// the unwrapped `ApiResponse<T>` semantics.

import {
  fetchCampaignById,
  fetchCampaigns,
  fetchGoalById,
  fetchGoals,
  fetchKeywords,
  fetchRecommendations,
  fetchSearchTerms,
  type CampaignVo,
  type PaginatedCampaignResponse,
} from '../api';
import { qk } from '../queryKeys';
import { ALL_SELECTION } from '../../components/table/types';
import {
  useAdvertisingQueryState,
  type AdvertisingQueryState,
  type SortDirection,
} from './useAdvertisingQueryState';
import { useApiQuery } from './useApiQuery';
import type { UseQueryOptions, UseQueryResult } from '@tanstack/react-query';

// ── pure state → request-params mapping ─────────────────────────────────────────

/**
 * The canonical, endpoint-neutral request params derived from an
 * {@link AdvertisingQueryState}. `page`/`pageSize` are always present; the search
 * term, sort, and date-range are included only when set; every active
 * type-selector is flattened to a top-level key so endpoint adapters can pick the
 * params they accept. This object is deterministic for a given state, so it also
 * serves as the structural Query_Key segment.
 */
export interface AdvertisingRequestParams {
  page: number;
  pageSize: number;
  search?: string;
  sortField?: string;
  sortDir?: SortDirection;
  startDate?: string;
  endDate?: string;
  /** Flattened type-selector values (e.g. `status`, `adType`, `matchType`). */
  [key: string]: string | number | undefined;
}

/**
 * Map an {@link AdvertisingQueryState} to {@link AdvertisingRequestParams}. Pure:
 * no React, no router, no globals — given the same state it always returns the
 * same params, which is what lets the result be used as a stable Query_Key.
 */
export function deriveRequestParams(
  state: AdvertisingQueryState,
): AdvertisingRequestParams {
  const params: AdvertisingRequestParams = {
    page: state.page,
    pageSize: state.pageSize,
  };

  if (state.filters.search) params.search = state.filters.search;

  for (const [key, value] of Object.entries(state.filters.typeSelections)) {
    // Skip the "no filter" sentinel and empty selections so they don't perturb
    // the Query_Key or send meaningless filters to the backend.
    if (value && value !== ALL_SELECTION) params[key] = value;
  }

  if (state.sort) {
    params.sortField = state.sort.field;
    params.sortDir = state.sort.direction;
  }

  if (state.dateRange) {
    params.startDate = state.dateRange.start;
    params.endDate = state.dateRange.end;
  }

  return params;
}

/** Read one derived param as a string (or `undefined`). */
function str(params: AdvertisingRequestParams, key: string): string | undefined {
  const v = params[key];
  return typeof v === 'string' ? v : v == null ? undefined : String(v);
}

// ── shared option type ──────────────────────────────────────────────────────────

/** react-query overrides accepted by the advertising hooks (key/fn are managed). */
export type AdvertisingQueryOptions<T> = Omit<
  UseQueryOptions<T, Error, T, readonly unknown[]>,
  'queryKey' | 'queryFn'
>;

// ── list hooks (driven by the single-source-of-truth query state) ───────────────

/**
 * Campaigns for a store, filtered/sorted/paged by the shared advertising query
 * state. Returns the paginated response so the table can render `total`.
 * Disabled until a `storeId` is resolved.
 */
export function useAdvertisingCampaigns(
  storeId: string | null | undefined,
  options?: AdvertisingQueryOptions<PaginatedCampaignResponse>,
): UseQueryResult<PaginatedCampaignResponse, Error> {
  const { state } = useAdvertisingQueryState();
  const params = deriveRequestParams(state);
  return useApiQuery<PaginatedCampaignResponse>(
    qk.campaigns(storeId ?? undefined, params),
    () =>
      fetchCampaigns({
        storeId: storeId!,
        page: params.page,
        pageSize: params.pageSize,
        goalId: str(params, 'goalId'),
        status: str(params, 'status'),
        adType: str(params, 'adType'),
        portfolioId: str(params, 'portfolioId'),
        parentAsin: str(params, 'parentAsin'),
        targetingGoal: str(params, 'targetingGoal'),
        smartFilter: str(params, 'smartFilter'),
        sortField: params.sortField,
        sortDir: params.sortDir,
      }),
    { enabled: !!storeId, ...options },
  );
}

/**
 * Keywords (投放) for a store, filtered by the shared query state's type-selectors
 * (`campaignId`, `adGroupId`, `matchType`, `status`). Disabled until `storeId`.
 */
export function useAdvertisingKeywords<T = unknown>(
  storeId: string | null | undefined,
  options?: AdvertisingQueryOptions<T[]>,
): UseQueryResult<T[], Error> {
  const { state } = useAdvertisingQueryState();
  const params = deriveRequestParams(state);
  return useApiQuery<T[]>(
    qk.keywords(storeId ?? undefined, params),
    () =>
      fetchKeywords({
        campaignId: str(params, 'campaignId'),
        adGroupId: str(params, 'adGroupId'),
        matchType: str(params, 'matchType'),
        status: str(params, 'status'),
      }) as Promise<T[]>,
    { enabled: !!storeId, ...options },
  );
}

/**
 * Search terms (搜索词) for a store. The shared date-range drives the reporting
 * window; `campaignId`/`harvestingStatus` come from the type-selectors. Disabled
 * until `storeId`.
 */
export function useAdvertisingSearchTerms<T = unknown>(
  storeId: string | null | undefined,
  options?: AdvertisingQueryOptions<T[]>,
): UseQueryResult<T[], Error> {
  const { state } = useAdvertisingQueryState();
  const params = deriveRequestParams(state);
  return useApiQuery<T[]>(
    qk.searchTerms(storeId ?? undefined, params),
    () =>
      fetchSearchTerms({
        campaignId: str(params, 'campaignId'),
        harvestingStatus: str(params, 'harvestingStatus'),
        startDate: str(params, 'startDate'),
        endDate: str(params, 'endDate'),
      }) as Promise<T[]>,
    { enabled: !!storeId, ...options },
  );
}

/**
 * AI recommendations (优化建议) for a store, filtered by the shared query state's
 * `status`/`riskLevel`/`type` selectors. Disabled until `storeId`.
 */
export function useAdvertisingRecommendations<T = unknown>(
  storeId: string | null | undefined,
  options?: AdvertisingQueryOptions<T[]>,
): UseQueryResult<T[], Error> {
  const { state } = useAdvertisingQueryState();
  const params = deriveRequestParams(state);
  return useApiQuery<T[]>(
    qk.recommendations(storeId ?? undefined, params),
    () =>
      fetchRecommendations({
        storeId: storeId!,
        status: str(params, 'status'),
        riskLevel: str(params, 'riskLevel'),
        type: str(params, 'type'),
      }) as Promise<T[]>,
    { enabled: !!storeId, ...options },
  );
}

/**
 * Goals (广告目标) for a store. Goals are store-scoped only, but the hook still
 * keys on the derived params so it shares the single-source-of-truth state and
 * re-keys consistently with the other surfaces. Disabled until `storeId`.
 */
export function useAdvertisingGoals<T = unknown>(
  storeId: string | null | undefined,
  options?: AdvertisingQueryOptions<T[]>,
): UseQueryResult<T[], Error> {
  const { state } = useAdvertisingQueryState();
  const params = deriveRequestParams(state);
  return useApiQuery<T[]>(
    qk.goals(storeId ?? undefined, params),
    () => fetchGoals(storeId ?? undefined) as Promise<T[]>,
    { enabled: !!storeId, ...options },
  );
}

// ── detail hooks ────────────────────────────────────────────────────────────────

/** A single campaign by id; disabled until an `id` is present. */
export function useAdvertisingCampaignDetail(
  id: string | null | undefined,
  options?: AdvertisingQueryOptions<CampaignVo>,
): UseQueryResult<CampaignVo, Error> {
  return useApiQuery<CampaignVo>(
    qk.campaignDetail(id ?? ''),
    () => fetchCampaignById(id!),
    { enabled: !!id, ...options },
  );
}

/** A single goal by id; disabled until an `id` is present. */
export function useAdvertisingGoalDetail<T = unknown>(
  id: string | null | undefined,
  options?: AdvertisingQueryOptions<T>,
): UseQueryResult<T, Error> {
  return useApiQuery<T>(
    qk.goalDetail(id ?? ''),
    () => fetchGoalById(id!) as Promise<T>,
    { enabled: !!id, ...options },
  );
}
