import { describe, it, expect } from 'vitest';
import { buildNavCommands, groupNavCommands, pickRecentCommands, withRecentRoute } from './commandPalette';

describe('buildNavCommands', () => {
  const allow = () => true;

  it('projects permitted, route-resolving nav items into commands tagged by block', () => {
    const cmds = buildNavCommands(allow);
    expect(cmds.length).toBeGreaterThan(10);
    // Every command carries a destination, label and group.
    for (const c of cmds) {
      expect(c.to).toBeTruthy();
      expect(c.label).toBeTruthy();
      expect(c.group).toBeTruthy();
    }
    // Representative items land under their block heading.
    expect(cmds.some((c) => c.label === '经营看板' && c.group === '亚马逊')).toBe(true);
    expect(cmds.some((c) => c.label === '独立站工作台' && c.group === '独立站')).toBe(true);
    // Account-area utilities are grouped under 系统与管理.
    expect(cmds.some((c) => c.group === '系统与管理')).toBe(true);
  });

  it('excludes items whose gating permission the account lacks', () => {
    const all = buildNavCommands(allow);
    const withoutDashboard = buildNavCommands((p) => p !== 'dashboard:view');
    // 经营看板 (/dashboard) is gated on dashboard:view and must drop out.
    expect(withoutDashboard.some((c) => c.label === '经营看板')).toBe(false);
    expect(withoutDashboard.length).toBeLessThan(all.length);
  });

  it('never offers a page the account cannot see (empty permission set)', () => {
    const denyAll = buildNavCommands(() => false);
    // Only items without a gating permission (e.g. always-on personal pages) may remain;
    // none of the permission-gated pages should appear.
    expect(denyAll.every((c) => c.group === '系统与管理')).toBe(true);
  });
});

describe('groupNavCommands', () => {
  it('groups commands preserving first-seen group order, each group non-empty', () => {
    const groups = groupNavCommands(buildNavCommands(() => true));
    expect(groups.length).toBeGreaterThan(0);
    expect(groups[0].group).toBe('亚马逊');
    for (const g of groups) {
      expect(g.items.length).toBeGreaterThan(0);
      expect(g.items.every((i) => i.group === g.group)).toBe(true);
    }
  });
});

describe('recent commands', () => {
  const all = buildNavCommands(() => true);
  const someRoutes = all.slice(0, 3).map((c) => c.to);

  it('withRecentRoute puts the newest first, de-duplicates and caps at 6', () => {
    let recent: string[] = [];
    for (const r of ['/a', '/b', '/c', '/d', '/e', '/f', '/g']) {
      recent = withRecentRoute(recent, r);
    }
    expect(recent[0]).toBe('/g');
    expect(recent).toHaveLength(6);
    // Re-visiting an existing route moves it to the front without duplicating.
    recent = withRecentRoute(recent, '/c');
    expect(recent[0]).toBe('/c');
    expect(recent.filter((r) => r === '/c')).toHaveLength(1);
    expect(recent).toHaveLength(6);
  });

  it('pickRecentCommands resolves routes most-recent-first, dropping unknown/duplicate', () => {
    const recent = [someRoutes[2], someRoutes[0], 'no-such-route', someRoutes[2]];
    const picked = pickRecentCommands(all, recent);
    // Order follows the recent list; unknown route dropped; duplicate collapsed.
    expect(picked.map((c) => c.to)).toEqual([someRoutes[2], someRoutes[0]]);
  });

  it('pickRecentCommands drops a route the account can no longer see', () => {
    const visible = buildNavCommands((p) => p !== 'dashboard:view');
    // /dashboard was recent but is no longer permitted → excluded.
    const picked = pickRecentCommands(visible, ['/dashboard']);
    expect(picked).toHaveLength(0);
  });
});
