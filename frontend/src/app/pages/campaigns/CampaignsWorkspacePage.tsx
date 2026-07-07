// CampaignsWorkspacePage — the routed advertising page (全部搜索广告).
//
// This is the decomposed replacement for the former monolithic `CampaignsPage`.
// It owns the page header and store-scoping, and delegates tab presentation to
// the AdvertisingWorkspace four-group navigation shell (Req 28), which presents
// the four groups (广告管理 / 搜索词管理 / 智能优化 / 记录与审计) over the
// eleven existing tabs as a total partition, wiring each tab to its extracted
// per-tab module via the workspace `renderTab` callback (Req 1.1, 1.2). Each
// per-tab module composes the reusable table building blocks (SharedDataTable /
// FilterToolbar / BulkActionBar) where the original tab had a table / filters /
// bulk actions (Req 1.3, 1.4, 1.9).

import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { AlertCircle, CheckCircle2, Megaphone, Smartphone } from 'lucide-react';

import { useStoreId } from '../../lib/useStoreId';
import { useStoreContext } from '../../lib/StoreContext';
import { Skeleton } from '../../components/ui/skeleton';
import { KpiPanel } from '../../components/kpi/KpiPanel';
import { useAdvertisingContextPreservation } from '../../lib/hooks/useAdvertisingContextPreservation';
import { useUnsavedEditGuard } from '../../lib/hooks/useUnsavedEditGuard';
import { fetchPlatformConnections } from '../../lib/api';
import { normalizeStorePlatform } from '../../lib/platformTaxonomy';

import { type CampaignsTabKey } from './CampaignsWorkspace';
import { AdvertisingWorkspace } from './AdvertisingWorkspace';
import { ContextBar } from './ContextBar';
import { FilterChips } from './FilterChips';
import { CampaignsTab } from './tabs/CampaignsTab';
import { AiHostingTab } from './tabs/AiHostingTab';
import { AdGroupsTab } from './tabs/AdGroupsTab';
import { PromotedProductsTab, OtherProductsTab } from './tabs/PromotedProductsTab';
import { TargetingTab } from './tabs/TargetingTab';
import { NegativeTargetingTab } from './tabs/NegativeTargetingTab';
import { SearchTermsTab } from './tabs/SearchTermsTab';
import { BidAdjustmentsTab } from './tabs/BidAdjustmentsTab';
import { BudgetCapsTab } from './tabs/BudgetCapsTab';
import { OperationLogTab } from './tabs/OperationLogTab';

