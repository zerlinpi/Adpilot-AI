// AdPilot AI — useUnsavedEditGuard
// (advertising-workspace-rework Req 40.2, 40.3)
//
// Guards against silently losing work: while an unsaved edit is in progress,
// attempting to leave the advertising module presents a confirmation
// (Req 40.2). The two exits are both covered:
//   - browser-level navigation/refresh/close via the `beforeunload` event, and
//   - in-app route navigation via react-router's data-router `useBlocker`.
// If the operator cancels the confirmation, navigation is aborted and the
// unsaved edit is preserved (Req 40.3); this hook never mutates the draft, so
// "remain and preserve" is the default outcome of a cancelled leave.

import { useEffect } from 'react';
import { useBlocker } from 'react-router';

/** Default copy shown when leaving with unsaved edits (Req 40.2). */
export const UNSAVED_EDIT_PROMPT = '有未保存的修改，确定要离开吗？未保存的修改将保留在当前页面。';

export interface UnsavedEditGuardOptions {
  /** Whether there is at least one unsaved in-progress edit to protect. */
  hasUnsavedEdits: boolean;
  /** Confirmation copy. Defaults to {@link UNSAVED_EDIT_PROMPT}. */
  message?: string;
  /**
   * Test seam: the confirm implementation. Defaults to `window.confirm`.
   * Returns `true` to allow leaving, `false` to remain.
   */
  confirm?: (message: string) => boolean;
}

/**
 * Installs the unsaved-edit leave guards. Active only while `hasUnsavedEdits`
 * is true; otherwise navigation is unguarded.
 */
export function useUnsavedEditGuard({
  hasUnsavedEdits,
  message = UNSAVED_EDIT_PROMPT,
  confirm,
}: UnsavedEditGuardOptions): void {
  // In-app navigation guard: block route changes while edits are pending and
  // ask the operator. Cancelling resets the blocker so we remain in place with
  // the edit intact (Req 40.3).
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      hasUnsavedEdits && currentLocation.pathname !== nextLocation.pathname,
  );

  useEffect(() => {
    if (blocker.state !== 'blocked') return;
    const ask = confirm ?? ((m: string) => window.confirm(m));
    if (ask(message)) {
      blocker.proceed();
    } else {
      blocker.reset();
    }
  }, [blocker, message, confirm]);

  // Browser-level guard: refresh / tab close / external navigation. The browser
  // shows its own native confirmation when `preventDefault` is called.
  useEffect(() => {
    if (!hasUnsavedEdits) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      // Legacy browsers require returnValue to be set to trigger the prompt.
      event.returnValue = message;
      return message;
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [hasUnsavedEdits, message]);
}
