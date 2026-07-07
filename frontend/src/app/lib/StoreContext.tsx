import { createContext, useContext, useState, useEffect, useCallback, useMemo, ReactNode } from 'react';
import { fetchStores, fetchCurrentUser } from './api';
import { selectDefaultStoreId, filterStoresByGroupScope } from './storeSwitcher';

interface StoreOption {
  id: string;
  name: string;
  marketplaceId?: string;
  marketplaceName?: string;
  marketplaceCode?: string;
  marketplaceCurrency?: string;
  marketplaceTimezone?: string;
  status?: string;
  /** Normalized platform family: amazon | shopify | woocommerce | tiktok. */
  platform?: string;
  /** Store platform group: marketplace | independent_site | unknown. */
  platformGroup?: string;
  /** Default ad platform for this store: amazon_ads | google_ads | tiktok_ads | none. */
  adPlatform?: string;
  /** Optional group label used to organize stores in the switcher (Req 5.2.4). */
  storeGroup?: string | null;
  /** First-class Store_Group id the store belongs to; used to scope the
   *  switcher to the account's Store_Group_Scope (platform-workspace-rbac
   *  Req 13.5). */
  storeGroupId?: string | null;
}

interface StoreContextValue {
  stores: StoreOption[];
  storeId: string | null;
  setStoreId: (id: string) => void;
  loading: boolean;
  error: string | null;
  reload: () => void;
}

const STORAGE_KEY = 'adpilot.currentStoreId';

const StoreContext = createContext<StoreContextValue | undefined>(undefined);

/**
 * Loads the stores the current user may access (the backend already scopes this
 * to assigned/owned stores for non-admins, satisfying Req 5.2.1) and tracks the
 * currently selected store. The user's configured default store is selected
 * when present (Req 5.2.3); otherwise a previously-saved selection persists
 * across reloads via localStorage. All pages read the active store through
 * {@link useStoreId} so data is filtered by the store the user actually picked.
 */
export function StoreProvider({ children }: { children: ReactNode }) {
  const [stores, setStores] = useState<StoreOption[]>([]);
  const [storeId, setStoreIdState] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const setStoreId = useCallback((id: string) => {
    setStoreIdState(id);
    try { localStorage.setItem(STORAGE_KEY, id); } catch { /* ignore */ }
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // Load the accessible stores and the user's configured default in
      // parallel; a failed "me" lookup must not block the store list.
      const [data, me] = await Promise.all([
        fetchStores(),
        fetchCurrentUser().catch(() => null),
      ]);
      const fetched: StoreOption[] = Array.isArray(data) ? data : [];
      // Restrict the switcher to stores whose Store_Group is within the
      // account's Store_Group_Scope (Req 13.5). The backend already scopes the
      // list; this is defense-in-depth so an out-of-scope store is never
      // offered. An unrestricted scope (super-admin / not loaded) keeps all.
      const list = filterStoresByGroupScope(fetched, me?.storeGroupScope ?? null);
      setStores(list);

      const saved = (() => { try { return localStorage.getItem(STORAGE_KEY); } catch { return null; } })();
      // The configured default store wins on login (Req 5.2.3); otherwise fall
      // back to the saved selection, then the first accessible store.
      const selected = selectDefaultStoreId(list, {
        defaultStoreId: me?.defaultStoreId ?? null,
        savedStoreId: saved,
      });
      setStoreIdState(selected);
      if (selected) {
        try { localStorage.setItem(STORAGE_KEY, selected); } catch { /* ignore */ }
      }
    } catch (err: any) {
      setError(err?.message || '加载店铺失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const value = useMemo<StoreContextValue>(
    () => ({ stores, storeId, setStoreId, loading, error, reload: load }),
    [stores, storeId, setStoreId, loading, error, load],
  );

  return (
    <StoreContext.Provider value={value}>
      {children}
    </StoreContext.Provider>
  );
}

export function useStoreContext(): StoreContextValue {
  const ctx = useContext(StoreContext);
  if (!ctx) {
    // Fallback when used outside the provider (should not happen in app routes).
    return { stores: [], storeId: null, setStoreId: () => { }, loading: false, error: null, reload: () => { } };
  }
  return ctx;
}
