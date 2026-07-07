import { useState } from 'react';
import { Plus, Eye, Pencil, Users, Star } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { fetchSuppliers, createSupplier, updateSupplier } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
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

const SUPPLIER_FIELDS: RecordField[] = [
  { key: 'name', label: '供应商名称', type: 'text' },
  { key: 'contactPerson', label: '联系人', type: 'text' },
  { key: 'email', label: '邮箱', type: 'text' },
  { key: 'phone', label: '电话', type: 'text' },
  { key: 'country', label: '国家', type: 'text' },
  { key: 'paymentTerms', label: '付款条件', type: 'text' },
  { key: 'leadTimeDays', label: '交期(天)', type: 'number' },
  { key: 'rating', label: '评级(0-5)', type: 'number' },
  { key: 'status', label: '状态', viewOnly: true },
];

// ─── Types ──────────────────────────────────────────────────────────
interface SupplierItem {
  id: string;
  name: string;
  contactPerson: string;
  email: string;
  phone: string;
  country: string;
  paymentTerms: string;
  leadTimeDays: number;
  rating: number;
  status: 'active' | 'inactive';
}

/**
 * The backend SupplierVo uses supplierName/contactName/contactEmail/contactPhone,
 * while this page renders name/contactPerson/email/phone. Normalize both shapes
 * so freshly-created rows and listed rows display consistently.
 */
function normalizeSupplier(raw: any): SupplierItem {
  return {
    id: raw.id,
    name: raw.name ?? raw.supplierName ?? '',
    contactPerson: raw.contactPerson ?? raw.contactName ?? '',
    email: raw.email ?? raw.contactEmail ?? '',
    phone: raw.phone ?? raw.contactPhone ?? '',
    country: raw.country ?? '',
    paymentTerms: raw.paymentTerms ?? '',
    leadTimeDays: raw.leadTimeDays ?? 0,
    rating: Math.round(Number(raw.rating ?? 0)),
    status: (raw.status as SupplierItem['status']) ?? 'active',
  };
}

// ─── Star rating ────────────────────────────────────────────────────
function StarRating({ rating, max = 5 }: { rating: number; max?: number }) {
  return (
    <span className="inline-flex items-center gap-0.5" title={String(rating) + '/' + String(max)}>
      {Array.from({ length: max }, (_, i) => (
        <Star
          key={i}
          size={14}
          fill={i < rating ? 'currentColor' : 'none'}
          className={i < rating ? 'text-amber-500' : 'text-slate-300'}
        />
      ))}
    </span>
  );
}
const EMPTY_FORM = {
  supplierName: '',
  contactName: '',
  contactEmail: '',
  contactPhone: '',
  country: '',
  paymentTerms: '',
  leadTimeDays: '',
  rating: '',
};

