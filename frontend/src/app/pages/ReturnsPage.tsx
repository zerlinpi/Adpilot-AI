import { useState } from 'react';
import { authFetch } from '../lib/auth';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { Search, RefreshCw, Eye } from 'lucide-react';
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
  { value: 'approved', label: '已审批' },
  { value: 'rejected', label: '已拒绝' },
  { value: 'completed', label: '已完成' },
];

export function ReturnsPage() {
  const { storeId } = useStoreId();
  const [statusFilter, setStatusFilter] = useState('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedReturn, setSelectedReturn] = useState<any | null>(null);

  const returnsQuery = useApiQuery<any[]>(
    ['returns', storeId, { statusFilter, searchQuery }],
    async () => {
      const query = new URLSearchParams();
      if (storeId) query.set('storeId', storeId);
      if (statusFilter !== 'all') query.set('status', statusFilter);
      if (searchQuery) query.set('search', searchQuery);
      const qs = query.toString();
      let json: any;
      try {
        const res = await authFetch(`/api/returns${qs ? `?${qs}` : ''}`);
        json = await res.json();
      } catch {
        throw new Error('网络错误');
      }
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data?.items ?? json.data?.records ?? (Array.isArray(json.data) ? json.data : []);
    },
  );
  const returns = returnsQuery.data ?? [];
  const loading = returnsQuery.isLoading;
  const error = returnsQuery.isError ? returnsQuery.error?.message ?? '加载失败' : null;
  const fetchReturns = () => returnsQuery.refetch();

  if (loading) return <div className="space-y-4"><ErpPageHeader title="退货管理" description="管理所有退货记录" /><ErpLoadingSkeleton rows={8} /></div>;
  if (error) return <ErpErrorState message={error} onRetry={fetchReturns} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="退货管理"
        description={`共 ${returns.length} 条退货记录`}
        actions={
          <button onClick={fetchReturns} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
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
            placeholder="搜索订单号/SKU..."
            className="text-xs bg-transparent outline-none w-36 placeholder-slate-400"
          />
        </div>
      </div>

      {/* Table */}
      {returns.length === 0 ? (
        <ErpEmptyState title="暂无退货数据" description="没有匹配的退货记录" />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50">
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">退货ID</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">订单号</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">SKU</th>
                  <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500">数量</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">退货原因</th>
                  <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500">状态</th>
                  <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500">退款金额</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">退货日期</th>
                  <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500">操作</th>
                </tr>
              </thead>
              <tbody>
                {returns.map((item: any) => (
                  <tr key={item.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-3 text-sm font-mono font-medium text-slate-900">{item.returnId || item.id || '-'}</td>
                    <td className="px-4 py-3 text-xs font-mono text-slate-600">{item.orderId || item.amazonOrderId || '-'}</td>
                    <td className="px-4 py-3 text-xs font-mono text-slate-600">{item.sku || '-'}</td>
                    <td className="px-4 py-3 text-sm text-right text-slate-700">{item.quantity ?? '-'}</td>
                    <td className="px-4 py-3 text-sm text-slate-600 max-w-[180px] truncate">{item.reason || item.returnReason || '-'}</td>
                    <td className="px-4 py-3 text-center"><ErpStatusBadge status={item.status || 'pending'} /></td>
                    <td className="px-4 py-3 text-sm text-right font-medium text-slate-900">{item.refundAmount != null ? formatCurrency(item.refundAmount) : '-'}</td>
                    <td className="px-4 py-3 text-sm text-slate-600">{formatDate(item.returnDate || item.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => setSelectedReturn(item)}
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
      {selectedReturn && (
        <Sheet open onOpenChange={(o) => { if (!o) setSelectedReturn(null); }}>
          <SheetContent side="right" className="w-full max-w-md sm:max-w-md gap-0 p-0 bg-white border-l border-slate-200 overflow-y-auto">
            <div className="sticky top-0 bg-white border-b border-slate-100 px-5 py-4 flex items-center justify-between">
              <SheetTitle className="text-base font-semibold text-slate-900">退货详情</SheetTitle>
            </div>
            <div className="p-5 space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-slate-500 mb-1">退货ID</p>
                  <p className="text-sm font-mono font-medium text-slate-900">{selectedReturn.returnId || selectedReturn.id || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">状态</p>
                  <ErpStatusBadge status={selectedReturn.status || 'pending'} />
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">订单号</p>
                  <p className="text-sm font-mono text-slate-700">{selectedReturn.orderId || selectedReturn.amazonOrderId || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">SKU</p>
                  <p className="text-sm font-mono text-slate-700">{selectedReturn.sku || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">数量</p>
                  <p className="text-sm text-slate-900">{selectedReturn.quantity ?? '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">退款金额</p>
                  <p className="text-sm font-medium text-slate-900">{selectedReturn.refundAmount != null ? formatCurrency(selectedReturn.refundAmount) : '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">退货日期</p>
                  <p className="text-sm text-slate-700">{formatDate(selectedReturn.returnDate || selectedReturn.createdAt)}</p>
                </div>
              </div>
              {(selectedReturn.reason || selectedReturn.returnReason) && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">退货原因</p>
                  <p className="text-sm text-slate-700">{selectedReturn.reason || selectedReturn.returnReason}</p>
                </div>
              )}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
