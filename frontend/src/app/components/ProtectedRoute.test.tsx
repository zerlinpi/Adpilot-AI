// Unit tests for the <ProtectedRoute> route guard.
// Validates: Requirements 3.1.4 (block direct navigation to unauthorized routes
// and show an access-denied indication).
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router';
import { ProtectedRoute } from './ProtectedRoute';
import { usePermissions } from '../lib/PermissionContext';

// Isolate the guard from the real permission fetch: drive it via a mocked context hook.
vi.mock('../lib/PermissionContext', () => ({
  usePermissions: vi.fn(),
}));

const mockedUsePermissions = vi.mocked(usePermissions);

function setPermissionState(overrides: Partial<ReturnType<typeof usePermissions>>) {
  mockedUsePermissions.mockReturnValue({
    user: null,
    permissions: [],
    platformAccess: { families: ['amazon', 'independent_site', 'logistics', 'finance'], superAdmin: false },
    storeGroupScope: null,
    loading: false,
    error: null,
    can: () => false,
    canAny: () => false,
    canAll: () => false,
    refresh: async () => { },
    ...overrides,
  });
}

/** Renders the guarded route at /secret with a sibling /403 access-denied page. */
function renderGuarded() {
  return render(
    <MemoryRouter initialEntries={['/secret']}>
      <Routes>
        <Route
          path="/secret"
          element={
            <ProtectedRoute permission="order:view">
              <div>Secret Content</div>
            </ProtectedRoute>
          }
        />
        <Route path="/403" element={<div>Access Denied</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    mockedUsePermissions.mockReset();
  });

  it('blocks navigation and shows the access-denied page when the user lacks the permission', () => {
    // user is loaded but does not hold "order:view"
    setPermissionState({
      user: { id: 'u1', email: 'a@b.c', name: 'A', permissions: ['product:view'] },
      can: (p) => p === 'product:view',
    });

    renderGuarded();

    expect(screen.getByText('Access Denied')).toBeInTheDocument();
    expect(screen.queryByText('Secret Content')).not.toBeInTheDocument();
  });

  it('renders the protected content when the user holds the required permission', () => {
    setPermissionState({
      user: { id: 'u1', email: 'a@b.c', name: 'A', permissions: ['order:view'] },
      can: (p) => p === 'order:view',
    });

    renderGuarded();

    expect(screen.getByText('Secret Content')).toBeInTheDocument();
    expect(screen.queryByText('Access Denied')).not.toBeInTheDocument();
  });

  it('shows a loader (does not deny) while the first permission fetch is in flight and no cached user exists', () => {
    setPermissionState({ user: null, loading: true, can: () => false });

    renderGuarded();

    // Neither denied nor granted yet — wait for permissions to resolve.
    expect(screen.getByText('加载中...')).toBeInTheDocument();
    expect(screen.queryByText('Access Denied')).not.toBeInTheDocument();
    expect(screen.queryByText('Secret Content')).not.toBeInTheDocument();
  });

  it('evaluates against the cached user without waiting when one is already available', () => {
    // loading is still true (a refresh is in flight) but a cached user is present,
    // so the guard should decide immediately instead of showing the loader.
    setPermissionState({
      user: { id: 'u1', email: 'a@b.c', name: 'A', permissions: ['order:view'] },
      loading: true,
      can: (p) => p === 'order:view',
    });

    renderGuarded();

    expect(screen.queryByText('加载中...')).not.toBeInTheDocument();
    expect(screen.getByText('Secret Content')).toBeInTheDocument();
  });
});
