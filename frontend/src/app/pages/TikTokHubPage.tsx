// AdPilot AI — TikTok工作台 (TikTok Store Hub)
//
// Mirrors the 独立站工作台 hub for the TikTok block. Instead of scattering TikTok
// capabilities across separate nav items, this hub lists every connected TikTok
// store and lets the operator click into a single store to manage its catalog
// and orders — all scoped to that one store via the single-store cockpit.
//
// Flow this implements:
//   连接店铺 → 工作台列出已连接 TikTok 店铺 → 点击单个店铺进入其操作（商品 / 订单数据）
//
// NOTE: unlike 独立站, TikTok has NO connection-state endpoint
// (fetchIndependentSiteConnectionState is independent_site-only), so the cards
// here just show store name + platform + a short note rather than live
// connection state. Capability honesty is surfaced through the FlowGuide.

import { useMemo } from 'react';
import { useNavigate } from 'react-router';
import { Store, Plus, ChevronRight, Video } from 'lucide-react';
import { useStoreContext } from '../lib/StoreContext';
import { storePlatformFamily, storePlatformLabel } from '../lib/platformTaxonomy';
import { cn } from '../lib/utils';
import { FlowGuide } from '../components/onboarding/FlowGuide';

interface HubStore {
  id: string;
  name: string;
  platform?: string;
}

/** A TikTok-tinted avatar so the platform reads at a glance. */
function PlatformAvatar() {
  return (
    <div className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-slate-900 to-slate-700 text-white">
      <Video size={16} />
    </div>
  );
}

export function TikTokHubPage() {
  const navigate = useNavigate();
  const { stores, storeId, setStoreId, loading: storesLoading } = useStoreContext();

  // Only TikTok-family stores (multistore-ai-ads-operations Req 5.1, 6.4).
  const tiktokStores = useMemo<HubStore[]>(
    () =>
      stores
        .filter((s) => storePlatformFamily(s.platform) === 'tiktok')
        .map((s) => ({ id: s.id, name: s.name, platform: s.platform })),
    [stores],
  );

  const goConnect = () => navigate('/data-sync?platform=tiktok');

  // Open the single-store cockpit: pin the store as active, then route in.
  const enterStore = (store: HubStore) => {
    setStoreId(store.id);
    navigate(`/tiktok/stores/${store.id}`);
  };

  // ─── Loading state ────────────────────────────────────────────────
  if (storesLoading) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-56 animate-pulse rounded bg-slate-200" />
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {Array.from({ length: 2 }).map((_, i) => (
            <div key={i} className="h-32 animate-pulse rounded-xl border border-slate-200 bg-white" />
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
          <h1 className="text-2xl font-bold text-slate-900">TikTok工作台</h1>
          <p className="mt-1 text-sm text-slate-500">
            管理已连接的 TikTok Shop 店铺。点击任一店铺进入其商品与订单数据操作。
          </p>
        </div>
        <button
          onClick={goConnect}
          className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
        >
          <Plus size={15} />
          连接新店铺
        </button>
      </div>

      <FlowGuide
        title="操作指引：TikTok 店铺管理"
        intro="连接店铺 → 在工作台选店铺 → 进入该店铺的商品 / 订单数据"
        storageKey="tiktok-hub"
        steps={[
          {
            title: '连接 TikTok Shop 店铺',
            detail: '点击右上角「连接新店铺」，用真实凭证绑定 TikTok 店铺；连接成功后会显示在下方列表。',
          },
          {
            title: '在工作台选择要操作的店铺',
            detail: '点击店铺卡片即把它设为当前店铺，并进入该店铺的单店驾驶舱。',
          },
          {
            title: '查看商品与订单数据',
            detail: 'TikTok 目前支持店铺连接与订单 / 商品数据同步。',
          },
          {
            title: 'TikTok Ads 广告管理',
            detail: 'TikTok 广告管理与库存 / 发货回写能力暂未接入。',
            unsupported: true,
          },
        ]}
      />

      {/* Store list */}
      {tiktokStores.length === 0 ? (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 bg-white py-16 text-center">
          <Store size={48} className="mb-4 text-slate-300" />
          <p className="text-lg font-medium text-slate-700">还没有连接 TikTok 店铺</p>
          <p className="mt-1 max-w-md text-sm text-slate-500">
            连接你的 TikTok Shop 店铺后，就能在这里统一管理该店铺的商品与订单数据。
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
          {tiktokStores.map((store) => (
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
  return (
    <button
      type="button"
      onClick={() => onEnter(store)}
      className={cn(
        'group w-full rounded-xl border bg-white p-4 text-left transition-shadow hover:shadow-sm',
        active ? 'border-blue-300 ring-1 ring-blue-100' : 'border-slate-200 hover:border-slate-300',
      )}
    >
      <div className="flex items-start gap-3">
        <PlatformAvatar />
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

      <p className="mt-3 text-xs text-slate-400">
        支持商品与订单数据同步；TikTok Ads 广告管理暂不支持。
      </p>
    </button>
  );
}

export default TikTokHubPage;
