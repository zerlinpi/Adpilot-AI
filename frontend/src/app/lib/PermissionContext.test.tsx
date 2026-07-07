// Unit tests for the permission bootstrap and re-fetch behavior of PermissionProvider.
// Validates:
//   - Requirement 3.1.3: the Frontend retrieves the user's permission set from the
//     System after login.
//   - Requirement 3.1.5: when the permission set changes, the Frontend re-renders
//     according to the updated set on the next permission fetch / navigation,
//     without requiring the user to log out and back in.
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useNavigate } from 'react-router';
import { PermissionProvider, usePermissions, derivePlatformAccess } from './PermissionContext';
import { fetchCurrentUser, type CurrentUserInfo } from './api';
import { getCurrentUser, setCurrentUser } from './auth';

// Mock the network and storage seams; the pure permission helpers remain real.
vi.mock('./api', () => ({ fetchCurrentUser: vi.fn() }));
vi.mock('./auth', () => ({ getCurrentUser: vi.fn(), setCurrentUser: vi.fn() }));

const mockedFetch = vi.mocked(fetchCurrentUser);
const mockedGetCurrentUser = vi.mocked(getCurrentUser);
const mockedSetCurrentUser = vi.mocked(setCurrentUser);

function user(permissions: string[]): CurrentUserInfo {
  return { id: 'u1', email: 'a@b.c', name: 'Alice', permissions };
}

/** Exposes the context values under test as DOM text. */
function Probe() {
  const { permissions, loading, can, platformAccess, storeGroupScope } = usePermissions();
  return (
    <div>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="perms">{permissions.join(',')}</span>
      <span data-testid="can-edit">{String(can('order:edit'))}</span>
      <span data-testid="families">{[...platformAccess.families].join(',')}</span>
      <span data-testid="super-admin">{String(platformAccess.superAdmin)}</span>
      <span data-testid="group-scope">{storeGroupScope === null ? 'null' : storeGroupScope.join(',')}</span>
    </div>
  );
}

/** Navigates to a new path to trigger the on-navigation re-fetch. */
function NavButton() {
  const navigate = useNavigate();
  return <button onClick={() => navigate('/other')}>go</button>;
}

describe('PermissionProvider', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
    mockedGetCurrentUser.mockReset();
    mockedSetCurrentUser.mockReset();
    // No cached user by default so we observe a clean bootstrap.
    mockedGetCurrentUser.mockReturnValue(null);
  });

  it('fetches the permission set from /api/auth/me after login (Req 3.1.3)', async () => {
    mockedFetch.mockResolvedValue(user(['order:view']));

    render(
      <MemoryRouter initialEntries={['/home']}>
        <PermissionProvider>
          <Probe />
        </PermissionProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByTestId('perms').textContent).toBe('order:view'));

    expect(mockedFetch).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('loading').textContent).toBe('false');
    // The freshly fetched user is written back to the cache so other consumers agree.
    expect(mockedSetCurrentUser).toHaveBeenCalledWith(user(['order:view']));
  });

  it('seeds permissions from the cached user before the first fetch resolves (Req 3.1.3)', async () => {
    mockedGetCurrentUser.mockReturnValue(user(['order:view']));
    // Never-resolving fetch so we observe only the seeded state.
    mockedFetch.mockReturnValue(new Promise<CurrentUserInfo>(() => { }));

    render(
      <MemoryRouter initialEntries={['/home']}>
        <PermissionProvider>
          <Probe />
        </PermissionProvider>
      </MemoryRouter>,
    );

    // Available immediately from the cache, before any network resolution.
    expect(screen.getByTestId('perms').textContent).toBe('order:view');
  });

  it('refreshes permissions on navigation without requiring re-login (Req 3.1.5)', async () => {
    // First fetch (bootstrap) lacks order:edit; the post-navigation fetch grants it.
    mockedFetch
      .mockResolvedValueOnce(user(['order:view']))
      .mockResolvedValueOnce(user(['order:view', 'order:edit']));

    render(
      <MemoryRouter initialEntries={['/home']}>
        <PermissionProvider>
          <NavButton />
          <Probe />
        </PermissionProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByTestId('can-edit').textContent).toBe('false'));
    expect(mockedFetch).toHaveBeenCalledTimes(1);

    // Navigate: the provider stays mounted (no logout) and re-fetches permissions.
    await userEvent.click(screen.getByText('go'));

    await waitFor(() => expect(screen.getByTestId('can-edit').textContent).toBe('true'));
    expect(screen.getByTestId('perms').textContent).toBe('order:view,order:edit');
    expect(mockedFetch).toHaveBeenCalledTimes(2);
  });

  it('keeps the previously loaded permissions when a re-fetch fails (no lockout)', async () => {
    mockedFetch
      .mockResolvedValueOnce(user(['order:view']))
      .mockRejectedValueOnce(new Error('network down'));

    render(
      <MemoryRouter initialEntries={['/home']}>
        <PermissionProvider>
          <NavButton />
          <Probe />
        </PermissionProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByTestId('perms').textContent).toBe('order:view'));

    await userEvent.click(screen.getByText('go'));

    // After the failed refresh the prior permission set is retained.
    await waitFor(() => expect(mockedFetch).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId('perms').textContent).toBe('order:view');
  });
});

