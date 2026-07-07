import { useState } from 'react';
import { Plus, Eye, Pencil, FileText } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { fetchPurchaseOrders, fetchSuppliers, createPurchaseOrder, updatePurchaseOrderStatus } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { formatCurrency, formatDate } from '../lib/utils';
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

const PO_STATUS_OPTIONS = [
  { value: 'draft', label: '草稿' },
  { value: 'pending_approval', label: '待审批' },
  { value: 'approved', label: '已批准' },
  { value: 'pending_arrival', label: '待到货' },
  { value: 'completed', label: '已完成' },
  { value: 'cancelled', label: '已取消' },
];

const PO_FIELDS: RecordField[] = [
  { key: 'poNumber', label: 'PO 编号', viewOnly: true },
  { key: 'supplierName', label: '供应商', viewOnly: true },
  { key: 'totalAmount', label: '总金额', viewOnly: true },
  { key: 'currency', label: '币种', viewOnly: true },
  { key: 'orderDate', label: '下单日期', viewOnly: true },
  { key: 'expectedArrival', label: '预计到货', viewOnly: true },
  { key: 'status', label: '状态', type: 'select', options: PO_STATUS_OPTIONS },
];

// ─── Types ──────────────────────────────────────────────────────────
interface PurchaseOrderItem {
  id: string;
  poNumber: string;
  supplierName: string;
  status: 'draft' | 'pending_approval' | 'approved' | 'pending_arrival' | 'completed' | 'cancelled';
  totalAmount: number;
  currency: string;
  orderDate: string;
  expectedArrival: string;
}

interface SupplierOption {
  id: string;
  name: string;
}

/**
 * The backend PurchaseOrderVo carries supplierId + expectedDeliveryDate, while
 * this page renders supplierName + expectedArrival. Resolve the supplier name
 * via the loaded supplier map and normalize the date field.
 */
function normalizeOrder(raw: any, supplierNames: Record<string, string>): PurchaseOrderItem {
  return {
    id: raw.id,
    poNumber: raw.poNumber ?? '',
    supplierName: raw.supplierName ?? supplierNames[raw.supplierId] ?? raw.supplierId ?? '',
    status: (raw.status as PurchaseOrderItem['status']) ?? 'draft',
    totalAmount: Number(raw.totalAmount ?? 0),
    currency: raw.currency ?? 'USD',
    orderDate: raw.orderDate ?? '',
    expectedArrival: raw.expectedArrival ?? raw.expectedDeliveryDate ?? '',
  };
}

const EMPTY_FORM = {
  supplierId: '',
  poNumber: '',
  currency: 'USD',
  orderDate: '',
  expectedDeliveryDate: '',
  productName: '',
  sku: '',
  quantityOrdered: '',
  unitCost: '',
  notes: '',
};

