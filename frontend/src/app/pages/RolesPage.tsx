import { useState, useMemo } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authFetch } from '../lib/auth';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { Plus, Shield, CheckCircle2, AlertCircle, Loader2 } from 'lucide-react';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { Button } from '../components/ui/button';
import { Checkbox } from '../components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/ui/dialog';

interface Role {
  id: string;
  name: string;
  description: string;
  permissionCount: number;
  userCount: number;
  status: string;
}

interface PermissionItem {
  id: string;
  code: string;
  name: string;
  module: string;
  action: string;
  description: string;
}

export function RolesPage() {
  const queryClient = useQueryClient();
  const rolesKey = ['roles'] as const;
  const rolesQuery = useApiQuery<Role[]>(
    rolesKey,
    async () => {
      const res = await authFetch('/api/roles');
      const json = await res.json();
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data?.items ?? json.data?.records ?? (Array.isArray(json.data) ? json.data : []);
    },
  );
  const data = rolesQuery.data ?? [];
  const loading = rolesQuery.isLoading;
  const error = rolesQuery.isError ? rolesQuery.error?.message ?? '加载失败' : null;
  const fetchData = () => rolesQuery.refetch();

  // Permission editor state
  const [editorRole, setEditorRole] = useState<Role | null>(null);
  const [permissions, setPermissions] = useState<PermissionItem[]>([]);
  const [assigned, setAssigned] = useState<Set<string>>(new Set());
  const [editorLoading, setEditorLoading] = useState(false);
  const [editorError, setEditorError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveSuccess, setSaveSuccess] = useState<string | null>(null);

  const openEditor = async (role: Role) => {
    setEditorRole(role);
    setEditorError(null);
    setSaveError(null);
    setSaveSuccess(null);
    setPermissions([]);
    setAssigned(new Set());
    setEditorLoading(true);
    try {
      const res = await authFetch(`/api/roles/${role.id}/permissions`);
      const json = await res.json();
      if (!json.success) {
        throw new Error(json.error?.message || '加载权限失败');
      }
      const all: PermissionItem[] = json.data?.permissions ?? [];
      const assignedIds: string[] = json.data?.assigned ?? [];
      setPermissions(all);
      setAssigned(new Set(assignedIds));
    } catch (e: any) {
      setEditorError(e?.message || '加载权限失败');
    } finally {
      setEditorLoading(false);
    }
  };

  const closeEditor = () => {
    if (saving) return;
    setEditorRole(null);
  };

  const togglePermission = (permId: string) => {
    setSaveSuccess(null);
    setAssigned((prev) => {
      const next = new Set(prev);
      if (next.has(permId)) {
        next.delete(permId);
      } else {
        next.add(permId);
      }
      return next;
    });
  };

  const savePermissions = async () => {
    if (!editorRole) return;
    setSaving(true);
    setSaveError(null);
    setSaveSuccess(null);
    try {
      const ids = Array.from(assigned);
      const res = await authFetch(`/api/roles/${editorRole.id}/permissions`, {
        method: 'POST',
        body: JSON.stringify(ids),
      });
      if (!res.ok) {
        let message = `保存失败 (${res.status})`;
        try {
          const json = await res.json();
          message = json?.error?.message || message;
        } catch {
          /* non-JSON / empty body */
        }
        throw new Error(message);
      }
      // Success: confirm and reflect the updated assignment count in the list.
      setSaveSuccess(`已保存，当前已分配 ${ids.length} 项权限`);
      queryClient.setQueryData<Role[]>(rolesKey, (prev) =>
        (prev ?? []).map((r) =>
          r.id === editorRole.id ? { ...r, permissionCount: ids.length } : r,
        ),
      );
      setEditorRole((prev) => (prev ? { ...prev, permissionCount: ids.length } : prev));
    } catch (e: any) {
      setSaveError(e?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  // Group permissions by module for a readable selector.
  const groupedPermissions = useMemo(() => {
    const groups: Record<string, PermissionItem[]> = {};
    for (const p of permissions) {
      const key = p.module || '其他';
      (groups[key] ??= []).push(p);
    }
    return Object.entries(groups).sort(([a], [b]) => a.localeCompare(b));
  }, [permissions]);

  const totalPermissions = data.reduce((sum, r) => sum + r.permissionCount, 0);
  const totalUsers = data.reduce((sum, r) => sum + r.userCount, 0);

  if (loading) return <ErpLoadingSkeleton />;
  if (error) return <ErpErrorState message={error} onRetry={fetchData} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="角色管理"
        description={`共 ${data.length} 个角色，${totalPermissions} 项权限，${totalUsers} 个关联用户`}
        actions={
          <Button className="inline-flex items-center gap-2">
            <Plus size={16} />
            新增角色
          </Button>
        }
      />

      {/* Table */}
      {data.length === 0 ? (
        <ErpEmptyState title="暂无角色" description="暂无角色配置，请新增角色" />
      ) : (
        <div className="bg-white rounded-lg border overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-slate-50">
                <th className="text-left px-4 py-3 font-medium text-slate-600">角色名称</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">描述</th>
                <th className="text-right px-4 py-3 font-medium text-slate-600">权限数量</th>
                <th className="text-right px-4 py-3 font-medium text-slate-600">关联用户数</th>
                <th className="text-center px-4 py-3 font-medium text-slate-600">状态</th>
                <th className="text-center px-4 py-3 font-medium text-slate-600">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.map((item) => (
                <tr key={item.id} className="border-b hover:bg-slate-50 transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <div className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 flex items-center justify-center">
                        <Shield size={16} />
                      </div>
                      <span className="font-medium text-slate-900">{item.name}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-slate-600">{item.description}</td>
                  <td className="px-4 py-3 text-right">
                    <span className="inline-flex items-center justify-center min-w-[2.5rem] px-2 py-0.5 rounded bg-slate-100 text-slate-700 text-xs font-medium">
                      {item.permissionCount}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <span className="inline-flex items-center justify-center min-w-[2.5rem] px-2 py-0.5 rounded bg-blue-50 text-blue-700 text-xs font-medium">
                      {item.userCount}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-center">
                    <ErpStatusBadge status={item.status === 'active' ? 'active' : 'inactive'} />
                  </td>
                  <td className="px-4 py-3 text-center">
                    <div className="flex items-center justify-center gap-2">
                      <button
                        onClick={() => openEditor(item)}
                        className="text-sm text-indigo-600 hover:text-indigo-800 transition-colors"
                      >
                        编辑权限
                      </button>
                      <span className="text-slate-300">|</span>
                      <button
                        onClick={() => openEditor(item)}
                        className="text-sm text-slate-600 hover:text-slate-800 transition-colors"
                      >
                        查看权限
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Permission editor dialog */}
      <Dialog open={!!editorRole} onOpenChange={(open) => { if (!open) closeEditor(); }}>
        <DialogContent className="sm:max-w-2xl max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>编辑权限{editorRole ? ` · ${editorRole.name}` : ''}</DialogTitle>
            <DialogDescription>
              勾选要分配给该角色的权限，保存后立即生效。
            </DialogDescription>
          </DialogHeader>

          {editorLoading ? (
            <div className="flex items-center justify-center py-10 text-slate-500">
              <Loader2 className="animate-spin mr-2" size={18} />
              正在加载权限…
            </div>
          ) : editorError ? (
            <div className="py-6">
              <ErpErrorState
                message={editorError}
                onRetry={() => editorRole && openEditor(editorRole)}
              />
            </div>
          ) : permissions.length === 0 ? (
            <ErpEmptyState title="暂无权限" description="系统未定义任何权限" />
          ) : (
            <div className="space-y-5">
              {groupedPermissions.map(([moduleName, perms]) => (
                <div key={moduleName}>
                  <div className="text-xs font-semibold uppercase tracking-wide text-slate-500 mb-2">
                    {moduleName}
                  </div>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                    {perms.map((p) => {
                      const checked = assigned.has(p.id);
                      return (
                        <label
                          key={p.id}
                          className="flex items-start gap-2 rounded-md border p-2 cursor-pointer hover:bg-slate-50 transition-colors"
                        >
                          <Checkbox
                            checked={checked}
                            onCheckedChange={() => togglePermission(p.id)}
                            className="mt-0.5"
                          />
                          <div className="min-w-0">
                            <div className="text-sm font-medium text-slate-900 truncate">
                              {p.name || p.code}
                            </div>
                            <div className="text-xs text-slate-500 truncate">{p.code}</div>
                          </div>
                        </label>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
          )}

          {saveSuccess && (
            <div className="flex items-center gap-2 rounded-md bg-emerald-50 text-emerald-700 px-3 py-2 text-sm">
              <CheckCircle2 size={16} />
              {saveSuccess}
            </div>
          )}
          {saveError && (
            <div className="flex items-center gap-2 rounded-md bg-red-50 text-red-700 px-3 py-2 text-sm">
              <AlertCircle size={16} />
              {saveError}
            </div>
          )}

          <DialogFooter>
            <Button variant="outline" onClick={closeEditor} disabled={saving}>
              关闭
            </Button>
            <Button
              onClick={savePermissions}
              disabled={saving || editorLoading || !!editorError}
              className="inline-flex items-center gap-2"
            >
              {saving && <Loader2 className="animate-spin" size={16} />}
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
