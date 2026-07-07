// FilterChips — the applied-filter chip row (Req 29.3, 29.4, 29.5).
//
// Reads the advertising query state (the single source of truth) from
// `useAdvertisingQueryState` and renders exactly one chip per applied filter
// condition via the PURE `deriveFilterChips` derivation (so task 20.6 can
// property-test the mapping in isolation). Removing a chip computes the
// remaining query state with the PURE `removeFilterChip` reducer and writes it
// back through the query-state setter, which re-requests the table data
// reflecting the remaining filters (Req 29.4). When no filters are applied the
// component renders nothing (Req 29.5).

import { X } from 'lucide-react';

import { cn } from '../../lib/utils';
import {
  useAdvertisingQueryState,
  type AdvertisingQueryStateApi,
} from '../../lib/hooks/useAdvertisingQueryState';
import {
  deriveFilterChips,
  removeFilterChip,
  type FilterChipLabels,
} from '../../lib/advertisingFilterChips';

export interface FilterChipsProps {
  /**
   * Optional query-state API. Defaults to the page's `useAdvertisingQueryState`
   * so the chips share the single source of truth with the toolbar, KPI panel,
   * and table. Accepting it as a prop keeps the component easy to drive in
   * tests without a router.
   */
  queryState?: AdvertisingQueryStateApi;
  /** Optional human-readable labels for chip text. */
  labels?: FilterChipLabels;
  /** Extra class names for the chip row root. */
  className?: string;
}

/**
 * FilterChips — renders the removable chips for the currently applied filters.
 *
 * When `queryState` is supplied the chips are driven by it directly; otherwise
 * the component connects to the page's `useAdvertisingQueryState`. The two
 * paths are separate components so each calls its hooks unconditionally.
 */
export function FilterChips({ queryState, labels, className }: FilterChipsProps) {
  if (queryState) {
    return (
      <FilterChipsView api={queryState} labels={labels} className={className} />
    );
  }
  return <ConnectedFilterChips labels={labels} className={className} />;
}

function ConnectedFilterChips({
  labels,
  className,
}: Omit<FilterChipsProps, 'queryState'>) {
  const api = useAdvertisingQueryState();
  return <FilterChipsView api={api} labels={labels} className={className} />;
}

interface FilterChipsViewProps {
  api: AdvertisingQueryStateApi;
  labels?: FilterChipLabels;
  className?: string;
}

function FilterChipsView({ api, labels, className }: FilterChipsViewProps) {
  const chips = deriveFilterChips(api.state, labels);

  // No applied filters ⇒ no chips (Req 29.5).
  if (chips.length === 0) return null;

  return (
    <div
      className={cn('flex flex-wrap items-center gap-2', className)}
      role="list"
      aria-label="已应用的筛选条件"
    >
      {chips.map((chip) => (
        <span
          key={chip.id}
          role="listitem"
          className="inline-flex items-center gap-1 rounded-full border border-blue-200 bg-blue-50 py-1 pl-3 pr-1.5 text-xs font-medium text-blue-700"
        >
          <span>{chip.label}</span>
          <button
            type="button"
            aria-label={`移除筛选 ${chip.label}`}
            onClick={() => api.patch(removeFilterChip(api.state, chip))}
            className="flex items-center justify-center rounded-full p-0.5 text-blue-400 transition-colors hover:bg-blue-100 hover:text-blue-700"
          >
            <X size={13} />
          </button>
        </span>
      ))}
    </div>
  );
}

export default FilterChips;
