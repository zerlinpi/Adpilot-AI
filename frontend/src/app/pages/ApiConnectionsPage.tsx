import { useState, useEffect } from 'react';
import {
  RefreshCw, Loader2, CheckCircle2, XCircle, AlertTriangle,
  Plug, Unplug, Zap, Settings, Plus, Megaphone, Package, Search, ShoppingBag, Music2, ShoppingCart, Clock, type LucideIcon,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { cn } from '../lib/utils';
import {
  fetchPlatformConnections, connectPlatform, disconnectPlatform, testPlatformConnection,
  fetchPlatformFields, updatePlatformConnection, fetchStores,
  connectIndependentSiteStore, bindGoogleAdsConnection,
} from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { storePlatformGroup, storePlatformLabel } from '../lib/platformTaxonomy';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';
import { AmazonAdsConnectWizard } from './AmazonAdsConnectWizard';

// Display metadata for the platforms the backend supports
// (PlatformConnector.SUPPORTED). Used to render the platform selector and to
// label connection cards. The list of *connections* is always fetched from the
// backend; this map only drives presentation of the supported platform keys.
const platformMeta: Record<string, { name: string; icon: LucideIcon; color: string; description: string }> = {
  amazon_ads: {
    name: 'Amazon Ads',
    icon: Megaphone,
    color: 'from-orange-500 to-amber-500',
    description: 'Amazon 广告平台，管理广告活动、关键词和预算',
  },
  amazon_sp_api: {
    name: 'Amazon SP-API',
    icon: Package,
    color: 'from-yellow-500 to-orange-500',
    description: 'Amazon 卖家平台 API，同步订单、库存和商品数据',
  },
  google_ads: {
    name: 'Google Ads',
    icon: Search,
    color: 'from-blue-500 to-emerald-500',
    description: '独立站广告账号，绑定到 Shopify / WordPress 店铺',
  },
  shopify: {
    name: 'Shopify',
    icon: ShoppingBag,
    color: 'from-green-500 to-emerald-500',
    description: 'Shopify 电商平台，管理商品和订单',
  },
  tiktok_shop: {
    name: 'TikTok Shop',
    icon: Music2,
    color: 'from-pink-500 to-rose-500',
    description: 'TikTok 电商平台，管理直播和短视频带货',
  },
  woocommerce: {
    name: 'WooCommerce',
    icon: ShoppingCart,
    color: 'from-purple-500 to-violet-500',
    description: 'WordPress WooCommerce 商店，同步商品与订单',
  },
};

// All platforms we know how to *display*. The order here drives the platform
// selector dropdown.
const ALL_PLATFORMS = Object.keys(platformMeta);

/** The Platform_Access family a platform key belongs to. Amazon Ads / SP-API
 *  are the 亚马逊 family; everything else (Shopify / WooCommerce / TikTok Shop /
 *  Google Ads) is the 独立站 family. Drives platform-scoped connection entries
 *  so the 亚马逊店铺连接 and 独立站店铺连接 nav entries each show only their own
 *  platforms (platform-workspace-rbac Req 4.1, 5.1). */
export type PlatformFamily = 'amazon' | 'independent_site';

function familyOf(platform: string): PlatformFamily {
  return platform === 'amazon_ads' || platform === 'amazon_sp_api' ? 'amazon' : 'independent_site';
}

// Which platforms can actually be connected today. Every platform in
// platformMeta now has a real, wired backend connect path:
//   - Amazon Ads + Amazon SP-API : OAuth scan-code wizard + LWA credential test
//     (AmazonAdsConnector / AmazonSpApiConnector).
//   - Shopify / WooCommerce / TikTok Shop : the one-step independent-site
//     connect flow (IndependentSiteConnectionService validates each against the
//     platform before creating the store + connection).
//   - Google Ads : bound to an existing independent-site store via
//     bindGoogleAdsConnection (OAuth token refresh validates the credentials).
// None genuinely lack a working connector, so the "即将支持" (coming soon) list
// is empty. PlatformConnector.SUPPORTED confirms all six platforms are wired.
const COMING_SOON_PLATFORMS: string[] = [];

function isComingSoon(platform: string): boolean {
  return COMING_SOON_PLATFORMS.includes(platform);
}

function metaFor(platform: string) {
  return platformMeta[platform] ?? {
    name: platform,
    icon: Plug,
    color: 'from-slate-400 to-slate-500',
    description: '第三方平台连接',
  };
}

interface FieldSpec {
  key: string;
  label: string;
  secret: boolean;
  required: boolean;
  placeholder?: string;
}

interface Connection {
  id?: string;
  platform?: string;
  platformId?: string;
  connectionName?: string;
  storeId?: string;
  status?: string;
  hasConfig?: boolean;
  lastSyncTime?: string | null;
  lastSyncAt?: string | null;
  [k: string]: any;
}

function connPlatform(c: Connection): string {
  return c.platform || c.platformId || '';
}

export function ApiConnectionsPage({ platformFamily }: { platformFamily?: PlatformFamily } = {}) {
  const queryClient = useQueryClient();
  const connectionsKey = ['api-connections'] as const;
  const connectionsQuery = useApiQuery<{ connections: Connection[]; stores: any[] }>(
    connectionsKey,
    async () => {
      const [result, storesData] = await Promise.all([fetchPlatformConnections(), fetchStores()]);
      return {
        connections: Array.isArray(result) ? (result as Connection[]) : [],
        stores: Array.isArray(storesData) ? storesData : [],
      };
    },
  );
  const connections = connectionsQuery.data?.connections ?? [];
  const stores = connectionsQuery.data?.stores ?? [];
  const loading = connectionsQuery.isLoading;
  const [mutationError, setMutationError] = useState<string | null>(null);
  const error = mutationError ?? (connectionsQuery.isError ? connectionsQuery.error?.message ?? '加载连接信息失败' : null);
  const loadConnections = () => { setMutationError(null); return connectionsQuery.refetch(); };
  const [actingId, setActingId] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<{ id: string; success: boolean; message: string } | null>(null);

  // The platform keys this entry is scoped to. When a family is given, only
  // that family's platforms appear in the selector and only its connection
  // cards are listed (Req 4.1, 5.1). With no family, every platform shows.
  const visiblePlatforms = platformFamily
    ? ALL_PLATFORMS.filter((p) => familyOf(p) === platformFamily)
    : ALL_PLATFORMS;
  // The 亚马逊 scan-code wizard is only meaningful for the Amazon family; the
  // generic credential / store-connect flow is hidden for Amazon. The 独立站
  // family hides the wizard and leads with the credential / store-connect flow.
  const showAmazonWizard = platformFamily !== 'independent_site';
  const showCredentialFlow = platformFamily !== 'amazon';

  // Add / edit connection modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [formPlatform, setFormPlatform] = useState<string>('');
  const [connectionName, setConnectionName] = useState<string>('');
  const [formStoreId, setFormStoreId] = useState<string>('');
  const [newStoreName, setNewStoreName] = useState<string>('');
  const [fields, setFields] = useState<FieldSpec[]>([]);
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [fieldsLoading, setFieldsLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const independentStores = stores.filter((s) => storePlatformGroup(s.platform) === 'independent_site');
  const isAdOnlyConnection = formPlatform === 'google_ads';

  // ── Amazon Ads OAuth wizard ──────────────────────────────────────────────
  const [wizardOpen, setWizardOpen] = useState(false);
  const [resumeCode, setResumeCode] = useState<string | null>(null);
  const [resumeState, setResumeState] = useState<string | null>(null);

  // Auto-resume when Amazon redirects back to this app with ?code&state. The
  // registered redirect URI should land on the connections page so the wizard
  // can pick up the authorization and list profiles.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get('code');
    const state = params.get('state');
    if (code && state) {
      setResumeCode(code);
      setResumeState(state);
      setWizardOpen(true);
      // Strip the OAuth params from the URL so a refresh doesn't re-trigger.
      const url = new URL(window.location.href);
      url.searchParams.delete('code');
      url.searchParams.delete('state');
      window.history.replaceState({}, '', url.toString());
    }
  }, []);

  function openWizard() {
    setResumeCode(null);
    setResumeState(null);
    setWizardOpen(true);
  }

  // ── Per-connection actions ──────────────────────────────────────────────
  // The backend connect/disconnect/test endpoints are keyed by platform; pass
  // the connection's platform key.
  const rowKey = (c: Connection) => c.id || connPlatform(c);

  const handleConnect = async (conn: Connection) => {
    const platform = connPlatform(conn);
    const key = rowKey(conn);
    const target = conn.id || platform;
    setActingId(key);
    setTestResult(null);
    try {
      const res = await connectPlatform(target);
      const status = res?.status || 'connected';
      setTestResult({ id: key, success: status === 'connected', message: res?.message || (status === 'connected' ? '连接成功' : '连接未通过，请检查凭证') });
      await loadConnections();
    } catch (err: any) {
      setTestResult({ id: key, success: false, message: err.message || '连接失败' });
    } finally {
      setActingId(null);
    }
  };

  const handleDisconnect = async (conn: Connection) => {
    const platform = connPlatform(conn);
    const key = rowKey(conn);
    const target = conn.id || platform;
    setActingId(key);
    setTestResult(null);
    try {
      await disconnectPlatform(target);
      queryClient.setQueryData<{ connections: Connection[]; stores: any[] }>(connectionsKey, (prev) =>
        prev ? { ...prev, connections: prev.connections.map((c) => (rowKey(c) === key ? { ...c, status: 'disconnected' } : c)) } : prev);
    } catch (err: any) {
      setMutationError(err.message || '断开连接失败');
    } finally {
      setActingId(null);
    }
  };

  const handleTest = async (conn: Connection) => {
    const platform = connPlatform(conn);
    const key = rowKey(conn);
    const target = conn.id || platform;
    setActingId(key);
    setTestResult(null);
    try {
      const result = await testPlatformConnection(target);
      const msg = result?.message || '连接测试完成';
      const ok = !/失败|不完整|未连接|错误|无效/.test(msg);
      setTestResult({ id: key, success: ok, message: msg });
    } catch (err: any) {
      setTestResult({ id: key, success: false, message: err.message || '连接测试失败' });
    } finally {
      setActingId(null);
    }
  };

  // ── Add / edit modal ──────────────────────────────────────────────────────
  function resetForm() {
    setFormPlatform('');
    setConnectionName('');
    setFormStoreId('');
    setNewStoreName('');
    setFields([]);
    setFormValues({});
    setFormError(null);
  }

  function openAdd() {
    resetForm();
    setEditingId(null);
    // When scoped to a single family, preselect a sensible default platform so
    // the form is immediately usable. Prefer a store-creating commerce platform
    // over the ad-only Google Ads bind (which needs an existing store).
    if (platformFamily && visiblePlatforms.length > 0) {
      const preferred = visiblePlatforms.find((p) => p !== 'google_ads') ?? visiblePlatforms[0];
      void selectPlatform(preferred);
    }
    setModalOpen(true);
  }

  function openEdit(conn: Connection) {
    resetForm();
    setEditingId(conn.id ?? null);
    const platform = connPlatform(conn);
    setConnectionName(conn.connectionName || '');
    setFormStoreId(conn.storeId || '');
    setModalOpen(true);
    void selectPlatform(platform);
  }

  async function selectPlatform(platform: string) {
    setFormPlatform(platform);
    setFormValues({});
    setFormError(null);
    if (platform === 'google_ads' && !formStoreId) {
      const firstIndependent = stores.find((s) => storePlatformGroup(s.platform) === 'independent_site');
      if (firstIndependent) setFormStoreId(firstIndependent.id);
    }
    if (!platform) { setFields([]); return; }
    setFieldsLoading(true);
    try {
      const fieldList = await fetchPlatformFields(platform);
      setFields((Array.isArray(fieldList) ? fieldList : []) as FieldSpec[]);
    } catch (err: any) {
      setFormError(err.message || '加载平台字段失败');
      setFields([]);
    } finally {
      setFieldsLoading(false);
    }
  }

  async function handleSave() {
    if (!formPlatform) { setFormError('请选择平台'); return; }

    // Build the config from entered values.
    const config: Record<string, string> = {};
    Object.entries(formValues).forEach(([k, v]) => {
      if (v != null && v.trim() !== '') config[k] = v.trim();
    });

    // For a new connection, all required credential fields must be supplied.
    // When editing, blank fields keep the stored value.
    if (isAdOnlyConnection && !formStoreId) {
      setFormError('Google Ads 必须绑定到已有 Shopify 或 WordPress / WooCommerce 店铺');
      return;
    }

    if (!editingId) {
      for (const f of fields) {
        if (f.required && !config[f.key]) {
          setFormError(`请填写必填项：${f.label}`);
          return;
        }
      }
    }

    setSaving(true);
    setFormError(null);
    try {
      if (editingId) {
        await updatePlatformConnection(editingId, {
          platform: formPlatform,
          connectionName: connectionName.trim() || undefined,
          storeId: formStoreId || undefined,
          config: Object.keys(config).length ? config : undefined,
        });
      } else if (isAdOnlyConnection) {
        // Google Ads is bound to an existing independent-site store rather than
        // creating a standalone ad-only connection (Req 5.5).
        await bindGoogleAdsConnection({
          storeId: formStoreId,
          connectionName: connectionName.trim() || `${storeName(formStoreId)} Google Ads`,
          config,
        });
      } else {
        // One-step independent-site connect: validates the credentials, creates
        // the store + its connection, and assigns the store to the
        // independent-site Store_Group system (Req 5.2, 5.4).
        await connectIndependentSiteStore({
          platform: formPlatform,
          storeName: newStoreName.trim() || undefined,
          config,
        });
      }
      setModalOpen(false);
      await loadConnections();
    } catch (err: any) {
      setFormError(err.message || '保存连接失败');
    } finally {
      setSaving(false);
    }
  }

  const storeName = (id?: string) => stores.find((s) => s.id === id)?.name;

  // Connections scoped to this entry's family (Req 4.1, 5.1).
  const visibleConnections = connections.filter(
    (conn) => !platformFamily || familyOf(connPlatform(conn)) === platformFamily,
  );

  if (loading) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="平台连接" description="把外部平台账号接入系统，用于同步该店铺的订单 / 商品 / 广告数据" />
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="bg-white rounded-xl border border-slate-200 p-6 animate-pulse">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-12 h-12 rounded-xl bg-slate-100" />
                <div className="space-y-2">
                  <div className="h-4 bg-slate-100 rounded w-32" />
                  <div className="h-3 bg-slate-100 rounded w-48" />
                </div>
              </div>
              <div className="h-16 bg-slate-50 rounded-lg" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (error && connections.length === 0) return <ErpErrorState message={error} onRetry={loadConnections} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="平台连接"
        description="把外部平台账号接入系统，用于同步该店铺的订单 / 商品 / 广告数据"
        actions={
          <div className="flex items-center gap-2">
            <button onClick={loadConnections} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
              <RefreshCw size={14} /> 刷新
            </button>
            {showAmazonWizard && (
              <button onClick={openWizard} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-white bg-orange-500 rounded-lg hover:bg-orange-600 transition-colors shadow-sm">
                <Megaphone size={14} /> 连接亚马逊（扫码授权）
              </button>
            )}
            {showCredentialFlow && (
              <button onClick={openAdd} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-white bg-indigo-600 rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
                <Plus size={14} /> 连接新店铺（填密钥）
              </button>
            )}
          </div>
        }
      />

      {/* Plain-language explainer so the store↔connection relationship is clear */}
      <div className="flex items-start gap-2 px-4 py-3 rounded-lg border border-blue-100 bg-blue-50 text-sm text-blue-800">
        <AlertTriangle size={16} className="mt-0.5 flex-shrink-0 text-blue-500" />
        <div className="space-y-1">
          <p><strong>一个"连接" = 一个店铺接入了一个平台</strong>。连接成功后，就能在「同步任务」里拉取该店铺的订单/商品/广告数据。</p>
          <div className="text-blue-700/90 space-y-0.5">
            {showAmazonWizard && (
              <p>· <strong>亚马逊</strong>：点「连接亚马逊（扫码授权）」走授权登录，<strong>不用手填密钥</strong>。</p>
            )}
            {showCredentialFlow && (
              <>
                <p>· <strong>Shopify / WooCommerce / TikTok</strong>：点「连接新店铺（填密钥）」自动建店。</p>
                <p>· <strong>Google Ads</strong>：绑定到已有 Shopify / WooCommerce 店铺，作为独立站广告账号，不单独创建店铺。</p>
              </>
            )}
          </div>
        </div>
      </div>

      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3 flex items-center gap-2">
          <AlertTriangle size={14} className="text-red-500 flex-shrink-0" />
          <p className="text-sm text-red-700">{error}</p>
        </div>
      )}

      {visibleConnections.length === 0 ? (
        <div className="bg-white rounded-xl border border-dashed border-slate-300 p-12 flex flex-col items-center justify-center text-center">
          <div className="w-14 h-14 rounded-2xl bg-slate-50 flex items-center justify-center text-slate-300 mb-4">
            <Plug size={28} />
          </div>
          <h3 className="text-base font-semibold text-slate-900 mb-1">暂无平台连接</h3>
          <p className="text-sm text-slate-500 mb-5">连接一个平台店铺即可开始同步广告与店铺数据。亚马逊用上方「扫码授权」,其他平台点下方「连接新店铺」。</p>
          {showAmazonWizard && !showCredentialFlow ? (
            <button onClick={openWizard} className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-orange-500 rounded-lg hover:bg-orange-600 transition-colors shadow-sm">
              <Megaphone size={15} /> 连接亚马逊（扫码授权）
            </button>
          ) : (
            <button onClick={openAdd} className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-indigo-600 rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
              <Plus size={15} /> 连接新店铺
            </button>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {visibleConnections.map((conn) => {
            const platform = connPlatform(conn);
            const meta = metaFor(platform);
            const PlatformIcon = meta.icon;
            const key = rowKey(conn);
            const isConnected = conn.status === 'connected';
            const comingSoon = isComingSoon(platform);
            const hasError = conn.status === 'config_error' || conn.status === 'token_expired';
            const isConfigured = conn.status === 'configured';
            const isActing = actingId === key;
            const showResult = testResult?.id === key;

            return (
              <div
                key={key}
                className={cn(
                  'bg-white rounded-xl border overflow-hidden transition-shadow',
                  isConnected ? 'border-emerald-200 shadow-sm' : hasError ? 'border-red-200' : 'border-slate-200',
                )}
              >
                <div className={cn('h-1.5 bg-gradient-to-r', meta.color)} />
                <div className="p-5">
                  <div className="flex items-start gap-3 mb-4">
                    <div className="w-12 h-12 rounded-xl bg-slate-50 flex items-center justify-center flex-shrink-0">
                      <PlatformIcon size={24} className="text-slate-600" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <h3 className="text-base font-semibold text-slate-900 truncate">{conn.connectionName || meta.name}</h3>
                        <ErpStatusBadge status={conn.status} />
                      </div>
                      <p className="text-xs text-slate-500 mt-0.5">{meta.name} · {meta.description}</p>
                    </div>
                  </div>

                  <div className="bg-slate-50 rounded-lg p-3 mb-4 space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-slate-500">连接状态</span>
                      <span className={cn(
                        'text-xs font-medium',
                        isConnected ? 'text-emerald-600' : hasError ? 'text-red-600' : isConfigured ? 'text-blue-600' : 'text-slate-500',
                      )}>
                        {isConnected ? '已连接' : hasError ? (conn.status === 'config_error' ? '配置错误' : '令牌过期') : isConfigured ? '已配置待连接' : '未连接'}
                      </span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-slate-500">绑定店铺</span>
                      <span className="text-xs text-slate-600">{storeName(conn.storeId) || '默认店铺'}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-slate-500">凭证</span>
                      <span className="text-xs text-slate-600">{conn.hasConfig ? '已填写' : '未填写'}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-slate-500">最后同步时间</span>
                      <span className="text-xs text-slate-600">
                        {conn.lastSyncTime ? new Date(conn.lastSyncTime).toLocaleString('zh-CN') : '-'}
                      </span>
                    </div>
                  </div>

                  {showResult && testResult && (
                    <div className={cn(
                      'mb-3 p-2.5 rounded-lg flex items-center gap-2 text-xs',
                      testResult.success ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700',
                    )}>
                      {testResult.success ? <CheckCircle2 size={14} /> : <XCircle size={14} />}
                      {testResult.message}
                    </div>
                  )}

                  <div className="flex flex-wrap items-center gap-2">
                    <button
                      onClick={() => openEdit(conn)}
                      disabled={isActing || comingSoon}
                      className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-slate-700 bg-slate-100 rounded-lg hover:bg-slate-200 transition-colors disabled:opacity-50"
                    >
                      <Settings size={12} /> 配置凭证
                    </button>
                    <button
                      onClick={() => handleTest(conn)}
                      disabled={isActing || comingSoon}
                      className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors disabled:opacity-50"
                    >
                      {isActing ? <Loader2 size={12} className="animate-spin" /> : <Zap size={12} />}
                      测试连接
                    </button>
                    {comingSoon ? (
                      <span
                        title="该平台连接即将支持，敬请期待"
                        className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-slate-500 bg-slate-100 rounded-lg cursor-not-allowed"
                      >
                        <Clock size={12} /> 即将支持
                      </span>
                    ) : isConnected ? (
                      <button
                        onClick={() => handleDisconnect(conn)}
                        disabled={isActing}
                        className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-red-700 bg-red-50 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50"
                      >
                        {isActing ? <Loader2 size={12} className="animate-spin" /> : <Unplug size={12} />}
                        断开连接
                      </button>
                    ) : (
                      <button
                        onClick={() => handleConnect(conn)}
                        disabled={isActing}
                        className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-white bg-slate-900 rounded-lg hover:bg-slate-800 transition-colors disabled:opacity-50"
                      >
                        {isActing ? <Loader2 size={12} className="animate-spin" /> : <Plug size={12} />}
                        连接
                      </button>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Add / edit connection modal */}
      {modalOpen && (
        <Dialog open onOpenChange={(o) => { if (!o && !saving) setModalOpen(false); }}>
          <DialogContent className="block gap-0 p-6 max-h-[90vh] overflow-y-auto w-full sm:max-w-md rounded-xl border-0 bg-white shadow-xl">
            <div className="flex items-center justify-between mb-4">
              <DialogTitle className="text-base font-semibold text-slate-900">
                {editingId ? '编辑连接' : isAdOnlyConnection ? '绑定广告账号' : '连接新店铺'}
              </DialogTitle>
            </div>

            {formError && (
              <div className="bg-red-50 border border-red-200 rounded-lg p-2.5 mb-4 text-sm text-red-700">{formError}</div>
            )}

            <div className="space-y-4">
              {/* Platform selector */}
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  平台 <span className="text-red-500">*</span>
                </label>
                <select
                  value={formPlatform}
                  onChange={(e) => selectPlatform(e.target.value)}
                  disabled={!!editingId}
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 disabled:bg-slate-50 disabled:text-slate-500"
                >
                  <option value="">请选择平台</option>
                  {visiblePlatforms.map((p) => (
                    <option key={p} value={p} disabled={isComingSoon(p)}>
                      {platformMeta[p].name}{isComingSoon(p) ? '（即将支持）' : ''}
                    </option>
                  ))}
                </select>
              </div>

              {/* Connection name (only meaningful when editing an existing connection) */}
              {editingId && (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">连接名称</label>
                  <input
                    type="text"
                    value={connectionName}
                    onChange={(e) => setConnectionName(e.target.value)}
                    placeholder="便于区分同一平台的多个连接"
                    className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  />
                </div>
              )}

              {/* Store: commerce platforms create a store; ad-only platforms bind to an existing store. */}
              {editingId || isAdOnlyConnection ? (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    归属店铺 {isAdOnlyConnection && <span className="text-red-500">*</span>}
                  </label>
                  <select
                    value={formStoreId}
                    onChange={(e) => setFormStoreId(e.target.value)}
                    className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  >
                    <option value="">{isAdOnlyConnection ? '请选择独立站店铺' : '默认店铺'}</option>
                    {(isAdOnlyConnection ? independentStores : stores).map((s) => (
                      <option key={s.id} value={s.id}>{s.name} · {storePlatformLabel(s.platform)}</option>
                    ))}
                  </select>
                  {isAdOnlyConnection && independentStores.length === 0 && (
                    <p className="mt-1 text-xs text-amber-600">请先连接 Shopify 或 WordPress / WooCommerce 店铺，再绑定 Google Ads。</p>
                  )}
                </div>
              ) : (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">店铺名称</label>
                  <input
                    type="text"
                    value={newStoreName}
                    onChange={(e) => setNewStoreName(e.target.value)}
                    placeholder={formPlatform ? `${metaFor(formPlatform).name} 店铺` : '给这个店铺起个名字'}
                    className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  />
                  <p className="mt-1 text-xs text-slate-400">连接成功后会自动创建这个店铺,数据都归到它名下。</p>
                </div>
              )}

              {/* Dynamic credential fields */}
              {fieldsLoading ? (
                <div className="flex items-center justify-center py-6">
                  <Loader2 size={22} className="animate-spin text-slate-400" />
                </div>
              ) : (
                <>
                  {fields.map((f) => (
                    <div key={f.key}>
                      <label className="block text-sm font-medium text-slate-700 mb-1.5">
                        {f.label} {f.required && <span className="text-red-500">*</span>}
                      </label>
                      <input
                        type={f.secret ? 'password' : 'text'}
                        value={formValues[f.key] ?? ''}
                        onChange={(e) => setFormValues((prev) => ({ ...prev, [f.key]: e.target.value }))}
                        placeholder={editingId && f.secret ? '留空则不修改' : (f.placeholder || '')}
                        autoComplete="off"
                        className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                      />
                    </div>
                  ))}
                  {formPlatform && fields.length === 0 && (
                    <p className="text-sm text-slate-500">该平台暂无可配置字段。</p>
                  )}
                </>
              )}
            </div>

            <div className="flex items-center justify-end gap-3 mt-6">
              <button onClick={() => setModalOpen(false)} disabled={saving} className="px-4 py-2 text-sm font-medium text-slate-600 hover:text-slate-800 disabled:opacity-50">
                取消
              </button>
              <button
                onClick={handleSave}
                disabled={saving || fieldsLoading || !formPlatform}
                className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm disabled:opacity-60"
              >
                {saving ? <Loader2 size={15} className="animate-spin" /> : <CheckCircle2 size={15} />}
                {editingId ? '保存修改' : isAdOnlyConnection ? '绑定账号' : '连接店铺'}
              </button>
            </div>
          </DialogContent>
        </Dialog>
      )}

      <AmazonAdsConnectWizard
        open={wizardOpen}
        stores={stores}
        resumeCode={resumeCode}
        resumeState={resumeState}
        onClose={() => setWizardOpen(false)}
        onConnected={loadConnections}
      />
    </div>
  );
}
