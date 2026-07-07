// Example tests for the FilterChips component (Task 20.2; Req 29.3, 29.4, 29.5).
//
// The component is driven through an injected query-state API so the behavior
// is exercised without a router. The exact "chips ↔ applied filters"
// correspondence is property-tested separately in task 20.6.

import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { FilterChips } from './FilterChips';
import {
  emptyQueryState,
  type AdvertisingQueryState,
} from '../../lib/advertisingQueryState';
import type { AdvertisingQueryStateApi } from '../../lib/hooks/useAdvertisingQueryState';

afterEach(cleanup);

function makeApi(
  state: AdvertisingQueryState,
  patch = vi.fn(),
): AdvertisingQueryStateApi {
  return {
    state,
    setFilters: vi.fn(),
    setSearch: vi.fn(),
    setTypeSelection: vi.fn(),
    setConditions: vi.fn(),
    setDateRange: vi.fn(),
    setSort: vi.fn(),
    setPage: vi.fn(),
    setPageSize: vi.fn(),
    patch,
    reset: vi.fn(),
  };
}

describe('FilterChips (Req 29.3, 29.4, 29.5)', () => {
  it('renders nothing when no filters are applied (Req 29.5)', () => {
    const { container } = render(
      <FilterChips queryState={makeApi(emptyQueryState())} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('renders exactly one chip per applied filter (Req 29.3)', () => {
    const state: AdvertisingQueryState = {
      ...emptyQueryState(),
      filters: {
        search: 'shoes',
        typeSelections: { status: 'enabled' },
        conditions: [{ field: 'acos', op: 'gt', value: 0.25 }],
      },
    };
    render(<FilterChips queryState={makeApi(state)} />);
    // 1 search + 1 selection + 1 condition = 3 chips.
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
  });

  it('removing a chip re-requests with the remaining filters via the setter (Req 29.4)', async () => {
    const user = userEvent.setup();
    const patch = vi.fn();
    const state: AdvertisingQueryState = {
      ...emptyQueryState(),
      filters: {
        search: 'shoes',
        typeSelections: { status: 'enabled' },
        conditions: [],
      },
    };
    render(<FilterChips queryState={makeApi(state, patch)} />);

    await user.click(
      screen.getByRole('button', { name: /移除筛选 .*shoes/ }),
    );

    expect(patch).toHaveBeenCalledTimes(1);
    const next = patch.mock.calls[0][0] as AdvertisingQueryState;
    expect(next.filters.search).toBe('');
    // The other applied filter is preserved.
    expect(next.filters.typeSelections).toEqual({ status: 'enabled' });
  });
});
