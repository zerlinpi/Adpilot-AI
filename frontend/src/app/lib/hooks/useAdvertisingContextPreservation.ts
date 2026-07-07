// AdPilot AI — useAdvertisingContextPreservation
// (advertising-workspace-rework Req 40.1, 45.1, 45.2, 45.3)
//
// Orchestrates the advertising workspace's cross-tab working context on top of
// the pure helpers in `../advertisingContextPreservation`:
//
//   - On every tab switch it CAPTURES the leaving tab's query state (the URL
//     single-source-of-truth), its row selection, and any unsaved draft, then
//     RESTORES the entering tab's preserved context — re-applying its filters
//     (Req 45.1), its selection for rows still present (Req 45.2), and leaving
//     its unsaved draft intact (Req 45.3).
//   - On an Active_Store switch it CLEARS all preserved context and resets the
//     query state so context from the previous store is never carried across
//     (Req 40.1).
//
// Tabs opt into selection/draft preservation through the {@link
// AdvertisingContextRegistry} React context (selection + draft + present-row
// registration). Query-state (filter) preservation works without any tab
// changes because it is driven entirely from the URL state this hook owns.

import {
  createContext,
  createElement,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from 'react';

import { useAdvertisingQueryState } from './useAdvertisingQueryState';
import { useResponsiveMode } from './useResponsiveMode';
import type { ResponsiveMode } from '../advertisingResponsive';
import type { CampaignsTabKey } from '../../pages/campaigns/CampaignsWorkspace';
import {
  captureTabContext,
  emptyContextStore,
  restoreTabContext,
  type AdvertisingContextStore,
  type DraftState,
} from '../advertisingContextPreservation';

/** The registry tab content uses to participate in context preservation. */
export interface AdvertisingContextRegistry {
  /** The active tab key. */
  activeTab: CampaignsTabKey;
  /** Live responsive mode for the workspace (Req 43). */
  responsiveMode: ResponsiveMode;
  /** Currently restored selection for a tab (intersected with present rows). */
  getSelection: (tab: CampaignsTabKey) => readonly string[];
  /** Report the current row selection for a tab (so it can be preserved). */
  setSelection: (tab: CampaignsTabKey, selectedRowIds: readonly string[]) => void;
  /** The preserved draft for a tab, if any. */
  getDraft: (tab: CampaignsTabKey) => DraftState | null;
  /** Report a tab's unsaved in-progress edit (null clears it). */
  setDraft: (tab: CampaignsTabKey, draft: DraftState | null) => void;
  /** Report the row ids currently present in a tab's data (for restore). */
  setPresentRows: (tab: CampaignsTabKey, presentRowIds: readonly string[]) => void;
}

const AdvertisingContextRegistryContext =
  createContext<AdvertisingContextRegistry | null>(null);

/**
 * Consume the advertising context registry from within tab content. Returns
 * `null` when used outside the provider (the tab then simply does not
 * participate in preservation).
 */
export function useAdvertisingContextRegistry(): AdvertisingContextRegistry | null {
  return useContext(AdvertisingContextRegistryContext);
}

export interface UseAdvertisingContextPreservationOptions {
  /** The Active_Store id; a change clears all preserved context (Req 40.1). */
  storeId?: string | number | null;
  /** The tab active on first render. */
  initialTab?: CampaignsTabKey;
}

export interface AdvertisingContextPreservationApi {
  /** The active tab (controlled value to feed the workspace shell). */
  activeTab: CampaignsTabKey;
  /** Tab-change handler that performs the capture→restore round-trip. */
  onTabChange: (next: CampaignsTabKey) => void;
  /** Live responsive mode for the workspace shell. */
  responsiveMode: ResponsiveMode;
  /** True iff any tab currently has an unsaved in-progress edit (Req 40.2). */
  hasUnsavedEdits: boolean;
  /** Provider that exposes the registry to the workspace's tab content. */
  ContextProvider: (props: { children: ReactNode }) => ReactElement;
}

/**
 * Drive cross-tab context preservation and store-switch clearing for the
 * advertising workspace. Intended to be used by the host page, which feeds
 * `activeTab`/`onTabChange` to the (controlled) `AdvertisingWorkspace` shell and
 * wraps it in {@link AdvertisingContextPreservationApi.ContextProvider}.
 */
export function useAdvertisingContextPreservation(
  options: UseAdvertisingContextPreservationOptions = {},
): AdvertisingContextPreservationApi {
  const { storeId, initialTab = 'campaigns' } = options;
  const query = useAdvertisingQueryState();
  const responsiveMode = useResponsiveMode();

  const [activeTab, setActiveTab] = useState<CampaignsTabKey>(initialTab);

  // Immutable per-tab query/selection/draft store; refs hold the live, per-tab
  // selection / draft / present-rows reported by tab content.
  const storeRef = useRef<AdvertisingContextStore>(emptyContextStore());
  const selectionRef = useRef<Record<string, readonly string[]>>({});
  const draftRef = useRef<Record<string, DraftState | null>>({});
  const presentRowsRef = useRef<Record<string, readonly string[]>>({});

  // The selection restored for the active tab, surfaced to tab content.
  const [restoredSelection, setRestoredSelection] = useState<
    Record<string, readonly string[]>
  >({});
  const [hasUnsavedEdits, setHasUnsavedEdits] = useState(false);

  const recomputeUnsaved = useCallback(() => {
    setHasUnsavedEdits(
      Object.values(draftRef.current).some(
        (d) => d != null && Object.keys(d).length > 0,
      ),
    );
  }, []);

  // ── Store-switch guard (Req 40.1) ─────────────────────────────────────────
  // When the Active_Store changes, drop every tab's preserved context and reset
  // the query state so selection/drafts/filters do not carry across stores.
  const prevStoreRef = useRef<string | number | null | undefined>(storeId);
  useEffect(() => {
    if (prevStoreRef.current === storeId) return;
    prevStoreRef.current = storeId;
    storeRef.current = emptyContextStore();
    selectionRef.current = {};
    draftRef.current = {};
    presentRowsRef.current = {};
    setRestoredSelection({});
    setHasUnsavedEdits(false);
    query.reset();
    // `query.reset` identity is stable; intentionally keyed on storeId only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storeId]);

  // ── Tab-switch capture → restore round-trip (Req 45.1–45.3) ────────────────
  const onTabChange = useCallback(
    (next: CampaignsTabKey) => {
      if (next === activeTab) return;

      // Capture the leaving tab's context: query (URL), selection, draft.
      storeRef.current = captureTabContext(storeRef.current, activeTab, {
        query: query.state,
        selectedRowIds: selectionRef.current[activeTab] ?? [],
        draft: draftRef.current[activeTab] ?? null,
      });

      // Restore the entering tab's context. Selection is intersected with the
      // rows currently present for that tab; query/filters are re-applied.
      const restored = restoreTabContext(
        storeRef.current,
        next,
        presentRowsRef.current[next] ?? [],
      );
      query.patch(restored.query);
      selectionRef.current[next] = restored.selectedRowIds;
      setRestoredSelection((prev) => ({
        ...prev,
        [next]: restored.selectedRowIds,
      }));
      setActiveTab(next);
    },
    [activeTab, query],
  );

  // ── Registry exposed to tab content ────────────────────────────────────────
  const registry = useMemo<AdvertisingContextRegistry>(
    () => ({
      activeTab,
      responsiveMode,
      getSelection: (tab) => restoredSelection[tab] ?? selectionRef.current[tab] ?? [],
      setSelection: (tab, ids) => {
        selectionRef.current[tab] = ids;
      },
      getDraft: (tab) => draftRef.current[tab] ?? null,
      setDraft: (tab, draft) => {
        draftRef.current[tab] = draft;
        recomputeUnsaved();
      },
      setPresentRows: (tab, ids) => {
        presentRowsRef.current[tab] = ids;
      },
    }),
    [activeTab, responsiveMode, restoredSelection, recomputeUnsaved],
  );

  const ContextProvider = useCallback(
    ({ children }: { children: ReactNode }): ReactElement =>
      createElement(
        AdvertisingContextRegistryContext.Provider,
        { value: registry },
        children,
      ),
    [registry],
  );

  return {
    activeTab,
    onTabChange,
    responsiveMode,
    hasUnsavedEdits,
    ContextProvider,
  };
}
