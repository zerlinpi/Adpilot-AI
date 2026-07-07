import { Suspense, useEffect, useState } from 'react';
import { Outlet, NavLink, useLocation } from 'react-router';
import { StoreProvider, useStoreContext } from '../lib/StoreContext';
import { usePermissions } from '../lib/PermissionContext';
import { logout } from '../lib/auth';
import { filterStoresByName, filterStoresByPlatformFamily, groupStores } from '../lib/storeSwitcher';
import {
  navBlocks,
  accountAreaItems,
  navItemBasePath,
  isNavItemVisible,
  activeNavBlockFamily,
  loadExpandState,
  serializeExpandState,
  defaultExpandState,
  NAV_EXPAND_STORAGE_KEY,
  type NavItem,
  type NavBlock,
  type NavExpandState,
  type PlatformFamily,
} from '../lib/navConfig';
import {
  adPlatformLabel,
  storeAdPlatform,
  storePlatformFamily,
  storePlatformFamilyLabel,
  storePlatformLabel,
} from '../lib/platformTaxonomy';
import { isBlockVisible, type PlatformAccess } from '../lib/navVisibility';
import { CommandPalette, useCommandPaletteShortcut } from './CommandPalette';
import { ThemeToggle } from './ThemeToggle';
import {
  Settings,
  Store, ChevronLeft, ChevronRight, Bell, Search as SearchIcon,
  LogOut, Zap, ChevronDown, Check,
} from 'lucide-react';

/** Header store switcher — lets the user pick the active store; all pages
 *  scope their data to this selection. Within a Nav_Block the switcher offers
 *  only the stores of that block's Platform_Family, organized by family, so a
 *  selection never crosses platform families (Req 6.4, 6.6). */
