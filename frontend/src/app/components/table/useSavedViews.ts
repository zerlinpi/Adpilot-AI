// AdPilot AI — Reusable table building blocks
//
// useSavedViews — a React hook that wires a Shared_Data_Table to the per-user,
// store-independent saved-view endpoints (`/api/table-views`) through the
// react-query data layer. A Saved_View is a named combination of a table's
// column configuration, filters, and sort order (Req 2.11/2.13/2.14, 17.5).
//
// Responsibilities:
//   - list the current user's saved views for a `tableKey`, for later selection
//     (Req 2.11);
//   - save the current columns/filters/sort as a named view via the backend,
//     invalidating the saved-views query on success so the list refreshes
//     (Req 2.11);
//   - apply a selected view by decoding its persisted column configuration,
//     filters, and sort order so the host can re-apply them (Req 2.14);
//   - surface the backend's naming-conflict / limit indication (empty,
//     over-length, or duplicate name) as a readable message the caller can
//     render, while leaving the existing views unchanged (Req 2.13).

import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';

import {
  deleteSavedView,
  fetchSavedViews,
  saveSavedView,
  type SavedView,
} from '../../lib/api';
import { qk } from '../../lib/queryKeys';
import { useApiMutation, useApiQuery } from '../../lib/hooks/useApiQuery';
import type { FilterState } from './types';

/** Sort order persisted with a Saved_View. */
export interface SavedViewSortOrder {
  /** Column key to sort by. */
  field: string;
  /** Sort direction. */
  direction: 'asc' | 'desc';
}

/** Column configuration persisted with a Saved_View. */
export interface SavedViewColumns {
  /** Ordered list of all column keys (visible and hidden). */
  order?: string[];
  /** Keys of the columns hidden in this view. */
  hidden?: string[];
}

/**
 * The decoded combination a Saved_View captures: column configuration, filters,
 * and sort order. This is what the host serializes when saving and receives when
 * applying a view. All parts are optional so a view can omit any of them.
 */
export interface SavedViewConfig {
  columns?: SavedViewColumns;
  filters?: FilterState;
  sort?: SavedViewSortOrder | null;
}

export interface UseSavedViewsResult {
  /** The current user's saved views for this table key (Req 2.11). */
  views: SavedView[];
  /** True while the saved-views list is loading. */
  isLoading: boolean;
  /** True when the saved-views list failed to load. */
  isError: boolean;
  /** Readable message for a failed list load, or null. */
  listError: string | null;

  /**
   * Save the current columns/filters/sort as a named view (Req 2.11). Resolves
   * with the created {@link SavedView}; rejects with an Error whose message is
   * the backend's naming-conflict / limit indication (Req 2.13). The
   * saved-views list is invalidated on success so {@link views} refreshes.
   */
  saveView: (name: string, config: SavedViewConfig) => Promise<SavedView>;
  /** True while a save is in flight. */
  isSaving: boolean;
  /**
   * The most recent save rejection message — the backend's naming-conflict or
   * limit indication (empty / over-length / duplicate name) — or null when the
   * last save succeeded or none has run (Req 2.13).
   */
  saveError: string | null;

  /**
   * Decode a saved view's persisted column configuration, filters, and sort
   * order so the host can re-apply them (Req 2.14). Accepts a {@link SavedView}
   * or a view id; returns null when the id is unknown or the stored config is
   * unreadable.
   */
  applyView: (view: SavedView | string) => SavedViewConfig | null;

  /** Delete a saved view by id; invalidates the list on success. */
  deleteView: (id: string) => Promise<void>;
  /** True while a delete is in flight. */
  isDeleting: boolean;

  /** Re-fetch the saved-views list. */
  refetch: () => void;
}

/**
 * Serialize a {@link SavedViewConfig} into the opaque JSON string the backend
 * persists. An undefined config is stored as an empty object.
 */
export function serializeSavedViewConfig(config?: SavedViewConfig): string {
  return JSON.stringify(config ?? {});
}

/**
 * Decode the backend's persisted config string into a {@link SavedViewConfig}.
 * Returns null when the string is missing or not valid JSON, so a corrupt record
 * can never crash the host (it simply cannot be applied).
 */
export function parseSavedViewConfig(config?: string | null): SavedViewConfig | null {
  if (!config) return {};
  try {
    const parsed = JSON.parse(config);
    if (parsed && typeof parsed === 'object') return parsed as SavedViewConfig;
    return null;
  } catch {
    return null;
  }
}

/**
 * Wire a table to its per-user saved views. `tableKey` is the host table's
 * stable identity; the hook is disabled until a non-empty key is supplied.
 */
export function useSavedViews(tableKey: string): UseSavedViewsResult {
  const queryClient = useQueryClient();

  const listQuery = useApiQuery<SavedView[]>(
    qk.savedViews(tableKey),
    () => fetchSavedViews(tableKey),
    { enabled: Boolean(tableKey) },
  );

  const invalidate = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: qk.savedViews(tableKey) });
  }, [queryClient, tableKey]);

  const saveMutation = useApiMutation<SavedView, { name: string; config: SavedViewConfig }>(
    ({ name, config }) =>
      saveSavedView({ tableKey, name, config: serializeSavedViewConfig(config) }),
    { onSuccess: invalidate },
  );

  const deleteMutation = useApiMutation<void, string>((id) => deleteSavedView(id), {
    onSuccess: invalidate,
  });

  const views = listQuery.data ?? [];

  const saveView = useCallback(
    (name: string, config: SavedViewConfig) => saveMutation.mutateAsync({ name, config }),
    [saveMutation],
  );

  const applyView = useCallback(
    (view: SavedView | string): SavedViewConfig | null => {
      const target = typeof view === 'string' ? views.find((v) => v.id === view) : view;
      if (!target) return null;
      return parseSavedViewConfig(target.config);
    },
    [views],
  );

  const deleteView = useCallback(
    (id: string) => deleteMutation.mutateAsync(id),
    [deleteMutation],
  );

  return {
    views,
    isLoading: listQuery.isLoading,
    isError: listQuery.isError,
    listError: listQuery.error?.message ?? null,

    saveView,
    isSaving: saveMutation.isPending,
    saveError: saveMutation.error?.message ?? null,

    applyView,

    deleteView,
    isDeleting: deleteMutation.isPending,

    refetch: () => {
      void listQuery.refetch();
    },
  };
}

export default useSavedViews;
