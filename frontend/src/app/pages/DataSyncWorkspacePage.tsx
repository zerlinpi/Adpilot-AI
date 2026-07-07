import { useSearchParams } from 'react-router';
import { Database, ListChecks, FileText } from 'lucide-react';
import { cn } from '../lib/utils';
import { ApiConnectionsPage } from './ApiConnectionsPage';
import { PlatformSyncPage } from './PlatformSyncPage';
import { SyncLogsPage } from './SyncLogsPage';
import { useStoreContext } from '../lib/StoreContext';
import { adPlatformLabel, storeAdPlatform, storePlatformGroupLabel, storePlatformLabel } from '../lib/platformTaxonomy';
import { FlowGuide } from '../components/onboarding/FlowGuide';

type SyncTab = 'connections' | 'jobs' | 'logs';
type PlatformFamily = 'amazon' | 'independent_site';

/** Map the `?platform=` query to a connection-entry Platform_Access family so
 *  the 亚马逊店铺连接 and 独立站店铺连接 nav entries each scope the connection
 *  UI to their own platforms. `amazon` → Amazon family; independent-site
 *  platforms (independent_site / shopify / woocommerce / tiktok_shop /
 *  google_ads) → 独立站 family; anything else leaves both families visible. */
function platformFamilyFromQuery(platform: string | null): PlatformFamily | undefined {
  if (!platform) return undefined;
  if (platform === 'amazon') return 'amazon';
  if (['independent_site', 'shopify', 'woocommerce', 'tiktok_shop', 'google_ads'].includes(platform)) {
    return 'independent_site';
  }
  return undefined;
}

const tabs: { key: SyncTab; label: string; description: string; icon: typeof Database }[] = [
  {
    key: 'connections',
    label: '平台连接',
    description: '接入店铺和平台凭证',
    icon: Database,
  },
  {
    key: 'jobs',
    label: '同步任务',
    description: '触发拉数并跟踪进度',
    icon: ListChecks,
  },
  {
    key: 'logs',
    label: '同步日志',
    description: '排查任务和记录错误',
    icon: FileText,
  },
];

function normalizeTab(value: string | null): SyncTab {
  return value === 'jobs' || value === 'logs' ? value : 'connections';
}

