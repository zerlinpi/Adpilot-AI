// AdPilot AI — 独立站工作台 (Independent-Site Store Hub)
//
// The spine of the 独立站 (independent-site) block. Instead of making the operator
// hop between scattered nav items and track "which store am I on", this hub lists
// every connected independent-site store (Shopify + WooCommerce together) and lets
// the operator click into a single store to manage its ads, catalog, orders,
// inventory and fulfillment — all scoped to that one store.
//
// Flow this implements:
//   连接店铺 → 工作台列出已连接店铺(shopify/wp 显示到一起) → 点击单个店铺进入其操作（绑定/管控广告等）
//
// It reuses the existing per-store pages: clicking a store action sets the active
// store (StoreContext) and navigates to the relevant existing page, so this hub
// adds the missing "store list + per-store entry" layer without duplicating logic.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Store,
  Plus,
  RefreshCw,
  CheckCircle2,
  AlertTriangle,
  Clock,
  Plug,
  ChevronRight,
} from 'lucide-react';
import { fetchIndependentSiteConnectionState, type IndependentSiteConnectionState } from '../lib/api';
import { useStoreContext } from '../lib/StoreContext';
import { storePlatformFamily, storePlatformLabel, normalizeStorePlatform } from '../lib/platformTaxonomy';
import { formatDate, cn } from '../lib/utils';
import { FlowGuide } from '../components/onboarding/FlowGuide';

// ─── Connection-state vocabulary (shared with the logistics page) ────
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

function ConnectionIcon({ state }: { state?: string | null }) {
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

/** A platform-tinted avatar so Shopify vs WooCommerce read at a glance. */
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
        'flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg bg-gradient-to-br text-sm font-semibold text-white',
        tint,
      )}
    >
      {letter}
    </div>
  );
}

interface HubStore {
  id: string;
  name: string;
  platform?: string;
  /** Aggregated connection states for this store (one per platform connection). */
  states: IndependentSiteConnectionState[];
}

