import { useState } from 'react';
import { authFetch } from '../lib/auth';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { Search, RefreshCw, Eye, DollarSign } from 'lucide-react';
import { cn, formatCurrency, formatDate } from '../lib/utils';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { Sheet, SheetContent, SheetTitle } from '../components/ui/sheet';

const statusOptions = [
  { value: 'all', label: '全部' },
  { value: 'pending', label: '待处理' },
  { value: 'completed', label: '已完成' },
  { value: 'in_progress', label: '处理中' },
];

export function SettlementsPage() {
  const { storeId } = useStoreId();
  const [statusFilter, setStatusFilter] = useState('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedSettlement, setSelectedSettlement] = useState<any | null>(null);

  const settlementsQuery = useApiQuery<any[]>(
    ['settlements', storeId, { statusFilter, searchQuery }],
    async () => {
      const query = new URLSearchParams();
      if (storeId) query.set('storeId', storeId);
      if (statusFilter !== 'all') query.set('status', statusFilter);
      if (searchQuery) query.set('search', searchQuery);
      const qs = query.toString();
      let json: any;
      try {
        const res = await authFetch(`/api/settlements${qs ? `?${qs}` : ''}`);
        json = await res.json();
      } catch {
        throw new Error('网络错误');
      }
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data?.items ?? json.data?.records ?? (Array.isArray(json.data) ? json.data : []);
    },
  );
  const settlements = settlementsQuery.data ?? [];
  const loading = settlementsQuery.isLoading;
  const error = settlementsQuery.isError ? settlementsQuery.error?.message ?? '加载失败' : null;
  const fetchSettlements = () => settlementsQuery.refetch();

  if (loading) return <div className="space-y-4"><ErpPageHeader title="结算管理" description="管理所有结算记录" /><ErpLoadingSkeleton rows={8} /></div>;
  if (error) return <ErpErrorState message={error} onRetry={fetchSettlements} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="结算管理"
        description={`共 ${settlements.length} 条结算记录`}
        actions={
          <button onClick={fetchSettlements} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
            <RefreshCw size={14} /> 刷新
          </button>
        }
      />

      {/* Filters */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-0.5">
          {statusOptions.map((s) => (
            <button
              key={s.value}
              onClick={() => setStatusFilter(s.value)}
              className={cn(
                'px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                statusFilter === s.value ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700',
              )}
            >
              {s.label}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 rounded-lg px-2 py-1">
          <Search size={14} className="text-slate-400" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="搜索结算ID..."
            className="text-xs bg-transparent outline-none w-32 placeholder-slate-400"
          />
        </div>
      </div>

      {/* Table */}
      {settlements.length === 0 ? (
        <ErpEmptyState title="暂无结算数据" description="没有匹配的结算记录" icon={<DollarSign size={24} className="text-slate-400" />} />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50">
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">结算ID</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">开始日期</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">结束日期</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">到账日期</th>
                  <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500">总金额</th>
                  <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500">币种</th>
                  <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500">状态</th>
                  <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500">操作</th>
                </tr>
              </thead>
              <tbody>
                {settlements.map((item: any) => (
                  <tr key={item.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-3 text-sm font-mono font-medium text-slate-900">{item.settlementId || item.id || '-'}</td>
                    <td className="px-4 py-3 text-sm text-slate-600">{formatDate(item.startDate || item.periodStart)}</td>
                    <td className="px-4 py-3 text-sm text-slate-600">{formatDate(item.endDate || item.periodEnd)}</td>
                    <td className="px-4 py-3 text-sm text-slate-600">{formatDate(item.depositDate || item.payoutDate)}</td>
                    <td className="px-4 py-3 text-sm text-right font-medium text-slate-900">
                      {item.totalAmount != null ? formatCurrency(item.totalAmount, item.currency || 'USD') : '-'}
                    </td>
                    <td className="px-4 py-3 text-center">
                      <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-slate-100 text-slate-700">
                        {item.currency || 'USD'}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-center"><ErpStatusBadge status={item.status || 'pending'} /></td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => setSelectedSettlement(item)}
                        className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 bg-slate-50 rounded hover:bg-slate-100 transition-colors ml-auto"
                      >
                        <Eye size={11} /> 详情
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Detail Drawer */}
      {selectedSettlement && (
        <Sheet open onOpenChange={(o) => { if (!o) setSelectedSettlement(null); }}>
          <SheetContent side="right" className="w-full max-w-md sm:max-w-md gap-0 p-0 bg-white border-l border-slate-200 overflow-y-auto">
            <div className="sticky top-0 bg-white border-b border-slate-100 px-5 py-4 flex items-center justify-between">
              <SheetTitle className="text-base font-semibold text-slate-900">结算详情</SheetTitle>
            </div>
            <div className="p-5 space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-slate-500 mb-1">结算ID</p>
                  <p className="text-sm font-mono font-medium text-slate-900">{selectedSettlement.settlementId || selectedSettlement.id || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">状态</p>
                  <ErpStatusBadge status={selectedSettlement.status || 'pending'} />
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">开始日期</p>
                  <p className="text-sm text-slate-700">{formatDate(selectedSettlement.startDate || selectedSettlement.periodStart)}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">结束日期</p>
                  <p className="text-sm text-slate-700">{formatDate(selectedSettlement.endDate || selectedSettlement.periodEnd)}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">到账日期</p>
                  <p className="text-sm text-slate-700">{formatDate(selectedSettlement.depositDate || selectedSettlement.payoutDate)}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">币种</p>
                  <p className="text-sm text-slate-700">{selectedSettlement.currency || 'USD'}</p>
                </div>
              </div>
              <div>
                <p className="text-xs text-slate-500 mb-1">总金额</p>
                <p className="text-lg font-semibold text-slate-900">
                  {selectedSettlement.totalAmount != null ? formatCurrency(selectedSettlement.totalAmount, selectedSettlement.currency || 'USD') : '-'}
                </p>
              </div>
              {selectedSettlement.totalAmount != null && (
                <div className="bg-slate-50 rounded-lg p-4">
                  <div className="flex items-center gap-2 mb-2">
                    <DollarSign size={16} className="text-slate-400" />
                    <span className="text-xs font-medium text-slate-500">结算明细</span>
                  </div>
                  <div className="grid grid-cols-2 gap-2 text-xs">
                    <div>
                      <span className="text-slate-500">总金额:</span>
                      <span className="ml-1 font-medium text-slate-900">{formatCurrency(selectedSettlement.totalAmount, selectedSettlement.currency || 'USD')}</span>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
