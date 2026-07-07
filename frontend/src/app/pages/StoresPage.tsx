import { useState } from 'react';
import { Link2, Unplug, RefreshCw, Plus, Globe, CheckCircle2, XCircle, AlertCircle, Loader2, Trash2, X, Plug, Cable } from 'lucide-react';
import { fetchStores, fetchMarketplaces, createStore, updateStore, deleteStore, fetchConfiguredPlatforms, bindStorePlatform, fetchPlatformConnections } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { cn } from '../lib/utils';
import { adPlatformLabel, storeAdPlatform, storePlatformFamily, storePlatformGroupLabel, storePlatformLabel } from '../lib/platformTaxonomy';
import type { Store, Marketplace } from '../types';
import { usePermissions } from '../lib/PermissionContext';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

const PLATFORM_NAMES: Record<string, string> = {
  amazon_ads: 'Amazon Ads',
  amazon_sp_api: 'Amazon SP-API',
  google_ads: 'Google Ads',
  shopify: 'Shopify',
  woocommerce: 'WooCommerce',
  tiktok_shop: 'TikTok Shop',
};

/** A credential channel that authenticates the system to one external platform on
 *  behalf of a store. One store may have many platform connections. */
interface PlatformConnection {
  id?: string;
  storeId?: string | null;
  platform?: string;
  platformId?: string;
  connectionName?: string;
  status?: string;
  lastSyncTime?: string | null;
  lastSyncAt?: string | null;
}

function platformLabel(conn: PlatformConnection): string {
  const key = conn.platform || conn.platformId || '';
  return PLATFORM_NAMES[key] ?? (conn.connectionName || key || '未知平台');
}

function getMarketplace(marketplaces: Marketplace[], mpId: string): Marketplace | undefined {
  return marketplaces.find((m) => m.id === mpId);
}

const statusConfig: Record<string, { label: string; icon: typeof CheckCircle2; bg: string; text: string; border: string }> = {
  connected: { label: '已连接', icon: CheckCircle2, bg: 'bg-emerald-50', text: 'text-emerald-700', border: 'border-emerald-200' },
  disconnected: { label: '未连接', icon: XCircle, bg: 'bg-slate-50', text: 'text-slate-500', border: 'border-slate-200' },
  error: { label: '异常', icon: AlertCircle, bg: 'bg-red-50', text: 'text-red-700', border: 'border-red-200' },
};

// Platform-connection status values surfaced by the backend (ApiSyncService).
const connectionStatusConfig: Record<string, { label: string; icon: typeof CheckCircle2; bg: string; text: string; border: string }> = {
  connected: { label: '已连接', icon: CheckCircle2, bg: 'bg-emerald-50', text: 'text-emerald-700', border: 'border-emerald-200' },
  configured: { label: '已配置待连接', icon: AlertCircle, bg: 'bg-blue-50', text: 'text-blue-700', border: 'border-blue-200' },
  config_error: { label: '配置错误', icon: AlertCircle, bg: 'bg-red-50', text: 'text-red-700', border: 'border-red-200' },
  token_expired: { label: '令牌过期', icon: AlertCircle, bg: 'bg-red-50', text: 'text-red-700', border: 'border-red-200' },
  disconnected: { label: '未连接', icon: XCircle, bg: 'bg-slate-50', text: 'text-slate-500', border: 'border-slate-200' },
};

function statusOf(status: string) {
  return statusConfig[status] ?? statusConfig.disconnected;
}

function connectionStatusOf(status?: string) {
  return connectionStatusConfig[status ?? ''] ?? connectionStatusConfig.disconnected;
}

/** A compact 就绪/未就绪 badge for a store's key connection (e.g. Amazon SP-API
 *  or Amazon Ads), so readiness is visible on the store card without opening
 *  店铺设置 / 连接详情. */
function ReadinessBadge({ label, ok }: { label: string; ok: boolean }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium',
        ok ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-amber-200 bg-amber-50 text-amber-700',
      )}
    >
      {ok ? <CheckCircle2 size={11} /> : <AlertCircle size={11} />}
      {label}：{ok ? '就绪' : '未就绪'}
    </span>
  );
}

