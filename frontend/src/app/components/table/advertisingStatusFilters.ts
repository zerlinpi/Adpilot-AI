// AdPilot AI — Fixed advertising status-filter enumerations (Req 35.1, 35.2, 35.3)
//
// A status type-selector's options come from a FIXED enumeration of the
// possible status values for that field — never derived from whichever rows
// happen to be loaded on the current page. This guarantees that:
//   • each advertising status filter is populated from a fixed enumeration of
//     the possible status values for that field (Req 35.1),
//   • the set of available options is NOT derived from the loaded rows
//     (Req 35.2), and
//   • a status is still offered as a selectable filter option even when no
//     loaded row currently has that value (Req 35.3).
//
// This module is PURE and side-effect free: `getStatusFilterOptions` /
// `getStatusTypeSelector` depend only on their `field` argument and the
// canonical machine-value enumerations below — never on component state,
// fetched data, or globals. It is exported so the fixed-enumeration property
// test (task 19.11, Property 65) can drive it directly.
//
// Validates: Requirements 35.1, 35.2, 35.3 (see Property 65, task 19.11).

import {
  AI_HOSTING_STATUS_VALUES,
  AI_HOSTING_STATUS_DISPLAY,
  OBJECT_STATUS_VALUES,
  OBJECT_STATUS_DISPLAY,
} from '../../lib/translateMachineValue';
import type { TypeSelectorDef } from './types';

/**
 * The advertising status fields whose filter options come from a fixed
 * enumeration. Each maps to one canonical machine-value vocabulary returned by
 * the backend (Req 14.7, 48.4).
 *
 *  - `objectStatus`    — Campaign / ad-group / keyword / target Object_Status
 *                        (`enabled` / `paused` / `archived`, Req 16).
 *  - `aiHostingStatus` — AI_Hosting_Status (`hosted` / `not_hosted`, Req 48).
 */
export type AdvertisingStatusField = 'objectStatus' | 'aiHostingStatus';

/** A single fixed status filter option: machine value + display label. */
export interface StatusFilterOption {
  /** The stable machine value sent to the backend as the filter selection. */
  value: string;
  /** The fixed display copy for the value (Req 48.5). */
  label: string;
}

/**
 * The FIXED enumeration of possible status machine values per field. This is
 * the single source of truth for status-filter options and is intentionally
 * independent of any loaded data (Req 35.1, 35.2). Exposed so a property test
 * can assert the produced options match exactly this enumeration regardless of
 * the rows on the page.
 */
export const ADVERTISING_STATUS_ENUMERATIONS: Record<
  AdvertisingStatusField,
  readonly string[]
> = {
  objectStatus: OBJECT_STATUS_VALUES,
  aiHostingStatus: AI_HOSTING_STATUS_VALUES,
};

/** Per-field machine-value → display-copy tables for the fixed enumerations. */
const STATUS_DISPLAY_BY_FIELD: Record<
  AdvertisingStatusField,
  Record<string, string>
> = {
  objectStatus: OBJECT_STATUS_DISPLAY,
  aiHostingStatus: AI_HOSTING_STATUS_DISPLAY,
};

/** The fixed "all status fields" set, useful for exhaustive iteration/tests. */
export const ADVERTISING_STATUS_FIELDS: readonly AdvertisingStatusField[] = [
  'objectStatus',
  'aiHostingStatus',
];

/**
 * PURE provider of the fixed set of selectable status-filter options for a
 * status field.
 *
 * The returned options are derived ONLY from the fixed enumeration for the
 * field, so the result is identical no matter which rows are currently loaded
 * (Req 35.1, 35.2) and every enumerated status is always offered even when no
 * loaded row carries that value (Req 35.3).
 *
 * @param field The advertising status field to provide options for.
 * @returns A new array of `{ value, label }` options, one per enumerated value.
 */
export function getStatusFilterOptions(
  field: AdvertisingStatusField,
): StatusFilterOption[] {
  const displays = STATUS_DISPLAY_BY_FIELD[field];
  return ADVERTISING_STATUS_ENUMERATIONS[field].map((value) => ({
    value,
    label: displays[value] ?? value,
  }));
}

/**
 * Build a ready-to-use {@link TypeSelectorDef} for a status type-selector whose
 * options come from the fixed enumeration of {@link getStatusFilterOptions}.
 *
 * PURE: the produced selector definition depends only on the arguments, never
 * on loaded data — wiring a `FilterToolbar` with this guarantees the status
 * filter offers the full fixed enumeration (Req 35.1–35.3).
 *
 * @param field     The advertising status field this selector filters on.
 * @param selector  The selector key + label (and optional "all" label) to use.
 */
export function getStatusTypeSelector(
  field: AdvertisingStatusField,
  selector: { key: string; label: string; allLabel?: string },
): TypeSelectorDef {
  return {
    key: selector.key,
    label: selector.label,
    allLabel: selector.allLabel,
    options: getStatusFilterOptions(field),
  };
}
