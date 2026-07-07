// AdPilot AI — First-run onboarding wizard
//
// Small hook owning the wizard's dismissal/completion state. Dismissal is
// persisted in localStorage so a user who closes it (or completes setup) does
// not see it again on the next visit, while live status is still re-checked by
// the caller so a returning user who has NOT finished setup can be re-shown the
// wizard (and a manual re-open entry always works).

import { useCallback, useState } from 'react';

/** localStorage key persisting the user's dismissal of the onboarding wizard. */
export const ONBOARDING_DISMISSED_KEY = 'adpilot.onboarding.dismissed';

function readDismissed(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return window.localStorage.getItem(ONBOARDING_DISMISSED_KEY) === 'true';
  } catch {
    // localStorage may be unavailable (private mode / SSR); treat as not dismissed.
    return false;
  }
}

function writeDismissed(value: boolean): void {
  if (typeof window === 'undefined') return;
  try {
    if (value) window.localStorage.setItem(ONBOARDING_DISMISSED_KEY, 'true');
    else window.localStorage.removeItem(ONBOARDING_DISMISSED_KEY);
  } catch {
    // Ignore persistence failures — the in-memory state still drives the UI.
  }
}

export interface UseOnboardingResult {
  /** True when the user has dismissed (or completed) the wizard previously. */
  dismissed: boolean;
  /** Persistently dismiss the wizard ("稍后再说" / X / "完成"). */
  dismiss: () => void;
  /** Manually re-open the wizard ("新手指引"), clearing the dismissal flag. */
  reopen: () => void;
}

/**
 * Manage the persisted dismissal flag for the onboarding wizard. The caller
 * combines `dismissed` with the live step status to decide whether to render
 * the wizard automatically.
 */
export function useOnboarding(): UseOnboardingResult {
  const [dismissed, setDismissed] = useState<boolean>(() => readDismissed());

  const dismiss = useCallback(() => {
    writeDismissed(true);
    setDismissed(true);
  }, []);

  const reopen = useCallback(() => {
    writeDismissed(false);
    setDismissed(false);
  }, []);

  return { dismissed, dismiss, reopen };
}

export default useOnboarding;