export function IndependentSiteHubPage() {
  const navigate = useNavigate();
  const { stores, storeId, setStoreId, loading: storesLoading } = useStoreContext();

  const [states, setStates] = useState<IndependentSiteConnectionState[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Only independent-site stores (Shopify + WooCommerce shown together, Req 6.4/6.6).
  const siteStores = useMemo(
    () => stores.filter((s) => storePlatformFamily(s.platform) === 'independent_site'),
    [stores],
  );

  const loadStates = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const result = await fetchIndependentSiteConnectionState();
      setStates(result ?? []);
    } catch (err: any) {
      setError(err?.message || '加载店铺连接状态失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadStates();
  }, [loadStates]);

  // Join each store with its connection-state rows (matched by store_id).
  const hubStores: HubStore[] = useMemo(
    () =>
      siteStores.map((s) => ({
        id: s.id,
        name: s.name,
        platform: s.platform,
        states: states.filter((st) => st.store_id === s.id),
      })),
    [siteStores, states],
  );

  const goConnect = () => navigate('/data-sync?platform=independent_site');

  // Open the single-store cockpit: pin the store as active, then route in.
  const enterStore = (store: HubStore) => {
    setStoreId(store.id);
    navigate(`/independent-site/stores/${store.id}`);
  };

  // ─── Loading / empty states ───────────────────────────────────────
  if (storesLoading) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-56 animate-pulse rounded bg-slate-200" />
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {Array.from({ length: 2 }).map((_, i) => (
            <div key={i} className="h-44 animate-pulse rounded-xl border border-slate-200 bg-white" />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">独立站工作台</h1>
          <p className="mt-1 text-sm text-slate-500">
            管理已连接的 Shopify / WooCommerce 店铺。点击任一店铺进入其广告、商品、订单与库存发货操作。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={loadStates}
            disabled={loading}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-60"
          >
            <RefreshCw size={14} className={cn(loading && 'animate-spin')} />
            刷新
          </button>
          <button
            onClick={goConnect}
            className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
          >
            <Plus size={15} />
            连接新店铺
          </button>
        </div>
      </div>

      <FlowGuide
        title="操作指引：独立站店铺管理"
        intro="连接店铺 → 在工作台选店铺 → 进入该店铺的广告 / 商品 / 订单 / 库存发货"
        storageKey="independent-site-hub"
        steps={[
          {
            title: '连接 Shopify / WooCommerce 店铺',
            detail: '点击右上角「连接新店铺」，用真实凭证绑定店铺；连接成功后会显示在下方列表。',
          },
          {
            title: '在工作台选择要操作的店铺',
            detail: 'Shopify 与 WooCommerce 店铺统一显示在这里，点击店铺上的操作即把它设为当前店铺。',
          },
          {
            title: '绑定并管控 Google 广告',
            detail: '点击店铺的「管理 Google 广告」绑定广告账户，并用 AI 托管按目标自动优化。',
          },
          {
            title: '商品发布 / 库存发货',
            detail: '通过 API 上传商品、回写库存与发货；连接器尚未支持的能力会如实标注「暂不支持」。',
          },
        ]}
      />

      {/* Store list */}
      {error ? (
        <div className="flex flex-col items-center justify-center rounded-xl border border-red-100 bg-red-50/50 py-12 text-center">
          <AlertTriangle size={40} className="mb-3 text-red-300" />
          <p className="max-w-md text-sm text-slate-600">{error}</p>
          <button
            onClick={loadStates}
            className="mt-4 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            重试
          </button>
        </div>
      ) : siteStores.length === 0 ? (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 bg-white py-16 text-center">
          <Store size={48} className="mb-4 text-slate-300" />
          <p className="text-lg font-medium text-slate-700">还没有连接独立站店铺</p>
          <p className="mt-1 max-w-md text-sm text-slate-500">
            连接你的 Shopify 或 WordPress / WooCommerce 店铺后，就能在这里统一管理广告、商品、订单与库存发货。
          </p>
          <button
            onClick={goConnect}
            className="mt-5 inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            <Plus size={15} />
            连接第一个店铺
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {hubStores.map((store) => (
            <StoreCard
              key={store.id}
              store={store}
              active={store.id === storeId}
              onEnter={enterStore}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function StoreCard({
  store,
  active,
  onEnter,
}: {
  store: HubStore;
  active: boolean;
  onEnter: (store: HubStore) => void;
}) {
  // Surface the store's connection rows; a store may have more than one platform
  // connection (rare), so show each. When none exist yet the store is connected
  // at the store level but has no Shopify/Woo platform-connection row.
  const rows = store.states;
  const lastSuccess = rows
    .map((r) => r.last_success_at)
    .filter(Boolean)
    .sort()
    .pop();

  return (
    <button
      type="button"
      onClick={() => onEnter(store)}
      className={cn(
        'group w-full rounded-xl border bg-white p-4 text-left transition-shadow hover:shadow-sm',
        active ? 'border-blue-300 ring-1 ring-blue-100' : 'border-slate-200 hover:border-slate-300',
      )}
    >
      {/* Card header */}
      <div className="flex items-start gap-3">
        <PlatformAvatar platform={store.platform} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="truncate text-base font-semibold text-slate-900">{store.name}</p>
            {active && (
              <span className="rounded-full bg-blue-50 px-2 py-0.5 text-[11px] font-medium text-blue-600">
                当前店铺
              </span>
            )}
          </div>
          <p className="mt-0.5 text-xs text-slate-500">{storePlatformLabel(store.platform)}</p>
        </div>
        <span className="inline-flex items-center gap-1 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white transition-colors group-hover:bg-blue-700">
          进入店铺
          <ChevronRight size={14} />
        </span>
      </div>

      {/* Connection status row(s) */}
      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        {rows.length === 0 ? (
          <span className="inline-flex items-center gap-1 rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 text-xs font-medium text-slate-500">
            <Plug size={12} />
            未检测到平台连接
          </span>
        ) : (
          rows.map((r, i) => (
            <span
              key={`${r.platform}-${i}`}
              className={cn(
                'inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium',
                connectionStateClass(r.connection_state),
              )}
            >
              <ConnectionIcon state={r.connection_state} />
              {connectionStateLabel[(r.connection_state || '').toLowerCase()] || r.connection_state || '未知'}
            </span>
          ))
        )}
        {lastSuccess && (
          <span className="text-[11px] text-slate-400">最近同步 {formatDate(lastSuccess)}</span>
        )}
      </div>
    </button>
  );
}

export default IndependentSiteHubPage;
