// AdPilot AI — Four-block, two-level navigation configuration
//
// platform-workspace-rbac Requirements 1, 2, 3.
//
// The navigation is collapsed from the legacy twelve flat groups into exactly
// four top-level blocks (亚马逊 / 独立站 / 物流 / 财务统计), each a
// click-to-expand/collapse parent that reveals its ordered second-level
// Nav_Items (Req 1.1–1.5, 2.1–2.4). System-level utilities (系统设置, 飞书机器人,
// 审计回滚, CSV导入) live in the Account_Area (avatar dropdown) and are never
// rendered as Nav_Items inside a block (Req 3.1).
//
// The module-to-block mapping is data, not scattered JSX, so the structure is
// verifiable and stable. Every Nav_Item carries the route, the gating
// functional permission, and — implicitly through its block — a Platform_Access
// family (Req 2.6). A Nav_Item whose route does not resolve against the route
// table is never rendered (Req 2.5).

import {
  LayoutDashboard, Target, Megaphone, KeyRound, Search,
  Sparkles, FileBarChart, Package, Settings,
  Store, Bell, FileUp, CheckSquare, DollarSign,
  Boxes, Truck, ShieldCheck, RotateCcw, Database, BarChart3,
  Crosshair, ClipboardList, TrendingUp, AlertTriangle,
  ShoppingCart, RotateCw, Users, Building2, Warehouse,
  MessageSquare, Star, Layers, Stethoscope, Library,
  Image as ImageIcon, Activity, Brain, Zap, Link2, Video,
  type LucideIcon,
} from 'lucide-react';
import type { CurrentUserInfo } from './api';
import { hasPermission, isSuperAdmin } from './permission';
import { hasPlatformAccess, type PlatformAccess } from './navVisibility';

/** The Platform_Access families, one per Nav_Block (Req 2.6, 12.1). The
 *  `tiktok` family is its own top-level block, isolated from 独立站
 *  (multistore-ai-ads-operations Req 5.1). */
export type PlatformFamily = 'amazon' | 'independent_site' | 'logistics' | 'finance' | 'tiktok';

/** A single second-level navigation entry. `permission`, when set, gates the
 *  item on the logged-in account's functional-permission set; items without a
 *  `permission` are always visible to an authenticated account. */
export interface NavItem {
  to: string;
  icon: LucideIcon;
  label: string;
  permission?: string;
  /** Optional visual grouping label for a block's items. Purely presentational:
   *  it lets a long block (e.g. 亚马逊) render subtle group sub-headers above
   *  contiguous items, and has NO effect on visibility, route resolution, or
   *  family logic. Items without a `group` render flat as before. */
  group?: string;
}

/** A top-level navigation block. Its `key` is the Platform_Access family used
 *  for platform-scoped block visibility (Req 12.2). Items render in order. */
export interface NavBlock {
  key: PlatformFamily;
  label: string;
  icon: LucideIcon;
  items: NavItem[];
}

