// Unit tests for <AdvertisingActionGate>.
//
// Confirms the gate shows/enables a control iff the permission set holds the
// matrix code (Req 27.7), and that a hidden OR disabled control never issues
// its backend request (Req 26.4).
//
// Validates: Requirements 26.1, 26.2, 26.4, 27.7

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AdvertisingActionGate } from './AdvertisingActionGate';
import { usePermissions } from '../lib/PermissionContext';

vi.mock('../lib/PermissionContext', () => ({
  usePermissions: vi.fn(),
}));

const mockedUsePermissions = vi.mocked(usePermissions);

function setPermissions(permissions: string[]) {
  mockedUsePermissions.mockReturnValue({
    user: { id: 'u1', email: 'a@b.c', name: 'A', permissions },
    permissions,
    platformAccess: { families: ['amazon', 'independent_site', 'logistics', 'finance'], superAdmin: false },
    storeGroupScope: null,
    loading: false,
    error: null,
    can: (p: string) => permissions.includes(p),
    canAny: (ps: string[]) => ps.some((p) => permissions.includes(p)),
    canAll: (ps: string[]) => ps.every((p) => permissions.includes(p)),
    refresh: async () => { },
  });
}

describe('<AdvertisingActionGate>', () => {
  beforeEach(() => mockedUsePermissions.mockReset());

  it('renders the control when the user holds the matrix code', () => {
    setPermissions(['keyword:manage']);
    render(
      <AdvertisingActionGate resource="Keyword" action="create-edit">
        <button>新建关键词</button>
      </AdvertisingActionGate>,
    );
    expect(screen.getByText('新建关键词')).toBeInTheDocument();
  });

  it('hides the control (mode=hide default) when the user lacks the matrix code', () => {
    setPermissions(['keyword:view']);
    render(
      <AdvertisingActionGate resource="Keyword" action="create-edit">
        <button>新建关键词</button>
      </AdvertisingActionGate>,
    );
    expect(screen.queryByText('新建关键词')).not.toBeInTheDocument();
  });

  it('a hidden control never issues its backend request (Req 26.4)', async () => {
    const onClick = vi.fn();
    setPermissions([]); // no permission
    render(
      <AdvertisingActionGate resource="Recommendation" action="approve">
        <button onClick={onClick}>应用</button>
      </AdvertisingActionGate>,
    );
    // Nothing rendered -> nothing to click -> request can never fire.
    expect(screen.queryByText('应用')).not.toBeInTheDocument();
    expect(onClick).not.toHaveBeenCalled();
  });

  it('mode=disable renders the control disabled and suppresses its click (Req 26.4)', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    setPermissions([]); // no permission
    render(
      <AdvertisingActionGate resource="Recommendation" action="approve" mode="disable">
        <button onClick={onClick}>应用</button>
      </AdvertisingActionGate>,
    );
    const btn = screen.getByText('应用');
    expect(btn).toBeDisabled();
    await user.click(btn).catch(() => { });
    expect(onClick).not.toHaveBeenCalled();
  });

  it('renders the fallback when denied and a fallback is provided', () => {
    setPermissions([]);
    render(
      <AdvertisingActionGate
        resource="Keyword"
        action="create-edit"
        fallback={<span>无权限</span>}
      >
        <button>新建关键词</button>
      </AdvertisingActionGate>,
    );
    expect(screen.getByText('无权限')).toBeInTheDocument();
    expect(screen.queryByText('新建关键词')).not.toBeInTheDocument();
  });

  it('never shows an N/A audit-delete control even with broad permissions', () => {
    setPermissions(['advertising:manage', 'advertising:execute', 'advertising:approve']);
    render(
      <AdvertisingActionGate resource="Operation" action="delete">
        <button>删除</button>
      </AdvertisingActionGate>,
    );
    expect(screen.queryByText('删除')).not.toBeInTheDocument();
  });
});
