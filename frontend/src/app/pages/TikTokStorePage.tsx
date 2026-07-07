// AdPilot AI — TikTok单店铺驾驶舱 (TikTok single-store cockpit)
//
// 做减法 + 保留功能：mirrors IndependentSiteStorePage for the TikTok block. The
// operator picks a store on the TikTok工作台 hub and lands here; everything for
// that ONE store — 概览 / 商品 / 订单 — lives on this single page as tabs, with
// the store pinned at the top and an in-place switcher to jump between TikTok
// stores without leaving the cockpit.
//
// The store is pinned by syncing the route `:storeId` into the global
// StoreContext on mount, so the embedded existing pages (ProductsPage,
// OrdersPage — both read the active store from context) all operate on the same
// store. No backend changes; pure composition.

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
  Upload,
  Boxes,
  Video,
  type LucideIcon,
} from 'lucide-react';
import { useStoreContext } from '../lib/StoreContext';
import { storePlatformFamily, storePlatformLabel } from '../lib/platformTaxonomy';
import { cn } from '../lib/utils';
import { FlowGuide } from '../components/onboarding/FlowGuide';
import { ProductsPage } from './ProductsPage';
import { OrdersPage } from './OrdersPage';
import { ProductUploadPage } from './ProductUploadPage';
import { IndependentSiteLogisticsPage } from './IndependentSiteLogisticsPage';
import { TikTokAdsPanel } from './TikTokAdsPanel';

type CockpitTab = 'overview' | 'ads' | 'products' | 'orders' | 'upload' | 'logistics';

const TABS: { key: CockpitTab; label: string; icon: LucideIcon }[] = [
  { key: 'overview', label: '概览', icon: LayoutDashboard },
  { key: 'ads', label: 'TikTok 广告', icon: Megaphone },
  { key: 'products', label: '商品', icon: Package },
  { key: 'orders', label: '订单', icon: ShoppingCart },
  { key: 'upload', label: '商品发布', icon: Upload },
  { key: 'logistics', label: '库存与发货', icon: Boxes },
];

const TAB_KEYS = TABS.map((t) => t.key);

function normalizeTab(value?: string | null): CockpitTab {
  return (value && (TAB_KEYS as string[]).includes(value) ? value : 'overview') as CockpitTab;
}

/** A TikTok-tinted avatar so the platform reads at a glance. */
function PlatformAvatar() {
  return (
    <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-slate-900 to-slate-700 text-white">
      <Video size={18} />
    </div>
  );
}

export function TikTokStorePage() {
  const { storeId: routeStoreId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const { stores, storeId: activeStoreId, setStoreId, loading: storesLoading } = useStoreContext();

  // Tab is fully URL-driven (?tab=...) so deep links and browser back/forward
  // always reflect the visible tab.
  const tab = normalizeTab(new URLSearchParams(location.search).get('tab'));
  const [switcherOpen, setSwitcherOpen] = useState(false);

  // TikTok-family stores only.
  const tiktokStores = useMemo(
    () => stores.filter((s) => storePlatformFamily(s.platform) === 'tiktok'),
    [stores],
  );

  const store = useMemo(
    () => tiktokStores.find((s) => s.id === routeStoreId),
    [tiktokStores, routeStoreId],
  );

  // Pin the route store into the global context so every embedded page operates
  // on it. Runs whenever the route store changes.
  useEffect(() => {
    if (routeStoreId && routeStoreId !== activeStoreId && store) {
      setStoreId(routeStoreId);
    }
  }, [routeStoreId, activeStoreId, store, setStoreId]);

  const changeTab = (next: CockpitTab) => {
    const qs = new URLSearchParams(location.search);
    qs.set('tab', next);
    // Keep the cockpit deep-linkable per tab without a full navigation.
    navigate(`/tiktok/stores/${routeStoreId}?${qs.toString()}`, { replace: true });
  };

  const switchStore = (id: string) => {
    setSwitcherOpen(false);
    setStoreId(id);
    navigate(`/tiktok/stores/${id}?tab=${tab}`);
  };

  if (storesLoading) {
    return (
      <div className="space-y-4">
        <div className="h-16 animate-pulse rounded-xl border border-slate-200 bg-white" />
        <div className="h-64 animate-pulse rounded-xl border border-slate-200 bg-white" />
      </div>
    );
  }

  // Unknown / non-TikTok store id → guide back to the hub.
  if (!store) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Store size={48} className="mb-4 text-slate-300" />
        <p className="text-lg font-medium text-slate-700">未找到该 TikTok 店铺</p>
        <p className="mt-1 max-w-md text-sm text-slate-500">
          该店铺可能不存在、不属于 TikTok，或不在你的权限范围内。
        </p>
        <button
          onClick={() => navigate('/tiktok')}
          className="mt-5 inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          <ArrowLeft size={15} />
          返回 TikTok工作台
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Pinned store header */}
      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <button
          onClick={() => navigate('/tiktok')}
          className="mb-3 inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700"
        >
          <ArrowLeft size={13} />
          TikTok工作台
        </button>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <PlatformAvatar />
            <div>
              <h1 className="text-xl font-bold text-slate-900">{store.name}</h1>
              <p className="text-xs text-slate-500">{storePlatformLabel(store.platform)}</p>
            </div>
          </div>

          {/* In-place store switcher (TikTok stores only) */}
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
                    TikTok 店铺
                  </p>
                  {tiktokStores.map((s) => (
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
        {tab === 'overview' && <StoreOverview />}
        {tab === 'ads' && <TikTokAdsPanel />}
        {tab === 'products' && <ProductsPage />}
        {tab === 'orders' && <OrdersPage />}
        {tab === 'upload' && <ProductUploadPage embedded />}
        {tab === 'logistics' && <IndependentSiteLogisticsPage variant="tiktok" />}
      </div>
    </div>
  );
}

/** Honest-capability overview for a TikTok store: states what is supported now
 *  and what is intentionally not yet supported (做减法 + 如实标注). */
function StoreOverview() {
  return (
    <FlowGuide
      title="TikTok 店铺能力说明"
      intro="如实标注当前支持与暂不支持的能力"
      defaultOpen
      storageKey="tiktok-store-overview"
      steps={[
        {
          title: '店铺连接',
          detail: '通过「TikTok店铺连接」用真实凭证绑定 TikTok Shop 店铺。',
        },
        {
          title: '订单 / 商品数据同步',
          detail: '在上方「商品」「订单」标签查看该店铺已同步的商品与订单数据。',
        },
        {
          title: '商品直发 TikTok Shop',
          detail: '在「商品发布」标签经 TikTok Shop API 真实创建商品；发布失败会显示平台返回的真实原因，不会伪造成功。',
        },
        {
          title: '库存 / 发货回写',
          detail: '在「库存与发货」标签经异步回写队列向 TikTok 回写库存与发货标记；连接器未提供的能力会如实标注「暂不支持」，不会静默失败。',
        },
        {
          title: 'TikTok Ads 广告监控',
          detail: '在「TikTok 广告」标签查看已绑定 TikTok Ads 账号的广告系列与绩效报告（只读）。广告投放与 AI 托管暂未接入。',
        },
      ]}
    />
  );
}

export default TikTokStorePage;