function StoreSwitcher() {
  const { stores, storeId, setStoreId, loading } = useStoreContext();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const current = stores.find((s) => s.id === storeId);

  // The Platform_Family of the Nav_Block the user is currently in. Only the
  // store-bearing families (amazon / independent_site / tiktok) scope the
  // switcher — every store belongs to one of these (Req 6.1). The 物流 / 财务统计
  // blocks are cross-cutting (they aggregate across stores), so they impose no
  // restriction. An ambiguous route (one shared across families) also imposes
  // none.
  const navFamily = activeNavBlockFamily(location.pathname, location.search);
  const currentFamily =
    navFamily === 'amazon' || navFamily === 'independent_site' || navFamily === 'tiktok'
      ? navFamily
      : null;

  // Tag each store with its Platform_Family, then: filter by name as the user
  // types (Req 5.2.2), restrict to the current block's family (Req 6.4), and
  // organize the survivors by family for display (Req 6.6). All three delegate
  // to the pure switcher helpers.
  const familyTagged = stores.map((s) => ({
    ...s,
    platformFamily: storePlatformFamily(s.platform),
    storeGroup: storePlatformFamilyLabel(storePlatformFamily(s.platform)),
  }));
  const matches = filterStoresByName(familyTagged, search);
  const scoped = filterStoresByPlatformFamily(matches, currentFamily);
  const groups = groupStores(scoped);

  const close = () => { setOpen(false); setSearch(''); };

  if (loading) {
    return (
      <div className="flex items-center gap-2 px-3 py-1.5 bg-muted rounded-full border border-border">
        <div className="w-2 h-2 bg-muted-foreground/40 rounded-full animate-pulse" />
        <span className="text-xs font-medium text-muted-foreground">加载店铺...</span>
      </div>
    );
  }

  if (stores.length === 0) {
    return (
      <NavLink to="/stores" className="flex items-center gap-2 px-3 py-1.5 bg-amber-50 rounded-full border border-amber-200 hover:bg-amber-100 transition-colors">
        <Store size={13} className="text-amber-600" />
        <span className="text-xs font-medium text-amber-700">暂无店铺，点击创建</span>
      </NavLink>
    );
  }

  return (
    <div className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 px-3 py-1.5 bg-background rounded-lg border border-border hover:border-border transition-colors max-w-[220px]"
      >
        <Store size={14} className="text-blue-600 flex-shrink-0" />
        <span className="text-xs font-medium text-foreground truncate">{current?.name ?? '选择店铺'}</span>
        {current?.marketplaceCode && (
          <span className="text-[10px] text-muted-foreground flex-shrink-0">{current.marketplaceCode}</span>
        )}
        <ChevronDown size={13} className="text-muted-foreground flex-shrink-0" />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={close} />
          <div className="absolute right-0 mt-1 w-64 bg-popover text-popover-foreground border border-border rounded-lg shadow-lg z-20 py-1 max-h-96 overflow-y-auto">
            <p className="px-3 py-1.5 text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
              {currentFamily ? `切换店铺 · ${storePlatformFamilyLabel(currentFamily)}` : '切换店铺'}
            </p>
            {/* Search field — filters the list by store name (Req 5.2.2). */}
            <div className="px-2 pb-2">
              <div className="flex items-center gap-2 bg-muted rounded-md px-2 py-1.5 border border-border">
                <SearchIcon size={13} className="text-muted-foreground flex-shrink-0" />
                <input
                  type="text"
                  autoFocus
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="搜索店铺名称..."
                  className="bg-transparent text-xs text-foreground placeholder:text-muted-foreground outline-none w-full"
                />
              </div>
            </div>
            {groups.length === 0 && (
              <p className="px-3 py-3 text-xs text-muted-foreground text-center">未找到匹配的店铺</p>
            )}
            {/* Stores organized by their assigned group (Req 5.2.4). */}
            {groups.map((group) => (
              <div key={group.group}>
                <p className="px-3 pt-1.5 pb-0.5 text-[10px] font-semibold text-muted-foreground uppercase tracking-wider">
                  {group.group}
                </p>
                {group.stores.map((s) => (
                  <button
                    key={s.id}
                    onClick={() => { setStoreId(s.id); close(); }}
                    className={`w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-accent transition-colors ${s.id === storeId ? 'bg-blue-50' : ''}`}
                  >
                    <Store size={14} className={s.id === storeId ? 'text-blue-600' : 'text-muted-foreground'} />
                    <div className="flex-1 min-w-0">
                      <p className={`text-sm truncate ${s.id === storeId ? 'text-blue-700 font-medium' : 'text-foreground'}`}>{s.name}</p>
                      <p className="text-[11px] text-muted-foreground truncate">
                        {storePlatformLabel(s.platform)} · {adPlatformLabel(s.adPlatform ?? storeAdPlatform(s.platform))}
                      </p>
                    </div>
                    {s.id === storeId && <Check size={13} className="text-blue-600" />}
                  </button>
                ))}
              </div>
            ))}
            <div className="border-t border-border mt-1 pt-1">
              <NavLink to="/stores" onClick={close} className="flex items-center gap-2 px-3 py-2 text-sm text-foreground hover:bg-accent">
                <Settings size={14} className="text-muted-foreground" /> 管理店铺
              </NavLink>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/** Decide whether a single Nav_Item is shown — delegates to the shared
 *  {@link isNavItemVisible} so the sidebar and the `visibleNavBlocks` projection
 *  can never diverge (Req 2.5, 3.1, 3.2). */
function isItemVisible(item: NavItem, can: (p: string) => boolean): boolean {
  return isNavItemVisible(item, can);
}

/** The Nav_Items in a block the account is permitted to see and whose routes
 *  resolve, preserving the defined order (Req 1.3, 2.5). */
function visibleItems(block: NavBlock, can: (p: string) => boolean): NavItem[] {
  return block.items.filter((item) => isItemVisible(item, can));
}

/** Active-state for a Nav_Item against the current location (Req 1.5). */
function useIsActive(to: string): boolean {
  const location = useLocation();
  const path = navItemBasePath(to);
  return path === '/' ? location.pathname === '/' : location.pathname.startsWith(path);
}

/** A single rendered Nav_Item link. */
function NavItemLink({ item, collapsed }: { item: NavItem; collapsed: boolean }) {
  const { icon: Icon, label, to } = item;
  const isActive = useIsActive(to);
  return (
    <NavLink
      to={to}
      title={collapsed ? label : undefined}
      className={`flex items-center gap-3 px-3 py-2 rounded-lg transition-all duration-150 group ${isActive
        ? 'bg-blue-50 text-blue-700'
        : 'text-muted-foreground hover:bg-accent hover:text-foreground'
        }`}
    >
      <Icon
        size={18}
        className={`flex-shrink-0 ${isActive ? 'text-blue-600' : 'text-muted-foreground group-hover:text-foreground'}`}
      />
      {!collapsed && <span className="text-sm font-medium">{label}</span>}
    </NavLink>
  );
}

/** One top-level Nav_Block: a click-to-expand/collapse header (Req 1.2) over its
 *  ordered second-level items (Req 1.3). Multiple blocks may be open at once
 *  (Req 1.4). Hidden entirely when the account's Platform_Access excludes the
 *  block's family (Req 12.2, 15.3) or no permitted item resolves (Req 1.7). */
function NavBlockSection({
  block,
  expanded,
  onToggle,
  collapsed,
  can,
  access,
}: {
  block: NavBlock;
  expanded: boolean;
  onToggle: () => void;
  collapsed: boolean;
  can: (p: string) => boolean;
  access: PlatformAccess;
}) {
  const items = visibleItems(block, can);
  // A block renders iff its platform family is within the account's
  // Platform_Access (super-admin bypasses) AND it has at least one permitted,
  // route-resolving item. Delegates the decision to the shared `isBlockVisible`
  // so the sidebar and the visibility logic can never diverge (Req 12.2, 1.7).
  if (!isBlockVisible(block, access, (item) => isItemVisible(item, can))) return null;

  const { icon: BlockIcon, label } = block;

  // Collapsed (icon-only) sidebar: render the block's items as icons without the
  // expand/collapse header so navigation stays usable at the narrow width.
  if (collapsed) {
    return (
      <div className="space-y-0.5">
        {items.map((item) => (
          <NavItemLink key={item.to} item={item} collapsed />
        ))}
      </div>
    );
  }

  return (
    <div>
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        className="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-foreground hover:bg-accent transition-colors"
      >
        <BlockIcon size={17} className="flex-shrink-0 text-muted-foreground" />
        <span className="flex-1 text-left text-sm font-semibold">{label}</span>
        <ChevronDown
          size={15}
          className={`flex-shrink-0 text-muted-foreground transition-transform duration-150 ${expanded ? '' : '-rotate-90'}`}
        />
      </button>
      {expanded && (
        <div className="mt-0.5 space-y-0.5 pl-2">
          {items.map((item, idx) => {
            // Render a subtle, non-clickable sub-header above the first item of
            // each group (groups are contiguous in the config). Ungrouped items
            // render as before, with no header. Purely presentational — the
            // `group` field never affects visibility or routing.
            const showGroupHeader =
              !!item.group && item.group !== items[idx - 1]?.group;
            return (
              <div key={item.to}>
                {showGroupHeader && (
                  <p className="px-3 pt-2 pb-1 text-[10px] font-semibold text-muted-foreground uppercase tracking-wider">
                    {item.group}
                  </p>
                )}
                <NavItemLink item={item} collapsed={false} />
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** Top-right avatar with a click-to-open dropdown holding the account info, the
 *  Account_Area utilities (permission-gated, route-resolved), and a working
 *  退出账户 action (Req 3). */
function AvatarMenu() {
  const { user, can } = usePermissions();
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);

  const name = user?.name?.trim() || '未登录用户';
  const email = user?.email?.trim() || '';
  const initial = (name || email || 'U').charAt(0).toUpperCase();

  // Account_Area utilities the account may see (Req 3.2, 3.3) and whose route
  // resolves.
  const items = accountAreaItems.filter((it) => isItemVisible(it, can));

  return (
    <div className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        className="w-8 h-8 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white text-xs font-medium hover:ring-2 hover:ring-blue-200 transition-all"
        title={name}
      >
        {initial}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={close} />
          <div className="absolute right-0 mt-2 w-60 bg-popover text-popover-foreground border border-border rounded-lg shadow-lg z-20 py-1">
            {/* Account identity */}
            <div className="px-3 py-2.5 border-b border-border">
              <p className="text-sm font-medium text-foreground truncate">{name}</p>
              {email && <p className="text-[11px] text-muted-foreground truncate">{email}</p>}
            </div>
            {/* Account_Area utilities */}
            <div className="py-1 max-h-[60vh] overflow-y-auto">
              <p className="px-3 pt-1 pb-1 text-[10px] font-semibold text-muted-foreground uppercase tracking-wider">系统与管理</p>
              {items.map(({ to, icon: Icon, label }) => (
                <NavLink
                  key={to}
                  to={to}
                  onClick={close}
                  className={({ isActive }) =>
                    `flex items-center gap-2.5 px-3 py-2 text-sm transition-colors ${isActive ? 'bg-blue-50 text-blue-700' : 'text-foreground hover:bg-accent'
                    }`
                  }
                >
                  <Icon size={15} className="text-muted-foreground flex-shrink-0" />
                  <span className="truncate">{label}</span>
                </NavLink>
              ))}
            </div>
            {/* Logout */}
            <div className="border-t border-border pt-1">
              <button
                onClick={() => { close(); logout(); }}
                className="w-full flex items-center gap-2.5 px-3 py-2 text-sm text-red-600 hover:bg-red-50 transition-colors"
              >
                <LogOut size={15} className="flex-shrink-0" />
                退出账户
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/** Sidebar navigation rendering exactly the four Nav_Blocks in fixed order
 *  (Req 1.1) with per-block expand/collapse persisted to localStorage (Req 1.6). */
function SidebarNav({ collapsed }: { collapsed: boolean }) {
  const { can, platformAccess } = usePermissions();

  // Restore the per-block expand/collapse state on mount (Req 1.6). Reads from
  // localStorage lazily so SSR/test environments without storage stay safe.
  const [expandState, setExpandState] = useState<NavExpandState>(() => {
    if (typeof window === 'undefined') return defaultExpandState();
    try {
      return loadExpandState(window.localStorage.getItem(NAV_EXPAND_STORAGE_KEY));
    } catch {
      return defaultExpandState();
    }
  });

  // Persist the state whenever it changes so a reload restores it (Req 1.6).
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      window.localStorage.setItem(NAV_EXPAND_STORAGE_KEY, serializeExpandState(expandState));
    } catch {
      /* storage unavailable — non-fatal */
    }
  }, [expandState]);

  const toggleBlock = (key: PlatformFamily) => {
    setExpandState((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  return (
    <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-2">
      {navBlocks.map((block) => (
        <NavBlockSection
          key={block.key}
          block={block}
          expanded={expandState[block.key]}
          onToggle={() => toggleBlock(block.key)}
          collapsed={collapsed}
          can={can}
          access={platformAccess}
        />
      ))}
    </nav>
  );
}

/** Header quick-search: type to filter the Nav_Items the account is permitted
 *  to see (plus the Account_Area utilities) and jump straight to one. Replaces
 *  the previously non-functional search box. */
function GlobalNavSearch() {
  const [open, setOpen] = useState(false);
  useCommandPaletteShortcut(setOpen);

  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="flex items-center gap-2 bg-muted rounded-lg px-3 py-2 w-72 border border-border text-left transition-colors hover:border-border"
      >
        <SearchIcon size={14} className="text-muted-foreground" />
        <span className="flex-1 text-sm text-muted-foreground">搜索功能页面 / 切换店铺…</span>
        <kbd className="rounded border border-border bg-background px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
          {isMac ? '⌘' : 'Ctrl'} K
        </kbd>
      </button>
      <CommandPalette open={open} onOpenChange={setOpen} />
    </>
  );
}

export function Layout() {
  const [collapsed, setCollapsed] = useState(false);
  const { user } = usePermissions();

  const displayName = user?.name?.trim() || '未登录用户';
  const displayEmail = user?.email?.trim() || '';
  const avatarInitial = (displayName || displayEmail || 'U').charAt(0).toUpperCase();

  return (
    <StoreProvider>
      <div className="flex h-screen bg-background overflow-hidden">
        {/* Sidebar */}
        <aside
          className={`relative flex flex-col bg-sidebar border-r border-sidebar-border transition-all duration-300 ${collapsed ? 'w-[68px]' : 'w-[240px]'
            }`}
        >
          {/* Logo */}
          <div className="flex items-center gap-3 px-4 h-16 border-b border-border">
            <div className="flex-shrink-0 w-8 h-8 bg-gradient-to-br from-blue-600 to-indigo-600 rounded-lg flex items-center justify-center shadow-sm">
              <Zap size={16} className="text-white" />
            </div>
            {!collapsed && (
              <div>
                <p className="text-sm text-sidebar-foreground font-semibold leading-tight">AdPilot AI</p>
                <p className="text-[11px] text-muted-foreground leading-tight">跨境电商智能运营</p>
              </div>
            )}
          </div>

          {/* Navigation */}
          <SidebarNav collapsed={collapsed} />

          {/* Collapse Toggle */}
          <button
            onClick={() => setCollapsed(!collapsed)}
            className="absolute -right-3 top-20 w-6 h-6 bg-background border border-border rounded-full flex items-center justify-center text-muted-foreground hover:text-foreground hover:border-border transition-all z-10 shadow-sm"
          >
            {collapsed ? <ChevronRight size={12} /> : <ChevronLeft size={12} />}
          </button>

          {/* User */}
          <div className="border-t border-border p-3">
            <div className={`flex items-center gap-2 ${collapsed ? 'justify-center' : ''}`}>
              <div className="w-8 h-8 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 flex-shrink-0 flex items-center justify-center text-white text-xs font-medium">
                {avatarInitial}
              </div>
              {!collapsed && (
                <>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm text-sidebar-foreground font-medium truncate">{displayName}</p>
                    {displayEmail && <p className="text-[11px] text-muted-foreground truncate">{displayEmail}</p>}
                  </div>
                  <button
                    onClick={() => logout()}
                    title="退出账户"
                    className="text-muted-foreground hover:text-red-500 transition-colors"
                  >
                    <LogOut size={14} />
                  </button>
                </>
              )}
            </div>
          </div>
        </aside>

        {/* Main Content */}
        <div className="flex-1 flex flex-col overflow-hidden">
          {/* Header */}
          <header className="bg-background border-b border-border px-6 h-16 flex items-center justify-between flex-shrink-0">
            <div className="flex items-center gap-3">
              <GlobalNavSearch />
            </div>
            <div className="flex items-center gap-3">
              <StoreSwitcher />
              <ThemeToggle />
              <NavLink
                to="/ai-notifications"
                title="AI 通知"
                className="relative w-9 h-9 flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-accent rounded-lg transition-all border border-border"
              >
                <Bell size={16} />
              </NavLink>
              <AvatarMenu />
            </div>
          </header>

          {/* Page Content */}
          <main className="flex-1 overflow-y-auto p-6">
            <Suspense
              fallback={
                <div className="flex h-full items-center justify-center py-20 text-sm text-muted-foreground">
                  加载中…
                </div>
              }
            >
              <Outlet />
            </Suspense>
          </main>
        </div>
      </div>
    </StoreProvider>
  );
}
