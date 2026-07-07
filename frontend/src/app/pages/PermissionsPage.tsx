import { useState, useEffect, useMemo, useCallback, Fragment } from 'react';
import { Filter, Search, ChevronDown, ChevronRight, Key } from 'lucide-react';
import { fetchPermissions, type PermissionItem } from '../lib/api';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { Button } from '../components/ui/button';

/** Catalog request must complete within this window or surface an error (Req 4.6). */
const CATALOG_TIMEOUT_MS = 3000;

/**
 * Friendly Chinese label for each backend module key. The backend returns
 * English module keys (e.g. `dashboard`, `audit`); we render a readable heading
 * per distinct module value. Unknown keys fall back to the raw key so a heading
 * always matches the permission's module value (Req 4.4).
 */
const moduleLabelMap: Record<string, string> = {
  dashboard: '仪表盘',
  order: '订单管理',
  advertising: '广告管理',
  product: '商品管理',
  keyword: '关键词',
  finance: '财务管理',
  warehouse: '库存与仓库',
  procurement: '采购与供应链',
  customer: '客服与评价',
  review: '评价管理',
  approval: '审批管理',
  user: '用户管理',
  role: '角色管理',
  department: '部门管理',
  import: '数据导入',
  report: '报表中心',
  automation: '自动化',
  feishu: '飞书集成',
  audit: '审计日志',
};

/** Preferred display order for known modules; unknown modules sort after, alphabetically. */
const moduleOrder = Object.keys(moduleLabelMap);

const actionLabelMap: Record<string, string> = {
  view: '查看',
  create: '新增',
  update: '编辑',
  delete: '删除',
  import: '导入',
  export: '导出',
  approve: '审批',
  approve_low: '低风险审批',
  approve_medium: '中风险审批',
  approve_high: '高风险审批',
  reject: '拒绝',
  execute: '执行',
  apply: '应用',
  manage: '管理',
};

const moduleColors: Record<string, { bg: string; text: string }> = {
  dashboard: { bg: 'bg-violet-50', text: 'text-violet-700' },
  order: { bg: 'bg-blue-50', text: 'text-blue-700' },
  advertising: { bg: 'bg-cyan-50', text: 'text-cyan-700' },
  product: { bg: 'bg-emerald-50', text: 'text-emerald-700' },
  keyword: { bg: 'bg-lime-50', text: 'text-lime-700' },
  finance: { bg: 'bg-indigo-50', text: 'text-indigo-700' },
  warehouse: { bg: 'bg-orange-50', text: 'text-orange-700' },
  procurement: { bg: 'bg-amber-50', text: 'text-amber-700' },
  customer: { bg: 'bg-pink-50', text: 'text-pink-700' },
  review: { bg: 'bg-rose-50', text: 'text-rose-700' },
  approval: { bg: 'bg-teal-50', text: 'text-teal-700' },
  user: { bg: 'bg-sky-50', text: 'text-sky-700' },
  role: { bg: 'bg-fuchsia-50', text: 'text-fuchsia-700' },
  department: { bg: 'bg-purple-50', text: 'text-purple-700' },
  import: { bg: 'bg-cyan-50', text: 'text-cyan-700' },
  report: { bg: 'bg-blue-50', text: 'text-blue-700' },
  automation: { bg: 'bg-teal-50', text: 'text-teal-700' },
  feishu: { bg: 'bg-emerald-50', text: 'text-emerald-700' },
  audit: { bg: 'bg-slate-100', text: 'text-slate-700' },
};

function moduleLabel(module: string): string {
  return moduleLabelMap[module] || module;
}

function moduleColor(module: string) {
  return moduleColors[module] || { bg: 'bg-slate-100', text: 'text-slate-600' };
}