export function StoresPage() {
  const { can } = usePermissions();
  const canManageStores = can('store:manage');
  const canManageConnections = can('import:manage');
  const storesKey = ['stores-page'] as const;
  const storesQuery = useApiQuery<{ stores: Store[]; marketplaces: Marketplace[]; configuredPlatforms: string[]; connections: PlatformConnection[] }>(
    storesKey,
    async () => {
      const [storesData, marketplacesData, configured, connectionsData] = await Promise.all([
        fetchStores(),
        fetchMarketplaces(),
        fetchConfiguredPlatforms().catch(() => []),
        fetchPlatformConnections().catch(() => []),
      ]);
      return {
        stores: Array.isArray(storesData) ? storesData : [],
        marketplaces: Array.isArray(marketplacesData) ? marketplacesData : [],
        configuredPlatforms: Array.isArray(configured) ? configured : [],
        connections: Array.isArray(connectionsData) ? (connectionsData as PlatformConnection[]) : [],
      };
    },
  );
  const stores = storesQuery.data?.stores ?? [];
  const marketplaces = storesQuery.data?.marketplaces ?? [];
  const connections = storesQuery.data?.connections ?? [];
  const configuredPlatforms = storesQuery.data?.configuredPlatforms ?? [];
  const [mutationError, setMutationError] = useState<string | null>(null);
  const loading = storesQuery.isLoading;
  const error = mutationError ?? (storesQuery.isError ? storesQuery.error?.message ?? '加载店铺数据失败，请重试。' : null);
  const loadData = () => { setMutationError(null); return storesQuery.refetch(); };

  // Create-store form state
  const [showForm, setShowForm] = useState(false);
  const [formName, setFormName] = useState('');
  const [formSellerId, setFormSellerId] = useState('');
  const [formMarketplace, setFormMarketplace] = useState<string>('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [actingId, setActingId] = useState<string | null>(null);

  // One-click bind state
  const [bindTarget, setBindTarget] = useState<Store | null>(null);
  const [binding, setBinding] = useState<string | null>(null);
  const [bindMsg, setBindMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const storeIdSet = new Set(stores.map((s) => s.id));
  const connectionsForStore = (storeId: string) => connections.filter((c) => c.storeId === storeId);
  const unassignedConnections = connections.filter((c) => !c.storeId || !storeIdSet.has(c.storeId));

  function openForm() {
    setFormName('');
    setFormSellerId('');
    setFormMarketplace(marketplaces[0]?.id ?? '');
    setFormError(null);
    setShowForm(true);
  }

  async function handleCreate() {
    if (!formName.trim()) { setFormError('请输入店铺名称'); return; }
    if (!formMarketplace) { setFormError('请选择站点'); return; }
    setSaving(true);
    setFormError(null);
    try {
      await createStore({
        name: formName.trim(),
        marketplaceId: formMarketplace,
        sellerId: formSellerId.trim() || undefined,
      });
      setShowForm(false);
      await loadData();
    } catch (err: any) {
      setFormError(err.message || '创建店铺失败');
    } finally {
      setSaving(false);
    }
  }

  async function handleToggleStatus(store: Store) {
    setActingId(store.id);
    try {
      const nextStatus = store.status === 'connected' ? 'disconnected' : 'connected';
      await updateStore(store.id, {
        name: store.name,
        marketplaceId: store.marketplaceId,
        sellerId: store.sellerId,
        status: nextStatus,
      });
      await loadData();
    } catch (err: any) {
      setMutationError(err.message || '更新店铺状态失败');
    } finally {
      setActingId(null);
    }
  }

  async function handleDelete(store: Store) {
    if (!confirm(`确定删除店铺「${store.name}」吗？`)) return;
    setActingId(store.id);
    try {
      await deleteStore(store.id);
      await loadData();
    } catch (err: any) {
      setMutationError(err.message || '删除店铺失败');
    } finally {
      setActingId(null);
    }
  }

  async function handleBind(platform: string) {
    if (!bindTarget) return;
    setBinding(platform);
    setBindMsg(null);
    try {
      const res = await bindStorePlatform(bindTarget.id, platform);
      const ok = res?.status === 'connected';
      setBindMsg({ ok, text: res?.message || (ok ? '绑定成功' : '已绑定，但连通性校验未通过') });
      await loadData();
    } catch (err: any) {
      setBindMsg({ ok: false, text: err.message || '绑定失败' });
    } finally {
      setBinding(null);
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[300px]">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="w-8 h-8 text-indigo-500 animate-spin" />
          <p className="text-sm text-slate-500">加载店铺中...</p>
        </div>
      </div>
    );
  }

  if (error && stores.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[300px] gap-4">
        <div className="w-12 h-12 rounded-full bg-red-100 flex items-center justify-center">
          <AlertCircle size={24} className="text-red-600" />
        </div>
        <div className="text-center">
          <p className="text-sm font-medium text-slate-900">加载店铺失败</p>
          <p className="text-sm text-slate-500 mt-1">{error}</p>
        </div>
        <button
          onClick={loadData}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm"
        >
          <RefreshCw size={16} />
          重试
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">店铺管理</h1>
          <p className="text-sm text-slate-500 mt-1">创建并管理您的销售店铺，共 {stores.length} 个</p>
        </div>
        {canManageStores && (
          <button
            onClick={openForm}
            className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm"
          >
            <Plus size={16} />
            新增店铺
          </button>
        )}
      </div>

      {/* Concept distinction — store vs. connection (Req 14.3) */}
      <div className="bg-slate-50 border border-slate-200 rounded-lg p-3 flex items-start gap-2">
        <Globe size={14} className="text-slate-400 flex-shrink-0 mt-0.5" />
        <p className="text-xs text-slate-500 leading-relaxed">
          <span className="font-medium text-slate-600">店铺</span> 是您的销售实体（某站点上的一家店）；
          <span className="font-medium text-slate-600">平台连接</span> 是为该店铺鉴权到外部平台（如 Amazon Ads、Shopify）的凭证渠道。
          一个店铺可以拥有多个平台连接。
        </p>
      </div>

      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3 flex items-center gap-2">
          <AlertCircle size={14} className="text-red-500 flex-shrink-0" />
          <p className="text-sm text-red-700">{error}</p>
        </div>
      )}

      {/* Store cards */}
      {stores.length === 0 ? (
        <div className="flex flex-col items-center justify-center min-h-[200px] gap-3">
          <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center">
            <Globe size={24} className="text-slate-400" />
          </div>
          <div className="text-center">
            <p className="text-sm font-medium text-slate-900">暂无店铺</p>
            <p className="text-sm text-slate-500 mt-1">{canManageStores ? '点击「新增店铺」创建您的第一个店铺。' : '当前账号尚未分配可查看的店铺。'}</p>
          </div>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {stores.map((store) => {
            const mp = getMarketplace(marketplaces, store.marketplaceId);
            const sc = statusOf(store.status);
            const StatusIcon = sc.icon;
            const busy = actingId === store.id;
            // Amazon 连接就绪概览: surface whether the two Amazon channels — SP-API
            // (orders/products data) and Amazon Ads (ad monitoring) — are connected,
            // right on the card instead of buried in 店铺设置 / 连接详情.
            const storeConns = connectionsForStore(store.id);
            const isAmazon = storePlatformFamily(store.platform) === 'amazon';
            const spReady = storeConns.some((c) => c.platform === 'amazon_sp_api' && c.status === 'connected');
            const adsReady = storeConns.some((c) => c.platform === 'amazon_ads' && c.status === 'connected');

            return (
              <div key={store.id} className="bg-white rounded-xl border border-slate-200 p-5 hover:shadow-md transition-shadow">
                <div className="flex items-start justify-between mb-4">
                  <div className="flex items-center gap-3">
                    <div className="flex h-10 w-10 flex-col items-center justify-center rounded-lg bg-slate-100 text-slate-500">
                      <Globe size={16} />
                      <span className="mt-0.5 text-[9px] font-semibold">{mp?.code ?? '--'}</span>
                    </div>
                    <div>
                      <h3 className="text-sm font-semibold text-slate-900">{store.name}</h3>
                      <p className="text-xs text-slate-500">{mp?.name ?? store.marketplaceName ?? '未知站点'}</p>
                    </div>
                  </div>
                  <span className={cn('inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium rounded-full border', sc.bg, sc.text, sc.border)}>
                    <StatusIcon size={12} />
                    {sc.label}
                  </span>
                </div>

                <div className="text-xs text-slate-500 mb-4">
                  <span className="font-medium text-slate-600">Seller ID：</span>
                  <span className="font-mono">{store.sellerId || '-'}</span>
                </div>
                <div className="mb-4 grid grid-cols-2 gap-2 text-xs">
                  <div className="rounded-lg bg-slate-50 px-3 py-2">
                    <p className="text-slate-400">业务类别</p>
                    <p className="mt-0.5 font-medium text-slate-700">{storePlatformGroupLabel(store.platform)}</p>
                  </div>
                  <div className="rounded-lg bg-slate-50 px-3 py-2">
                    <p className="text-slate-400">默认广告</p>
                    <p className="mt-0.5 font-medium text-slate-700">{adPlatformLabel(store.adPlatform ?? storeAdPlatform(store.platform))}</p>
                  </div>
                  <div className="col-span-2 rounded-lg bg-slate-50 px-3 py-2">
                    <p className="text-slate-400">销售平台</p>
                    <p className="mt-0.5 font-medium text-slate-700">{storePlatformLabel(store.platform)}</p>
                  </div>
                </div>

                {/* Amazon 连接就绪 — SP-API (数据同步) + Amazon Ads (广告监控) at a glance */}
                {isAmazon && (
                  <div className="mb-4 rounded-lg border border-slate-200 p-3">
                    <p className="mb-2 text-xs font-medium text-slate-600">连接就绪</p>
                    <div className="flex flex-wrap gap-2">
                      <ReadinessBadge label="SP-API 数据" ok={spReady} />
                      <ReadinessBadge label="Amazon 广告" ok={adsReady} />
                    </div>
                    {(!spReady || !adsReady) && (
                      <p className="mt-2 text-[11px] text-amber-600">
                        {!spReady && !adsReady
                          ? '订单/商品同步与广告监控均未就绪'
                          : !spReady
                            ? '订单/商品数据同步未就绪'
                            : '广告监控未就绪'}
                        ，可用下方「一键绑定」补齐。
                      </p>
                    )}
                  </div>
                )}

                {/* Platform connections — the credential channels bound to this store (Req 14.1, 14.4) */}
                <div className="mb-4 border-t border-slate-100 pt-3">
                  <div className="flex items-center gap-1.5 mb-2">
                    <Cable size={13} className="text-slate-400" />
                    <span className="text-xs font-medium text-slate-600">平台连接（凭证渠道）</span>
                    <span className="text-xs text-slate-400">· {connectionsForStore(store.id).length}</span>
                  </div>
                  {connectionsForStore(store.id).length === 0 ? (
                    <p className="text-xs text-slate-400">尚未绑定任何平台连接，点击「一键绑定」添加。</p>
                  ) : (
                    <ul className="space-y-1.5">
                      {connectionsForStore(store.id).map((conn) => {
                        const cs = connectionStatusOf(conn.status);
                        const CIcon = cs.icon;
                        return (
                          <li key={conn.id ?? `${conn.platform}-${conn.storeId}`} className="flex items-center justify-between gap-2">
                            <span className="text-xs text-slate-700 truncate">{platformLabel(conn)}</span>
                            <span className={cn('inline-flex items-center gap-1 px-1.5 py-0.5 text-[11px] font-medium rounded-full border flex-shrink-0', cs.bg, cs.text, cs.border)}>
                              <CIcon size={11} />
                              {cs.label}
                            </span>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>

                {(canManageStores || canManageConnections) && <div className="flex items-center gap-2">
                  {canManageStores && (
                    <button
                      onClick={() => handleToggleStatus(store)}
                      disabled={busy}
                      className={cn(
                        'flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg transition-colors disabled:opacity-50',
                        store.status === 'connected'
                          ? 'text-slate-600 bg-white border border-slate-200 hover:bg-slate-50'
                          : 'text-white bg-indigo-600 hover:bg-indigo-700',
                      )}
                    >
                      {busy ? <Loader2 size={13} className="animate-spin" /> : store.status === 'connected' ? <Unplug size={13} /> : <RefreshCw size={13} />}
                      {store.status === 'connected' ? '停用' : '启用'}
                    </button>
                  )}
                  {canManageConnections && (
                    <button
                      onClick={() => { setBindTarget(store); setBindMsg(null); }}
                      disabled={busy}
                      className="inline-flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium text-indigo-700 bg-indigo-50 rounded-lg hover:bg-indigo-100 transition-colors disabled:opacity-50"
                      title="一键绑定平台（复用管理员已配置的凭证）"
                    >
                      <Plug size={13} /> 一键绑定
                    </button>
                  )}
                  {canManageStores && (
                    <button
                      onClick={() => handleDelete(store)}
                      disabled={busy}
                      className="inline-flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50"
                    >
                      <Trash2 size={13} />
                    </button>
                  )}
                </div>}
              </div>
            );
          })}
        </div>
      )}

      {/* Unassigned connections — credential channels not bound to any store (Req 14.5) */}
      {unassignedConnections.length > 0 && (
        <div className="bg-white rounded-xl border border-amber-200 p-5">
          <div className="flex items-center gap-2 mb-3">
            <AlertCircle size={16} className="text-amber-500" />
            <h2 className="text-sm font-semibold text-slate-900">未分配的平台连接</h2>
            <span className="text-xs text-slate-400">· {unassignedConnections.length}</span>
          </div>
          <p className="text-xs text-slate-500 mb-3">
            以下平台连接尚未关联到任何店铺。请在对应店铺中使用「一键绑定」将其分配给店铺。
          </p>
          <ul className="divide-y divide-slate-100">
            {unassignedConnections.map((conn) => {
              const cs = connectionStatusOf(conn.status);
              const CIcon = cs.icon;
              return (
                <li key={conn.id ?? `${conn.platform}-unassigned`} className="flex items-center justify-between gap-2 py-2">
                  <div className="flex items-center gap-2 min-w-0">
                    <Cable size={13} className="text-slate-400 flex-shrink-0" />
                    <span className="text-sm text-slate-700 truncate">{platformLabel(conn)}</span>
                    <span className="inline-flex items-center px-1.5 py-0.5 text-[11px] font-medium rounded-full bg-amber-50 text-amber-700 border border-amber-200 flex-shrink-0">
                      未分配
                    </span>
                  </div>
                  <span className={cn('inline-flex items-center gap-1 px-1.5 py-0.5 text-[11px] font-medium rounded-full border flex-shrink-0', cs.bg, cs.text, cs.border)}>
                    <CIcon size={11} />
                    {cs.label}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      {/* Create store modal */}
      {showForm && (
        <Dialog open onOpenChange={(o) => { if (!o && !saving) setShowForm(false); }}>
          <DialogContent className="block gap-0 w-full sm:max-w-md rounded-xl border-0 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between mb-4">
              <DialogTitle className="text-base font-semibold text-slate-900">新增店铺</DialogTitle>
            </div>

            {formError && (
              <div className="bg-red-50 border border-red-200 rounded-lg p-2.5 mb-4 text-sm text-red-700">{formError}</div>
            )}

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">店铺名称 *</label>
                <input
                  type="text"
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  placeholder="例如：AudioMax 美国旗舰店"
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">站点 *</label>
                <select
                  value={formMarketplace}
                  onChange={(e) => setFormMarketplace(e.target.value)}
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                >
                  <option value="">请选择站点</option>
                  {marketplaces.map((mp) => (
                    <option key={mp.id} value={mp.id}>{mp.flag} {mp.name} ({mp.code})</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Seller ID（选填）</label>
                <input
                  type="text"
                  value={formSellerId}
                  onChange={(e) => setFormSellerId(e.target.value)}
                  placeholder="例如：A1B2C3D4E5"
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                />
              </div>
            </div>

            <div className="flex items-center justify-end gap-3 mt-6">
              <button onClick={() => setShowForm(false)} disabled={saving} className="px-4 py-2 text-sm font-medium text-slate-600 hover:text-slate-800 disabled:opacity-50">
                取消
              </button>
              <button
                onClick={handleCreate}
                disabled={saving}
                className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm disabled:opacity-60"
              >
                {saving ? <Loader2 size={15} className="animate-spin" /> : <Link2 size={15} />}
                创建店铺
              </button>
            </div>
          </DialogContent>
        </Dialog>
      )}
      {/* One-click bind modal */}
      {bindTarget && (
        <Dialog open onOpenChange={(o) => { if (!o && !binding) setBindTarget(null); }}>
          <DialogContent className="block gap-0 w-full sm:max-w-md rounded-xl border-0 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between mb-4">
              <DialogTitle className="text-base font-semibold text-slate-900">一键绑定平台 — {bindTarget.name}</DialogTitle>
            </div>

            <p className="text-sm text-slate-500 mb-4">
              选择一个平台进行绑定，将复用管理员在「API 连接」中配置好的凭证，无需重复填写。
            </p>

            {bindMsg && (
              <div className={cn('rounded-lg p-2.5 mb-4 text-sm', bindMsg.ok ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700')}>
                {bindMsg.text}
              </div>
            )}

            {configuredPlatforms.length === 0 ? (
              <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 text-sm text-amber-700">
                还没有任何平台配置了凭证。请先让管理员前往「API 连接」配置平台凭证后再绑定。
              </div>
            ) : (
              <div className="space-y-2">
                {configuredPlatforms.map((p) => (
                  <button
                    key={p}
                    onClick={() => handleBind(p)}
                    disabled={!!binding}
                    className="w-full flex items-center justify-between px-4 py-3 rounded-lg border border-slate-200 hover:border-indigo-300 hover:bg-indigo-50/50 transition-colors disabled:opacity-50"
                  >
                    <span className="text-sm font-medium text-slate-800">{PLATFORM_NAMES[p] ?? p}</span>
                    {binding === p ? <Loader2 size={15} className="animate-spin text-indigo-600" /> : <Plug size={15} className="text-indigo-600" />}
                  </button>
                ))}
              </div>
            )}

            <div className="flex items-center justify-end mt-6">
              <button onClick={() => setBindTarget(null)} disabled={!!binding} className="px-4 py-2 text-sm font-medium text-slate-600 hover:text-slate-800 disabled:opacity-50">
                关闭
              </button>
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
