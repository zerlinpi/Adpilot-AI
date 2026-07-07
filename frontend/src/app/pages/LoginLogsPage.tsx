import { useState } from 'react';
import { authFetch } from '../lib/auth';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { Filter, Search, CheckCircle, XCircle, Monitor } from 'lucide-react';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';

interface LoginLog {
  id: string;
  loginTime: string;
  email: string;
  userName: string;
  status: 'success' | 'failed';
  ip: string;
  browser: string;
  failReason: string;
}

const statusOptions = ['全部', '成功', '失败'];

// Map the Chinese status labels to the backend's loginStatus values so the
// status filter is wired to GET /api/login-logs?status=... (Req 15.4).
const STATUS_PARAM: Record<string, string> = {
  '全部': '',
  '成功': 'success',
  '失败': 'failed',
};

/**
 * Build the query string for the login-logs request from the selected status
 * label. A specific status ("成功"/"失败") maps to the backend's loginStatus
 * value; "全部" (all) omits the status param so the backend returns every
 * record. Always requests a generous page size so the table and summary counts
 * reflect more than the default page. (Req 15.4)
 */
export function buildLoginLogsQuery(statusLabel: string): string {
  const params = new URLSearchParams();
  const statusParam = STATUS_PARAM[statusLabel] ?? '';
  if (statusParam) params.set('status', statusParam);
  params.set('pageSize', '200');
  return params.toString();
}

export function LoginLogsPage() {
  const [filterStatus, setFilterStatus] = useState('全部');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [searchQuery, setSearchQuery] = useState('');

  // Re-fetch from the backend whenever the status filter changes so the
  // server returns only the matching records (Req 15.4). `placeholderData`
  // keeps the previously loaded rows visible during the refetch, matching the
  // original behavior where the table was not blanked on filter change.
  const logsQuery = useApiQuery<LoginLog[]>(
    ['login-logs', filterStatus],
    async () => {
      const qs = buildLoginLogsQuery(filterStatus);
      const res = await authFetch(`/api/login-logs${qs ? `?${qs}` : ''}`);
      const json = await res.json();
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data?.items || json.data || [];
    },
    { placeholderData: (prev) => prev },
  );
  const data = logsQuery.data ?? [];
  const loading = logsQuery.isLoading;
  const error = logsQuery.isError ? logsQuery.error?.message ?? '加载失败' : null;
  const fetchData = () => logsQuery.refetch();

  // Status filtering is handled by the backend; date/search remain client-side.
  const filteredData = data.filter((item) => {
    if (dateFrom) {
      const from = new Date(dateFrom);
      const itemDate = new Date(item.loginTime);
      if (itemDate < from) return false;
    }
    if (dateTo) {
      const to = new Date(dateTo + 'T23:59:59');
      const itemDate = new Date(item.loginTime);
      if (itemDate > to) return false;
    }
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      if (!item.email.toLowerCase().includes(q) && !item.userName.toLowerCase().includes(q)) {
        return false;
      }
    }
    return true;
  });

  const successCount = data.filter((d) => d.status === 'success').length;
  const failCount = data.filter((d) => d.status === 'failed').length;

  if (loading && data.length === 0) return <ErpLoadingSkeleton />;
  if (error) return <ErpErrorState message={error} onRetry={fetchData} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="登录日志"
        description={`共 ${data.length} 条记录，成功 ${successCount} 次，失败 ${failCount} 次`}
      />

      {/* Filters */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            placeholder="搜索邮箱或姓名..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9 pr-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 w-56"
          />
        </div>
        <div className="flex items-center gap-1.5 text-sm text-slate-500">
          <Filter size={14} />
        </div>
        <select
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value)}
          className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
        >
          {statusOptions.map((opt) => (
            <option key={opt} value={opt}>{opt === '全部' ? '全部状态' : opt}</option>
          ))}
        </select>
        <div className="flex items-center gap-2">
          <input
            type="date"
            value={dateFrom}
            onChange={(e) => setDateFrom(e.target.value)}
            className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
            placeholder="开始日期"
          />
          <span className="text-slate-400 text-sm">至</span>
          <input
            type="date"
            value={dateTo}
            onChange={(e) => setDateTo(e.target.value)}
            className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
            placeholder="结束日期"
          />
        </div>
        <span className="text-sm text-slate-400">共 {filteredData.length} 条</span>
      </div>

      {/* Table */}
      {filteredData.length === 0 ? (
        <ErpEmptyState title="暂无登录日志" description="暂无匹配的登录记录，请调整筛选条件" />
      ) : (
        <div className="bg-white rounded-lg border overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-slate-50">
                <th className="text-left px-4 py-3 font-medium text-slate-600">登录时间</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">登录账号</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">用户姓名</th>
                <th className="text-center px-4 py-3 font-medium text-slate-600">登录状态</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">IP 地址</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">浏览器</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">失败原因</th>
              </tr>
            </thead>
            <tbody>
              {filteredData.map((item) => (
                <tr key={item.id} className="border-b hover:bg-slate-50 transition-colors">
                  <td className="px-4 py-3 text-slate-900 font-mono text-xs">{item.loginTime}</td>
                  <td className="px-4 py-3 text-slate-600">{item.email}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <div className="w-7 h-7 rounded-full bg-slate-100 text-slate-600 flex items-center justify-center text-xs font-medium">
                        {(item.userName || '?').charAt(0)}
                      </div>
                      <span className="text-slate-900">{item.userName}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-center">
                    {item.status === 'success' ? (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-emerald-50 text-emerald-700 text-xs font-medium">
                        <CheckCircle size={12} />
                        成功
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-red-50 text-red-700 text-xs font-medium">
                        <XCircle size={12} />
                        失败
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-slate-600 font-mono text-xs">{item.ip}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1.5 text-slate-600">
                      <Monitor size={12} className="text-slate-400" />
                      <span className="text-xs">{item.browser}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    {item.failReason ? (
                      <span className="text-xs text-red-600">{item.failReason}</span>
                    ) : (
                      <span className="text-xs text-slate-400">-</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
