import { useState } from 'react';
import { Plus, Eye, Pencil, Truck, AlertTriangle } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { fetchFbaShipments, fetchActiveStoreShipmentExceptions, createFbaShipment, updateShipmentStatus } from '../lib/api';
import { useApiQuery, useApiMutation } from '../lib/hooks/useApiQuery';
import { qk } from '../lib/queryKeys';
import { useStoreContext } from '../lib/StoreContext';
import { getChannelCapability } from '../lib/channelCapabilities';
import { usePermissions } from '../lib/PermissionContext';
import { cn, formatCurrency, formatDate, formatNumber } from '../lib/utils';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { RecordModal, type RecordField } from '../components/ui/RecordModal';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '../components/ui/dialog';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';

const SHIPMENT_STATUS_OPTIONS = [
  { value: 'pending', label: '待发货' },
  { value: 'shipped', label: '已发货' },
  { value: 'in_transit', label: '运输中' },
  { value: 'delivered', label: '已签收' },
  { value: 'cancelled', label: '已取消' },
];

const SHIPMENT_FIELDS: RecordField[] = [
  { key: 'shipmentId', label: '货件 ID', viewOnly: true },
  { key: 'type', label: '类型', viewOnly: true },
  { key: 'carrier', label: '承运商', viewOnly: true },
  { key: 'trackingNumber', label: '追踪号', viewOnly: true },
  { key: 'shipDate', label: '发货日期', viewOnly: true },
  { key: 'expectedArrival', label: '预计到达', viewOnly: true },
  { key: 'totalQuantity', label: '总数量', viewOnly: true },
  { key: 'shippingCost', label: '运费', viewOnly: true },
  { key: 'status', label: '状态', type: 'select', options: SHIPMENT_STATUS_OPTIONS },
];

// ─── Types ──────────────────────────────────────────────────────────
interface FbaShipmentItem {
  id: string;
  shipmentId: string;
  type: string;
  status: string;
  carrier: string;
  trackingNumber: string;
  shipDate: string;
  expectedArrival: string;
  totalQuantity: number;
  shippingCost: number;
}

/**
 * The backend ShipmentVo uses shipmentType/estimatedDeliveryDate/totalItems,
 * while this page renders type/expectedArrival/totalQuantity. Normalize both.
 */
function normalizeShipment(raw: any): FbaShipmentItem {
  return {
    id: raw.id,
    shipmentId: raw.shipmentId ?? '',
    type: raw.type ?? raw.shipmentType ?? '',
    status: raw.status ?? 'pending',
    carrier: raw.carrier ?? '',
    trackingNumber: raw.trackingNumber ?? '',
    shipDate: raw.shipDate ?? '',
    expectedArrival: raw.expectedArrival ?? raw.estimatedDeliveryDate ?? '',
    totalQuantity: Number(raw.totalQuantity ?? raw.totalItems ?? 0),
    shippingCost: Number(raw.shippingCost ?? 0),
  };
}

// ─── Type badge ─────────────────────────────────────────────────────
function ShipmentTypeBadge({ type }: { type: string }) {
  const map: Record<string, { label: string; className: string }> = {
    small_parcel: { label: '小包裹', className: 'bg-blue-50 text-blue-700 border-blue-200' },
    ltl: { label: '零担运输', className: 'bg-violet-50 text-violet-700 border-violet-200' },
    ftl: { label: '整车运输', className: 'bg-orange-50 text-orange-700 border-orange-200' },
    spd: { label: '小件快递', className: 'bg-cyan-50 text-cyan-700 border-cyan-200' },
  };
  const config = map[type] || { label: type || '-', className: 'bg-slate-100 text-slate-600 border-slate-200' };
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border', config.className)}>
      {config.label}
    </span>
  );
}

