// AdPilot AI — 独立站单店铺驾驶舱 (Independent-Site single-store cockpit)
//
// 做减法 + 保留功能：instead of one menu item per capability, the operator picks a
// store on the 独立站工作台 hub and lands here. Everything for that ONE store —
// 概览 / Google 广告 / 商品 / 订单 / 库存发货 / 发布 — lives on this single page as
// tabs, with the store pinned at the top and an in-place switcher to jump between
// independent-site stores without leaving the cockpit.
//
// The store is pinned by syncing the route `:storeId` into the global StoreContext
// on mount, so the embedded existing pages (which read the active store from
// context) all operate on the same store. No backend changes; pure composition.

import { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router';
import {
  ArrowLeft,
  Store,
  ChevronDown,
  Check,
  LayoutDashboard,
  Megaphone,
  Package,
  ShoppingCart,
  Boxes,
  Upload,
  Plug,
  CheckCircle2,
  AlertTriangle,
  Clock,
  type LucideIcon,
} from 'lucide-react';
import { useStoreContext } from '../lib/StoreContext';
import { storePlatformFamily, storePlatformLabel, normalizeStorePlatform } from '../lib/platformTaxonomy';
import { fetchIndependentSiteConnectionState, fetchPlatformConnections, type IndependentSiteConnectionState } from '../lib/api';
import { formatDate, cn } from '../lib/utils';
import { ProductsPage } from './ProductsPage';
import { OrdersPage } from './OrdersPage';
import { ProductUploadPage } from './ProductUploadPage';
import { IndependentSiteLogisticsPage } from './IndependentSiteLogisticsPage';
import { GoogleAdsWorkspacePage } from './google-ads/GoogleAdsWorkspacePage';

type CockpitTab = 'overview' | 'ads' | 'products' | 'orders' | 'logistics' | 'upload';

const TABS: { key: CockpitTab; label: string; icon: LucideIcon }[] = [
  { key: 'overview', label: '概览', icon: LayoutDashboard },
  { key: 'ads', label: 'Google 广告', icon: Megaphone },
  { key: 'products', label: '商品', icon: Package },
  { key: 'orders', label: '订单', icon: ShoppingCart },
  { key: 'logistics', label: '库存与发货', icon: Boxes },
  { key: 'upload', label: '商品发布', icon: Upload },
];

const TAB_KEYS = TABS.map((t) => t.key);

function normalizeTab(value?: string | null): CockpitTab {
  return (value && (TAB_KEYS as string[]).includes(value) ? value : 'overview') as CockpitTab;
}

const connectionStateLabel: Record<string, string> = {
  connected: '已连接',
  syncing: '同步中',
  failed: '连接失败',
  not_authorized: '未授权',
};

function connectionStateClass(state?: string | null): string {
  switch ((state || '').toLowerCase()) {
    case 'connected':
      return 'bg-emerald-100 text-emerald-700 border-emerald-200';
    case 'syncing':
      return 'bg-blue-100 text-blue-700 border-blue-200';
    case 'failed':
      return 'bg-red-100 text-red-700 border-red-200';
    default:
      return 'bg-slate-100 text-slate-500 border-slate-200';
  }
}

function ConnIcon({ state }: { state?: string | null }) {
  switch ((state || '').toLowerCase()) {
    case 'connected':
      return <CheckCircle2 size={12} />;
    case 'syncing':
      return <Clock size={12} />;
    case 'failed':
      return <AlertTriangle size={12} />;
    default:
      return <Plug size={12} />;
  }
}

function PlatformAvatar({ platform }: { platform?: string | null }) {
  const norm = normalizeStorePlatform(platform);
  const tint =
    norm === 'shopify'
      ? 'from-emerald-500 to-green-600'
      : norm === 'woocommerce'
        ? 'from-violet-500 to-purple-600'
        : 'from-slate-400 to-slate-500';
  const letter = norm === 'shopify' ? 'S' : norm === 'woocommerce' ? 'W' : '·';
  return (
    <div
      className={cn(
        'flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg bg-gradient-to-br text-base font-semibold text-white',
        tint,
      )}
    >
      {letter}
    </div>
  );
}

export function IndependentSiteStorePage() {
  const { storeId: routeStoreId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const { stores, storeId: activeStoreId, setStoreId, loading: storesLoading } = useStoreContext();

  // Tab is fully URL-driven (?tab=...) so deep links and browser back/forward
  // always reflect the visible tab.
  const tab = normalizeTab(new URLSearchParams(location.search).get('tab'));
  const [switcherOpen, setSwitcherOpen] = useState(false);
  const [states, setStates] = useState<IndependentSiteConnectionState[]>([]);
  // Whether this store has a connected Google Ads account (drives the "广告监控
  // 是否就绪" indicator on the overview). null = unknown/not yet loaded.
  const [adConnected, setAdConnected] = useState<boolean | null>(null);

  // Independent-site stores only (Shopify + WooCommerce shown together).
  const siteStores = useMemo(
    () => stores.filter((s) => storePlatformFamily(s.platform) === 'independent_site'),
    [stores],
  );

  const store = useMemo(
    () => siteStores.find((s) => s.id === routeStoreId),
    [siteStores, routeStoreId],
  );

  // Pin the route store into the global context so every embedded page operates
  // on it. Runs whenever the route store changes.
  useEffect(() => {
    if (routeStoreId && routeStoreId !== activeStoreId && store) {
      setStoreId(routeStoreId);
    }
  }, [routeStoreId, activeStoreId, store, setStoreId]);

  // Connection / capability state for this store (used by the 概览 tab).
  useEffect(() => {
    let cancelled = false;
    if (!routeStoreId) return;
    fetchIndependentSiteConnectionState(routeStoreId)
      .then((rows) => {
        if (!cancelled) setStates(rows ?? []);
      })
      .catch(() => {
        if (!cancelled) setStates([]);
      });
    return () => {
      cancelled = true;
    };
  }, [routeStoreId]);

  // Google Ads account connection state for this store — so the overview shows
  // whether ad monitoring (Google Ads) is ready, not just the sales connection.
  useEffect(() => {
    let cancelled = false;
    if (!routeStoreId) return;
    setAdConnected(null);
    fetchPlatformConnections()
      .then((rows) => {
        if (cancelled) return;
        const ga = (rows as any[]).find(
          (c) => c.storeId === routeStoreId && c.platform === 'google_ads',
        );
        setAdConnected(!!ga && ga.status === 'connected');
      })
      .catch(() => {
        if (!cancelled) setAdConnected(null);
      });
    return () => {
      cancelled = true;
    };
  }, [routeStoreId]);

  const changeTab = (next: CockpitTab) => {
    const qs = new URLSearchParams(location.search);
    qs.set('tab', next);
    // Keep the cockpit deep-linkable per tab without a full navigation.
    navigate(`/independent-site/stores/${routeStoreId}?${qs.toString()}`, { replace: true });
  };

  const switchStore = (id: string) => {
    setSwitcherOpen(false);
    setStoreId(id);
    navigate(`/independent-site/stores/${id}?tab=${tab}`);
  };

  if (storesLoading) {
    return (
      <div className="space-y-4">
        <div className="h-16 animate-pulse rounded-xl border border-slate-200 bg-white" />
        <div className="h-64 animate-pulse rounded-xl border border-slate-200 bg-white" />
      </div>
    );
  }

  // Unknown / non-independent-site store id → guide back to the hub.
  if (!store) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Store size={48} className="mb-4 text-slate-300" />
        <p className="text-lg font-medium text-slate-700">未找到该独立站店铺</p>
        <p className="mt-1 max-w-md text-sm text-slate-500">
          该店铺可能不存在、不属于独立站，或不在你的权限范围内。
        </p>
        <button
          onClick={() => navigate('/independent-site')}
          className="mt-5 inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          <ArrowLeft size={15} />
          返回独立站工作台
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Pinned store header */}
      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <button
          onClick={() => navigate('/independent-site')}
          className="mb-3 inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700"
        >
          <ArrowLeft size={13} />
          独立站工作台
        </button>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <PlatformAvatar platform={store.platform} />
            <div>
              <h1 className="text-xl font-bold text-slate-900">{store.name}</h1>
              <p className="text-xs text-slate-500">{storePlatformLabel(store.platform)}</p>
            </div>
          </div>

          {/* In-place store switcher (independent-site stores only) */}
          <div className="relative">
            <button
              onClick={() => setSwitcherOpen((v) => !v)}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-600 hover:border-slate-300"
            >
              <Store size={14} className="text-blue-600" />
              切换店铺
              <ChevronDown size={14} className="text-slate-400" />
            </button>
            {switcherOpen && (
              <>
                <div className="fixed inset-0 z-10" onClick={() => setSwitcherOpen(false)} />
                <div className="absolute right-0 z-20 mt-1 max-h-80 w-64 overflow-y-auto rounded-lg border border-slate-200 bg-white py-1 shadow-lg">
                  <p className="px-3 py-1.5 text-[11px] font-medium uppercase tracking-wider text-slate-400">
                    独立站店铺
                  </p>
                  {siteStores.map((s) => (
                    <button
                      key={s.id}
                      onClick={() => switchStore(s.id)}
                      className={cn(
                        'flex w-full items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-slate-50',
                        s.id === store.id && 'bg-blue-50',
                      )}
                    >
                      <Store size={14} className={s.id === store.id ? 'text-blue-600' : 'text-slate-400'} />
                      <span className={cn('flex-1 truncate text-sm', s.id === store.id ? 'font-medium text-blue-700' : 'text-slate-700')}>
                        {s.name}
                      </span>
                      {s.id === store.id && <Check size={13} className="text-blue-600" />}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>

        {/* Tab strip */}
        <div className="mt-4 flex flex-wrap gap-1.5 border-t border-slate-100 pt-3">
          {TABS.map(({ key, label, icon: Icon }) => {
            const selected = key === tab;
            return (
              <button
                key={key}
                onClick={() => changeTab(key)}
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                  selected
                    ? 'border border-blue-200 bg-blue-50 text-blue-700'
                    : 'border border-transparent text-slate-600 hover:bg-slate-50',
                )}
              >
                <Icon size={15} className={selected ? 'text-blue-600' : 'text-slate-400'} />
                {label}
              </button>
            );
          })}
        </div>
      </div>

      {/* Tab content — existing pages, reused unchanged, scoped to the pinned store */}
      <div>
        {tab === 'overview' && <StoreOverview store={store} states={states} adConnected={adConnected} onGoTab={changeTab} />}
        {tab === 'ads' && <GoogleAdsWorkspacePage embedded />}
        {tab === 'products' && <ProductsPage />}
        {tab === 'orders' && <OrdersPage />}
        {tab === 'logistics' && <IndependentSiteLogisticsPage />}
        {tab === 'upload' && <ProductUploadPage embedded />}
      </div>
    </div>
  );
}

/** Compact per-store overview: connection state + write capabilities at a glance. */
function StoreOverview({
  store,
  states,
  adConnected,
  onGoTab,
}: {
  store: { id: string; name: string; platform?: string };
  states: IndependentSiteConnectionState[];
  adConnected: boolean | null;
  onGoTab: (tab: CockpitTab) => void;
}) {
  const rows = states.filter((s) => s.store_id === store.id);
  const lastSuccess = rows.map((r) => r.last_success_at).filter(Boolean).sort().pop();
  const inventoryOk = rows.some((r) => r.inventory_write_supported);
  const fulfillmentOk = rows.some((r) => r.fulfillment_write_supported);

  // Guided quick actions mapping the operator's independent-site workflow to the
  // cockpit tabs: bind/manage GA ads, AI-optimize content, API-publish products,
  // and write back inventory/fulfillment — all without leaving the page.
  const actions: {
    tab: CockpitTab;
    icon: LucideIcon;
    title: string;
    desc: string;
    tint: string;
  }[] = [
      {
        tab: 'ads',
        icon: Megaphone,
        title: '绑定并管控 Google 广告',
        desc: '绑定 Google Ads 账户，用 AI 托管按目标自动优化投放。',
        tint: 'text-blue-600 bg-blue-50 border-blue-100',
      },
      {
        tab: 'products',
        icon: Package,
        title: 'AI 优化商品内容',
        desc: '在商品列表打开 Listing AI，按独立站 SEO 优化标题与描述。',
        tint: 'text-indigo-600 bg-indigo-50 border-indigo-100',
      },
      {
        tab: 'upload',
        icon: Upload,
        title: 'API 发布商品',
        desc: '校验后经 Shopify / WooCommerce API 直接发布商品。',
        tint: 'text-emerald-600 bg-emerald-50 border-emerald-100',
      },
      {
        tab: 'logistics',
        icon: Boxes,
        title: '库存与发货回写',
        desc: '回写库存与发货状态；连接器未支持的能力会如实标注。',
        tint: 'text-amber-600 bg-amber-50 border-amber-100',
      },
      {
        tab: 'orders',
        icon: ShoppingCart,
        title: '查看订单',
        desc: '查看并跟进该店铺从平台同步的订单。',
        tint: 'text-violet-600 bg-violet-50 border-violet-100',
      },
    ];

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-slate-700">连接与能力</h2>

        {/* Google Ads 广告账号 readiness — so ad monitoring status is visible at a
            glance alongside the Shopify/WooCommerce sales connection. */}
        <div className="mb-3 flex flex-wrap items-center gap-2 border-b border-slate-100 pb-3">
          <span className="inline-flex items-center gap-1.5 text-sm font-medium text-slate-700">
            <Megaphone size={14} className="text-slate-400" />
            Google 广告账号
          </span>
          {adConnected === null ? (
            <span className="inline-flex items-center gap-1 rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 text-xs font-medium text-slate-400">
              <Clock size={12} />
              检测中
            </span>
          ) : adConnected ? (
            <span className="inline-flex items-center gap-1 rounded-full border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700">
              <CheckCircle2 size={12} />
              已连接 · 广告监控就绪
            </span>
          ) : (
            <>
              <span className="inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-700">
                <AlertTriangle size={12} />
                未绑定广告账号
              </span>
              <button
                onClick={() => onGoTab('ads')}
                className="text-xs font-medium text-blue-600 hover:text-blue-700"
              >
                去绑定 →
              </button>
            </>
          )}
        </div>

        {rows.length === 0 ? (
          <p className="text-sm text-slate-400">该店铺暂无 Shopify / WooCommerce 平台连接。</p>
        ) : (
          <div className="space-y-3">
            {rows.map((r, i) => (
              <div key={`${r.platform}-${i}`} className="flex flex-wrap items-center gap-2">
                <span className="inline-flex items-center gap-1.5 text-sm font-medium text-slate-700">
                  <Plug size={14} className="text-slate-400" />
                  {r.platform}
                </span>
                <span
                  className={cn(
                    'inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium',
                    connectionStateClass(r.connection_state),
                  )}
                >
                  <ConnIcon state={r.connection_state} />
                  {connectionStateLabel[(r.connection_state || '').toLowerCase()] || r.connection_state || '未知'}
                </span>
              </div>
            ))}
            <div className="flex flex-wrap gap-2 pt-1 text-xs">
              <span className={cn('rounded-full border px-2 py-0.5 font-medium', inventoryOk ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-50 text-slate-400')}>
                库存更新：{inventoryOk ? '支持' : '暂不支持'}
              </span>
              <span className={cn('rounded-full border px-2 py-0.5 font-medium', fulfillmentOk ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-50 text-slate-400')}>
                发货标记：{fulfillmentOk ? '支持' : '暂不支持'}
              </span>
            </div>
            {lastSuccess && (
              <p className="text-xs text-slate-400">最近成功同步：{formatDate(lastSuccess)}</p>
            )}
          </div>
        )}
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <h2 className="mb-1 text-sm font-semibold text-slate-700">快速开始</h2>
        <p className="mb-3 text-xs text-slate-500">用下面的入口在本页完成该店铺的全部操作，无需跳转其他菜单。</p>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {actions.map(({ tab, icon: Icon, title, desc, tint }) => (
            <button
              key={tab}
              onClick={() => onGoTab(tab)}
              className="group flex flex-col gap-2 rounded-xl border border-slate-200 bg-white p-3 text-left transition-shadow hover:border-slate-300 hover:shadow-sm"
            >
              <span className={cn('inline-flex h-8 w-8 items-center justify-center rounded-lg border', tint)}>
                <Icon size={16} />
              </span>
              <span className="text-sm font-semibold text-slate-800">{title}</span>
              <span className="text-xs leading-relaxed text-slate-500">{desc}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

export default IndependentSiteStorePage;
