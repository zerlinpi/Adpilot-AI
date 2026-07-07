import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Store,
  RefreshCw,
  Boxes,
  Truck,
  CheckCircle2,
  AlertTriangle,
  Ban,
  Plug,
  Loader2,
} from 'lucide-react';
import {
  fetchIndependentSiteConnectionState,
  updateIndependentSiteInventory,
  markIndependentSiteFulfillment,
  fetchTikTokConnectionState,
  updateTikTokInventory,
  markTikTokFulfillment,
  type IndependentSiteConnectionState,
  type InventoryUpdateRequest,
  type FulfillmentRequest,
  type IndependentSiteWriteResult,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { useStoreContext } from '../lib/StoreContext';
import { formatDate, cn } from '../lib/utils';
import { FlowGuide } from '../components/onboarding/FlowGuide';

// ─── Connection-state vocabulary (Req 4.4) ───────────────────────────
// The backend maps a platform connection's status into one of these four
// Connection_State values. `not_authorized` means no valid connection /
// credentials — a write would be refused by the backend (Req 4.3).
const connectionStateLabel: Record<string, string> = {
  connected: '已连接',
  syncing: '同步中',
  failed: '连接失败',
  not_authorized: '未授权',
};

const platformLabel: Record<string, string> = {
  shopify: 'Shopify',
  woocommerce: 'WooCommerce',
  tiktok_shop: 'TikTok Shop',
};

// ─── Variant wiring ──────────────────────────────────────────────────
// This page powers both the 独立站 (Shopify/WooCommerce) and the TikTok Shop
// inventory/fulfillment write-back, which are identical UIs over two symmetric
// backend endpoints. The variant selects the API functions + a little copy; the
// whole UI (capability gating, async-outbox semantics, honest 暂不支持) is shared.
export type LogisticsVariant = 'independent_site' | 'tiktok';

interface LogisticsApi {
  fetchState: (storeId?: string) => Promise<IndependentSiteConnectionState[]>;
  submitInventory: (productId: string, req: InventoryUpdateRequest) => Promise<IndependentSiteWriteResult>;
  submitFulfillment: (orderId: string, req: FulfillmentRequest) => Promise<IndependentSiteWriteResult>;
}

const VARIANT_API: Record<LogisticsVariant, LogisticsApi> = {
  independent_site: {
    fetchState: fetchIndependentSiteConnectionState,
    submitInventory: updateIndependentSiteInventory,
    submitFulfillment: markIndependentSiteFulfillment,
  },
  tiktok: {
    fetchState: fetchTikTokConnectionState,
    submitInventory: updateTikTokInventory,
    submitFulfillment: markTikTokFulfillment,
  },
};

const VARIANT_COPY: Record<LogisticsVariant, { channelLabel: string; connectLabel: string; storageKey: string }> = {
  independent_site: {
    channelLabel: '独立站',
    connectLabel: '连接 Shopify / WooCommerce 店铺',
    storageKey: 'independent-site-writeback',
  },
  tiktok: {
    channelLabel: 'TikTok Shop',
    connectLabel: '连接 TikTok Shop 店铺',
    storageKey: 'tiktok-writeback',
  },
};

function isConnected(state?: string | null): boolean {
  return (state || '').toLowerCase() === 'connected';
}

function connectionStateBadgeClass(state?: string | null): string {
  switch ((state || '').toLowerCase()) {
    case 'connected':
      return 'bg-emerald-100 text-emerald-700 border-emerald-200';
    case 'syncing':
      return 'bg-blue-100 text-blue-700 border-blue-200';
    case 'failed':
      return 'bg-red-100 text-red-700 border-red-200';
    case 'not_authorized':
    default:
      return 'bg-slate-100 text-slate-500 border-slate-200';
  }
}

/** Small capability pill: shows 支持 / 暂不支持 (Req 4.7). */
function CapabilityPill({ supported, label }: { supported: boolean; label: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border',
        supported
          ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
          : 'bg-slate-50 text-slate-400 border-slate-200',
      )}
    >
      {supported ? <CheckCircle2 size={11} /> : <Ban size={11} />}
      {label}：{supported ? '支持' : '暂不支持'}
    </span>
  );
}