// The four blocks in their fixed, required order: 亚马逊, 独立站, 物流, 财务统计
// (Req 1.1). Item order within each block is the defined display order (Req 1.3).
export const navBlocks: NavBlock[] = [
  {
    key: 'amazon',
    label: '亚马逊',
    icon: ShoppingCart,
    // RESTRAINED grouping (visual only): the ~30 flat items are tagged with a
    // `group` label and ordered so each group's items are contiguous, letting the
    // sidebar render subtle non-clickable sub-headers. Every item, route and
    // permission is preserved unchanged — no function or route was removed and the
    // `group` field is purely presentational (does not affect visibility/family).
    items: [
      // ── 连接与概览 ──
      // Connection entry (Req 4.1) — single Amazon store-connection entry point.
      { to: '/data-sync?platform=amazon', icon: Link2, label: '亚马逊店铺连接', permission: 'import:manage', group: '连接与概览' },
      { to: '/dashboard', icon: BarChart3, label: '经营看板', permission: 'dashboard:view', group: '连接与概览' },
      { to: '/today-actions', icon: ClipboardList, label: '今日待办', permission: 'dashboard:view', group: '连接与概览' },
      { to: '/approvals', icon: CheckSquare, label: '审批中心', permission: 'approval:view', group: '连接与概览' },
      // ── 广告 (Req 2.1) ── flattened second-level items, kept contiguous.
      { to: '/campaigns', icon: Megaphone, label: '全部搜索广告', permission: 'advertising:view', group: '广告' },
      { to: '/goals', icon: Target, label: '广告目标', permission: 'advertising:view', group: '广告' },
      { to: '/recommendations', icon: Sparkles, label: 'AI优化建议', permission: 'advertising:view', group: '广告' },
      { to: '/ad-monitor', icon: Activity, label: 'AI广告监控', permission: 'advertising:view', group: '广告' },
      { to: '/ad-portfolios', icon: Layers, label: '广告组合', permission: 'advertising:view', group: '广告' },
      { to: '/smart-diagnosis', icon: Stethoscope, label: '智能诊断', permission: 'advertising:view', group: '广告' },
      { to: '/ai-notifications', icon: Bell, label: 'AI通知', permission: 'advertising:view', group: '广告' },
      { to: '/automation-rules', icon: Zap, label: '自动化规则', permission: 'automation:view', group: '广告' },
      { to: '/placement-locks', icon: Crosshair, label: '广告位锁定', permission: 'advertising:view', group: '广告' },
      { to: '/creative-assets', icon: ImageIcon, label: '创意素材', permission: 'advertising:view', group: '广告' },
      { to: '/insight-agent', icon: Brain, label: 'Insight Agent', permission: 'advertising:view', group: '广告' },
      // ── 关键词 ──
      { to: '/keywords', icon: KeyRound, label: '关键词', permission: 'keyword:view', group: '关键词' },
      { to: '/keyword-library', icon: Library, label: '词库', permission: 'keyword:view', group: '关键词' },
      { to: '/search-terms', icon: Search, label: '搜索词', permission: 'keyword:view', group: '关键词' },
      { to: '/keyword-intelligence', icon: Brain, label: '关键词智能', permission: 'keyword:view', group: '关键词' },
      { to: '/rank-monitor', icon: TrendingUp, label: '排名监控', permission: 'keyword:view', group: '关键词' },
      // ── 商品 (Req 2.1) ──
      { to: '/products', icon: Package, label: '商品管理', permission: 'product:view', group: '商品' },
      { to: '/product-ad-data', icon: FileBarChart, label: '产品广告数据', permission: 'advertising:view', group: '商品' },
      { to: '/products/new/listing-ai', icon: Sparkles, label: 'AI商品内容', permission: 'product:view', group: '商品' },
      // ── 销售与客服 ──
      { to: '/orders', icon: ShoppingCart, label: '订单', permission: 'order:view', group: '销售与客服' },
      { to: '/returns', icon: RotateCw, label: '退货', permission: 'order:view', group: '销售与客服' },
      { to: '/refunds', icon: RotateCw, label: '退款', permission: 'order:view', group: '销售与客服' },
      { to: '/buyer-messages', icon: MessageSquare, label: '买家消息', permission: 'customer:view', group: '销售与客服' },
      { to: '/customer-tickets', icon: MessageSquare, label: '客服工单', permission: 'customer:view', group: '销售与客服' },
      { to: '/reviews', icon: Star, label: 'Review', permission: 'review:view', group: '销售与客服' },
      { to: '/feedback', icon: Star, label: 'Feedback', permission: 'review:view', group: '销售与客服' },
      // ── 数据 ──
      { to: '/amc-studio', icon: Boxes, label: 'AMC数据工作室', permission: 'report:view', group: '数据' },
    ],
  },
  {
    key: 'independent_site',
    label: '独立站',
    icon: Store,
    items: [
      // 做减法：the whole 独立站 block collapses to just the hub + the connection
      // entry. Picking a store on the hub opens its single-store cockpit
      // (`/independent-site/stores/:id`) where 概览 / Google 广告 / 商品 / 订单 /
      // 库存发货 / 发布 all live as tabs — so no capability needs its own menu item.
      { to: '/independent-site', icon: LayoutDashboard, label: '独立站工作台', permission: 'store:view' },
      { to: '/data-sync?platform=independent_site', icon: Link2, label: '独立站店铺连接', permission: 'import:manage' },
    ],
  },
  {
    key: 'logistics',
    label: '物流',
    icon: Truck,
    items: [
      { to: '/inventory-health', icon: Boxes, label: '库存健康', permission: 'warehouse:view' },
      { to: '/replenishment', icon: Truck, label: '补货建议', permission: 'warehouse:view' },
      { to: '/warehouses', icon: Warehouse, label: '仓库管理', permission: 'warehouse:view' },
      { to: '/fba-shipments', icon: Truck, label: 'FBA货件', permission: 'warehouse:view' },
      { to: '/purchase-orders', icon: ClipboardList, label: '采购订单', permission: 'procurement:view' },
      { to: '/suppliers', icon: Building2, label: '供应商', permission: 'procurement:view' },
    ],
  },
  {
    key: 'finance',
    label: '财务统计',
    icon: DollarSign,
    items: [
      { to: '/profit-dashboard', icon: DollarSign, label: '利润看板', permission: 'finance:view' },
      { to: '/product-profit', icon: TrendingUp, label: 'SKU利润', permission: 'finance:view' },
      { to: '/settlements', icon: FileBarChart, label: '结算管理', permission: 'finance:view' },
      { to: '/reports', icon: FileBarChart, label: '报表中心', permission: 'report:view' },
      { to: '/data-insights', icon: BarChart3, label: '数据洞察', permission: 'report:view' },
      { to: '/data-quality', icon: AlertTriangle, label: '数据质量', permission: 'import:view' },
    ],
  },
  {
    // TikTok is its own top-level block, isolated from 独立站
    // (multistore-ai-ads-operations Req 5.1, 5.2). Its store-connection entry is
    // scoped to the `tiktok` platform family so it only creates tiktok_shop-family
    // connections (Req 5.3); visibility is gated by the `tiktok` Platform_Access
    // in the navigation shell (Req 5.4). Currently-supported capabilities are
    // store connection plus order/product data sync; TikTok ad-management actions
    // are not yet supported, and their routes are intentionally absent from
    // RESOLVABLE_ROUTES so no silently-failing entry is rendered (Req 5.6).
    key: 'tiktok',
    label: 'TikTok',
    icon: Video,
    items: [
      // 做减法：the whole TikTok block collapses to just the hub + the connection
      // entry. Picking a store on the hub opens its single-store cockpit
      // (`/tiktok/stores/:id`) where 概览 / 商品 / 订单 all live as tabs — so no
      // capability needs its own menu item.
      { to: '/tiktok', icon: LayoutDashboard, label: 'TikTok工作台', permission: 'store:view' },
      // Connection entry (Req 5.2, 5.3) — single TikTok store-connection entry,
      // platform scope limited to the tiktok family.
      { to: '/data-sync?platform=tiktok', icon: Link2, label: 'TikTok店铺连接', permission: 'import:manage' },
    ],
  },
];