export function CampaignsWorkspacePage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const { stores } = useStoreContext();

  const activeStore = stores.find((s) => s.id === storeId);
  const [connections, setConnections] = useState<any[]>([]);
  const [connectionLoading, setConnectionLoading] = useState(false);
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const [connectionReloadKey, setConnectionReloadKey] = useState(0);

  useEffect(() => {
    if (!storeId || normalizeStorePlatform(activeStore?.platform) !== 'amazon') {
      setConnections([]);
      setConnectionLoading(false);
      setConnectionError(null);
      return;
    }
    let cancelled = false;
    setConnectionLoading(true);
    fetchPlatformConnections()
      .then((items) => {
        if (!cancelled) {
          setConnections(Array.isArray(items) ? items : []);
          setConnectionError(null);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setConnections([]);
          setConnectionError(error instanceof Error ? error.message : '读取 Amazon Ads 连接失败');
        }
      })
      .finally(() => { if (!cancelled) setConnectionLoading(false); });
    return () => { cancelled = true; };
  }, [storeId, activeStore?.platform, connectionReloadKey]);

  const amazonAdsConnection = connections.find((connection) =>
    (connection.platform === 'amazon_ads' || connection.platformId === 'amazon_ads')
      && connection.storeId === storeId,
  );
  const amazonAdsReady = amazonAdsConnection?.status === 'connected';

  // Cross-tab context preservation (Req 45) + store-switch clearing (Req 40.1).
  // The hook owns the controlled active tab and the capture→restore round-trip;
  // it also exposes the live responsive mode (Req 43) and whether any unsaved
  // edit is in progress (Req 40.2).
  const {
    activeTab,
    onTabChange,
    responsiveMode,
    hasUnsavedEdits,
    ContextProvider,
  } = useAdvertisingContextPreservation({ storeId });

  // Unsaved-edit leave guard (Req 40.2, 40.3): confirm before navigating away
  // from the advertising module while an edit is in progress; cancelling keeps
  // the operator here with the edit preserved.
  useUnsavedEditGuard({ hasUnsavedEdits });

  const renderTab = (key: CampaignsTabKey) => {
    switch (key) {
      case 'campaigns':
        return <CampaignsTab storeId={storeId} />;
      case 'hosting':
        return <AiHostingTab storeId={storeId} />;
      case 'adGroups':
        return <AdGroupsTab storeId={storeId} />;
      case 'promotedProducts':
        return <PromotedProductsTab storeId={storeId} />;
      case 'targeting':
        return <TargetingTab />;
      case 'negativeTargeting':
        return <NegativeTargetingTab storeId={storeId} />;
      case 'searchTerms':
        return <SearchTermsTab />;
      case 'otherProducts':
        return <OtherProductsTab storeId={storeId} />;
      case 'bidAdjustments':
        return <BidAdjustmentsTab storeId={storeId} />;
      case 'budgetCaps':
        return <BudgetCapsTab storeId={storeId} />;
      case 'operationLog':
        return <OperationLogTab storeId={storeId} />;
      default:
        return null;
    }
  };

  // Read-oriented alternative for viewports below 768px (Req 43.2): a banner
  // makes the constrained, view/pause/approve-only mode explicit, then the tab
  // content renders within the responsive context so its table degrades to a
  // stacked, read-first presentation rather than a broken wide table.
  const renderCompact = (key: CampaignsTabKey) => (
    <div className="space-y-3">
      <div
        className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2.5"
        role="note"
      >
        <Smartphone size={16} className="mt-0.5 shrink-0 text-amber-500" />
        <p className="text-xs text-amber-700">
          小屏只读模式：仅支持查看、暂停与审批操作。完整的批量编辑请在宽度 ≥ 768px 的桌面端进行。
        </p>
      </div>
      {renderTab(key)}
    </div>
  );

  const header = (
    <>
      {/* Page Header */}
      <div className="flex items-center gap-3">
        <div className="flex items-center justify-center w-10 h-10 rounded-lg bg-gradient-to-br from-blue-600 to-indigo-600 shadow-sm">
          <Megaphone size={20} className="text-white" />
        </div>
        <div>
          <h1 className="text-xl font-semibold text-slate-900">全部搜索广告</h1>
          <p className="text-sm text-slate-500">管理店铺的 SP / SB / SD 广告活动</p>
        </div>
      </div>

      {/* Store error */}
      {storeError && (
        <div className="flex items-center gap-3 px-4 py-3 bg-red-50 border border-red-200 rounded-lg">
          <AlertCircle size={16} className="text-red-500 shrink-0" />
          <p className="text-sm text-red-700">{storeError}</p>
        </div>
      )}
    </>
  );

  // Store loading / no-store states preserve the original page behavior and are
  // shown without the tab container.
  if (storeLoading) {
    return (
      <div className="space-y-5">
        {header}
        <div className="space-y-4">
          <Skeleton className="h-40 w-full rounded-xl" />
          <Skeleton className="h-64 w-full rounded-xl" />
        </div>
      </div>
    );
  }

  if (!storeId) {
    return (
      <div className="space-y-5">
        {header}
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <Megaphone size={48} className="text-slate-300 mb-4" />
          <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
          <p className="text-sm text-slate-500 mt-1 max-w-md">
            请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
          </p>
        </div>
      </div>
    );
  }

  if (normalizeStorePlatform(activeStore?.platform) !== 'amazon') {
    return (
      <div className="space-y-5">
        {header}
        <div className="rounded-xl border border-amber-200 bg-amber-50 p-6">
          <div className="flex items-start gap-3">
            <AlertCircle size={20} className="mt-0.5 shrink-0 text-amber-600" />
            <div>
              <h2 className="font-semibold text-amber-900">此工作台仅适用于 Amazon Ads</h2>
              <p className="mt-1 text-sm text-amber-800">
                当前店铺是 {activeStore?.name ?? '非 Amazon 店铺'}，不会调用 Amazon 广告接口。请切换 Amazon 店铺，或返回广告监控台进入对应渠道。
              </p>
              <Link to="/ad-monitor" className="mt-4 inline-flex items-center gap-2 rounded-lg bg-amber-700 px-3 py-2 text-sm font-medium text-white hover:bg-amber-800">
                <Megaphone size={14} /> 返回广告监控台
              </Link>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (connectionLoading) {
    return <div className="space-y-5">{header}<Skeleton className="h-40 w-full rounded-xl" /></div>;
  }

  if (connectionError || !amazonAdsReady) {
    return (
      <div className="space-y-5">
        {header}
        <div className="rounded-xl border border-slate-200 bg-white p-6">
          <div className="flex items-start gap-3">
            {connectionError ? <AlertCircle size={20} className="mt-0.5 shrink-0 text-red-500" /> : <CheckCircle2 size={20} className="mt-0.5 shrink-0 text-slate-400" />}
            <div className="flex-1">
              <h2 className="font-semibold text-slate-900">Amazon Ads 尚未连接</h2>
              <p className="mt-1 text-sm text-slate-600">
                {connectionError || `当前状态：${amazonAdsConnection?.status || '未配置'}。只有连接测试通过后才会加载广告活动和执行入口。`}
              </p>
              <div className="mt-4 flex flex-wrap gap-2">
                <Link to="/data-sync?platform=amazon_ads" className="inline-flex items-center gap-2 rounded-lg bg-orange-500 px-3 py-2 text-sm font-medium text-white hover:bg-orange-600">
                  <Megaphone size={14} /> 连接 Amazon Ads
                </Link>
                {connectionError && <button type="button" onClick={() => setConnectionReloadKey((value) => value + 1)} className="rounded-lg border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50">重试</button>}
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <ContextProvider>
      <AdvertisingWorkspace
        header={header}
        contextBar={
          <ContextBar
            store={activeStore?.name}
            site={activeStore?.marketplaceName ?? activeStore?.marketplaceCode}
            account={amazonAdsConnection?.connectionName}
            currency={activeStore?.marketplaceCurrency}
            dataDate={amazonAdsConnection?.lastSyncAt?.slice?.(0, 10) ?? amazonAdsConnection?.lastSyncTime?.slice?.(0, 10)}
            lastSync={amazonAdsConnection?.lastSyncAt ?? amazonAdsConnection?.lastSyncTime}
          />
        }
        kpiPanel={
          // KpiPanel resolves its default last-7 vs prior-7 comparison in the
          // Active_Store's Marketplace_Timezone (Req 30). The timezone is not yet
          // carried on the store option, so the panel honestly surfaces its
          // configuration-error state rather than defaulting to a server/browser
          // timezone (Req 30.4 / 51.14); the data layer supplies it and the
          // metrics when wired.
          <KpiPanel timeZone={activeStore?.marketplaceTimezone} />
        }
        filterChips={<FilterChips />}
        activeTab={activeTab}
        onTabChange={onTabChange}
        responsiveMode={responsiveMode}
        renderTab={renderTab}
        renderCompact={renderCompact}
      />
    </ContextProvider>
  );
}

export default CampaignsWorkspacePage;