// ─── Connection-state card (Req 4.4, 4.7) ────────────────────────────
function ConnectionStateCard({ row }: { row: IndependentSiteConnectionState }) {
  const state = row.connection_state;
  const failed = (state || '').toLowerCase() === 'failed';
  return (
    <div
      className={cn(
        'rounded-xl border px-4 py-3',
        failed ? 'border-red-200 bg-red-50/60' : 'border-slate-200 bg-white',
      )}
    >
      <div className="flex items-center justify-between">
        <span className="inline-flex items-center gap-1.5 text-sm font-medium text-slate-700">
          <Plug size={14} className="text-slate-400" />
          {platformLabel[row.platform] || row.platform || '-'}
        </span>
        <span
          className={cn(
            'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
            connectionStateBadgeClass(state),
          )}
        >
          {connectionStateLabel[(state || '').toLowerCase()] || state || '未知'}
        </span>
      </div>
      <div className="mt-2 flex flex-wrap gap-1.5">
        <CapabilityPill supported={!!row.inventory_write_supported} label="库存更新" />
        <CapabilityPill supported={!!row.fulfillment_write_supported} label="发货标记" />
      </div>
      <div className="mt-2 text-xs text-slate-500">
        最近成功同步：
        <span className="text-slate-700 ml-1">
          {row.last_success_at ? formatDate(row.last_success_at) : '尚无成功记录'}
        </span>
      </div>
    </div>
  );
}

type Feedback = { kind: 'success' | 'error'; message: string } | null;

