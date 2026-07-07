import { useStoreContext } from './StoreContext';

/**
 * Returns the currently SELECTED store id (from the global store switcher),
 * not a hardcoded default. Pages keep using `{ storeId, loading, error }`
 * unchanged, but the value now follows whatever store the user picked in the
 * header switcher and is scoped to the stores they are allowed to access.
 */
export function useStoreId() {
  const { storeId, loading, error } = useStoreContext();
  return { storeId, loading, error };
}
