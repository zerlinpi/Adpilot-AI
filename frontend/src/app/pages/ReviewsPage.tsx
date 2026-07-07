import { useState } from 'react';
import { Eye, Pencil, MessageSquare, CheckCircle, XCircle, Star } from 'lucide-react';
import { fetchReviews, respondToReview } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { cn, formatDate } from '../lib/utils';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { RecordModal, type RecordField } from '../components/ui/RecordModal';

const REVIEW_VIEW_FIELDS: RecordField[] = [
  { key: 'asin', label: 'ASIN' },
  { key: 'sku', label: 'SKU' },
  { key: 'reviewer', label: '评论者' },
  { key: 'rating', label: '评分' },
  { key: 'title', label: '标题' },
  { key: 'sentiment', label: '情感' },
  { key: 'reviewDate', label: '评论日期' },
  { key: 'status', label: '状态' },
];

const REVIEW_RESPOND_FIELDS: RecordField[] = [
  { key: 'title', label: '评论标题', type: 'readonly' },
  { key: 'responseText', label: '回复内容', type: 'textarea', placeholder: '输入对该评论的回复…' },
];

// ─── Types ──────────────────────────────────────────────────────────
interface ReviewItem {
  id: string;
  asin: string;
  sku: string;
  reviewer: string;
  rating: number;
  title: string;
  sentiment: 'positive' | 'neutral' | 'negative';
  reviewDate: string;
  verifiedPurchase: boolean;
  status: 'active' | 'pending' | 'resolved';
}

// ─── Star rating display ────────────────────────────────────────────
function StarRating({ rating, max = 5 }: { rating: number; max?: number }) {
  return (
    <span className="inline-flex items-center gap-0.5" title={String(rating) + '/' + String(max)}>
      {Array.from({ length: max }, (_, i) => (
        <Star
          key={i}
          size={14}
          fill={i < rating ? 'currentColor' : 'none'}
          className={i < rating ? 'text-amber-500' : 'text-slate-300'}
        />
      ))}
    </span>
  );
}
// ─── Sentiment badge ────────────────────────────────────────────────
function SentimentBadge({ sentiment }: { sentiment: string }) {
  const map: Record<string, { label: string; className: string }> = {
    positive: { label: '正面', className: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
    neutral: { label: '中性', className: 'bg-slate-100 text-slate-600 border-slate-200' },
    negative: { label: '负面', className: 'bg-red-50 text-red-700 border-red-200' },
  };
  const config = map[sentiment] || { label: sentiment, className: 'bg-slate-100 text-slate-600 border-slate-200' };
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border', config.className)}>
      {config.label}
    </span>
  );
}

// ─── Main Page ──────────────────────────────────────────────────────
export function ReviewsPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const [viewRow, setViewRow] = useState<ReviewItem | null>(null);
  const [respondRow, setRespondRow] = useState<(ReviewItem & { responseText?: string }) | null>(null);

  const reviewsQuery = useApiQuery<ReviewItem[]>(
    ['reviews', storeId],
    () => fetchReviews(storeId!) as Promise<ReviewItem[]>,
    { enabled: !!storeId },
  );
  const reviews = Array.isArray(reviewsQuery.data) ? reviewsQuery.data : [];
  const loading = !!storeId && reviewsQuery.isLoading;
  const error = reviewsQuery.isError ? reviewsQuery.error?.message ?? '加载评论数据失败' : null;
  const loadData = () => reviewsQuery.refetch();

  // ─── Loading state ────────────────────────────────────────────────
  if (loading || storeLoading) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="Review 管理" description="管理产品评论与反馈" />
        <ErpLoadingSkeleton rows={8} />
      </div>
    );
  }

  // ─── Error state ──────────────────────────────────────────────────
  if (error || storeError) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="Review 管理" />
        <div className="bg-white rounded-xl border border-slate-200 p-8">
          <ErpErrorState message={storeError || error || undefined} onRetry={loadData} />
        </div>
      </div>
    );
  }

  // ─── No store state ───────────────────────────────────────────────
  if (!storeId) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="Review 管理" description="管理产品评论与反馈" />
        <div className="bg-white rounded-xl border border-slate-200">
          <ErpEmptyState
            title="暂无可用店铺"
            description="请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。"
            icon={<MessageSquare size={24} className="text-slate-400" />}
          />
        </div>
      </div>
    );
  }

  // ─── Empty state ──────────────────────────────────────────────────
  if (reviews.length === 0) {
    return (
      <div className="space-y-4">
        <ErpPageHeader
          title="Review 管理"
          description="共 0 条记录"
        />
        <div className="bg-white rounded-xl border border-slate-200">
          <ErpEmptyState
            title="暂无评论"
            description="暂无产品评论数据"
            icon={<MessageSquare size={24} className="text-slate-400" />}
          />
        </div>
      </div>
    );
  }

  // ─── Data state ───────────────────────────────────────────────────
  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="Review 管理"
        description={`共 ${reviews.length} 条评论`}
      />

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50/60">
                <th className="text-left font-medium text-slate-500 px-4 py-3">ASIN</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">SKU</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">评论者</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">评分</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">标题</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">情感</th>
                <th className="text-left font-medium text-slate-500 px-4 py-3">评论日期</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">已验证购买</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">状态</th>
                <th className="text-center font-medium text-slate-500 px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {reviews.map((review) => (
                <tr
                  key={review.id}
                  className={cn(
                    'border-b border-slate-50 last:border-0 hover:bg-slate-50/50 transition-colors',
                    review.sentiment === 'negative' && 'bg-red-50/20',
                  )}
                >
                  <td className="px-4 py-3 text-slate-600 font-mono text-xs">{review.asin}</td>
                  <td className="px-4 py-3 text-slate-600 font-mono text-xs">{review.sku}</td>
                  <td className="px-4 py-3 text-slate-700">{review.reviewer}</td>
                  <td className="px-4 py-3 text-center">
                    <StarRating rating={review.rating} />
                  </td>
                  <td className="px-4 py-3 text-slate-900 max-w-[200px] truncate">{review.title}</td>
                  <td className="px-4 py-3 text-center">
                    <SentimentBadge sentiment={review.sentiment} />
                  </td>
                  <td className="px-4 py-3 text-slate-600">{formatDate(review.reviewDate)}</td>
                  <td className="px-4 py-3 text-center">
                    {review.verifiedPurchase ? (
                      <CheckCircle size={16} className="text-emerald-500 mx-auto" />
                    ) : (
                      <XCircle size={16} className="text-slate-300 mx-auto" />
                    )}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <ErpStatusBadge status={review.status} />
                  </td>
                  <td className="px-4 py-3 text-center">
                    <div className="inline-flex items-center gap-1">
                      <button
                        onClick={() => setViewRow(review)}
                        className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                        title="查看"
                      >
                        <Eye size={15} />
                      </button>
                      <button
                        onClick={() => setRespondRow({ ...review, responseText: '' })}
                        className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                        title="回复"
                      >
                        <Pencil size={15} />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <RecordModal
        open={!!viewRow}
        mode="view"
        title="评论详情"
        fields={REVIEW_VIEW_FIELDS}
        record={viewRow}
        onClose={() => setViewRow(null)}
      />
      <RecordModal
        open={!!respondRow}
        mode="edit"
        title="回复评论"
        fields={REVIEW_RESPOND_FIELDS}
        record={respondRow}
        onClose={() => setRespondRow(null)}
        saveLabel="发送回复"
        onSave={async (values) => {
          if (!respondRow) return;
          await respondToReview(respondRow.id, values.responseText || '');
          setRespondRow(null);
          await loadData();
        }}
      />
    </div>
  );
}