function ActionBadge({ action }: { action: string }) {
  const config: Record<string, { bg: string; text: string }> = {
    view: { bg: 'bg-blue-50', text: 'text-blue-700' },
    create: { bg: 'bg-emerald-50', text: 'text-emerald-700' },
    update: { bg: 'bg-amber-50', text: 'text-amber-700' },
    delete: { bg: 'bg-red-50', text: 'text-red-700' },
    import: { bg: 'bg-cyan-50', text: 'text-cyan-700' },
    export: { bg: 'bg-indigo-50', text: 'text-indigo-700' },
    approve: { bg: 'bg-teal-50', text: 'text-teal-700' },
    approve_low: { bg: 'bg-teal-50', text: 'text-teal-700' },
    approve_medium: { bg: 'bg-amber-50', text: 'text-amber-700' },
    approve_high: { bg: 'bg-orange-50', text: 'text-orange-700' },
    apply: { bg: 'bg-violet-50', text: 'text-violet-700' },
    manage: { bg: 'bg-pink-50', text: 'text-pink-700' },
  };
  const c = config[action] || { bg: 'bg-slate-100', text: 'text-slate-600' };
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${c.bg} ${c.text}`}>
      {actionLabelMap[action] || action}
    </span>
  );
}

export function PermissionsPage() {
  const [data, setData] = useState<PermissionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [filterModule, setFilterModule] = useState('全部');
  const [filterAction, setFilterAction] = useState('全部');
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedModules, setExpandedModules] = useState<Set<string>>(new Set());

  const loadData = useCallback(async () => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), CATALOG_TIMEOUT_MS);
    try {
      setLoading(true);
      setError(null);
      const items = await fetchPermissions({ signal: controller.signal });
      setData(items);
      // Expand every module group by default so the catalog is visible at a glance.
      setExpandedModules(new Set(items.map((p) => p.module)));
    } catch (e: any) {
      const aborted = e?.name === 'AbortError' || controller.signal.aborted;
      setError(aborted ? '权限目录加载超时，请重试' : e?.message || '权限目录加载失败');
      setData([]);
    } finally {
      clearTimeout(timer);
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Distinct modules present in the data, ordered by the known order then
  // alphabetically for anything unrecognized (Req 4.4).
  const presentModules = useMemo(() => {
    const set = new Set(data.map((p) => p.module));
    return Array.from(set).sort((a, b) => {
      const ia = moduleOrder.indexOf(a);
      const ib = moduleOrder.indexOf(b);
      if (ia !== -1 && ib !== -1) return ia - ib;
      if (ia !== -1) return -1;
      if (ib !== -1) return 1;
      return a.localeCompare(b);
    });
  }, [data]);

  // Distinct actions present, for the filter dropdown.
  const presentActions = useMemo(
    () => Array.from(new Set(data.map((p) => p.action))).sort(),
    [data],
  );

  const filteredData = useMemo(
    () =>
      data.filter((item) => {
        if (filterModule !== '全部' && item.module !== filterModule) return false;
        if (filterAction !== '全部' && item.action !== filterAction) return false;
        if (searchQuery) {
          const q = searchQuery.toLowerCase();
          const hay = `${item.code} ${item.name} ${item.description ?? ''}`.toLowerCase();
          if (!hay.includes(q)) return false;
        }
        return true;
      }),
    [data, filterModule, filterAction, searchQuery],
  );

  const groupedData = useMemo(() => {
    const groups: Record<string, PermissionItem[]> = {};
    for (const item of filteredData) {
      (groups[item.module] ||= []).push(item);
    }
    return groups;
  }, [filteredData]);

  const toggleModule = (module: string) => {
    setExpandedModules((prev) => {
      const next = new Set(prev);
      next.has(module) ? next.delete(module) : next.add(module);
      return next;
    });
  };

  const allExpanded = presentModules.length > 0 && expandedModules.size >= presentModules.length;

  const toggleAll = () => {
    setExpandedModules(allExpanded ? new Set() : new Set(presentModules));
  };

  if (loading) return <ErpLoadingSkeleton />;
  if (error) return <ErpErrorState message={error} onRetry={loadData} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="权限管理"
        description={`共 ${data.length} 项权限，涉及 ${presentModules.length} 个模块`}
        actions={
          data.length > 0 ? (
            <Button variant="outline" className="inline-flex items-center gap-2" onClick={toggleAll}>
              {allExpanded ? '全部折叠' : '全部展开'}
            </Button>
          ) : undefined
        }
      />

      {/* Empty state — backend returned an empty catalog (Req 4.5). */}
      {data.length === 0 ? (
        <ErpEmptyState title="暂无权限" description="系统尚未定义任何权限" />
      ) : (
        <>
          {/* Filters */}
          <div className="flex items-center gap-3 flex-wrap">
            <div className="relative">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                type="text"
                placeholder="搜索权限编码或名称..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-9 pr-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 w-56"
              />
            </div>
            <div className="flex items-center gap-1.5 text-sm text-slate-500">
              <Filter size={14} />
            </div>
            <select
              value={filterModule}
              onChange={(e) => setFilterModule(e.target.value)}
              className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="全部">全部模块</option>
              {presentModules.map((m) => (
                <option key={m} value={m}>{moduleLabel(m)}</option>
              ))}
            </select>
            <select
              value={filterAction}
              onChange={(e) => setFilterAction(e.target.value)}
              className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="全部">全部动作</option>
              {presentActions.map((a) => (
                <option key={a} value={a}>{actionLabelMap[a] || a}</option>
              ))}
            </select>
            <span className="text-sm text-slate-400">共 {filteredData.length} 项</span>
          </div>

          {/* Grouped catalog */}
          {filteredData.length === 0 ? (
            <ErpEmptyState title="无匹配权限" description="暂无匹配的权限记录，请调整筛选条件" />
          ) : (
            <div className="bg-white rounded-lg border overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b bg-slate-50">
                    <th className="text-left px-4 py-3 font-medium text-slate-600 w-[220px]">权限编码</th>
                    <th className="text-left px-4 py-3 font-medium text-slate-600">权限名称</th>
                    <th className="text-left px-4 py-3 font-medium text-slate-600">描述</th>
                    <th className="text-left px-4 py-3 font-medium text-slate-600 w-[120px]">动作</th>
                  </tr>
                </thead>
                <tbody>
                  {presentModules.map((module) => {
                    const items = groupedData[module];
                    if (!items || items.length === 0) return null;
                    const expanded = expandedModules.has(module);
                    const mc = moduleColor(module);

                    return (
                      <Fragment key={module}>
                        {/* Module group header (Req 4.4) */}
                        <tr
                          className="border-b bg-slate-50/70 cursor-pointer hover:bg-slate-100 transition-colors"
                          onClick={() => toggleModule(module)}
                        >
                          <td colSpan={4} className="px-4 py-2.5">
                            <div className="flex items-center gap-2">
                              {expanded ? (
                                <ChevronDown size={16} className="text-slate-400" />
                              ) : (
                                <ChevronRight size={16} className="text-slate-400" />
                              )}
                              <span className={`inline-flex items-center px-2.5 py-1 rounded text-xs font-semibold ${mc.bg} ${mc.text}`}>
                                {moduleLabel(module)}
                              </span>
                              <code className="text-[11px] text-slate-400 font-mono">{module}</code>
                              <span className="text-xs text-slate-400 ml-1">{items.length} 项权限</span>
                            </div>
                          </td>
                        </tr>
                        {/* Module rows — identifier + description (Req 4.2) */}
                        {expanded && items.map((item) => (
                          <tr key={item.id} className="border-b hover:bg-slate-50 transition-colors">
                            <td className="px-4 py-3">
                              <div className="flex items-center gap-2">
                                <Key size={14} className="text-slate-400" />
                                <code className="text-xs bg-slate-100 px-1.5 py-0.5 rounded font-mono text-slate-700">{item.code}</code>
                              </div>
                            </td>
                            <td className="px-4 py-3 text-slate-900 font-medium">{item.name}</td>
                            <td className="px-4 py-3 text-slate-500">
                              {item.description?.trim() ? item.description : <span className="text-slate-300">—</span>}
                            </td>
                            <td className="px-4 py-3">
                              <ActionBadge action={item.action} />
                            </td>
                          </tr>
                        ))}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}
