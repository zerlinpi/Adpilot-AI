import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authFetch } from '../lib/auth';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { Plus, Filter, Search, X, Building2, Globe, Store, User } from 'lucide-react';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { Button } from '../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '../components/ui/dialog';
import { Label } from '../components/ui/label';

interface DataScope {
  id: string;
  roleName: string;
  scopeType: string;
  shops: string[];
  sites: string[];
  departments: string[];
}

const scopeTypes = ['全公司', '指定部门', '指定店铺', '指定站点', '指定类目', '仅自己'];

const scopeTypeColors: Record<string, { bg: string; text: string }> = {
  '全公司': { bg: 'bg-emerald-50', text: 'text-emerald-700' },
  '指定部门': { bg: 'bg-blue-50', text: 'text-blue-700' },
  '指定店铺': { bg: 'bg-purple-50', text: 'text-purple-700' },
  '指定站点': { bg: 'bg-cyan-50', text: 'text-cyan-700' },
  '指定类目': { bg: 'bg-amber-50', text: 'text-amber-700' },
  '仅自己': { bg: 'bg-slate-100', text: 'text-slate-600' },
};

const allShops = ['美国旗舰店', '欧洲精品店', '日本精品店', '澳洲精品店', '加拿大精品店', '中东精品店'];
const allSites = ['US', 'UK', 'DE', 'FR', 'IT', 'ES', 'JP', 'AU', 'CA', 'AE', 'SA'];
const allDepartments = ['运营部', '广告部', '供应链部', '仓储部', '财务部', '客服部', '商品部', '数据部', '技术部'];

