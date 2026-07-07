import { useState } from 'react';
import { authFetch } from '../lib/auth';
import { createDepartment, updateDepartment, deleteDepartment } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { Plus, Building2 } from 'lucide-react';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { Button } from '../components/ui/button';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '../components/ui/dialog';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';

interface Department {
  id: string;
  name: string;
  code: string;
  parentId?: string | null;
}

const DEFAULT_ORG = '00000000-0000-0000-0000-000000000001';

export function DepartmentsPage() {
  const departmentsQuery = useApiQuery<Department[]>(
    ['departments'],
    async () => {
      const res = await authFetch('/api/departments');
      const json = await res.json();
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data?.items ?? json.data?.records ?? (Array.isArray(json.data) ? json.data : []);
    },
  );
  const data = departmentsQuery.data ?? [];
  const loading = departmentsQuery.isLoading;
  const error = departmentsQuery.isError ? departmentsQuery.error?.message ?? '加载部门失败' : null;
  const fetchData = () => departmentsQuery.refetch();

  const [editOpen, setEditOpen] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [actionMsg, setActionMsg] = useState<string | null>(null);
  const [editDept, setEditDept] = useState<Department | null>(null);
  const [form, setForm] = useState({ name: '', code: '' });

  const openEdit = (item: Department) => {
    setEditDept(item);
    setForm({ name: item.name || '', code: item.code || '' });
    setActionMsg(null);
    setEditOpen(true);
  };

  const saveEdit = async () => {
    if (!editDept) return;
    try {
      setSaving(true);
      await updateDepartment(editDept.id, { name: form.name, code: form.code });
      setEditOpen(false);
      await fetchData();
    } catch (e: any) {
      setActionMsg(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const saveAdd = async () => {
    try {
      setSaving(true);
      await createDepartment({ orgId: DEFAULT_ORG, name: form.name, code: form.code });
      setAddOpen(false);
      await fetchData();
    } catch (e: any) {
      setActionMsg(e.message || '新增失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (item: Department) => {
    if (!window.confirm(`确定删除部门「${item.name}」吗？`)) return;
    try {
      await deleteDepartment(item.id);
      await fetchData();
    } catch (e: any) {
      window.alert('删除失败：' + (e.message || ''));
    }
  };

  if (loading) return <ErpLoadingSkeleton />;
  if (error) return <ErpErrorState message={error} onRetry={fetchData} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="部门管理"
        description={`共 ${data.length} 个部门`}
        actions={
          <Button className="inline-flex items-center gap-2" onClick={() => { setForm({ name: '', code: '' }); setActionMsg(null); setAddOpen(true); }}>
            <Plus size={16} />
            新增部门
          </Button>
        }
      />

      {data.length === 0 ? (
        <ErpEmptyState title="暂无部门" description="暂无部门配置，请新增部门" />
      ) : (
        <div className="bg-white rounded-lg border overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-slate-50">
                <th className="text-left px-4 py-3 font-medium text-slate-600">部门名称</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">部门编码</th>
                <th className="text-center px-4 py-3 font-medium text-slate-600">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.map((item) => (
                <tr key={item.id} className="border-b hover:bg-slate-50 transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Building2 size={16} className="text-slate-400 flex-shrink-0" />
                      <span className="font-medium text-slate-900">{item.name}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-slate-100 text-slate-600 font-mono">
                      {item.code || '-'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-center">
                    <div className="flex items-center justify-center gap-2">
                      <button onClick={() => openEdit(item)} className="text-sm text-indigo-600 hover:text-indigo-800 transition-colors">
                        编辑
                      </button>
                      <span className="text-slate-300">|</span>
                      <button onClick={() => handleDelete(item)} className="text-sm text-red-600 hover:text-red-800 transition-colors">
                        删除
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Edit modal */}
      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>编辑部门</DialogTitle>
          </DialogHeader>
          <div className="space-y-3 py-2">
            <div>
              <Label>部门名称</Label>
              <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div>
              <Label>部门编码</Label>
              <Input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} />
            </div>
            {actionMsg && <p className="text-sm text-red-600">{actionMsg}</p>}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditOpen(false)}>取消</Button>
            <Button onClick={saveEdit} disabled={saving}>{saving ? '保存中...' : '保存'}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Add modal */}
      <Dialog open={addOpen} onOpenChange={setAddOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>新增部门</DialogTitle>
          </DialogHeader>
          <div className="space-y-3 py-2">
            <div>
              <Label>部门名称</Label>
              <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div>
              <Label>部门编码</Label>
              <Input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} />
            </div>
            {actionMsg && <p className="text-sm text-red-600">{actionMsg}</p>}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAddOpen(false)}>取消</Button>
            <Button onClick={saveAdd} disabled={saving || !form.name}>{saving ? '创建中...' : '创建'}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
