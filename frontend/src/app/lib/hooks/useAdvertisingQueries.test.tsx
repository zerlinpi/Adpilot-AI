// Example tests for the advertising react-query hooks (Task 18.2).
//
// Two concerns are covered:
//   1. The PURE state→params mapping (`deriveRequestParams`) — the heart of the
//      single-source-of-truth wiring (Req 39.1–39.3): page/pageSize always
//      present, search/sort/date-range included only when set, type-selectors
//      flattened, and the "all" sentinel dropped.
//   2. The hook wiring — a list hook reads the query state from the URL, derives
//      its request params, and fetches through react-query, so the URL drives
//      fetching and is disabled until a store is resolved.

import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ReactNode } from 'react';
import React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';

import { ALL_SELECTION } from '../../components/table/types';
import { emptyQueryState, type AdvertisingQueryState } from '../advertisingQueryState';
import { deriveRequestParams, useAdvertisingCampaigns } from './useAdvertisingQueries';

// Mock the network seam so no real request is made.
vi.mock('../api', () => ({
  fetchCampaigns: vi.fn(),
  fetchCampaignById: vi.fn(),
  fetchKeywords: vi.fn(),
  fetchSearchTerms: vi.fn(),
  fetchRecommendations: vi.fn(),
  fetchGoals: vi.fn(),
  fetchGoalById: vi.fn(),
}));

import { fetchCampaigns } from '../api';

function makeState(over: Partial<AdvertisingQueryState>): AdvertisingQueryState {
  return { ...emptyQueryState(), ...over };
}

describe('deriveRequestParams — pure state→params mapping (Req 39)', () => {
  it('always includes page and pageSize from the state', () => {
    const params = deriveRequestParams(makeState({ page: 3, pageSize: 25 }));
    expect(params.page).toBe(3);
    expect(params.pageSize).toBe(25);
  });

  it('omits search, sort, and date-range when they are not set', () => {
    const params = deriveRequestParams(emptyQueryState());
    expect(params.search).toBeUndefined();
    expect(params.sortField).toBeUndefined();
    expect(params.sortDir).toBeUndefined();
    expect(params.startDate).toBeUndefined();
    expect(params.endDate).toBeUndefined();
  });

  it('includes search, sort, and date-range when set', () => {
    const params = deriveRequestParams(
      makeState({
        filters: { search: 'shoes', typeSelections: {}, conditions: [] },
        sort: { field: 'spend', direction: 'desc' },
        dateRange: { start: '2026-01-01', end: '2026-01-07' },
      }),
    );
    expect(params.search).toBe('shoes');
    expect(params.sortField).toBe('spend');
    expect(params.sortDir).toBe('desc');
    expect(params.startDate).toBe('2026-01-01');
    expect(params.endDate).toBe('2026-01-07');
  });

  it('flattens active type-selectors and drops the "all" sentinel and empties', () => {
    const params = deriveRequestParams(
      makeState({
        filters: {
          search: '',
          typeSelections: { status: 'enabled', adType: ALL_SELECTION, goalId: '' },
          conditions: [],
        },
      }),
    );
    expect(params.status).toBe('enabled');
    expect(params.adType).toBeUndefined();
    expect(params.goalId).toBeUndefined();
  });

  it('is deterministic — same state yields a deep-equal params object', () => {
    const state = makeState({
      page: 2,
      filters: { search: 'x', typeSelections: { status: 'paused' }, conditions: [] },
    });
    expect(deriveRequestParams(state)).toEqual(deriveRequestParams(state));
  });
});

describe('useAdvertisingCampaigns — wiring (Req 39)', () => {
  beforeEach(() => {
    vi.mocked(fetchCampaigns).mockReset();
  });

  function wrapper(initialUrl: string) {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
    });
    return ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[initialUrl]}>{children}</MemoryRouter>
      </QueryClientProvider>
    );
  }

  it('derives request params from the URL query state and fetches', async () => {
    vi.mocked(fetchCampaigns).mockResolvedValue({
      items: [], total: 0, page: 2, pageSize: 50, totalPages: 0,
    });

    const { result } = renderHook(() => useAdvertisingCampaigns('store-1'), {
      // q=shoes, status=enabled selector, page 2 — encoded by the query-state model.
      wrapper: wrapper('/ads?q=shoes&ts=%7B%22status%22%3A%22enabled%22%7D&page=2'),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchCampaigns).toHaveBeenCalledWith(
      expect.objectContaining({ storeId: 'store-1', page: 2, status: 'enabled' }),
    );
  });

  it('is disabled until a storeId is resolved (no fetch)', async () => {
    const { result } = renderHook(() => useAdvertisingCampaigns(null), {
      wrapper: wrapper('/ads'),
    });
    // Disabled queries never enter the fetching/loading-with-fetch state.
    expect(result.current.fetchStatus).toBe('idle');
    expect(fetchCampaigns).not.toHaveBeenCalled();
  });
});