// ─── Main Page ──────────────────────────────────────────────────────
export function SuppliersPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const queryClient = useQueryClient();

  const [addOpen, setAddOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [addMsg, setAddMsg] = useState<string | null>(null);
  const [form, setForm] = useState({ ...EMPTY_FORM });

  const suppliersKey = ['suppliers', storeId] as const;
  const suppliersQuery = useApiQuery<SupplierItem[]>(
    suppliersKey,
    () => fetchSuppliers(storeId!).then((data) => (Array.isArray(data) ? data.map(normalizeSupplier) : [])),
    { enabled: !!storeId },
  );
  const suppliers = suppliersQuery.data ?? [];
  const loading = !!storeId && suppliersQuery.isLoading;
  const error = suppliersQuery.isError ? suppliersQuery.error?.message ?? '加载供应商数据失败' : null;
  const loadData = () => suppliersQuery.refetch();

  const openAdd = () => { setForm({ ...EMPTY_FORM }); setAddMsg(null); setAddOpen(true); };

  const [modalMode, setModalMode] = useState<'view' | 'edit'>('view');
  const [modalRow, setModalRow] = useState<SupplierItem | null>(null);

  const saveEdit = async (values: Record<string, any>) => {
    if (!modalRow) return;
    await updateSupplier(modalRow.id, {
      supplierName: values.name,
      contactName: values.contactPerson || undefined,
      contactEmail: values.email || undefined,
      contactPhone: values.phone || undefined,
      country: values.country || undefined,
      paymentTerms: values.paymentTerms || undefined,
      leadTimeDays: values.leadTimeDays !== '' && values.leadTimeDays != null ? Number(values.leadTimeDays) : undefined,
      rating: values.rating !== '' && values.rating != null ? Number(values.rating) : undefined,
    });
    setModalRow(null);
    await loadData();
  };

  const isValidEmail = (v: string) => v === '' || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);

  const saveAdd = async () => {
    if (!form.supplierName.trim()) { setAddMsg('请填写供应商名称'); return; }
    if (!isValidEmail(form.contactEmail.trim())) { setAddMsg('邮箱格式不正确'); return; }
    try {
      setSaving(true);
      setAddMsg(null);
      const created = await createSupplier({
        supplierName: form.supplierName.trim(),
        contactName: form.contactName.trim() || undefined,
        contactEmail: form.contactEmail.trim() || undefined,
        contactPhone: form.contactPhone.trim() || undefined,
        country: form.country.trim() || undefined,
        paymentTerms: form.paymentTerms.trim() || undefined,
        leadTimeDays: form.leadTimeDays ? Number(form.leadTimeDays) : undefined,
        rating: form.rating ? Number(form.rating) : undefined,
      });
      // Optimistic update: prepend the new row, then refetch to stay in sync.
      if (created?.id) {
        queryClient.setQueryData<SupplierItem[]>(suppliersKey, (prev) => [normalizeSupplier(created), ...(prev ?? [])]);
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
          <DialogTitle>新增供应商</DialogTitle>
        </DialogHeader>
        <div className="space-y-3 py-2">
          <div>
            <Label>供应商名称 *</Label>
            <Input value={form.supplierName} onChange={(e) => setForm({ ...form, supplierName: e.target.value })} placeholder="供应商名称" />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>联系人</Label>
              <Input value={form.contactName} onChange={(e) => setForm({ ...form, contactName: e.target.value })} />
            </div>
            <div>
              <Label>电话</Label>
              <Input value={form.contactPhone} onChange={(e) => setForm({ ...form, contactPhone: e.target.value })} />
            </div>
          </div>
          <div>
            <Label>邮箱</Label>
            <Input value={form.contactEmail} onChange={(e) => setForm({ ...form, contactEmail: e.target.value })} placeholder="name@example.com" />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>国家/地区</Label>
              <Input value={form.country} onChange={(e) => setForm({ ...form, country: e.target.value })} />
            </div>
            <div>
              <Label>付款条件</Label>
              <Input value={form.paymentTerms} onChange={(e) => setForm({ ...form, paymentTerms: e.target.value })} placeholder="如 Net 30" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>交期(天)</Label>
              <Input type="number" min="0" value={form.leadTimeDays} onChange={(e) => setForm({ ...form, leadTimeDays: e.target.value })} />
            </div>
            <div>
              <Label>评级(0-5)</Label>
              <Input type="number" min="0" max="5" step="0.5" value={form.rating} onChange={(e) => setForm({ ...form, rating: e.target.value })} />
            </div>
          </div>
          {addMsg && <p className="text-sm text-red-600">{addMsg}</p>}
        </div>
        <DialogFooter>
          <button onClick={() => setAddOpen(false)} className="px-4 py-2 text-sm rounded-lg border border-slate-200">取消</button>
          <button onClick={saveAdd} disabled={saving || !form.supplierName.trim()}
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
        <ErpPageHeader title="供应商管理" description="管理供应商信息与合作关系" />
        <ErpLoadingSkeleton rows={8} />
      </div>
    );
  }

  // ─── Error state ──────────────────────────────────────────────────
  if (error || storeError) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="供应商管理" />
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
        <ErpPageHeader title="供应商管理" description="管理供应商信息与合作关系" />
        <div className="bg-white rounded-xl border border-slate-200">
          <ErpEmptyState
            title="暂无可用店铺"
            description="请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。"
            icon={<Users size={24} className="text-slate-400" />}
          />
        </div>
      </div>
    );
  }

  // ─── Empty state ──────────────────────────────────────────────────
  if (suppliers.length === 0) {
    return (
      <div className="space-y-4">
        <ErpPageHeader
          title="供应商管理"
          description="共 0 条记录"
          actions={
            <button onClick={openAdd} className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
              <Plus size={16} />
              新增供应商
            </button>
          }
        />
        <div className="bg-white rounded-xl border border-slate-200">
          <ErpEmptyState
            title="暂无供应商"
            description="添加第一个供应商以开始采购管理"
            icon={<Users size={24} className="text-slate-400" />}
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
        title="供应商管理"
        description={`共 ${suppliers.length} 个供应商`}
        actions={
          <button onClick={openAdd} className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
            <Plus size={16} />
            新增供应商
          </button>
        }
      />

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50/60">
                <th className="text-left font-medium text-slate-500 px-4 py-3">供应商名称</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">联系人</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">邮箱</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">电话</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">国家</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">付款条件</th>
                <th className="text-right font-medium text-slate-500 px-4 py-3">交期(天)</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">评级</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">状态</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {suppliers.map((supplier) => (
                <tr
                  key={supplier.id}
                  className="border-b border-slate-50 last:border-0 hover:bg-slate-50/50 transition-colors"
                >
                  <td className="px-4 py-3">
                    <span className="font-medium text-slate-900">{supplier.name}</span>
                  </td>
                  <td className="px-4 py-3 text-slate-700">{supplier.contactPerson}</td>
                  <td className="px-4 py-3 text-slate-600 text-xs">{supplier.email}</td>
                  <td className="px-4 py-3 text-slate-600 text-xs font-mono">{supplier.phone}</td>
                  <td className="px-4 py-3 text-slate-700">{supplier.country}</td>
                  <td className="px-4 py-3 text-slate-600">{supplier.paymentTerms}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{supplier.leadTimeDays}</td>
                  <td className="px-4 py-3 text-center">
                    <StarRating rating={supplier.rating} />
                  </td>
                  <td className="px-4 py-3 text-center">
                    <ErpStatusBadge status={supplier.status} />
                  </td>
                  <td className="px-4 py-3 text-center">
                    <div className="inline-flex items-center gap-1">
                      <button
                        onClick={() => { setModalMode('view'); setModalRow(supplier); }}
                        className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                        title="查看"
                      >
                        <Eye size={15} />
                      </button>
                      <button
                        onClick={() => { setModalMode('edit'); setModalRow(supplier); }}
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
        title={modalMode === 'view' ? '供应商详情' : '编辑供应商'}
        fields={SUPPLIER_FIELDS}
        record={modalRow}
        onClose={() => setModalRow(null)}
        onSave={saveEdit}
      />
    </div>
  );
}