// Account_Area utilities (Req 3.1) — the four required system-level utilities
// plus the existing administration pages, each permission-gated at render time
// (Req 3.2, 3.3). These are rendered in the avatar dropdown, never inside a
// Nav_Block.
export const accountAreaItems: NavItem[] = [
  // The four utilities mandated by Requirement 3.1.
  { to: '/settings', icon: Settings, label: '系统设置' },
  { to: '/integrations/feishu', icon: Bell, label: '飞书机器人', permission: 'feishu:view' },
  { to: '/audit-rollback', icon: RotateCcw, label: '审计回滚', permission: 'audit:view' },
  { to: '/imports', icon: FileUp, label: 'CSV导入', permission: 'import:view' },
  // Administration pages preserved from the legacy avatar menu.
  { to: '/stores', icon: Store, label: '店铺设置', permission: 'store:view' },
  { to: '/users', icon: Users, label: '用户管理', permission: 'user:view' },
  { to: '/roles', icon: ShieldCheck, label: '角色管理', permission: 'role:view' },
  { to: '/departments', icon: Building2, label: '部门管理', permission: 'department:view' },
  { to: '/permissions', icon: ShieldCheck, label: '权限管理', permission: 'role:view' },
  { to: '/data-scopes', icon: Database, label: '数据权限', permission: 'role:view' },
  { to: '/settings/ai', icon: Sparkles, label: 'AI 接口配置', permission: 'automation:manage' },
  { to: '/login-logs', icon: FileBarChart, label: '登录日志', permission: 'audit:view' },
  // Personal page — always available to the authenticated account.
  { to: '/profile', icon: Users, label: '个人中心' },
];

