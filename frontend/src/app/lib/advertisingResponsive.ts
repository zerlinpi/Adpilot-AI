// AdPilot AI — Advertising responsive strategy decision
// (advertising-workspace-rework Req 43.2, 43.3, 43.4)
//
// Advertising is a desktop-dense workflow: the full table and full editing
// experience are a desktop experience and the module must NOT blanket-convert
// every table to cards on mobile (Req 43.1). Instead, 768px is defined as the
// minimum width at which the full table and full editing controls render
// (Req 43.3); below it the module presents a read-oriented alternative (a
// stacked list of the key fields) that offers ONLY view / pause / approve and
// never full bulk editing (Req 43.2); and the UI switches between the two as
// the threshold is crossed (Req 43.4).
//
// This module is the PURE decision core — given a viewport width it resolves
// the mode and the allowed actions — so the threshold behavior can be
// property-tested in isolation (task 20.8, Property 73) and reused by the
// `useResponsiveMode` hook and the workspace shell.

/**
 * The minimum viewport width (px) at which the full advertising table and full
 * editing controls render (Req 43.3). At or above this width the experience is
 * the desktop experience; below it the read-oriented alternative is shown.
 */
export const ADVERTISING_DESKTOP_MIN_WIDTH = 768;

/**
 * The two responsive presentations:
 *  - `desktop` — full table + full editing controls (width ≥ threshold).
 *  - `compact` — read-oriented stacked list, view/pause/approve only.
 */
export type ResponsiveMode = 'desktop' | 'compact';

/**
 * The actions the compact (below-threshold) presentation is allowed to offer
 * (Req 43.2). Full bulk editing is deliberately excluded on small screens.
 */
export const COMPACT_ALLOWED_ACTIONS = ['view', 'pause', 'approve'] as const;

/** A row action subject to responsive gating. */
export type AdvertisingRowAction =
  | 'view'
  | 'pause'
  | 'approve'
  | 'edit'
  | 'delete'
  | 'bulkEdit'
  | (string & {});

/**
 * Resolve the responsive mode for a viewport width. At or above
 * {@link ADVERTISING_DESKTOP_MIN_WIDTH} the full desktop table renders
 * (`desktop`); strictly below it the read-oriented alternative renders
 * (`compact`). Non-finite widths are treated as the desktop default so the full
 * experience is the safe fallback when a width cannot be measured.
 */
export function resolveResponsiveMode(viewportWidth: number): ResponsiveMode {
  if (!Number.isFinite(viewportWidth)) return 'desktop';
  return viewportWidth >= ADVERTISING_DESKTOP_MIN_WIDTH ? 'desktop' : 'compact';
}

/**
 * Whether full editing controls (full bulk editing, inline edit, delete) are
 * rendered at this width. True iff the mode is `desktop` (Req 43.2, 43.3).
 */
export function isFullEditingEnabled(viewportWidth: number): boolean {
  return resolveResponsiveMode(viewportWidth) === 'desktop';
}

/**
 * The set of actions allowed at a viewport width. Desktop allows everything
 * (full editing); compact is restricted to view / pause / approve (Req 43.2).
 */
export function allowedActionsForMode(
  mode: ResponsiveMode,
): readonly AdvertisingRowAction[] | 'all' {
  return mode === 'desktop' ? 'all' : COMPACT_ALLOWED_ACTIONS;
}

/**
 * Whether a specific action is permitted at a viewport width. On desktop every
 * action is permitted; on compact only view / pause / approve are (Req 43.2).
 */
export function isActionAllowed(
  viewportWidth: number,
  action: AdvertisingRowAction,
): boolean {
  if (resolveResponsiveMode(viewportWidth) === 'desktop') return true;
  return (COMPACT_ALLOWED_ACTIONS as readonly string[]).includes(action);
}
