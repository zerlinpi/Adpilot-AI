import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authFetch } from '../lib/auth';
import { createUser, updateUser, setUserStatus, resetUserPassword, fetchStores, fetchUserStores, assignUserStores } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { validateUserForm, type UserFieldErrors } from '../lib/userValidation';
import { Plus, Filter, Search } from 'lucide-react';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { Button } from '../components/ui/button';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '../components/ui/dialog';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';

interface User {
  id: string;
  name: string;
  email: string;
  role: string;
  department: string;
  status: string;
  lastLogin: string;
}

const roleOptions = ['全部', '管理员', '经理', '运营', '投手', '采购', '仓库', '财务', '客服', '查看者'];
const departmentOptions = ['全部', '技术部', '运营部', '广告部', '供应链部', '财务部', '客服部'];
const statusOptions = ['全部', '正常', '停用'];

function RoleBadge({ role }: { role: string }) {
  const config: Record<string, { bg: string; text: string }> = {
    '管理员': { bg: 'bg-purple-50', text: 'text-purple-700' },
    '经理': { bg: 'bg-indigo-50', text: 'text-indigo-700' },
    '运营': { bg: 'bg-blue-50', text: 'text-blue-700' },
    '投手': { bg: 'bg-cyan-50', text: 'text-cyan-700' },
    '采购': { bg: 'bg-amber-50', text: 'text-amber-700' },
    '仓库': { bg: 'bg-orange-50', text: 'text-orange-700' },
    '财务': { bg: 'bg-emerald-50', text: 'text-emerald-700' },
    '客服': { bg: 'bg-pink-50', text: 'text-pink-700' },
    '查看者': { bg: 'bg-slate-100', text: 'text-slate-600' },
  };
  const c = config[role] || config['查看者'];
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${c.bg} ${c.text}`}>
      {role}
    </span>
  );
}

export function UsersPage() {
  const queryClient = useQueryClient();
  const usersKey = ['users'] as const;
  const usersQuery = useApiQuery<User[]>(
    usersKey,
    async () => {
      const res = await authFetch('/api/users');
      const json = await res.json();
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data?.items || json.data || [];
    },
  );
  const data = usersQuery.data ?? [];
  const loading = usersQuery.isLoading;
  const error = usersQuery.isError ? usersQuery.error?.message ?? '加载失败' : null;
  const fetchData = () => usersQuery.refetch();

  // Filters
  const [filterRole, setFilterRole] = useState('全部');
  const [filterDepartment, setFilterDepartment] = useState('全部');
  const [filterStatus, setFilterStatus] = useState('全部');
  const [searchQuery, setSearchQuery] = useState('');

  // Modal / action state
  const [editOpen, setEditOpen] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [actionMsg, setActionMsg] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [editUser, setEditUser] = useState<any | null>(null);
  const [editForm, setEditForm] = useState({ name: '', phone: '', status: 'active' });
  const [addForm, setAddForm] = useState({ name: '', email: '', password: 'Adpilot@123456' });
  const [addErrors, setAddErrors] = useState<UserFieldErrors>({});

  // Store assignment state
  const [assignOpen, setAssignOpen] = useState(false);
  const [assignUser, setAssignUser] = useState<any | null>(null);
  const [allStores, setAllStores] = useState<any[]>([]);
  const [selectedStoreIds, setSelectedStoreIds] = useState<string[]>([]);
  const [assignLoading, setAssignLoading] = useState(false);

  const DEFAULT_ORG = '00000000-0000-0000-0000-000000000001';

  const openEdit = (item: any) => {
    setEditUser(item);
    setEditForm({ name: item.name || '', phone: item.phone || '', status: item.status || 'active' });
    setActionMsg(null);
    setEditOpen(true);
  };

  const saveEdit = async () => {
    if (!editUser) return;
    try {
      setSaving(true);
      await updateUser(editUser.id, { name: editForm.name, phone: editForm.phone, status: editForm.status });
      setEditOpen(false);
      await fetchData();
    } catch (e: any) {
      setActionMsg(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const toggleStatus = async (item: any) => {
    try {
      const next = item.status === 'active' ? 'disabled' : 'active';
      await setUserStatus(item.id, next);
      await fetchData();
    } catch (e: any) {
      setActionMsg(e.message || '操作失败');
    }
  };

  const handleReset = async (item: any) => {
    const pwd = window.prompt(`为「${item.name || item.email}」重置密码，请输入新密码：`, 'Adpilot@123456');
    if (!pwd) return;
    try {
      await resetUserPassword(item.id, pwd);
      window.alert('密码已重置');
    } catch (e: any) {
      window.alert('重置失败：' + (e.message || ''));
    }
  };

  const openAssign = async (item: any) => {
    setAssignUser(item);
    setActionMsg(null);
    setAssignOpen(true);
    setAssignLoading(true);
    try {
      const [stores, assigned] = await Promise.all([
        fetchStores(),
        fetchUserStores(item.id).catch(() => []),
      ]);
      setAllStores(Array.isArray(stores) ? stores : []);
      setSelectedStoreIds(Array.isArray(assigned) ? assigned : []);
    } catch (e: any) {
      setActionMsg(e.message || '加载店铺失败');
    } finally {
      setAssignLoading(false);
    }
  };

  const toggleStore = (storeId: string) => {
    setSelectedStoreIds((prev) =>
      prev.includes(storeId) ? prev.filter((id) => id !== storeId) : [...prev, storeId]
    );
  };

  const saveAssign = async () => {
    if (!assignUser) return;
    try {
      setSaving(true);
      await assignUserStores(assignUser.id, selectedStoreIds);
      setAssignOpen(false);
    } catch (e: any) {
      setActionMsg(e.message || '分配失败');
    } finally {
      setSaving(false);
    }
  };

  const saveAdd = async () => {
    // Field-level validation runs before any request is sent (Req 5.3).
    const errors = validateUserForm({ name: addForm.name, email: addForm.email });
    setAddErrors(errors);
    if (Object.keys(errors).length > 0) {
      return;
    }
    try {
      setSaving(true);
      setActionMsg(null);
      const created = await createUser({
        orgId: DEFAULT_ORG,
        name: addForm.name,
        email: addForm.email,
        passwordHash: addForm.password,
        status: 'active',
      });
      // Optimistic list update: add the created record (with its generated id) to the
      // displayed list without a full reload (Req 5.2).
      if (created && created.id) {
        queryClient.setQueryData<User[]>(usersKey, (prev) => [
          {
            id: created.id,
            name: created.name ?? addForm.name,
            email: created.email ?? addForm.email,
            role: created.role ?? '',
            department: created.department ?? '',
            status: created.status ?? 'active',
            lastLogin: created.lastLogin ?? '-',
          } as User,
          ...(prev ?? []),
        ]);
      }
      setAddOpen(false);
      setAddForm({ name: '', email: '', password: 'Adpilot@123456' });
      setAddErrors({});
      setSuccessMsg(`已成功新增用户「${created?.name ?? addForm.name}」`);
      // Reconcile with the backend so derived fields (role/department) are accurate.
      await fetchData();
    } catch (e: any) {
      setActionMsg(e.message || '新增失败');
    } finally {
      setSaving(false);
    }
  };

  const filteredData = data.filter((item) => {
    if (filterRole !== '全部' && item.role !== filterRole) return false;
    if (filterDepartment !== '全部' && item.department !== filterDepartment) return false;
    if (filterStatus !== '全部') {
      const isActive = item.status === 'active';
      if (filterStatus === '正常' && !isActive) return false;
      if (filterStatus === '停用' && isActive) return false;
    }
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      if (!item.name.toLowerCase().includes(q) && !item.email.toLowerCase().includes(q)) {
        return false;
      }
    }
    return true;
  });

  if (loading) return <ErpLoadingSkeleton />;
  if (error) return <ErpErrorState message={error} onRetry={fetchData} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="用户管理"
        description={`共 ${data.length} 个用户`}
        actions={
          <Button className="inline-flex items-center gap-2" onClick={() => { setActionMsg(null); setAddErrors({}); setAddForm({ name: '', email: '', password: 'Adpilot@123456' }); setAddOpen(true); }}>
            <Plus size={16} />
            新增用户
          </Button>
        }
      />

      {successMsg && (
        <div className="flex items-center justify-between rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-2.5 text-sm text-emerald-700">
          <span>{successMsg}</span>
          <button onClick={() => setSuccessMsg(null)} className="text-emerald-500 hover:text-emerald-700">×</button>
        </div>
      )}

      {/* Filters & Search */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            placeholder="搜索姓名或邮箱..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9 pr-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 w-56"
          />
        </div>
        <div className="flex items-center gap-1.5 text-sm text-slate-500">
          <Filter size={14} />
        </div>
        <select
          value={filterRole}
          onChange={(e) => setFilterRole(e.target.value)}
          className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
        >
          {roleOptions.map((opt) => (
            <option key={opt} value={opt}>{opt === '全部' ? '全部角色' : opt}</option>
          ))}
        </select>
        <select
          value={filterDepartment}
          onChange={(e) => setFilterDepartment(e.target.value)}
          className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
        >
          {departmentOptions.map((opt) => (
            <option key={opt} value={opt}>{opt === '全部' ? '全部部门' : opt}</option>
          ))}
        </select>
        <select
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value)}
          className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
        >
          {statusOptions.map((opt) => (
            <option key={opt} value={opt}>{opt === '全部' ? '全部状态' : opt}</option>
          ))}
        </select>
        <span className="text-sm text-slate-400">共 {filteredData.length} 人</span>
      </div>

      {/* Table */}
      {filteredData.length === 0 ? (
        <ErpEmptyState title="暂无用户" description="暂无匹配的用户，请调整筛选条件或新增用户" />
      ) : (
        <div className="bg-white rounded-lg border overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-slate-50">
                <th className="text-left px-4 py-3 font-medium text-slate-600">姓名</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">邮箱</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">角色</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">部门</th>
                <th className="text-center px-4 py-3 font-medium text-slate-600">状态</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">最后登录时间</th>
                <th className="text-center px-4 py-3 font-medium text-slate-600">操作</th>
              </tr>
            </thead>
            <tbody>
              {filteredData.map((item) => (
                <tr key={item.id} className="border-b hover:bg-slate-50 transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <div className="w-8 h-8 rounded-full bg-indigo-100 text-indigo-700 flex items-center justify-center text-sm font-medium">
                        {(item.name || '?').charAt(0)}
                      </div>
                      <span className="font-medium text-slate-900">{item.name}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-slate-600">{item.email}</td>
                  <td className="px-4 py-3">
                    <RoleBadge role={item.role} />
                  </td>
                  <td className="px-4 py-3 text-slate-600">{item.department}</td>
                  <td className="px-4 py-3 text-center">
                    <ErpStatusBadge status={item.status === 'active' ? 'active' : 'inactive'} />
                  </td>
                  <td className="px-4 py-3 text-slate-600 text-sm">{item.lastLogin}</td>
                  <td className="px-4 py-3 text-center">
                    <div className="flex items-center justify-center gap-2">
                      <button onClick={() => openEdit(item)} className="text-sm text-indigo-600 hover:text-indigo-800 transition-colors">
                        编辑
                      </button>
                      <span className="text-slate-300">|</span>
                      <button onClick={() => toggleStatus(item)} className="text-sm text-slate-600 hover:text-slate-800 transition-colors">
                        {item.status === 'active' ? '停用' : '启用'}
                      </button>
                      <span className="text-slate-300">|</span>
                      <button onClick={() => handleReset(item)} className="text-sm text-amber-600 hover:text-amber-800 transition-colors">
                        重置密码
                      </button>
                      <span className="text-slate-300">|</span>
                      <button onClick={() => openAssign(item)} className="text-sm text-emerald-600 hover:text-emerald-800 transition-colors">
                        分配店铺
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Add user modal */}
      <Dialog open={addOpen} onOpenChange={setAddOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>新增用户</DialogTitle>
          </DialogHeader>
          <div className="space-y-3 py-2">
            <div>
              <Label>姓名</Label>
              <Input
                value={addForm.name}
                onChange={(e) => { setAddForm({ ...addForm, name: e.target.value }); if (addErrors.name) setAddErrors({ ...addErrors, name: undefined }); }}
                placeholder="请输入姓名"
              />
              {addErrors.name && <p className="text-sm text-red-600 mt-1">{addErrors.name}</p>}
            </div>
            <div>
              <Label>邮箱</Label>
              <Input
                type="email"
                value={addForm.email}
                onChange={(e) => { setAddForm({ ...addForm, email: e.target.value }); if (addErrors.email) setAddErrors({ ...addErrors, email: undefined }); }}
                placeholder="name@example.com"
              />
              {addErrors.email && <p className="text-sm text-red-600 mt-1">{addErrors.email}</p>}
            </div>
            <div>
              <Label>初始密码</Label>
              <Input
                value={addForm.password}
                onChange={(e) => setAddForm({ ...addForm, password: e.target.value })}
              />
            </div>
            {actionMsg && <p className="text-sm text-red-600">{actionMsg}</p>}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAddOpen(false)}>取消</Button>
            <Button onClick={saveAdd} disabled={saving}>{saving ? '保存中...' : '创建'}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Edit user modal */}
      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>编辑用户</DialogTitle>
          </DialogHeader>
          <div className="space-y-3 py-2">
            <div>
              <Label>姓名</Label>
              <Input value={editForm.name} onChange={(e) => setEditForm({ ...editForm, name: e.target.value })} />
            </div>
            <div>
              <Label>手机号</Label>
              <Input value={editForm.phone} onChange={(e) => setEditForm({ ...editForm, phone: e.target.value })} />
            </div>
            <div>
              <Label>状态</Label>
              <select
                value={editForm.status}
                onChange={(e) => setEditForm({ ...editForm, status: e.target.value })}
                className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg bg-white"
              >
                <option value="active">正常</option>
                <option value="disabled">停用</option>
              </select>
            </div>
            {actionMsg && <p className="text-sm text-red-600">{actionMsg}</p>}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditOpen(false)}>取消</Button>
            <Button onClick={saveEdit} disabled={saving}>{saving ? '保存中...' : '保存'}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Assign stores modal */}
      <Dialog open={assignOpen} onOpenChange={setAssignOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>分配店铺 — {assignUser?.name || assignUser?.email}</DialogTitle>
          </DialogHeader>
          <div className="py-2">
            <p className="text-sm text-slate-500 mb-3">勾选该用户可访问的店铺。运营只能看到分配给他的店铺。</p>
            {assignLoading ? (
              <p className="text-sm text-slate-400 py-6 text-center">加载中...</p>
            ) : allStores.length === 0 ? (
              <p className="text-sm text-slate-400 py-6 text-center">暂无店铺，请先在「店铺管理」创建店铺。</p>
            ) : (
              <div className="max-h-64 overflow-y-auto space-y-1.5">
                {allStores.map((s) => (
                  <label key={s.id} className="flex items-center gap-2.5 px-3 py-2 rounded-lg border border-slate-100 hover:bg-slate-50 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={selectedStoreIds.includes(s.id)}
                      onChange={() => toggleStore(s.id)}
                      className="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                    />
                    <span className="text-sm text-slate-800">{s.name}</span>
                    <span className="text-xs text-slate-400 ml-auto">{s.marketplaceName || s.marketplaceCode || ''}</span>
                  </label>
                ))}
              </div>
            )}
            {actionMsg && <p className="text-sm text-red-600 mt-2">{actionMsg}</p>}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAssignOpen(false)}>取消</Button>
            <Button onClick={saveAssign} disabled={saving || assignLoading}>{saving ? '保存中...' : '保存分配'}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
