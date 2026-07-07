import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authFetch } from '../lib/auth';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { Search, RefreshCw, Send, Eye, MessageSquare } from 'lucide-react';
import { cn, formatDate } from '../lib/utils';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { Sheet, SheetContent, SheetTitle } from '../components/ui/sheet';

const statusOptions = [
  { value: 'all', label: '全部' },
  { value: 'unread', label: '未读' },
  { value: 'read', label: '已读' },
  { value: 'replied', label: '已回复' },
];

const directionLabels: Record<string, { label: string; color: string }> = {
  inbound: { label: '收到', color: 'bg-blue-50 text-blue-700' },
  outbound: { label: '发送', color: 'bg-emerald-50 text-emerald-700' },
};

const messageStatusLabels: Record<string, { label: string; bg: string; text: string }> = {
  unread: { label: '未读', bg: 'bg-amber-50', text: 'text-amber-700' },
  read: { label: '已读', bg: 'bg-blue-50', text: 'text-blue-700' },
  replied: { label: '已回复', bg: 'bg-emerald-50', text: 'text-emerald-700' },
};

export function BuyerMessagesPage() {
  const { storeId } = useStoreId();
  const queryClient = useQueryClient();
  const [statusFilter, setStatusFilter] = useState('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedMessage, setSelectedMessage] = useState<any | null>(null);
  const [replyText, setReplyText] = useState('');
  const [replying, setReplying] = useState(false);
  const [replyError, setReplyError] = useState<string | null>(null);

  const messagesKey = ['buyer-messages', storeId, { statusFilter, searchQuery }] as const;
  const messagesQuery = useApiQuery<any[]>(
    messagesKey,
    async () => {
      const query = new URLSearchParams();
      if (storeId) query.set('storeId', storeId);
      if (statusFilter !== 'all') query.set('status', statusFilter);
      if (searchQuery) query.set('search', searchQuery);
      const qs = query.toString();
      let json: any;
      try {
        const res = await authFetch(`/api/buyer-messages${qs ? `?${qs}` : ''}`);
        json = await res.json();
      } catch {
        throw new Error('网络错误');
      }
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data?.items ?? json.data?.records ?? (Array.isArray(json.data) ? json.data : []);
    },
  );
  const messages = messagesQuery.data ?? [];
  const loading = messagesQuery.isLoading;
  const error = messagesQuery.isError ? messagesQuery.error?.message ?? '加载失败' : null;
  const fetchMessages = () => messagesQuery.refetch();

  const handleReply = async () => {
    if (!selectedMessage || !replyText.trim()) return;
    try {
      setReplying(true);
      setReplyError(null);
      const res = await authFetch(`/api/buyer-messages/${selectedMessage.id}/reply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: replyText.trim() }),
      });
      const json = await res.json();
      if (json.success) {
        queryClient.setQueryData<any[]>(messagesKey, (prev) =>
          (prev ?? []).map((m) =>
            m.id === selectedMessage.id ? { ...m, status: 'replied' } : m,
          ),
        );
        setSelectedMessage(null);
        setReplyText('');
      } else {
        setReplyError(json.error?.message || '发送失败');
      }
    } catch (e) {
      setReplyError('网络错误');
    } finally {
      setReplying(false);
    }
  };

  if (loading) return <div className="space-y-4"><ErpPageHeader title="买家消息" description="管理买家沟通消息" /><ErpLoadingSkeleton rows={8} /></div>;
  if (error) return <ErpErrorState message={error} onRetry={fetchMessages} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="买家消息"
        description={`共 ${messages.length} 条消息`}
        actions={
          <button onClick={fetchMessages} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
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
            placeholder="搜索订单号/邮箱..."
            className="text-xs bg-transparent outline-none w-36 placeholder-slate-400"
          />
        </div>
      </div>

      {/* Table */}
      {messages.length === 0 ? (
        <ErpEmptyState title="暂无消息" description="没有匹配的买家消息" icon={<MessageSquare size={24} className="text-slate-400" />} />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50">
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">订单号</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">买家邮箱</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">主题</th>
                  <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500">方向</th>
                  <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500">状态</th>
                  <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">时间</th>
                  <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500">操作</th>
                </tr>
              </thead>
              <tbody>
                {messages.map((msg: any) => {
                  const dir = directionLabels[msg.direction] || directionLabels.inbound;
                  const msgStatus = messageStatusLabels[msg.status] || messageStatusLabels.unread;
                  return (
                    <tr
                      key={msg.id}
                      className={cn(
                        'border-b border-slate-50 hover:bg-slate-50/50 transition-colors',
                        msg.status === 'unread' && 'bg-amber-50/20',
                      )}
                    >
                      <td className="px-4 py-3 text-xs font-mono text-slate-600">{msg.orderId || '-'}</td>
                      <td className="px-4 py-3 text-sm text-slate-700">{msg.buyerEmail || msg.email || '-'}</td>
                      <td className="px-4 py-3 text-sm text-slate-700 max-w-[220px] truncate">{msg.subject || '-'}</td>
                      <td className="px-4 py-3 text-center">
                        <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', dir.color)}>
                          {dir.label}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-center">
                        <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', msgStatus.bg, msgStatus.text)}>
                          {msgStatus.label}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-sm text-slate-600">{formatDate(msg.sentAt || msg.createdAt)}</td>
                      <td className="px-4 py-3 text-right">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            onClick={() => setSelectedMessage(msg)}
                            className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 bg-slate-50 rounded hover:bg-slate-100 transition-colors"
                          >
                            <Eye size={11} /> 查看
                          </button>
                          {msg.status !== 'replied' && msg.direction === 'inbound' && (
                            <button
                              onClick={() => { setSelectedMessage(msg); setReplyText(''); setReplyError(null); }}
                              className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-blue-600 bg-blue-50 rounded hover:bg-blue-100 transition-colors"
                            >
                              <Send size={11} /> 回复
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Detail / Reply Drawer */}
      {selectedMessage && (
        <Sheet open onOpenChange={(o) => { if (!o) { setSelectedMessage(null); setReplyText(''); setReplyError(null); } }}>
          <SheetContent side="right" className="w-full max-w-md sm:max-w-md gap-0 p-0 bg-white border-l border-slate-200 overflow-y-auto flex flex-col">
            <div className="sticky top-0 bg-white border-b border-slate-100 px-5 py-4 flex items-center justify-between">
              <SheetTitle className="text-base font-semibold text-slate-900">消息详情</SheetTitle>
            </div>
            <div className="p-5 space-y-4 flex-1">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-slate-500 mb-1">订单号</p>
                  <p className="text-sm font-mono text-slate-700">{selectedMessage.orderId || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">买家邮箱</p>
                  <p className="text-sm text-slate-700">{selectedMessage.buyerEmail || selectedMessage.email || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">方向</p>
                  <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', directionLabels[selectedMessage.direction]?.color || 'bg-slate-100 text-slate-600')}>
                    {directionLabels[selectedMessage.direction]?.label || selectedMessage.direction}
                  </span>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">状态</p>
                  <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', messageStatusLabels[selectedMessage.status]?.bg, messageStatusLabels[selectedMessage.status]?.text)}>
                    {messageStatusLabels[selectedMessage.status]?.label || selectedMessage.status}
                  </span>
                </div>
                <div>
                  <p className="text-xs text-slate-500 mb-1">时间</p>
                  <p className="text-sm text-slate-700">{formatDate(selectedMessage.sentAt || selectedMessage.createdAt)}</p>
                </div>
              </div>
              {selectedMessage.subject && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">主题</p>
                  <p className="text-sm font-medium text-slate-900">{selectedMessage.subject}</p>
                </div>
              )}
              {selectedMessage.body && (
                <div>
                  <p className="text-xs text-slate-500 mb-1">内容</p>
                  <p className="text-sm text-slate-700 whitespace-pre-wrap bg-slate-50 rounded-lg p-3">{selectedMessage.body}</p>
                </div>
              )}

              {/* Reply section for inbound messages */}
              {selectedMessage.direction === 'inbound' && selectedMessage.status !== 'replied' && (
                <div className="border-t border-slate-100 pt-4 space-y-3">
                  <p className="text-xs font-medium text-slate-700">回复买家</p>
                  {replyError && <p className="text-xs text-red-500">{replyError}</p>}
                  <textarea
                    value={replyText}
                    onChange={(e) => setReplyText(e.target.value)}
                    placeholder="输入回复内容..."
                    rows={4}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-100 resize-none"
                  />
                  <button
                    onClick={handleReply}
                    disabled={replying || !replyText.trim()}
                    className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <Send size={14} />
                    {replying ? '发送中...' : '发送回复'}
                  </button>
                </div>
              )}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