// ─── Main Page ──────────────────────────────────────────────────────
export function PurchaseOrdersPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const queryClient = useQueryClient();

  const [addOpen, setAddOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [addMsg, setAddMsg] = useState<string | null>(null);
  const [form, setForm] = useState({ ...EMPTY_FORM });

  const poKey = ['purchase-orders', storeId] as const;
  const poQuery = useApiQuery<{ orders: PurchaseOrderItem[]; suppliers: SupplierOption[] }>(
    poKey,
    async () => {
      // Load suppliers in parallel so the PO list can resolve supplier names and
      // the create form can offer a supplier dropdown.
      const [poData, supData] = await Promise.all([
        fetchPurchaseOrders(storeId!),
        fetchSuppliers(storeId!).catch(() => []),
      ]);
      const supOptions: SupplierOption[] = (Array.isArray(supData) ? supData : []).map((s: any) => ({
        id: s.id,
        name: s.supplierName ?? s.name ?? '',
      }));
      const supplierNames = Object.fromEntries(supOptions.map((s) => [s.id, s.name]));
      const orders = Array.isArray(poData) ? poData.map((o: any) => normalizeOrder(o, supplierNames)) : [];
      return { orders, suppliers: supOptions };
    },
    { enabled: !!storeId },
  );
  const orders = poQuery.data?.orders ?? [];
  const suppliers = poQuery.data?.suppliers ?? [];
  const loading = !!storeId && poQuery.isLoading;
  const error = poQuery.isError ? poQuery.error?.message ?? '加载采购订单失败' : null;
  const loadData = () => poQuery.refetch();

  const openAdd = () => { setForm({ ...EMPTY_FORM }); setAddMsg(null); setAddOpen(true); };

  const [modalMode, setModalMode] = useState<'view' | 'edit'>('view');
  const [modalRow, setModalRow] = useState<PurchaseOrderItem | null>(null);

  const saveEdit = async (values: Record<string, any>) => {
    if (!modalRow) return;
    if (values.status && values.status !== modalRow.status) {
      await updatePurchaseOrderStatus(modalRow.id, values.status);
    }
    setModalRow(null);
    await loadData();
  };

  const saveAdd = async () => {
    if (!storeId) { setAddMsg('未选择店铺'); return; }
    if (!form.supplierId) { setAddMsg('请选择供应商'); return; }
    if (!form.poNumber.trim()) { setAddMsg('请填写 PO 编号'); return; }
    const qty = form.quantityOrdered ? Number(form.quantityOrdered) : 0;
    if (form.quantityOrdered && (!Number.isFinite(qty) || qty <= 0)) { setAddMsg('数量必须为正数'); return; }
    try {
      setSaving(true);
      setAddMsg(null);
      const items = qty > 0
        ? [{
          sku: form.sku.trim() || undefined,
          productName: form.productName.trim() || undefined,
          quantityOrdered: qty,
          unitCost: form.unitCost ? Number(form.unitCost) : undefined,
        }]
        : undefined;
      const created = await createPurchaseOrder({
        storeId,
        supplierId: form.supplierId,
        poNumber: form.poNumber.trim(),
        currency: form.currency || 'USD',
        orderDate: form.orderDate || undefined,
        expectedDeliveryDate: form.expectedDeliveryDate || undefined,
        notes: form.notes.trim() || undefined,
        items,
      });
      const supplierNames = Object.fromEntries(suppliers.map((s) => [s.id, s.name]));
      if (created?.id) {
        queryClient.setQueryData<{ orders: PurchaseOrderItem[]; suppliers: SupplierOption[] }>(poKey, (prev) =>
          prev ? { ...prev, orders: [normalizeOrder(created, supplierNames), ...prev.orders] } : prev);
      }
      setAddOpen(false);
      await loadData();
    } catch (e: any) {
      setAddMsg(e?.message || '新增失败');
    } finally {
      setSaving(false);
    }
  };

  const addModal = (
    <Dialog open={addOpen} onOpenChange={setAddOpen}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>新增采购订单</DialogTitle>
        </DialogHeader>
        <div className="space-y-3 py-2">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>供应商 *</Label>
              <select value={form.supplierId} onChange={(e) => setForm({ ...form, supplierId: e.target.value })}
                className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg bg-white">
                <option value="">请选择供应商</option>
                {suppliers.map((s) => (
                  <option key={s.id} value={s.id}>{s.name || s.id}</option>
                ))}
              </select>
            </div>
            <div>
              <Label>PO 编号 *</Label>
              <Input value={form.poNumber} onChange={(e) => setForm({ ...form, poNumber: e.target.value })} placeholder="如 PO-2024-001" />
            </div>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <Label>币种</Label>
              <select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}
                className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg bg-white">
                <option value="USD">USD</option>
                <option value="CNY">CNY</option>
                <option value="EUR">EUR</option>
                <option value="GBP">GBP</option>
                <option value="JPY">JPY</option>
              </select>
            </div>
            <div>
              <Label>下单日期</Label>
              <Input type="date" value={form.orderDate} onChange={(e) => setForm({ ...form, orderDate: e.target.value })} />
            </div>
            <div>
              <Label>预计到货</Label>
              <Input type="date" value={form.expectedDeliveryDate} onChange={(e) => setForm({ ...form, expectedDeliveryDate: e.target.value })} />
            </div>
          </div>
          <div className="border-t border-slate-100 pt-3">
            <p className="text-xs font-medium text-slate-500 mb-2">采购明细（可选）</p>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>产品名称</Label>
                <Input value={form.productName} onChange={(e) => setForm({ ...form, productName: e.target.value })} />
              </div>
              <div>
                <Label>SKU</Label>
                <Input value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} />
              </div>
              <div>
                <Label>数量</Label>
                <Input type="number" min="0" value={form.quantityOrdered} onChange={(e) => setForm({ ...form, quantityOrdered: e.target.value })} />
              </div>
              <div>
                <Label>单价</Label>
                <Input type="number" min="0" step="0.01" value={form.unitCost} onChange={(e) => setForm({ ...form, unitCost: e.target.value })} />
              </div>
            </div>
          </div>
          {addMsg && <p className="text-sm text-red-600">{addMsg}</p>}
        </div>
        <DialogFooter>
          <button onClick={() => setAddOpen(false)} className="px-4 py-2 text-sm rounded-lg border border-slate-200">取消</button>
          <button onClick={saveAdd} disabled={saving || !form.supplierId || !form.poNumber.trim()}
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
        <ErpPageHeader title="采购订单" description="管理采购订单流程" />
        <ErpLoadingSkeleton rows={8} />
      </div>
    );
  }

  // ─── Error state ──────────────────────────────────────────────────
  if (error || storeError) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="采购订单" />
        <div className="bg-white rounded-xl border border-slate-200 p-8">
          <ErpErrorState message={storeError || error || undefined} onRetry={loadData} />
        </div>
      </div>
    );
  }

  // ─── No store state ───────────────────────────────────────────────
  if (!storeId) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="采购订单" description="管理采购订单流程" />
        <div className="bg-white rounded-xl border border-slate-200">
          <ErpEmptyState
            title="暂无可用店铺"
            description="请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。"
            icon={<FileText size={24} className="text-slate-400" />}
          />
        </div>
      </div>
    );
  }

  // ─── Empty state ──────────────────────────────────────────────────
  if (orders.length === 0) {
    return (
      <div className="space-y-4">
        <ErpPageHeader
          title="采购订单"
          description="共 0 条记录"
          actions={
            <button onClick={openAdd} className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
              <Plus size={16} />
              新增采购订单
            </button>
          }
        />
        <div className="bg-white rounded-xl border border-slate-200">
          <ErpEmptyState
            title="暂无采购订单"
            description="创建第一个采购订单以开始采购流程"
            icon={<FileText size={24} className="text-slate-400" />}
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
        title="采购订单"
        description={`共 ${orders.length} 笔订单`}
        actions={
          <button onClick={openAdd} className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
            <Plus size={16} />
            新增采购订单
          </button>
        }
      />

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50/60">
                <th className="text-left font-medium text-slate-500 px-4 py-3">PO编号</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">供应商</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">状态</th>
                <th className="text-right font-medium text-slate-500 px-4 py-3">总金额</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">币种</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">下单日期</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">预计到货</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => (
                <tr
                  key={order.id}
                  className="border-b border-slate-50 last:border-0 hover:bg-slate-50/50 transition-colors"
                >
                  <td className="px-4 py-3">
                    <span className="font-mono text-xs font-medium text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded">
                      {order.poNumber}
                    </span>
                  </td>
                  <td className="px-4 py-3 font-medium text-slate-900">{order.supplierName}</td>
                  <td className="px-4 py-3 text-center">
                    <ErpStatusBadge status={order.status} />
                  </td>
                  <td className="px-4 py-3 text-right font-medium text-slate-900">
                    {formatCurrency(order.totalAmount, order.currency || 'USD')}
                  </td>
                  <td className="px-4 py-3 text-slate-600 text-xs">{order.currency}</td>
                  <td className="px-4 py-3 text-slate-600">{formatDate(order.orderDate)}</td>
                  <td className="px-4 py-3 text-slate-600">{formatDate(order.expectedArrival)}</td>
                  <td className="px-4 py-3 text-center">
                    <div className="inline-flex items-center gap-1">
                      <button
                        onClick={() => { setModalMode('view'); setModalRow(order); }}
                        className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                        title="查看"
                      >
                        <Eye size={15} />
                      </button>
                      <button
                        onClick={() => { setModalMode('edit'); setModalRow(order); }}
                        className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                        title="编辑"
                      >
                        <Pencil size={15} />
                      </button>
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
        title={modalMode === 'view' ? '采购订单详情' : '编辑采购订单状态'}
        fields={PO_FIELDS}
        record={modalRow}
        onClose={() => setModalRow(null)}
        onSave={saveEdit}
        saveLabel="更新状态"
      />
    </div>
  );
}