// Unit tests for the Platform_Access and Store_Group_Scope dimensions the
// PermissionContext now surfaces from /api/auth/me.
// Validates:
//   - Req 12.2 / 15.3: navigation gates on Platform_Access; super-admin enters all.
//   - Req 12.5: the dimensions re-derive on the next permission fetch, no re-login.
//   - Req 13.5: the Store_Group_Scope is exposed for the store switcher.
describe('derivePlatformAccess', () => {
  it('falls back to every family (unrestricted) when the backend omits platformAccess', () => {
    const access = derivePlatformAccess(user(['order:view']));
    expect(access.superAdmin).toBe(false);
    // Every Nav_Block family is present so navigation degrades to
    // functional-permission gating only rather than hiding every block.
    expect([...access.families].sort()).toEqual(
      ['amazon', 'finance', 'independent_site', 'logistics', 'tiktok'].sort(),
    );
  });

  it('uses the explicit granted families when present (even an empty list gates)', () => {
    const granted = { ...user(['order:view']), platformAccess: ['amazon', 'finance'] };
    expect([...derivePlatformAccess(granted).families]).toEqual(['amazon', 'finance']);

    const none = { ...user(['order:view']), platformAccess: [] as string[] };
    expect([...derivePlatformAccess(none).families]).toEqual([]);
  });

  it('marks a super_admin role as super-admin (bypasses the family gate)', () => {
    const admin = { ...user([]), role: 'super_admin', platformAccess: [] as string[] };
    expect(derivePlatformAccess(admin).superAdmin).toBe(true);
  });

  it('treats a null user as unrestricted and not a super-admin', () => {
    const access = derivePlatformAccess(null);
    expect(access.superAdmin).toBe(false);
    expect(access.families.length).toBe(5);
  });
});

describe('PermissionProvider — Platform_Access / Store_Group_Scope', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
    mockedGetCurrentUser.mockReset();
    mockedSetCurrentUser.mockReset();
    mockedGetCurrentUser.mockReturnValue(null);
  });

  it('exposes the fetched platformAccess and storeGroupScope (Req 12.2, 13.5)', async () => {
    mockedFetch.mockResolvedValue({
      ...user(['order:view']),
      platformAccess: ['amazon'],
      storeGroupScope: ['g1', 'g2'],
    });

    render(
      <MemoryRouter initialEntries={['/home']}>
        <PermissionProvider>
          <Probe />
        </PermissionProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByTestId('families').textContent).toBe('amazon'));
    expect(screen.getByTestId('super-admin').textContent).toBe('false');
    expect(screen.getByTestId('group-scope').textContent).toBe('g1,g2');
  });

  it('re-derives the dimensions on the next fetch without re-login (Req 12.5)', async () => {
    // Bootstrap grants only amazon; the post-navigation fetch widens to finance.
    mockedFetch
      .mockResolvedValueOnce({ ...user(['order:view']), platformAccess: ['amazon'] })
      .mockResolvedValueOnce({ ...user(['order:view']), platformAccess: ['amazon', 'finance'] });

    render(
      <MemoryRouter initialEntries={['/home']}>
        <PermissionProvider>
          <NavButton />
          <Probe />
        </PermissionProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByTestId('families').textContent).toBe('amazon'));

    await userEvent.click(screen.getByText('go'));

    await waitFor(() => expect(screen.getByTestId('families').textContent).toBe('amazon,finance'));
    expect(mockedFetch).toHaveBeenCalledTimes(2);
  });

  it('exposes a null Store_Group_Scope (unrestricted) when the backend omits it', async () => {
    mockedFetch.mockResolvedValue(user(['order:view']));

    render(
      <MemoryRouter initialEntries={['/home']}>
        <PermissionProvider>
          <Probe />
        </PermissionProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByTestId('group-scope').textContent).toBe('null'));
  });
});
