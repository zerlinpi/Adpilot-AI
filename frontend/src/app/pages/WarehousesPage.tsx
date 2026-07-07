import { useState } from 'react';
import { Plus, Eye, Pencil, Warehouse } from 'lucide-react';
import { fetchWarehouses, createWarehouseLocation, updateWarehouseLocation } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { usePermissions } from '../lib/PermissionContext';
import { cn, formatNumber } from '../lib/utils';
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

const WAREHOUSE_FIELDS: RecordField[] = [
  { key: 'name', label: '仓库名称', type: 'text' },
  { key: 'code', label: '仓库编码', type: 'text' },
  {
    key: 'type', label: '类型', type: 'select',
    options: [
      { value: 'local', label: '本地仓' },
      { value: 'fba', label: 'FBA仓' },
      { value: 'overseas', label: '海外仓' },
      { value: 'third_party', label: '第三方仓' },
    ],
  },
  { key: 'address', label: '地址', type: 'text' },
  { key: 'country', label: '国家/地区', type: 'text' },
  { key: 'capacity', label: '容量', type: 'number' },
  { key: 'status', label: '状态', viewOnly: true },
];

// ─── Types ──────────────────────────────────────────────────────────
interface WarehouseItem {
  id: string;
  name: string;
  code: string;
  type: 'local' | 'fba' | 'overseas' | 'third_party';
  address: string;
  country: string;
  capacity: number;
  status: 'active' | 'inactive';
}

// ─── Type badge ─────────────────────────────────────────────────────
function WarehouseTypeBadge({ type }: { type: string }) {
  const map: Record<string, { label: string; className: string }> = {
    local: { label: '本地仓', className: 'bg-blue-50 text-blue-700 border-blue-200' },
    fba: { label: 'FBA仓', className: 'bg-orange-50 text-orange-700 border-orange-200' },
    overseas: { label: '海外仓', className: 'bg-violet-50 text-violet-700 border-violet-200' },
    third_party: { label: '第三方仓', className: 'bg-cyan-50 text-cyan-700 border-cyan-200' },
  };
  const config = map[type] || { label: type, className: 'bg-slate-100 text-slate-600 border-slate-200' };
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border', config.className)}>
      {config.label}
    </span>
  );
}