// ─── Inventory update entry (Req 4.1, 4.7) ───────────────────────────
function InventoryUpdateForm({
  storeId,
  supported,
  submit,
}: {
  storeId: string;
  supported: boolean;
  submit: LogisticsApi['submitInventory'];
}) {
  const [productId, setProductId] = useState('');
  const [sku, setSku] = useState('');
  const [quantity, setQuantity] = useState('');
  const [locationId, setLocationId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<Feedback>(null);

  // Req 4.7: when the capability is unsupported, the entry is rendered as
  // "暂不支持" (disabled) rather than offering an action that would silently fail.
  if (!supported) {
    return (
      <UnsupportedNotice
        icon={<Boxes size={18} className="text-slate-400 flex-shrink-0 mt-0.5" />}
        title="库存更新"
        detail="当前店铺的连接器尚未提供库存写入能力，暂不支持库存更新。请在连接器支持后再使用。"
      />
    );
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const qty = Number(quantity);
    if (!productId.trim()) {
      setFeedback({ kind: 'error', message: '请填写产品 ID' });
      return;
    }
    if (!Number.isInteger(qty) || qty < 0) {
      setFeedback({ kind: 'error', message: '库存数量必须为零或正整数' });
      return;
    }
    try {
      setSubmitting(true);
      setFeedback(null);
      const result = await submit(productId.trim(), {
        storeId,
        quantity: qty,
        sku: sku.trim() || undefined,
        locationId: locationId.trim() || undefined,
      });
      setFeedback({
        kind: 'success',
        message: `已提交库存更新，操作已进入异步回写队列（状态：${result.sync_state || '已排队'}）`,
      });
    } catch (err: any) {
      setFeedback({ kind: 'error', message: err?.message || '提交库存更新失败' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="产品 ID" required>
          <input
            type="text"
            value={productId}
            onChange={(e) => setProductId(e.target.value)}
            placeholder="独立站产品 ID"
            className={inputClass}
          />
        </Field>
        <Field label="数量（绝对可用库存）" required>
          <input
            type="number"
            min={0}
            step={1}
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            placeholder="例如：100"
            className={inputClass}
          />
        </Field>
        <Field label="SKU（可选）">
          <input
            type="text"
            value={sku}
            onChange={(e) => setSku(e.target.value)}
            placeholder="变体 / Listing SKU"
            className={inputClass}
          />
        </Field>
        <Field label="库存地点 ID（可选）">
          <input
            type="text"
            value={locationId}
            onChange={(e) => setLocationId(e.target.value)}
            placeholder="平台库存地点"
            className={inputClass}
          />
        </Field>
      </div>
      <FeedbackLine feedback={feedback} />
      <button type="submit" disabled={submitting} className={submitButtonClass}>
        {submitting ? <Loader2 size={15} className="animate-spin" /> : <Boxes size={15} />}
        提交库存更新
      </button>
    </form>
  );
}

// ─── Fulfillment mark entry (Req 4.1, 4.7) ───────────────────────────
function FulfillmentForm({
  storeId,
  supported,
  submit,
}: {
  storeId: string;
  supported: boolean;
  submit: LogisticsApi['submitFulfillment'];
}) {
  const [orderId, setOrderId] = useState('');
  const [trackingNumber, setTrackingNumber] = useState('');
  const [carrier, setCarrier] = useState('');
  const [trackingUrl, setTrackingUrl] = useState('');
  const [notifyCustomer, setNotifyCustomer] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<Feedback>(null);

  // Req 4.7: unsupported capability → "暂不支持" disabled entry, never a
  // silently-failing action.
  if (!supported) {
    return (
      <UnsupportedNotice
        icon={<Truck size={18} className="text-slate-400 flex-shrink-0 mt-0.5" />}
        title="发货标记"
        detail="当前店铺的连接器尚未提供发货/履约写入能力，暂不支持发货标记。请在连接器支持后再使用。"
      />
    );
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!orderId.trim()) {
      setFeedback({ kind: 'error', message: '请填写订单 ID' });
      return;
    }
    try {
      setSubmitting(true);
      setFeedback(null);
      const result = await submit(orderId.trim(), {
        storeId,
        trackingNumber: trackingNumber.trim() || undefined,
        carrier: carrier.trim() || undefined,
        trackingUrl: trackingUrl.trim() || undefined,
        notifyCustomer,
      });
      setFeedback({
        kind: 'success',
        message: `已提交发货标记，操作已进入异步回写队列（状态：${result.sync_state || '已排队'}）`,
      });
    } catch (err: any) {
      setFeedback({ kind: 'error', message: err?.message || '提交发货标记失败' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="订单 ID" required>
          <input
            type="text"
            value={orderId}
            onChange={(e) => setOrderId(e.target.value)}
            placeholder="独立站订单 ID"
            className={inputClass}
          />
        </Field>
        <Field label="物流单号（可选）">
          <input
            type="text"
            value={trackingNumber}
            onChange={(e) => setTrackingNumber(e.target.value)}
            placeholder="Tracking number"
            className={inputClass}
          />
        </Field>
        <Field label="承运商（可选）">
          <input
            type="text"
            value={carrier}
            onChange={(e) => setCarrier(e.target.value)}
            placeholder="例如：USPS / DHL"
            className={inputClass}
          />
        </Field>
        <Field label="物流追踪链接（可选）">
          <input
            type="text"
            value={trackingUrl}
            onChange={(e) => setTrackingUrl(e.target.value)}
            placeholder="https://..."
            className={inputClass}
          />
        </Field>
      </div>
      <label className="inline-flex items-center gap-2 text-sm text-slate-600">
        <input
          type="checkbox"
          checked={notifyCustomer}
          onChange={(e) => setNotifyCustomer(e.target.checked)}
          className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
        />
        通知买家发货
      </label>
      <FeedbackLine feedback={feedback} />
      <button type="submit" disabled={submitting} className={submitButtonClass}>
        {submitting ? <Loader2 size={15} className="animate-spin" /> : <Truck size={15} />}
        提交发货标记
      </button>
    </form>
  );
}

// ─── Shared presentational helpers ───────────────────────────────────
const inputClass =
  'w-full h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm text-slate-700 placeholder-slate-400 outline-none focus:border-blue-400';

const submitButtonClass =
  'inline-flex items-center justify-center gap-2 h-10 px-4 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-60';

function Field({
  label,
  required,
  children,
}: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="block text-xs font-medium text-slate-500 mb-1">
        {label}
        {required && <span className="text-red-400 ml-0.5">*</span>}
      </label>
      {children}
    </div>
  );
}

function FeedbackLine({ feedback }: { feedback: Feedback }) {
  if (!feedback) return null;
  return (
    <p
      className={cn(
        'text-sm flex items-center gap-1.5',
        feedback.kind === 'success' ? 'text-emerald-600' : 'text-red-600',
      )}
    >
      {feedback.kind === 'success' ? (
        <CheckCircle2 size={14} />
      ) : (
        <AlertTriangle size={14} />
      )}
      {feedback.message}
    </p>
  );
}

function UnsupportedNotice({
  icon,
  title,
  detail,
}: {
  icon: React.ReactNode;
  title: string;
  detail: string;
}) {
  return (
    <div className="flex gap-3 rounded-xl border border-slate-200 bg-slate-50/70 px-4 py-3">
      {icon}
      <div className="text-sm text-slate-600 leading-relaxed">
        <p className="font-medium text-slate-700 inline-flex items-center gap-1.5">
          {title}
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border bg-slate-100 text-slate-400 border-slate-200">
            <Ban size={11} />
            暂不支持
          </span>
        </p>
        <p className="mt-1">{detail}</p>
      </div>
    </div>
  );
}

// ─── Main page ───────────────────────────────────────────────────────
export function IndependentSiteLogisticsPage({
  variant = 'independent_site',
}: { variant?: LogisticsVariant } = {}) {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const { stores } = useStoreContext();
  const api = VARIANT_API[variant];
  const copy = VARIANT_COPY[variant];

  const [states, setStates] = useState<IndependentSiteConnectionState[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const storeName = useMemo(
    () => stores.find((s) => s.id === storeId)?.name ?? '-',
    [stores, storeId],
  );

  const loadStates = useCallback(async () => {
    if (!storeId) return;
    try {
      setLoading(true);
      setError(null);
      const result = await api.fetchState(storeId);
      setStates(result ?? []);
    } catch (err: any) {
      setError(err?.message || '加载连接状态失败');
    } finally {
      setLoading(false);
    }
  }, [storeId, api]);

  useEffect(() => {
    if (storeId) loadStates();
  }, [storeId, loadStates]);

  // Rows describing the active store. Capability for the write entries is derived
  // from these rows: an entry is offered only when at least one connected row of
  // the active store reports the capability as supported (Req 4.7). When no row
  // reports support (connector not registered / capability missing), the entry is
  // shown as "暂不支持" rather than a silently-failing action.
  const activeRows = useMemo(
    () => states.filter((r) => !storeId || r.store_id === storeId),
    [states, storeId],
  );

  const inventorySupported = useMemo(
    () => activeRows.some((r) => r.inventory_write_supported && isConnected(r.connection_state)),
    [activeRows],
  );
  const fulfillmentSupported = useMemo(
    () => activeRows.some((r) => r.fulfillment_write_supported && isConnected(r.connection_state)),
    [activeRows],
  );

  if (storeLoading) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-48 bg-slate-200 rounded animate-pulse" />
        <div className="h-24 bg-white rounded-xl border border-slate-200 animate-pulse" />
        <div className="h-40 bg-white rounded-xl border border-slate-200 animate-pulse" />
      </div>
    );
  }

  if (storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Store size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出错了</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">{storeError}</p>
      </div>
    );
  }

  if (!storeId) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Store size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">
          请先在右上角切换/选择一个{copy.channelLabel}店铺，或前往「系统设置 → 店铺设置」{copy.connectLabel}后再使用本页。
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-2xl font-bold text-slate-900">{copy.channelLabel}库存与发货</h1>
        <p className="text-sm text-slate-500 mt-1">
          更新{copy.channelLabel}产品库存、标记订单发货，并查看各平台的连接与写入能力 · 当前店铺：{storeName}
        </p>
      </div>

      {/* Step-by-step guidance: write-back (Req 8.3, 8.6) */}
      <FlowGuide
        title={`操作指引：${copy.channelLabel}库存更新与发货回写`}
        intro="在后台直接改库存、标发货，无需登录各店铺后台"
        storageKey={copy.storageKey}
        steps={[
          {
            title: copy.connectLabel,
            detail:
              '前往「连接与同步 → 平台连接」，用真实凭证绑定店铺。',
          },
          {
            title: '确认连接与写入能力',
            detail:
              '在下方「连接与写入能力」查看每个平台的连接状态（已连接 / 同步中 / 连接失败 / 未授权）与库存、发货能力是否支持。',
          },
          {
            title: '提交库存更新',
            detail: '填写产品 ID 与绝对可用库存数量后提交，操作进入异步回写队列，由系统回写到对应平台。',
            unsupported: !inventorySupported,
          },
          {
            title: '提交发货标记',
            detail: '填写订单 ID 与物流单号等信息后提交，操作进入异步回写队列，标记订单为已发货 / 履约。',
            unsupported: !fulfillmentSupported,
          },
        ]}
        note={
          <>
            写入均经异步回写队列提交，不会在请求中同步调用外部平台；若回写失败会保留可读的失败原因。
            当某店铺没有有效连接或缺少凭据时，系统会如实拒绝而<strong>不会伪造成功</strong>；某能力暂不可用时入口会标注为
            <strong>「暂不支持」</strong>，而非提供会静默失败的入口。
          </>
        }
      />

      {/* Connection state (Req 4.4, 4.7) */}
      <div className="bg-white rounded-xl border border-slate-200 p-4">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-slate-700">连接与写入能力</h2>
          <button
            onClick={loadStates}
            disabled={loading}
            className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-500 hover:text-slate-700 disabled:opacity-60"
          >
            <RefreshCw size={13} className={cn(loading && 'animate-spin')} />
            刷新
          </button>
        </div>
        {error ? (
          <p className="text-sm text-red-500">{error}</p>
        ) : loading && states.length === 0 ? (
          <p className="text-sm text-slate-400">加载中...</p>
        ) : states.length === 0 ? (
          <p className="text-sm text-slate-400">
            该店铺暂无 Shopify / WooCommerce 连接。请先在「系统设置 → 店铺设置」连接独立站平台。
          </p>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {states.map((row, i) => (
              <ConnectionStateCard key={`${row.store_id}-${row.platform}-${i}`} row={row} />
            ))}
          </div>
        )}
      </div>

      {/* Inventory update entry (Req 4.1, 4.7) */}
      <div className="bg-white rounded-xl border border-slate-200 p-4">
        <h2 className="text-sm font-semibold text-slate-700 mb-3 inline-flex items-center gap-1.5">
          <Boxes size={15} className="text-slate-400" />
          库存更新
        </h2>
        <InventoryUpdateForm storeId={storeId} supported={inventorySupported} submit={api.submitInventory} />
      </div>

      {/* Fulfillment mark entry (Req 4.1, 4.7) */}
      <div className="bg-white rounded-xl border border-slate-200 p-4">
        <h2 className="text-sm font-semibold text-slate-700 mb-3 inline-flex items-center gap-1.5">
          <Truck size={15} className="text-slate-400" />
          发货标记
        </h2>
        <FulfillmentForm storeId={storeId} supported={fulfillmentSupported} submit={api.submitFulfillment} />
      </div>
    </div>
  );
}