export function DataSyncWorkspacePage() {
  const [params, setParams] = useSearchParams();
  const { stores, storeId } = useStoreContext();
  const activeTab = normalizeTab(params.get('tab'));
  const requestedPlatform = params.get('platform');
  const currentStore = stores.find((s) => s.id === storeId);
  // A family-level query (`amazon` / `independent_site`) is not itself a concrete
  // ad-platform key; map it to the family's real default ad platform so the
  // breadcrumb shows "Amazon Ads" / "Google Ads" instead of "未配置广告平台".
  const FAMILY_AD_PLATFORM: Record<string, string> = {
    amazon: 'amazon_ads',
    independent_site: 'google_ads',
  };
  const effectiveAdPlatform =
    requestedPlatform === 'tiktok_shop'
      ? 'tiktok_ads'
      : (requestedPlatform ? FAMILY_AD_PLATFORM[requestedPlatform] : undefined)
      || requestedPlatform
      || currentStore?.adPlatform
      || storeAdPlatform(currentStore?.platform);
  const connectionPlatformLabel =
    requestedPlatform === 'tiktok_shop' ? 'TikTok Shop' : adPlatformLabel(effectiveAdPlatform);

  const setActiveTab = (tab: SyncTab) => {
    const next = new URLSearchParams(params);
    if (tab === 'connections') {
      next.delete('tab');
    } else {
      next.set('tab', tab);
    }
    setParams(next, { replace: true });
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 border-b border-slate-200 pb-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">连接与同步</h1>
            <p className="mt-1 text-sm text-slate-500">
              先接入平台，再触发同步，最后在日志里定位异常。
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {tabs.map(({ key, label, description, icon: Icon }) => {
            const selected = key === activeTab;
            return (
              <button
                key={key}
                type="button"
                onClick={() => setActiveTab(key)}
                className={cn(
                  'flex min-w-[180px] items-center gap-3 rounded-lg border px-3 py-2 text-left transition-colors',
                  selected
                    ? 'border-blue-200 bg-blue-50 text-blue-700'
                    : 'border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50',
                )}
              >
                <Icon size={17} className={selected ? 'text-blue-600' : 'text-slate-400'} />
                <span className="min-w-0">
                  <span className="block text-sm font-medium">{label}</span>
                  <span className="block truncate text-xs opacity-80">{description}</span>
                </span>
              </button>
            );
          })}
        </div>
        <div className="rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
          <span className="font-medium text-slate-800">{currentStore?.name ?? '当前店铺'}</span>
          <span className="mx-2 text-slate-300">/</span>
          <span>{storePlatformGroupLabel(currentStore?.platform)}</span>
          <span className="mx-2 text-slate-300">/</span>
          <span>{storePlatformLabel(currentStore?.platform)}</span>
          <span className="mx-2 text-slate-300">/</span>
          <span>{requestedPlatform === 'tiktok_shop' ? '连接平台' : '默认广告'}：{connectionPlatformLabel}</span>
          {effectiveAdPlatform === 'google_ads' && (
            <p className="mt-1 text-xs text-slate-500">
              独立站使用 Google Ads 作为广告账号；这里仅做真实凭证绑定与同步任务入口，不展示未接入的数据。
            </p>
          )}
          {(effectiveAdPlatform === 'tiktok_ads' || requestedPlatform === 'tiktok_shop') && (
            <p className="mt-1 text-xs text-slate-500">
              TikTok 店铺先保留店铺数据同步入口；TikTok Ads 管理尚未接入，不会混用 Amazon Ads 模块。
            </p>
          )}
        </div>
      </div>

      {/* Step-by-step guidance: store connection (各平台族) & TikTok (Req 8.3, 8.6) */}
      <FlowGuide
        title="操作指引：店铺连接与数据同步"
        intro="各平台族在各自菜单内连接，互不混用"
        storageKey="data-sync-workspace"
        steps={[
          {
            title: '在「平台连接」绑定店铺凭证',
            detail:
              '亚马逊店铺连亚马逊（amazon_ads / amazon_sp_api），独立站连 Shopify / WooCommerce（及 Google Ads 广告账户），TikTok 连 TikTok Shop。每个平台族只在自己的菜单入口内创建同族连接。',
          },
          {
            title: '在「同步任务」触发拉数',
            detail: '绑定成功后触发同步任务并跟踪进度，把店铺、商品与广告表现数据拉入系统。',
          },
          {
            title: '在「同步日志」排查异常',
            detail: '同步失败时在日志里定位错误原因，修正凭证或配置后重试。',
          },
          {
            title: 'TikTok 广告管理',
            detail:
              'TikTok 目前先支持店铺连接与订单 / 商品数据同步；TikTok Ads 广告管理动作尚未接入，不会混用 Amazon Ads 模块。',
            unsupported: true,
          },
        ]}
        note={
          requestedPlatform === 'tiktok_shop' || requestedPlatform === 'tiktok' ? (
            <>
              当前入口范围为 <strong>TikTok</strong>：仅创建 TikTok 族连接（tiktok_shop）。TikTok 广告管理能力尚未提供，已如实标注为「暂不支持」。
            </>
          ) : (
            <>
              渠道之间相互隔离：亚马逊、独立站、TikTok 各自连接与显示，店铺切换只在当前平台族内进行，不会把不同渠道的数据搞混。
            </>
          )
        }
      />

      {activeTab === 'connections' && (
        <ApiConnectionsPage platformFamily={platformFamilyFromQuery(requestedPlatform)} />
      )}
      {activeTab === 'jobs' && <PlatformSyncPage />}
      {activeTab === 'logs' && <SyncLogsPage />}
    </div>
  );
}
