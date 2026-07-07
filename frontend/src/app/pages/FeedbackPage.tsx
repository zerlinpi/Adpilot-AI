import { useState } from 'react';
import {
  MessageSquare, Search, RefreshCw, Eye, ThumbsUp, ThumbsDown, Star,
} from 'lucide-react';
import { cn } from '../lib/utils';
import { fetchFeedback } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { Sheet, SheetContent, SheetTitle } from '../components/ui/sheet';

const typeConfig: Record<string, { label: string; color: string; icon: typeof ThumbsUp }> = {
  positive: { label: '正面', color: 'bg-emerald-50 text-emerald-700', icon: ThumbsUp },
  negative: { label: '负面', color: 'bg-red-50 text-red-700', icon: ThumbsDown },
};

export function FeedbackPage() {
  const { storeId } = useStoreId();
  const [typeFilter, setTypeFilter] = useState('all');
  const [statusFilter, setStatusFilter] = useState('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFeedback, setSelectedFeedback] = useState<any | null>(null);

  const feedbackQuery = useApiQuery<any[]>(
    ['feedback', storeId, { typeFilter, statusFilter, searchQuery }],
    () => {
      const params: any = {};
      if (storeId) params.storeId = storeId;
      if (typeFilter !== 'all') params.type = typeFilter;
      if (statusFilter !== 'all') params.status = statusFilter;
      if (searchQuery) params.search = searchQuery;
      return fetchFeedback(params).then((result: any) => (Array.isArray(result) ? result : result?.items || []));
    },
  );
  const feedbacks = feedbackQuery.data ?? [];
  const loading = feedbackQuery.isLoading;
  const error = feedbackQuery.isError ? feedbackQuery.error?.message ?? '加载 Feedback 失败' : null;
  const loadFeedback = () => feedbackQuery.refetch();

  const filteredFeedbacks = feedbacks.filter((f) => {
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      return (
        f.asin?.toLowerCase().includes(q) ||
        f.summary?.toLowerCase().includes(q)
      );
    }
    return true;
  });

  const renderStars = (rating: number) => {
    return (
      <div className="flex items-center gap-0.5">
        {Array.from({ length: 5 }).map((_, i) => (
          <Star
            key={i}
            size={12}
            className={cn(i < rating ? 'text-amber-400 fill-amber-400' : 'text-slate-200')}
          />
        ))}
        <span className="text-xs text-slate-500 ml-1">{rating}</span>
      </div>
    );
  };

  if (loading) return <div className="space-y-4"><ErpPageHeader title="Feedback 管理" description="管理买家评价与反馈" /><ErpLoadingSkeleton rows={10} /></div>;
  if (error) return <ErpErrorState message={error} onRetry={loadFeedback} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="Feedback 管理"
        description={`共 ${feedbacks.length} 条反馈`}
        actions={
          <button onClick={loadFeedback} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
            <RefreshCw size={14} /> 刷新
          </button>
        }
      />

      {/* Filters */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-0.5">
          {['all', 'positive', 'negative'].map((t) => (
            <button key={t} onClick={() => setTypeFilter(t)}
              className={cn('px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                typeFilter === t ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700')}>
              {t === 'all' ? '全部类型' : t === 'positive' ? '正面' : '负面'}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-0.5">
          {['all', 'open', 'resolved', 'ignored'].map((s) => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={cn('px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                statusFilter === s ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700')}>
              {s === 'all' ? '全部状态' : s === 'open' ? '待处理' : s === 'resolved' ? '已解决' : '已忽略'}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 rounded-lg px-2 py-1">
          <Search size={14} className="text-slate-400" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="搜索 ASIN 或摘要..."
            className="text-xs bg-transparent outline-none w-40 placeholder-slate-400"
          />
        </div>
      </div>

      {/* Table */}
      {filteredFeedbacks.length === 0 ? (
        <ErpEmptyState
          title="暂无反馈"
          description="没有匹配的 Feedback 记录"
          icon={<MessageSquare size={24} className="text-slate-400" />}
        />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50">
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">ASIN</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-20">类型</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-32">评分</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">内容摘要</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-32">日期</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-20">状态</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500 w-20">操作</th>
              </tr>
            </thead>
            <tbody>
              {filteredFeedbacks.map((fb) => {
                const type = typeConfig[fb.type] || typeConfig.positive;
                const TypeIcon = type.icon;

                return (
                  <tr key={fb.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-3">
                      <span className="text-sm font-mono font-medium text-slate-900">{fb.asin || '-'}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn('inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium', type.color)}>
                        <TypeIcon size={10} />
                        {type.label}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      {fb.rating != null ? renderStars(fb.rating) : <span className="text-xs text-slate-400">-</span>}
                    </td>
                    <td className="px-4 py-3">
                      <p className="text-sm text-slate-700 truncate max-w-[300px]">{fb.summary || fb.content || '-'}</p>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs text-slate-500">
                        {fb.date ? new Date(fb.date).toLocaleDateString('zh-CN') : '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <ErpStatusBadge status={fb.status} />
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => setSelectedFeedback(fb)}
                        className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 bg-slate-50 rounded hover:bg-slate-100 transition-colors"
                      >
                        <Eye size={11} />
                        查看
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Detail Drawer */}
      {selectedFeedback && (
        <Sheet open onOpenChange={(o) => { if (!o) setSelectedFeedback(null); }}>
          <SheetContent side="right" className="w-full max-w-md sm:max-w-md gap-0 p-0 bg-white border-l border-slate-200 overflow-y-auto">
            <div className="sticky top-0 bg-white border-b border-slate-100 px-5 py-4 flex items-center justify-between">
              <SheetTitle className="text-base font-semibold text-slate-900">Feedback 详情</SheetTitle>
            </div>
            <div className="p-5 space-y-4">
              <div>
                <p className="text-xs text-slate-500 mb-1">ASIN</p>
                <p className="text-sm font-mono text-slate-900 font-medium">{selectedFeedback.asin || '-'}</p>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-slate-500 mb-1">类型</p>
                  <span className={cn('inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium', typeConfig[selectedFeedback.type]?.color || 'bg-slate-100 text-slate-600')}>
                    {typeConfig[selectedFeedback.type]?.label || selectedFeedback.type || '-'}
                  </span>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">评分</p>
                  {selectedFeedback.rating != null ? renderStars(selectedFeedback.rating) : <span className="text-xs text-slate-400">-</span>}
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">状态</p>
                  <ErpStatusBadge status={selectedFeedback.status} />
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">日期</p>
                  <p className="text-sm text-slate-700">
                    {selectedFeedback.date ? new Date(selectedFeedback.date).toLocaleDateString('zh-CN') : '-'}
                  </p>
                </div>
              </div>
              <div>
                <p className="text-xs text-slate-500 mb-1">内容摘要</p>
                <p className="text-sm text-slate-700">{selectedFeedback.summary || selectedFeedback.content || '无'}</p>
              </div>
              {selectedFeedback.fullContent && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">完整内容</p>
                  <p className="text-sm text-slate-700 whitespace-pre-wrap">{selectedFeedback.fullContent}</p>
                </div>
              )}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