// The set of concrete route base-paths the router can resolve, mirroring the
// children declared in `routes.tsx`. A Nav_Item is only rendered when its route
// (stripped of any query string / fragment) is in this set, so an item pointing
// at a not-yet-implemented route is never shown (Req 2.5). Routes added by
// later tasks (e.g. the Google Ads `/google-ads/*` views) are added here when
// their routes are introduced.
export const RESOLVABLE_ROUTES: ReadonlySet<string> = new Set<string>([
  '/',
  '/dashboard',
  '/ad-monitor',
  '/today-actions',
  '/approvals',
  '/orders',
  '/returns',
  '/refunds',
  '/buyer-messages',
  '/products',
  '/products/new/listing-ai',
  '/product-upload',
  '/product-ad-data',
  '/goals',
  '/campaigns',
  '/ad-portfolios',
  '/smart-diagnosis',
  '/ai-notifications',
  '/insight-agent',
  '/keywords',
  '/keyword-library',
  '/search-terms',
  '/keyword-intelligence',
  '/rank-monitor',
  '/creative-assets',
  '/recommendations',
  '/inventory-health',
  '/replenishment',
  '/warehouses',
  '/purchase-orders',
  '/suppliers',
  '/fba-shipments',
  '/profit-dashboard',
  '/product-profit',
  '/settlements',
  '/customer-tickets',
  '/reviews',
  '/feedback',
  '/tasks',
  '/automation-rules',
  '/placement-locks',
  '/integrations/feishu',
  '/audit-rollback',
  '/imports',
  '/data-quality',
  '/data-sync',
  '/reports',
  '/data-insights',
  '/amc-studio',
  // 独立站 Google Ads — single tabbed workspace (replaces the former 6 pages).
  '/google-ads',
  '/independent-site',
  '/independent-site-logistics',
  // TikTok 工作台 (hub). The single-store cockpit `/tiktok/stores/:id` is reached
  // from the hub, not the nav, so it is intentionally left out of this set.
  '/tiktok',
  '/stores',
  '/users',
  '/roles',
  '/departments',
  '/permissions',
  '/data-scopes',
  '/login-logs',
  '/profile',
  '/change-password',
  '/settings',
  '/settings/ai',
]);

/** Strip query string / fragment and return the route's base path. */
export function navItemBasePath(to: string): string {
  return to.split('?')[0].split('#')[0];
}

/** True when a Nav_Item's route resolves against the route table (Req 2.5). */
export function routeResolves(to: string): boolean {
  return RESOLVABLE_ROUTES.has(navItemBasePath(to));
}

// ─── Current Nav_Block platform family (route → family) ─────────────────────
//
// multistore-ai-ads-operations Req 6.4, 6.6.
//
// The store switcher must restrict switching to the stores of the Nav_Block the
// user is currently in, so a selection never crosses platform families. This
// pure projection answers "which Platform_Family does the active route belong
// to?". A connection entry pins the family explicitly via its `?platform=`
// query; otherwise the family is inferred from which Nav_Block(s) own the route.
// Routes shared by multiple blocks (e.g. /orders, /products) are ambiguous and
// resolve to `null`, in which case the switcher imposes no family restriction.

/** Index of base path → the set of Nav_Block families whose items use it. */
const ROUTE_FAMILY_INDEX: ReadonlyMap<string, ReadonlySet<PlatformFamily>> = (() => {
  const index = new Map<string, Set<PlatformFamily>>();
  for (const block of navBlocks) {
    for (const item of block.items) {
      const base = navItemBasePath(item.to);
      let families = index.get(base);
      if (!families) {
        families = new Set<PlatformFamily>();
        index.set(base, families);
      }
      families.add(block.key);
    }
  }
  return index;
})();

const PLATFORM_FAMILY_KEYS: ReadonlySet<string> = new Set(navBlocks.map((b) => b.key));

/**
 * Resolve the Platform_Family of the Nav_Block the user is currently in, from
 * the active route (multistore-ai-ads-operations Req 6.4, 6.6).
 *
 *  - A `?platform=<family>` query (carried by the store-connection entries)
 *    pins the family explicitly when it names a known Platform_Family.
 *  - Otherwise the family is the unique Nav_Block that owns the path; a path
 *    owned by more than one block (a route shared across families, e.g.
 *    `/orders`) or by none is ambiguous and returns `null` so the switcher
 *    leaves the candidate list unrestricted.
 */
export function activeNavBlockFamily(pathname: string, search?: string | null): PlatformFamily | null {
  if (search) {
    const params = new URLSearchParams(search.startsWith('?') ? search : `?${search}`);
    const requested = params.get('platform');
    if (requested && PLATFORM_FAMILY_KEYS.has(requested)) {
      return requested as PlatformFamily;
    }
  }
  const families = ROUTE_FAMILY_INDEX.get(navItemBasePath(pathname));
  if (families && families.size === 1) {
    return [...families][0];
  }
  return null;
}

// ─── Navigation visibility (pure projection of Platform_Access) ─────────────
//
// multistore-ai-ads-operations Req 3.1, 3.2, 3.4, 5.4 (Property 10).
//
// `visibleNavBlocks` is the single, render-independent source of truth for
// "which Nav_Blocks and Nav_Items a given account may see". The navigation
// shell (`Layout`) delegates to these helpers so the rendered sidebar and the
// visibility logic can never diverge. The projection depends solely on the
// account's Platform_Access (which families it may enter) and its functional
// permission set, so a caller that passes a freshly fetched user (from
// `GET /api/auth/me`, never a stale login-time cache) always gets up-to-date
// visibility without requiring re-login (Req 3.4, 5.4).