const EMPTY_FORM = {
  shipmentId: '',
  shipmentType: 'small_parcel',
  carrier: '',
  trackingNumber: '',
  shipDate: '',
  estimatedDeliveryDate: '',
  totalItems: '',
  shippingCost: '',
  currency: 'USD',
  notes: '',
};

// ─── Main Page ──────────────────────────────────────────────────────
export function FbaShipmentsPage() {
  const { stores, storeId, loading: storeLoading, error: storeError } = useStoreContext();
  const { can } = usePermissions();
  const canManage = can('warehouse:manage');
  const currentStore = stores.find((store) => store.id === storeId);
  const capability = getChannelCapability(currentStore?.platform);
  const shipmentTitle = capability.platform === 'amazon' ? 'FBA 货件' : '物流货件';
  const shipmentDescription = capability.logisticsLabel + ' · 货件与物流追踪';
  const queryClient = useQueryClient();

  const [addOpen, setAddOpen] = useState(false);
  const [addMsg, setAddMsg] = useState<string | null>(null);
  const [form, setForm] = useState({ ...EMPTY_FORM });

  // Read shipments through the data-fetching layer. Scoped to the active store
  // via the query key (Req 3.7, 3.8); only enabled once a store is resolved so
  // the no-store branch below renders instead of an empty fetch.
  const shipmentsQuery = useApiQuery(
    qk.shipments(storeId),
    () => fetchFbaShipments(storeId),
    { enabled: !!storeId },
  );

  // Active-store exceptions drive the open-exception indicator (Req 9.6). This
  // is supplementary: if it fails we simply show no indicators rather than
  // turning the whole page into an error state.
  const exceptionsQuery = useApiQuery(
    qk.shipmentExceptionsByStore(storeId),
    () => fetchActiveStoreShipmentExceptions(storeId ?? undefined),
    { enabled: !!storeId },
  );

  const shipments: FbaShipmentItem[] = Array.isArray(shipmentsQuery.data)
    ? shipmentsQuery.data.map(normalizeShipment)
    : [];

  // Set of shipment ids that have at least one exception still in the open state.
  const openExceptionShipmentIds = new Set(
    (exceptionsQuery.data ?? [])
      .filter((e) => e.resolutionState === 'open')
      .map((e) => e.shipmentId),
  );

  const loading = storeLoading || (!!storeId && shipmentsQuery.isLoading);
  const error = storeError || (shipmentsQuery.isError ? shipmentsQuery.error?.message : null);

  const refreshShipments = () => {
    queryClient.invalidateQueries({ queryKey: qk.shipments(storeId) });
    queryClient.invalidateQueries({ queryKey: qk.shipmentExceptionsByStore(storeId) });
  };

  const openAdd = () => { setForm({ ...EMPTY_FORM }); setAddMsg(null); setAddOpen(true); };

  const [modalMode, setModalMode] = useState<'view' | 'edit'>('view');
  const [modalRow, setModalRow] = useState<FbaShipmentItem | null>(null);

  const updateStatusMutation = useApiMutation(
    (vars: { id: string; status: string }) => updateShipmentStatus(vars.id, vars.status),
    { onSuccess: refreshShipments },
  );

  const createMutation = useApiMutation(createFbaShipment, {
    onSuccess: () => {
      setAddOpen(false);
      refreshShipments();
    },
    onError: (e) => setAddMsg(e?.message || '新增失败'),
  });

  const saving = createMutation.isPending;

  const saveEdit = async (values: Record<string, any>) => {
    if (!modalRow) return;
    if (values.status && values.status !== modalRow.status) {
      await updateStatusMutation.mutateAsync({ id: modalRow.id, status: values.status });
    }
    setModalRow(null);
  };

  const saveAdd = () => {
    if (!storeId) { setAddMsg('未选择店铺'); return; }
    if (!form.shipmentId.trim()) { setAddMsg('请填写货件ID'); return; }
    setAddMsg(null);
    createMutation.mutate({
      storeId,
      shipmentId: form.shipmentId.trim(),
      shipmentType: form.shipmentType || undefined,
      carrier: form.carrier.trim() || undefined,
      trackingNumber: form.trackingNumber.trim() || undefined,
      shipDate: form.shipDate || undefined,
      estimatedDeliveryDate: form.estimatedDeliveryDate || undefined,
      totalItems: form.totalItems ? Number(form.totalItems) : undefined,
      shippingCost: form.shippingCost ? Number(form.shippingCost) : undefined,
      currency: form.currency || undefined,
      notes: form.notes.trim() || undefined,
    });
  };

  const addModal = (
    <Dialog open={addOpen} onOpenChange={setAddOpen}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>新增 {shipmentTitle}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3 py-2">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>货件ID *</Label>
              <Input value={form.shipmentId} onChange={(e) => setForm({ ...form, shipmentId: e.target.value })} placeholder={capability.platform === 'amazon' ? '如 FBA15ABCD' : '如 SHIPMENT-001'} />
            </div>
            <div>
              <Label>类型</Label>
              <select value={form.shipmentType} onChange={(e) => setForm({ ...form, shipmentType: e.target.value })}
                className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg bg-white">
                <option value="small_parcel">小包裹</option>
                <option value="ltl">零担运输</option>
                <option value="ftl">整车运输</option>
                <option value="spd">小件快递</option>
              </select>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>承运商</Label>
              <Input value={form.carrier} onChange={(e) => setForm({ ...form, carrier: e.target.value })} />
            </div>
            <div>
              <Label>追踪号</Label>
              <Input value={form.trackingNumber} onChange={(e) => setForm({ ...form, trackingNumber: e.target.value })} />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>发货日期</Label>
              <Input type="date" value={form.shipDate} onChange={(e) => setForm({ ...form, shipDate: e.target.value })} />
            </div>
            <div>
              <Label>预计到达</Label>
              <Input type="date" value={form.estimatedDeliveryDate} onChange={(e) => setForm({ ...form, estimatedDeliveryDate: e.target.value })} />
            </div>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <Label>总数量</Label>
              <Input type="number" min="0" value={form.totalItems} onChange={(e) => setForm({ ...form, totalItems: e.target.value })} />
            </div>
            <div>
              <Label>运费</Label>
              <Input type="number" min="0" step="0.01" value={form.shippingCost} onChange={(e) => setForm({ ...form, shippingCost: e.target.value })} />
            </div>
            <div>
              <Label>币种</Label>
              <select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}
                className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg bg-white">
                <option value="USD">USD</option>
                <option value="CNY">CNY</option>
                <option value="EUR">EUR</option>
                <option value="GBP">GBP</option>
              </select>
            </div>
          </div>
          {addMsg && <p className="text-sm text-red-600">{addMsg}</p>}
        </div>
        <DialogFooter>
          <button onClick={() => setAddOpen(false)} className="px-4 py-2 text-sm rounded-lg border border-slate-200">取消</button>
          <button onClick={saveAdd} disabled={saving || !form.shipmentId.trim()}
            className="px-4 py-2 text-sm rounded-lg bg-indigo-600 text-white disabled:opacity-50">
            {saving ? '创建中...' : '创建'}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );

  // ─── Loading state ────────────────────────────────────────────────
  if (loading || storeLoading) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title={shipmentTitle} description={shipmentDescription} />
        <ErpLoadingSkeleton rows={8} />
      </div>
    );
  }

  // ─── Error state ──────────────────────────────────────────────────
  if (error || storeError) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title={shipmentTitle} />
        <div className="bg-white rounded-xl border border-slate-200 p-8">
          <ErpErrorState message={storeError || error || undefined} onRetry={() => shipmentsQuery.refetch()} />
        </div>
      </div>
    );
  }

  // ─── No store state ───────────────────────────────────────────────
  if (!storeId) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title={shipmentTitle} description={shipmentDescription} />
        <div className="bg-white rounded-xl border border-slate-200">
          <ErpEmptyState
            title="暂无可用店铺"
            description="请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。"
            icon={<Truck size={24} className="text-slate-400" />}
          />
        </div>
      </div>
    );
  }

  // ─── Empty state ──────────────────────────────────────────────────
  if (shipments.length === 0) {
    return (
      <div className="space-y-4">
        <ErpPageHeader
          title={shipmentTitle}
          description="共 0 条记录"
          actions={canManage ? (
            <button onClick={openAdd} className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
              <Plus size={16} />
              新增货件
            </button>
          ) : undefined}
        />
        <div className="bg-white rounded-xl border border-slate-200">
          <ErpEmptyState
            title="暂无货件"
            description={capability.platform === 'amazon' ? '创建第一个 FBA 货件以开始发货' : '创建第一个物流货件以开始追踪'}
            icon={<Truck size={24} className="text-slate-400" />}
          />
        </div>
        {addModal}
      </div>
    );
  }

  // ─── Data state ───────────────────────────────────────────────────
  return (
    <div className="space-y-4">
      <ErpPageHeader
        title={shipmentTitle}
        description={'共 ' + shipments.length + ' 个货件'}
        actions={canManage ? (
          <button onClick={openAdd} className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
            <Plus size={16} />
            新增货件
          </button>
        ) : undefined}
      />

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50/60">
                <th className="text-left font-medium text-slate-500 px-4 py-3">货件ID</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">类型</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">状态</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">承运商</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">追踪号</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">发货日期</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">预计到达</th>
                <th className="text-right font-medium text-slate-500 px-4 py-3">总数量</th>
                <th className="text-right font-medium text-slate-500 px-4 py-3">运费</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {shipments.map((shipment) => (
                <tr
                  key={shipment.id}
                  className="border-b border-slate-50 last:border-0 hover:bg-slate-50/50 transition-colors"
                >
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-xs font-medium text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded">
                        {shipment.shipmentId}
                      </span>
                      {openExceptionShipmentIds.has(shipment.id) && (
                        <span
                          className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs font-medium border bg-red-50 text-red-700 border-red-200"
                          title="该货件存在未解决的异常"
                        >
                          <AlertTriangle size={12} />
                          异常
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-center">
                    <ShipmentTypeBadge type={shipment.type} />
                  </td>
                  <td className="px-4 py-3 text-center">
                    <ErpStatusBadge status={shipment.status} />
                  </td>
                  <td className="px-4 py-3 text-slate-700">{shipment.carrier}</td>
                  <td className="px-4 py-3 text-slate-600 font-mono text-xs">{shipment.trackingNumber}</td>
                  <td className="px-4 py-3 text-slate-600">{formatDate(shipment.shipDate)}</td>
                  <td className="px-4 py-3 text-slate-600">{formatDate(shipment.expectedArrival)}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatNumber(shipment.totalQuantity)}</td>
                  <td className="px-4 py-3 text-right font-medium text-slate-700">
                    {formatCurrency(shipment.shippingCost)}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <div className="inline-flex items-center gap-1">
                      <button
                        onClick={() => { setModalMode('view'); setModalRow(shipment); }}
                        className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                        title="查看"
                      >
                        <Eye size={15} />
                      </button>
                      {canManage && (
                        <button
                          onClick={() => { setModalMode('edit'); setModalRow(shipment); }}
                          className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                          title="编辑"
                        >
                          <Pencil size={15} />
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {addModal}
      <RecordModal
        open={!!modalRow}
        mode={modalMode}
        title={modalMode === 'view' ? '货件详情' : '更新货件状态'}
        fields={SHIPMENT_FIELDS}
        record={modalRow}
        onClose={() => setModalRow(null)}
        onSave={saveEdit}
        saveLabel="更新状态"
      />
    </div>
  );
}