// ─── Main Page ──────────────────────────────────────────────────────
export function WarehousesPage() {
  const { can } = usePermissions();
  const canManage = can('warehouse:manage');
  const warehousesQuery = useApiQuery<WarehouseItem[]>(
    ['warehouses'],
    () => fetchWarehouses().then((data) => (Array.isArray(data) ? data : [])),
  );
  const warehouses = warehousesQuery.data ?? [];
  const loading = warehousesQuery.isLoading;
  const error = warehousesQuery.isError ? warehousesQuery.error?.message ?? '加载仓库数据失败' : null;
  const loadData = () => warehousesQuery.refetch();

  const [addOpen, setAddOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [addMsg, setAddMsg] = useState<string | null>(null);
  const [form, setForm] = useState({ locationName: '', locationCode: '', locationType: 'local', address: '', country: '', capacity: '' });

  const openAdd = () => {
    setForm({ locationName: '', locationCode: '', locationType: 'local', address: '', country: '', capacity: '' });
    setAddMsg(null);
    setAddOpen(true);
  };

  // View / edit modal state
  const [modalMode, setModalMode] = useState<'view' | 'edit'>('view');
  const [modalRow, setModalRow] = useState<WarehouseItem | null>(null);

  const saveEdit = async (values: Record<string, any>) => {
    if (!modalRow) return;
    await updateWarehouseLocation(modalRow.id, {
      locationName: values.name,
      locationCode: values.code || undefined,
      locationType: values.type || undefined,
      address: values.address || undefined,
      country: values.country || undefined,
      capacity: values.capacity !== '' && values.capacity != null ? Number(values.capacity) : undefined,
    });
    setModalRow(null);
    await loadData();
  };

  const saveAdd = async () => {
    try {
      setSaving(true);
      await createWarehouseLocation({
        locationName: form.locationName,
        locationCode: form.locationCode || undefined,
        locationType: form.locationType || undefined,
        address: form.address || undefined,
        country: form.country || undefined,
        capacity: form.capacity ? Number(form.capacity) : undefined,
      });
      setAddOpen(false);
      await loadData();
    } catch (e: any) {
      setAddMsg(e.message || '新增失败');
    } finally {
      setSaving(false);
    }
  };

  const addModal = (
    <Dialog open={addOpen} onOpenChange={setAddOpen}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>新增仓库</DialogTitle>
        </DialogHeader>
        <div className="space-y-3 py-2">
          <div>
            <Label>仓库名称</Label>
            <Input value={form.locationName} onChange={(e) => setForm({ ...form, locationName: e.target.value })} />
          </div>
          <div>
            <Label>仓库编码</Label>
            <Input value={form.locationCode} onChange={(e) => setForm({ ...form, locationCode: e.target.value })} />
          </div>
          <div>
            <Label>类型</Label>
            <select value={form.locationType} onChange={(e) => setForm({ ...form, locationType: e.target.value })}
              className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg bg-white">
              <option value="local">本地仓</option>
              <option value="fba">FBA仓</option>
              <option value="overseas">海外仓</option>
              <option value="third_party">第三方仓</option>
            </select>
          </div>
          <div>
            <Label>地址</Label>
            <Input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
          </div>
          <div>
            <Label>国家/地区</Label>
            <Input value={form.country} onChange={(e) => setForm({ ...form, country: e.target.value })} />
          </div>
          <div>
            <Label>容量</Label>
            <Input type="number" value={form.capacity} onChange={(e) => setForm({ ...form, capacity: e.target.value })} />
          </div>
          {addMsg && <p className="text-sm text-red-600">{addMsg}</p>}
        </div>
        <DialogFooter>
          <button onClick={() => setAddOpen(false)} className="px-4 py-2 text-sm rounded-lg border border-slate-200">取消</button>
          <button onClick={saveAdd} disabled={saving || !form.locationName}
            className="px-4 py-2 text-sm rounded-lg bg-indigo-600 text-white disabled:opacity-50">
            {saving ? '创建中...' : '创建'}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );

  // ─── Loading state ────────────────────────────────────────────────
  if (loading) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="仓库管理" description="组织共享仓库，供各店铺库存与物流履约使用" />
        <ErpLoadingSkeleton rows={8} />
      </div>
    );
  }

  // ─── Error state ──────────────────────────────────────────────────
  if (error) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="仓库管理" />
        <div className="bg-white rounded-xl border border-slate-200 p-8">
          <ErpErrorState message={error || undefined} onRetry={loadData} />
        </div>
      </div>
    );
  }

  // ─── Empty state ──────────────────────────────────────────────────
  if (warehouses.length === 0) {
    return (
      <div className="space-y-4">
        <ErpPageHeader
          title="仓库管理"
          description="组织共享仓库 · 共 0 条记录"
          actions={canManage ?
            <button onClick={openAdd} className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
              <Plus size={16} />
              新增仓库
            </button>
            : undefined
          }
        />
        <div className="bg-white rounded-xl border border-slate-200">
          <ErpEmptyState
            title="暂无仓库"
            description="添加第一个仓库以开始管理库存"
            icon={<Warehouse size={24} className="text-slate-400" />}
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
        title="仓库管理"
        description={'组织共享仓库 · 共 ' + warehouses.length + ' 个仓库'}
        actions={canManage ?
          <button onClick={openAdd} className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm">
            <Plus size={16} />
            新增仓库
          </button>
          : undefined
        }
      />

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50/60">
                <th className="text-left font-medium text-slate-500 px-4 py-3">仓库名称</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">编码</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">类型</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">地址</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">国家</th>
                <th className="text-right font-medium text-slate-500 px-4 py-3">容量</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">状态</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {warehouses.map((wh) => (
                <tr
                  key={wh.id}
                  className="border-b border-slate-50 last:border-0 hover:bg-slate-50/50 transition-colors"
                >
                  <td className="px-4 py-3">
                    <span className="font-medium text-slate-900">{wh.name}</span>
                  </td>
                  <td className="px-4 py-3 text-slate-600 font-mono text-xs">{wh.code}</td>
                  <td className="px-4 py-3 text-center">
                    <WarehouseTypeBadge type={wh.type} />
                  </td>
                  <td className="px-4 py-3 text-slate-600 max-w-[200px] truncate">{wh.address}</td>
                  <td className="px-4 py-3 text-slate-700">{wh.country}</td>
                  <td className="px-4 py-3 text-right text-slate-700">{formatNumber(wh.capacity)}</td>
                  <td className="px-4 py-3 text-center">
                    <ErpStatusBadge status={wh.status} />
                  </td>
                  <td className="px-4 py-3 text-center">
                    <div className="inline-flex items-center gap-1">
                      <button
                        onClick={() => { setModalMode('view'); setModalRow(wh); }}
                        className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                        title="查看"
                      >
                        <Eye size={15} />
                      </button>
                      {canManage && (
                        <button
                          onClick={() => { setModalMode('edit'); setModalRow(wh); }}
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
        title={modalMode === 'view' ? '仓库详情' : '编辑仓库'}
        fields={WAREHOUSE_FIELDS}
        record={modalRow}
        onClose={() => setModalRow(null)}
        onSave={saveEdit}
      />
    </div>
  );
}
