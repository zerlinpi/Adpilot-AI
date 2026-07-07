// AdPilot AI — Explicit UI-state resolution + in-progress control model for the
// Shared_Data_Table (advertising-workspace-rework Req 41.1–41.5, Req 36.3).
//
// Everything here is PURE (no React, no DOM) so the view-state precedence and
// the double-submission guard can be unit-/property-tested in isolation
// (tasks 19.16 / 19.17) and reused by any advertising surface.
//
// Validates: Requirements 36.3, 41.1, 41.2, 41.3, 41.4, 41.5

/**
 * The mutually-exclusive UI states every advertising view must resolve to, so
 * the UI never shows an ambiguous blank or misleading screen (Req 41).
 *
 *   - `loading`        — an advertising data request is in flight (Req 41.1)
 *   - `empty`          — the query returned no records (Req 41.2), distinct
 *                        from `loading`
 *   - `partial-error`  — the response partially failed; identify what failed
 *                        while still showing the successful content (Req 41.3)
 *   - `stale-cache`    — cached content that is known to be out of date (Req 41.4)
 *   - `no-permission`  — the user lacks permission to view the surface, shown
 *                        instead of an empty table (Req 41.5)
 *   - `content`        — the table itself
 */
export type ViewState =
  | 'loading'
  | 'empty'
  | 'partial-error'
  | 'stale-cache'
  | 'no-permission'
  | 'content';

/** Inputs that determine the single explicit view state for a surface. */
export interface ViewStateInputs {
  /**
   * Whether the logged-in user may view this advertising surface. Defaults to
   * `true`. When `false`, the no-permission state is shown rather than an empty
   * table (Req 41.5).
   */
  hasPermission?: boolean;
  /** Whether an advertising data request is currently in flight (Req 41.1). */
  loading?: boolean;
  /**
   * Whether the response failed in whole or in part. When `true` the view
   * resolves to `partial-error`: the renderer identifies what failed while
   * still showing whatever content loaded (Req 41.3).
   */
  hasError?: boolean;
  /** Whether the shown content is cached data known to be stale (Req 41.4). */
  stale?: boolean;
  /** Number of records available to render; `0` means an empty result (Req 41.2). */
  rowCount: number;
}

/**
 * Resolve a view to EXACTLY ONE explicit UI state (Req 41.1–41.5). The function
 * is total and deterministic over its inputs, so for any combination of signals
 * it returns one — and only one — of the {@link ViewState} values.
 *
 * Precedence (highest first), chosen so the surface never shows a misleading
 * screen:
 *   1. `no-permission` — if the user cannot view the surface at all, that
 *      overrides everything else (shown instead of an empty table, Req 41.5).
 *   2. `loading`       — an in-flight request takes precedence over
 *      stale/empty/content so we never flash a stale-or-empty screen mid-fetch
 *      (Req 41.1).
 *   3. `partial-error` — a failure signal is surfaced over staleness/empty so
 *      the operator sees what failed (Req 41.3).
 *   4. `stale-cache`   — content is shown but flagged out of date (Req 41.4).
 *   5. `empty`         — a genuine zero-record result, distinct from loading
 *      (Req 41.2).
 *   6. `content`       — the table itself.
 */
export function resolveViewState(inputs: ViewStateInputs): ViewState {
  const hasPermission = inputs.hasPermission ?? true;
  const loading = inputs.loading ?? false;
  const hasError = inputs.hasError ?? false;
  const stale = inputs.stale ?? false;
  const rowCount =
    Number.isFinite(inputs.rowCount) && inputs.rowCount > 0
      ? Math.floor(inputs.rowCount)
      : 0;

  if (!hasPermission) return 'no-permission';
  if (loading) return 'loading';
  if (hasError) return 'partial-error';
  if (stale) return 'stale-cache';
  if (rowCount === 0) return 'empty';
  return 'content';
}

// ── In-progress control model (Req 36.3) ───────────────────────────────────
//
// A control that has initiated an async action must be disabled until that
// action completes, so the SAME Operation cannot be submitted again (no double
// submission). We model the set of currently in-progress controls keyed by a
// stable control id; `beginAction` reports whether an activation is ALLOWED to
// start (it is rejected when the same control is already running).

/** The set of controls currently running an async action, keyed by control id. */
export type InProgressControls = ReadonlySet<string>;

/** The empty in-progress set (no control is running). */
export const EMPTY_IN_PROGRESS: InProgressControls = Object.freeze(
  new Set<string>(),
) as InProgressControls;

/** Whether `id` is currently in progress (and therefore must be disabled). */
export function isControlInProgress(
  state: InProgressControls,
  id: string,
): boolean {
  return state.has(id);
}

export interface BeginActionResult {
  /** The next in-progress set (unchanged when the activation is rejected). */
  state: InProgressControls;
  /**
   * Whether the activation is allowed to start. `false` when the control is
   * already in progress — the duplicate activation must be ignored (Req 36.3).
   */
  allowed: boolean;
}

/**
 * Begin an async action for `id`. If the control is already in progress the
 * activation is rejected (`allowed: false`) and the state is left unchanged, so
 * the same Operation cannot be submitted again until it completes (Req 36.3).
 */
export function beginAction(
  state: InProgressControls,
  id: string,
): BeginActionResult {
  if (state.has(id)) {
    return { state, allowed: false };
  }
  const next = new Set(state);
  next.add(id);
  return { state: next, allowed: true };
}

/**
 * Mark the async action for `id` complete, re-enabling the control. Completing
 * a control that is not in progress is a no-op (returns the same state).
 */
export function completeAction(
  state: InProgressControls,
  id: string,
): InProgressControls {
  if (!state.has(id)) return state;
  const next = new Set(state);
  next.delete(id);
  return next;
}