/** Predicate that returns true when the logged-in account holds `permission`. */
export type CanFn = (permission: string) => boolean;

/** The Platform_Access families, derived from the navigation configuration so
 *  the unrestricted fallback always matches the rendered Nav_Blocks. */
export const ALL_PLATFORM_FAMILIES: readonly PlatformFamily[] = navBlocks.map((b) => b.key);

/**
 * Derive the account's {@link PlatformAccess} dimension from the fetched user
 * (platform-workspace-rbac Req 12.2, 15.3):
 *  - `superAdmin` is true for a Super_Administrator, who enters every block.
 *  - `families` are the granted platform families. When the backend has not yet
 *    surfaced `platformAccess` (absent/null), it falls back to every family so
 *    navigation degrades to functional-permission gating only rather than
 *    hiding all blocks; an explicit list (even empty) gates the blocks.
 */
export function derivePlatformAccess(user: CurrentUserInfo | null): PlatformAccess {
  const superAdmin = isSuperAdmin(user);
  const families: readonly string[] =
    user?.platformAccess != null ? user.platformAccess : ALL_PLATFORM_FAMILIES;
  return { families, superAdmin };
}

/**
 * Decide whether a single Nav_Item is shown: its route must resolve against the
 * route table (Req 2.5) AND, when the item declares a gating permission, the
 * account must hold that functional permission (Req 3.1, 3.2). Items without a
 * `permission` are visible to any authenticated account.
 */
export function isNavItemVisible(item: NavItem, can: CanFn): boolean {
  if (!routeResolves(item.to)) return false;
  return !item.permission || can(item.permission);
}

/** A Nav_Block paired with exactly the Nav_Items the account may see, in the
 *  block's defined display order (Req 1.3). */
export interface VisibleNavBlock {
  block: NavBlock;
  items: NavItem[];
}

/**
 * Pure projection of the navigation a given account may see (Property 10).
 *
 * A Nav_Block is included if and only if BOTH hold:
 *   1. its platform family is within the account's Platform_Access (a
 *      Super_Administrator bypasses the family gate; Req 3.1, 5.4), AND
 *   2. it contains at least one route-resolving Nav_Item the account holds the
 *      gating functional permission for (Req 3.1, 3.2).
 * A block failing either condition is omitted entirely — in particular the
 * Google Ads items live in the `independent_site` block, so an account without
 * `independent_site` access (or without `advertising:view`) sees no Google Ads
 * Nav_Item (Req 3.2); the `tiktok` block is gated the same way (Req 5.4). Each
 * included block carries only its visible items, preserving display order.
 *
 * The result is a deterministic function of the account's Platform_Access and
 * permission set: the same identity under different Platform_Access inputs
 * yields correspondingly different visibility, with no login-state caching
 * (Req 3.4, 5.4).
 */
export function visibleNavBlocks(user: CurrentUserInfo | null): VisibleNavBlock[] {
  const access = derivePlatformAccess(user);
  const can: CanFn = (permission) => hasPermission(user, permission);
  const result: VisibleNavBlock[] = [];
  for (const block of navBlocks) {
    if (!hasPlatformAccess(block.key, access)) continue;
    const items = block.items.filter((item) => isNavItemVisible(item, can));
    if (items.length === 0) continue;
    result.push({ block, items });
  }
  return result;
}

// ─── Expand/collapse state persistence (Req 1.6) ────────────────────────────

/** Per-block expanded/collapsed state, keyed by Platform_Family. */
export type NavExpandState = Record<PlatformFamily, boolean>;

export const NAV_EXPAND_STORAGE_KEY = 'adpilot.nav.expanded';

/** Default state: every block starts expanded. */
export function defaultExpandState(): NavExpandState {
  return { amazon: true, independent_site: true, logistics: true, finance: true, tiktok: true };
}

/**
 * Restore the per-block expand/collapse state from persisted storage, falling
 * back to the default for any missing/invalid block so the result is always a
 * complete, valid state (Req 1.6).
 */
export function loadExpandState(raw: string | null): NavExpandState {
  const base = defaultExpandState();
  if (!raw) return base;
  try {
    const parsed = JSON.parse(raw) as Partial<Record<string, unknown>>;
    if (!parsed || typeof parsed !== 'object') return base;
    for (const key of Object.keys(base) as PlatformFamily[]) {
      const value = parsed[key];
      if (typeof value === 'boolean') base[key] = value;
    }
    return base;
  } catch {
    return base;
  }
}

/** Serialize the expand/collapse state for persistence (Req 1.6). */
export function serializeExpandState(state: NavExpandState): string {
  return JSON.stringify(state);
}
