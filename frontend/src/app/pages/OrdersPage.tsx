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
  { value: 'shipped', label: '已发货' },
  { value: 'delivered', label: '已送达' },
  { value: 'cancelled', label: '已取消' },
  { value: 'returned', label: '已退货' },
];

const channelOptions = [
  { value: 'all', label: '全部渠道' },
  { value: 'FBA', label: 'FBA' },
  { value: 'FBM', label: 'FBM' },
];

export function OrdersPage() {
  const { storeId } = useStoreId();
  const [statusFilter, setStatusFilter] = useState('all');
  const [channelFilter, setChannelFilter] = useState('all');
  const [skuSearch, setSkuSearch] = useState('');
  const [asinSearch, setAsinSearch] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [selectedOrder, setSelectedOrder] = useState<any | null>(null);

  const ordersQuery = useApiQuery<any[]>(
    ['orders', storeId, { statusFilter, channelFilter, skuSearch, asinSearch, startDate, endDate }],
    async () => {
      const query = new URLSearchParams();
      if (storeId) query.set('storeId', storeId);
      if (statusFilter !== 'all') query.set('status', statusFilter);
      if (channelFilter !== 'all') query.set('channel', channelFilter);
      if (skuSearch) query.set('sku', skuSearch);
      if (asinSearch) query.set('asin', asinSearch);
      if (startDate) query.set('startDate', startDate);
      if (endDate) query.set('endDate', endDate);
      const qs = query.toString();
      let json: any;
      try {
        const res = await authFetch(`/api/orders${qs ? `?${qs}` : ''}`);
        json = await res.json();
      } catch {
        throw new Error('网络错误');
      }
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data?.items ?? json.data?.records ?? (Array.isArray(json.data) ? json.data : []);
    },
  );

  const orders = ordersQuery.data ?? [];
  const loading = ordersQuery.isLoading;
  const error = ordersQuery.isError ? ordersQuery.error?.message ?? '加载失败' : null;
  const fetchOrders = () => ordersQuery.refetch();

  if (loading) return <div className="space-y-4"><ErpPageHeader title="订单管理" description="管理所有订单数据" /><ErpLoadingSkeleton rows={8} /></div>;
  if (error) return <ErpErrorState message={error} onRetry={fetchOrders} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="订单管理"
        description={`共 ${orders.length} 个订单`}
        actions={
          <button onClick={fetchOrders} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
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
        <select
          value={channelFilter}
          onChange={(e) => setChannelFilter(e.target.value)}
          className="px-2.5 py-1.5 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg outline-none"
        >
          {channelOptions.map((c) => (
            <option key={c.value} value={c.value}>{c.label}</option>
          ))}
        </select>
        <div className="flex items-center gap-1.5 bg-slate-100 rounded-lg px-2 py-1">
          <Search size={14} className="text-slate-400" />
          <input
            type="text"
            value={skuSearch}
            onChange={(e) => setSkuSearch(e.target.value)}
            placeholder="搜索SKU..."
            className="text-xs bg-transparent outline-none w-24 placeholder-slate-400"
          />
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 rounded-lg px-2 py-1">
          <Search size={14} className="text-slate-400" />
          <input
            type="text"
            value={asinSearch}
            onChange={(e) => setAsinSearch(e.target.value)}
            placeholder="搜索ASIN..."
            className="text-xs bg-transparent outline-none w-24 placeholder-slate-400"
          />
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 rounded-lg px-2 py-1">
          <input
            type="date"
            value={startDate}
            onChange={(e) => setStartDate(e.target.value)}
            className="text-xs bg-transparent outline-none text-slate-600"
          />
          <span className="text-xs text-slate-400">至</span>
          <input
            type="date"
            value={endDate}
            onChange={(e) => setEndDate(e.target.value)}
            className="text-xs bg-transparent outline-none text-slate-600"
          />
        </div>
      </div>

      {/* Table */}
      {orders.length === 0 ? (
        <ErpEmptyState title="暂无订单数据" description="没有匹配的订单记录" />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50">
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">订单号</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">SKU</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">ASIN</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">产品名称</th>
                  <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500">数量</th>
                  <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500">金额</th>
                  <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500">状态</th>
                  <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500">渠道</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">购买日期</th>
                  <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500">操作</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((order: any) => (
                  <tr key={order.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-3 text-sm font-mono font-medium text-slate-900">{order.orderId || order.amazonOrderId || '-'}</td>
                    <td className="px-4 py-3 text-xs font-mono text-slate-600">{order.sku || '-'}</td>
                    <td className="px-4 py-3 text-xs font-mono text-slate-600">{order.asin || '-'}</td>
                    <td className="px-4 py-3 text-sm text-slate-700 max-w-[200px] truncate">{order.productName || order.title || '-'}</td>
                    <td className="px-4 py-3 text-sm text-right text-slate-700">{order.quantity ?? '-'}</td>
                    <td className="px-4 py-3 text-sm text-right font-medium text-slate-900">{order.amount != null ? formatCurrency(order.amount) : '-'}</td>
                    <td className="px-4 py-3 text-center"><ErpStatusBadge status={order.status || 'pending'} /></td>
                    <td className="px-4 py-3 text-center">
                      <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-blue-50 text-blue-700">
                        {order.channel || 'FBA'}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-sm text-slate-600">{formatDate(order.purchaseDate || order.orderDate)}</td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => setSelectedOrder(order)}
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
      {selectedOrder && (
        <Sheet open onOpenChange={(o) => { if (!o) setSelectedOrder(null); }}>
          <SheetContent side="right" className="w-full max-w-md sm:max-w-md gap-0 p-0 bg-white border-l border-slate-200 overflow-y-auto">
            <div className="sticky top-0 bg-white border-b border-slate-100 px-5 py-4 flex items-center justify-between">
              <SheetTitle className="text-base font-semibold text-slate-900">订单详情</SheetTitle>
            </div>
            <div className="p-5 space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-slate-500 mb-1">订单号</p>
                  <p className="text-sm font-mono font-medium text-slate-900">{selectedOrder.orderId || selectedOrder.amazonOrderId || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">状态</p>
                  <ErpStatusBadge status={selectedOrder.status || 'pending'} />
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">SKU</p>
                  <p className="text-sm font-mono text-slate-700">{selectedOrder.sku || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">ASIN</p>
                  <p className="text-sm font-mono text-slate-700">{selectedOrder.asin || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">数量</p>
                  <p className="text-sm text-slate-900">{selectedOrder.quantity ?? '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">金额</p>
                  <p className="text-sm font-medium text-slate-900">{selectedOrder.amount != null ? formatCurrency(selectedOrder.amount) : '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">渠道</p>
                  <p className="text-sm text-slate-700">{selectedOrder.channel || 'FBA'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">购买日期</p>
                  <p className="text-sm text-slate-700">{formatDate(selectedOrder.purchaseDate || selectedOrder.orderDate)}</p>
                </div>
              </div>
              {(selectedOrder.productName || selectedOrder.title) && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">产品名称</p>
                  <p className="text-sm text-slate-700">{selectedOrder.productName || selectedOrder.title}</p>
                </div>
              )}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
