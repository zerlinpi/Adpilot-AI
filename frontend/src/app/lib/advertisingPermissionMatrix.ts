// AdPilot AI — Advertising permission matrix (pure logic)
//
// Pure, render-independent encoding of the full advertising permission matrix
// from Requirement 27.1, plus the predicate that decides whether an advertising
// action control may be shown/enabled for a given permission set.
//
// The frontend MUST drive the visibility and enabled state of every advertising
// control from the SAME permission code the matrix assigns to that control's
// action (Requirement 27.7), and a control that is hidden or disabled for lack
// of permission MUST never issue its backend request (Requirement 26.4). The
// gating wrapper (`AdvertisingActionGate`) and the `useAdvertisingPermission`
// hook both delegate to `canShowControl` here, so "which advertising controls
// are exposed" is governed by exactly this matrix and nothing outside it.
//
// This module is intentionally free of React so the core predicate can be
// property-tested directly (Property 58 / task 21.6) without rendering.
//
// Validates: Requirements 26.1, 26.2, 26.3, 26.4, 27.7

/**
 * The advertising resources of the Requirement 27.1 matrix. Each resource maps
 * a set of {@link AdvertisingAction}s to the permission code required to perform
 * that action on the resource.
 */
export type AdvertisingResource =
  | 'Campaign'
  | 'AdGroup'
  | 'ProductAd'
  | 'Target'
  | 'Keyword'
  | 'NegativeKeyword'
  | 'SearchTerm'
  | 'Recommendation'
  | 'Operation'
  | 'SyncLog'
  | 'BidChange'
  | 'Goal'
  | 'Portfolio'
  | 'Diagnosis'
  | 'Hosting'
  | 'Notification';

/**
 * The action columns of the Requirement 27.1 matrix.
 *
 * - `view` — read the resource.
 * - `create-edit` — create or edit the resource (includes Search_Term harvest,
 *   which creates a Keyword/Negative_Keyword under `keyword:manage`).
 * - `delete` — delete the resource. For audit resources (Operation, SyncLog,
 *   BidChange) the matrix cell is N/A: no interactive delete control exists, so
 *   `permissionCodeForAction` returns `null` and the control is never shown.
 * - `approve` — approve a held advertising action.
 * - `platform-execute` — submit/execute the change against the platform.
 */
export type AdvertisingAction =
  | 'view'
  | 'create-edit'
  | 'delete'
  | 'approve'
  | 'platform-execute';

/**
 * `null` marks a matrix cell that exposes NO interactive control (the audit
 * resources' delete cell — archival only per retention policy, Req 27.3). A
 * `null` cell is never shown or enabled.
 */
type MatrixCell = string | null;

/** The advertising-default action codes shared by most resources. */
const ADVERTISING_DEFAULT: Record<AdvertisingAction, MatrixCell> = {
  view: 'advertising:view',
  'create-edit': 'advertising:manage',
  delete: 'advertising:manage',
  approve: 'advertising:approve',
  'platform-execute': 'advertising:execute',
};

/** The keyword-family action codes shared by Target / Keyword / NegativeKeyword. */
const KEYWORD_DEFAULT: Record<AdvertisingAction, MatrixCell> = {
  view: 'keyword:view',
  'create-edit': 'keyword:manage',
  delete: 'keyword:manage',
  approve: 'advertising:approve',
  'platform-execute': 'keyword:apply',
};

/**
 * The complete Requirement 27.1 advertising permission matrix. Each cell names
 * the permission code required to perform the action on the resource; `null`
 * marks an N/A cell (no interactive control). This is the single source of
 * truth the frontend uses to gate advertising controls (Req 27.7).
 */
export const ADVERTISING_PERMISSION_MATRIX: Readonly<
  Record<AdvertisingResource, Readonly<Record<AdvertisingAction, MatrixCell>>>
> = {
  Campaign: { ...ADVERTISING_DEFAULT },
  AdGroup: { ...ADVERTISING_DEFAULT },
  ProductAd: { ...ADVERTISING_DEFAULT },
  Target: { ...KEYWORD_DEFAULT },
  Keyword: { ...KEYWORD_DEFAULT },
  NegativeKeyword: { ...KEYWORD_DEFAULT },
  // Search_Term: viewing uses advertising:view; create-edit (harvest) and delete
  // use the keyword family; platform-execute uses keyword:apply.
  SearchTerm: {
    view: 'advertising:view',
    'create-edit': 'keyword:manage',
    delete: 'keyword:manage',
    approve: 'advertising:approve',
    'platform-execute': 'keyword:apply',
  },
  Recommendation: { ...ADVERTISING_DEFAULT },
  // Operation/SyncLog/BidChange are audit records: no interactive delete (Req 27.3).
  Operation: {
    view: 'operation:view',
    'create-edit': 'advertising:manage',
    delete: null,
    approve: 'advertising:approve',
    'platform-execute': 'advertising:execute',
  },
  SyncLog: {
    view: 'advertising:view',
    'create-edit': 'advertising:manage',
    delete: null,
    approve: 'advertising:approve',
    'platform-execute': 'advertising:execute',
  },
  BidChange: {
    view: 'advertising:view',
    'create-edit': 'advertising:manage',
    delete: null,
    approve: 'advertising:approve',
    'platform-execute': 'advertising:execute',
  },
  Goal: { ...ADVERTISING_DEFAULT },
  Portfolio: { ...ADVERTISING_DEFAULT },
  Diagnosis: { ...ADVERTISING_DEFAULT },
  // Hosting create-edit/delete use the distinct hosting:manage code.
  Hosting: {
    view: 'advertising:view',
    'create-edit': 'hosting:manage',
    delete: 'hosting:manage',
    approve: 'advertising:approve',
    'platform-execute': 'advertising:execute',
  },
  Notification: { ...ADVERTISING_DEFAULT },
};

/**
 * The permission code the matrix assigns to a (resource, action) control, or
 * `null` when the cell is N/A (no interactive control — the audit resources'
 * delete cell). This is the code the frontend drives control visibility from
 * (Requirement 27.7).
 */
export function permissionCodeForAction(
  resource: AdvertisingResource,
  action: AdvertisingAction,
): string | null {
  return ADVERTISING_PERMISSION_MATRIX[resource][action];
}

/** Normalize a permission set (array or Set) into a `Set` for O(1) lookups. */
function toPermissionSet(permissions: Iterable<string>): Set<string> {
  return permissions instanceof Set ? permissions : new Set(permissions);
}

/**
 * The core predicate (PURE). An advertising action control is shown/enabled IF
 * AND ONLY IF the permission set contains the matrix code for that control's
 * action (Requirement 27.7). A cell with no code (N/A, e.g. audit delete) is
 * never shown regardless of the permission set.
 *
 * Because a hidden or disabled control never issues its backend request
 * (Requirement 26.4), this single predicate decides both whether the control is
 * exposed and whether its request may fire.
 *
 * @param permissions the logged-in user's aggregated permission set
 * @param resource the advertising resource the control acts on
 * @param action the action the control performs
 */
export function canShowControl(
  permissions: Iterable<string>,
  resource: AdvertisingResource,
  action: AdvertisingAction,
): boolean {
  const code = permissionCodeForAction(resource, action);
  if (code === null) return false;
  return toPermissionSet(permissions).has(code);
}