function ScopeTypeBadge({ type }: { type: string }) {
  const c = scopeTypeColors[type] || { bg: 'bg-slate-100', text: 'text-slate-600' };
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${c.bg} ${c.text}`}>
      {type}
    </span>
  );
}

function TagList({ items, max = 3 }: { items: string[]; max?: number }) {
  if (items.length === 0) return <span className="text-slate-400 text-xs">-</span>;
  const shown = items.slice(0, max);
  const remaining = items.length - max;
  return (
    <div className="flex items-center gap-1 flex-wrap">
      {shown.map((item) => (
        <span key={item} className="inline-flex items-center px-1.5 py-0.5 rounded bg-slate-100 text-xs text-slate-600">
          {item}
        </span>
      ))}
      {remaining > 0 && (
        <span className="inline-flex items-center px-1.5 py-0.5 rounded bg-blue-50 text-xs text-blue-600">
          +{remaining}
        </span>
      )}
    </div>
  );
}

export function DataScopesPage() {
  const queryClient = useQueryClient();
  const scopesKey = ['data-scopes'] as const;
  const scopesQuery = useApiQuery<DataScope[]>(
    scopesKey,
    async () => {
      const res = await authFetch('/api/data-scopes');
      const json = await res.json();
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data?.items ?? json.data?.records ?? (Array.isArray(json.data) ? json.data : []);
    },
  );
  const data = scopesQuery.data ?? [];
  const loading = scopesQuery.isLoading;
  const error = scopesQuery.isError ? scopesQuery.error?.message ?? '加载失败' : null;
  const fetchData = () => scopesQuery.refetch();

  const [filterScopeType, setFilterScopeType] = useState('全部');
  const [searchQuery, setSearchQuery] = useState('');
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editItem, setEditItem] = useState<DataScope | null>(null);

  // Edit form state
  const [formScopeType, setFormScopeType] = useState('全公司');
  const [formShops, setFormShops] = useState<string[]>([]);
  const [formSites, setFormSites] = useState<string[]>([]);
  const [formDepartments, setFormDepartments] = useState<string[]>([]);

  const filteredData = data.filter((item) => {
    if (filterScopeType !== '全部' && item.scopeType !== filterScopeType) return false;
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      if (!item.roleName.toLowerCase().includes(q)) return false;
    }
    return true;
  });

  const handleOpenEdit = (item?: DataScope) => {
    if (item) {
      setEditItem(item);
      setFormScopeType(item.scopeType);
      setFormShops([...item.shops]);
      setFormSites([...item.sites]);
      setFormDepartments([...item.departments]);
    } else {
      setEditItem(null);
      setFormScopeType('全公司');
      setFormShops([]);
      setFormSites([]);
      setFormDepartments([]);
    }
    setDialogOpen(true);
  };

  const handleToggleItem = (list: string[], setList: (v: string[]) => void, item: string) => {
    if (list.includes(item)) {
      setList(list.filter((i) => i !== item));
    } else {
      setList([...list, item]);
    }
  };

  const handleSave = () => {
    // In real app, would POST to API
    if (editItem) {
      queryClient.setQueryData<DataScope[]>(scopesKey, (prev) =>
        (prev ?? []).map((d) =>
          d.id === editItem.id
            ? { ...d, scopeType: formScopeType, shops: formShops, sites: formSites, departments: formDepartments }
            : d
        )
      );
    }
    setDialogOpen(false);
  };

  if (loading) return <ErpLoadingSkeleton />;
  if (error) return <ErpErrorState message={error} onRetry={fetchData} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="数据权限"
        description={`共 ${data.length} 个角色的数据范围配置`}
        actions={
          <Button className="inline-flex items-center gap-2" onClick={() => handleOpenEdit()}>
            <Plus size={16} />
            新增数据权限
          </Button>
        }
      />

      {/* Filters */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            placeholder="搜索角色名称..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9 pr-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 w-56"
          />
        </div>
        <div className="flex items-center gap-1.5 text-sm text-slate-500">
          <Filter size={14} />
        </div>
        <select
          value={filterScopeType}
          onChange={(e) => setFilterScopeType(e.target.value)}
          className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
        >
          <option value="全部">全部范围类型</option>
          {scopeTypes.map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>
        <span className="text-sm text-slate-400">共 {filteredData.length} 条</span>
      </div>

      {/* Table */}
      {filteredData.length === 0 ? (
        <ErpEmptyState title="暂无数据权限" description="暂无匹配的数据权限配置，请调整筛选条件或新增" />
      ) : (
        <div className="bg-white rounded-lg border overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-slate-50">
                <th className="text-left px-4 py-3 font-medium text-slate-600">角色名称</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">数据范围类型</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">关联店铺</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">关联站点</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">关联部门</th>
                <th className="text-center px-4 py-3 font-medium text-slate-600">操作</th>
              </tr>
            </thead>
            <tbody>
              {filteredData.map((item) => (
                <tr key={item.id} className="border-b hover:bg-slate-50 transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <div className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 flex items-center justify-center">
                        <User size={16} />
                      </div>
                      <span className="font-medium text-slate-900">{item.roleName}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <ScopeTypeBadge type={item.scopeType} />
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1">
                      <Store size={12} className="text-slate-400 shrink-0" />
                      <TagList items={item.shops} />
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1">
                      <Globe size={12} className="text-slate-400 shrink-0" />
                      <TagList items={item.sites} />
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1">
                      <Building2 size={12} className="text-slate-400 shrink-0" />
                      <TagList items={item.departments} />
                    </div>
                  </td>
                  <td className="px-4 py-3 text-center">
                    <button
                      className="text-sm text-indigo-600 hover:text-indigo-800 transition-colors"
                      onClick={() => handleOpenEdit(item)}
                    >
                      编辑
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Edit Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editItem ? '编辑数据权限' : '新增数据权限'}</DialogTitle>
            <DialogDescription>
              {editItem ? `配置角色「${editItem.roleName}」的数据访问范围` : '为角色配置数据访问范围'}
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            {/* Scope Type */}
            <div className="space-y-2">
              <Label>数据范围类型</Label>
              <select
                value={formScopeType}
                onChange={(e) => setFormScopeType(e.target.value)}
                className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                {scopeTypes.map((t) => (
                  <option key={t} value={t}>{t}</option>
                ))}
              </select>
            </div>

            {/* Shops */}
            {formScopeType === '指定店铺' && (
              <div className="space-y-2">
                <Label>关联店铺</Label>
                <div className="flex flex-wrap gap-2">
                  {allShops.map((shop) => (
                    <button
                      key={shop}
                      type="button"
                      onClick={() => handleToggleItem(formShops, setFormShops, shop)}
                      className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${formShops.includes(shop)
                        ? 'bg-indigo-50 border-indigo-200 text-indigo-700'
                        : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-50'
                        }`}
                    >
                      {shop}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* Sites */}
            {(formScopeType === '指定站点' || formScopeType === '指定店铺') && (
              <div className="space-y-2">
                <Label>关联站点</Label>
                <div className="flex flex-wrap gap-2">
                  {allSites.map((site) => (
                    <button
                      key={site}
                      type="button"
                      onClick={() => handleToggleItem(formSites, setFormSites, site)}
                      className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${formSites.includes(site)
                        ? 'bg-indigo-50 border-indigo-200 text-indigo-700'
                        : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-50'
                        }`}
                    >
                      {site}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* Departments */}
            {(formScopeType === '指定部门' || formScopeType === '指定店铺' || formScopeType === '指定站点') && (
              <div className="space-y-2">
                <Label>关联部门</Label>
                <div className="flex flex-wrap gap-2">
                  {allDepartments.map((dept) => (
                    <button
                      key={dept}
                      type="button"
                      onClick={() => handleToggleItem(formDepartments, setFormDepartments, dept)}
                      className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${formDepartments.includes(dept)
                        ? 'bg-indigo-50 border-indigo-200 text-indigo-700'
                        : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-50'
                        }`}
                    >
                      {dept}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>取消</Button>
            <Button onClick={handleSave}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
