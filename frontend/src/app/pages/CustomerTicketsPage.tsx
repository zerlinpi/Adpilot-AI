import { useState, useEffect, useCallback } from 'react';
import {
  Headphones, Search, RefreshCw, Eye, Loader2,
  Sparkles, Tag, AlertCircle, CheckCircle2, Send,
} from 'lucide-react';
import { cn } from '../lib/utils';
import {
  fetchCustomerTickets,
  assistCustomerTicket,
  confirmCustomerTicketProposal,
  type TicketAiProposal,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { Sheet, SheetContent, SheetTitle } from '../components/ui/sheet';

const priorityConfig: Record<string, { label: string; color: string }> = {
  high: { label: '高', color: 'bg-red-100 text-red-700' },
  medium: { label: '中', color: 'bg-amber-100 text-amber-700' },
  low: { label: '低', color: 'bg-blue-100 text-blue-700' },
};

const categoryConfig: Record<string, { label: string; color: string }> = {
  order_issue: { label: '订单问题', color: 'bg-orange-50 text-orange-600' },
  shipping: { label: '物流配送', color: 'bg-blue-50 text-blue-600' },
  product_quality: { label: '产品质量', color: 'bg-red-50 text-red-600' },
  return_refund: { label: '退换货', color: 'bg-purple-50 text-purple-600' },
  account: { label: '账户问题', color: 'bg-slate-100 text-slate-600' },
  other: { label: '其他', color: 'bg-slate-100 text-slate-600' },
};

export function CustomerTicketsPage() {
  const { storeId } = useStoreId();
  const [tickets, setTickets] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState('all');
  const [priorityFilter, setPriorityFilter] = useState('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedTicket, setSelectedTicket] = useState<any | null>(null);

  const loadTickets = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params: any = {};
      if (storeId) params.storeId = storeId;
      if (statusFilter !== 'all') params.status = statusFilter;
      if (priorityFilter !== 'all') params.priority = priorityFilter;
      if (searchQuery) params.search = searchQuery;
      const result = await fetchCustomerTickets(params);
      setTickets(Array.isArray(result) ? result : result?.items || []);
    } catch (err: any) {
      setError(err.message || '加载工单失败');
    } finally {
      setLoading(false);
    }
  }, [statusFilter, priorityFilter, searchQuery, storeId]);

  useEffect(() => { loadTickets(); }, [loadTickets]);

  const filteredTickets = tickets.filter((t) => {
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      return (
        t.ticketNo?.toLowerCase().includes(q) ||
        t.orderNo?.toLowerCase().includes(q) ||
        t.buyerEmail?.toLowerCase().includes(q) ||
        t.subject?.toLowerCase().includes(q)
      );
    }
    return true;
  });

  if (loading) return <div className="space-y-4"><ErpPageHeader title="客服工单" description="管理买家客服工单" /><ErpLoadingSkeleton rows={10} /></div>;
  if (error) return <ErpErrorState message={error} onRetry={loadTickets} />;

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="客服工单"
        description={`共 ${tickets.length} 个工单`}
        actions={
          <button onClick={loadTickets} className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
            <RefreshCw size={14} /> 刷新
          </button>
        }
      />

      {/* Filters */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-0.5">
          {['all', 'pending', 'in_progress', 'resolved', 'closed'].map((s) => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={cn('px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                statusFilter === s ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700')}>
              {s === 'all' ? '全部' : s === 'pending' ? '待处理' : s === 'in_progress' ? '处理中' : s === 'resolved' ? '已解决' : '已关闭'}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-0.5">
          {['all', 'high', 'medium', 'low'].map((p) => (
            <button key={p} onClick={() => setPriorityFilter(p)}
              className={cn('px-2.5 py-1 text-xs font-medium rounded-md transition-colors',
                priorityFilter === p ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700')}>
              {p === 'all' ? '全部优先级' : priorityConfig[p]?.label || p}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 rounded-lg px-2 py-1">
          <Search size={14} className="text-slate-400" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="搜索工单..."
            className="text-xs bg-transparent outline-none w-40 placeholder-slate-400"
          />
        </div>
      </div>

      {/* Table */}
      {filteredTickets.length === 0 ? (
        <ErpEmptyState
          title="暂无工单"
          description="没有匹配的客服工单"
          icon={<Headphones size={24} className="text-slate-400" />}
        />
      ) : (
        <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50">
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">工单号</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">订单号</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">买家邮箱</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">主题</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-24">分类</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-16">优先级</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-20">状态</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-24">负责人</th>
                <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-32">创建时间</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-slate-500 w-20">操作</th>
              </tr>
            </thead>
            <tbody>
              {filteredTickets.map((ticket) => {
                const priority = priorityConfig[ticket.priority] || priorityConfig.medium;
                const category = categoryConfig[ticket.category] || categoryConfig.other;

                return (
                  <tr key={ticket.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-3">
                      <span className="text-sm font-mono font-medium text-slate-900">{ticket.ticketNo || ticket.id}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-sm font-mono text-slate-600">{ticket.orderNo || '-'}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-sm text-slate-600 truncate max-w-[160px] block">{ticket.buyerEmail || '-'}</span>
                    </td>
                    <td className="px-4 py-3">
                      <p className="text-sm font-medium text-slate-900 truncate max-w-[200px]">{ticket.subject || '-'}</p>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', category.color)}>
                        {category.label}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', priority.color)}>
                        {priority.label}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <ErpStatusBadge status={ticket.status} />
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-sm text-slate-600">{ticket.assignee || '-'}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs text-slate-500">
                        {ticket.createdAt ? new Date(ticket.createdAt).toLocaleString('zh-CN') : '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => setSelectedTicket(ticket)}
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
      {selectedTicket && (
        <TicketDetailDrawer
          ticket={selectedTicket}
          onClose={() => setSelectedTicket(null)}
          onApplied={() => { setSelectedTicket(null); loadTickets(); }}
        />
      )}
    </div>
  );
}

const statusActionConfig: Record<string, string> = {
  in_progress: '标记为处理中',
  resolved: '标记为已解决',
  closed: '关闭工单',
  pending: '标记为待处理',
};

function describeAction(action?: string | null): string {
  if (!action) return '';
  return statusActionConfig[action] || action;
}

/**
 * Ticket detail drawer with the Ticket_AI_Assistant proposal flow
 * (platform-workspace-rbac Req 9.3, 9.4). Requesting AI assistance only
 * PRODUCES proposals (a draft reply, a suggested classification, a suggested
 * action); nothing is sent or applied until the operator explicitly confirms.
 */
function TicketDetailDrawer({
  ticket,
  onClose,
  onApplied,
}: {
  ticket: any;
  onClose: () => void;
  onApplied: () => void;
}) {
  const [proposal, setProposal] = useState<TicketAiProposal | null>(null);
  const [draftText, setDraftText] = useState('');
  const [includeClassification, setIncludeClassification] = useState(true);
  const [includeAction, setIncludeAction] = useState(true);
  const [assisting, setAssisting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [assistError, setAssistError] = useState<string | null>(null);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const [applied, setApplied] = useState<string | null>(null);

  const requestAssist = async () => {
    setAssisting(true);
    setAssistError(null);
    setConfirmError(null);
    setApplied(null);
    try {
      const result = await assistCustomerTicket(ticket.id);
      setProposal(result);
      setDraftText(result.draft || '');
      setIncludeClassification(Boolean(result.classification));
      setIncludeAction(Boolean(result.suggestedAction));
    } catch (err: any) {
      // Generation failure leaves the ticket unchanged and surfaces the reason (Req 9.6).
      setAssistError(err?.message || 'AI 辅助生成失败');
    } finally {
      setAssisting(false);
    }
  };

  // Explicit operator confirmation is the ONLY path that sends a reply or
  // mutates the ticket (Req 9.4, 9.5). `sendReply` distinguishes "确认并发送回复"
  // from "仅应用分类与操作".
  const confirmProposal = async (sendReply: boolean) => {
    if (!proposal) return;
    const payload: { reply?: string; classification?: string; action?: string } = {};
    if (sendReply) {
      if (!draftText.trim()) {
        setConfirmError('回复内容不能为空');
        return;
      }
      payload.reply = draftText.trim();
    }
    if (includeClassification && proposal.classification) {
      payload.classification = proposal.classification;
    }
    if (includeAction && proposal.suggestedAction) {
      payload.action = proposal.suggestedAction;
    }
    if (Object.keys(payload).length === 0) {
      setConfirmError('请至少选择一项要应用的内容');
      return;
    }

    setConfirming(true);
    setConfirmError(null);
    try {
      await confirmCustomerTicketProposal(ticket.id, payload);
      setApplied(sendReply ? '回复已发送并应用所选建议' : '已应用所选建议');
      setProposal(null);
      // Give the operator a moment to read the success indication before refreshing.
      setTimeout(onApplied, 600);
    } catch (err: any) {
      setConfirmError(err?.message || '确认应用失败');
    } finally {
      setConfirming(false);
    }
  };

  return (
    <Sheet open onOpenChange={(o) => { if (!o) onClose(); }}>
      <SheetContent side="right" className="w-full max-w-md sm:max-w-md gap-0 p-0 bg-white border-l border-slate-200 overflow-y-auto">
        <div className="sticky top-0 bg-white border-b border-slate-100 px-5 py-4 flex items-center justify-between z-10">
          <SheetTitle className="text-base font-semibold text-slate-900">工单详情</SheetTitle>
        </div>
        <div className="p-5 space-y-4">
          <div>
            <p className="text-xs text-slate-500 mb-1">工单号</p>
            <p className="text-sm text-slate-900 font-mono font-medium">{ticket.ticketNo || ticket.id}</p>
          </div>
          <div>
            <p className="text-xs text-slate-500 mb-1">主题</p>
            <p className="text-sm text-slate-900 font-medium">{ticket.subject || '-'}</p>
          </div>
          <div>
            <p className="text-xs text-slate-500 mb-1">描述</p>
            <p className="text-sm text-slate-700 whitespace-pre-wrap">{ticket.description || '无'}</p>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-xs text-slate-500 mb-1">订单号</p>
              <p className="text-sm font-mono text-slate-700">{ticket.orderNo || '-'}</p>
            </div>
            <div>
              <p className="text-xs text-slate-500 mb-1">买家邮箱</p>
              <p className="text-sm text-slate-700 truncate">{ticket.buyerEmail || '-'}</p>
            </div>
            <div>
              <p className="text-xs text-slate-500 mb-1">分类</p>
              <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', categoryConfig[ticket.category]?.color || 'bg-slate-100 text-slate-600')}>
                {categoryConfig[ticket.category]?.label || ticket.category || '-'}
              </span>
            </div>
            <div>
              <p className="text-xs text-slate-500 mb-1">优先级</p>
              <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', priorityConfig[ticket.priority]?.color || 'bg-slate-100 text-slate-600')}>
                {priorityConfig[ticket.priority]?.label || ticket.priority || '-'}
              </span>
            </div>
            <div>
              <p className="text-xs text-slate-500 mb-1">状态</p>
              <ErpStatusBadge status={ticket.status} />
            </div>
            <div>
              <p className="text-xs text-slate-500 mb-1">负责人</p>
              <p className="text-sm text-slate-700">{ticket.assignee || '-'}</p>
            </div>
          </div>
          <div>
            <p className="text-xs text-slate-500 mb-1">创建时间</p>
            <p className="text-sm text-slate-700">
              {ticket.createdAt ? new Date(ticket.createdAt).toLocaleString('zh-CN') : '-'}
            </p>
          </div>

          {/* AI Assist */}
          <div className="border-t border-slate-100 pt-4">
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-1.5">
                <Sparkles size={15} className="text-violet-500" />
                <span className="text-sm font-semibold text-slate-900">AI 辅助</span>
              </div>
              <button
                onClick={requestAssist}
                disabled={assisting}
                className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium text-violet-700 bg-violet-50 border border-violet-200 rounded-lg hover:bg-violet-100 transition-colors disabled:opacity-60"
              >
                {assisting ? <Loader2 size={13} className="animate-spin" /> : <Sparkles size={13} />}
                {proposal ? '重新生成建议' : '生成 AI 建议'}
              </button>
            </div>

            {assistError && (
              <div className="flex items-start gap-2 p-2.5 rounded-lg bg-red-50 border border-red-100 text-xs text-red-600">
                <AlertCircle size={14} className="mt-0.5 shrink-0" />
                <span>{assistError}</span>
              </div>
            )}

            {applied && (
              <div className="flex items-start gap-2 p-2.5 rounded-lg bg-emerald-50 border border-emerald-100 text-xs text-emerald-700">
                <CheckCircle2 size={14} className="mt-0.5 shrink-0" />
                <span>{applied}</span>
              </div>
            )}

            {proposal && (
              <div className="space-y-3 mt-2">
                {/* Proposal notice — nothing is sent/applied until explicit confirmation (Req 9.4). */}
                <div className="flex items-start gap-2 p-2.5 rounded-lg bg-amber-50 border border-amber-100 text-xs text-amber-700">
                  <AlertCircle size={14} className="mt-0.5 shrink-0" />
                  <span>以下内容由 AI 生成，仅为建议。在你确认前，系统不会发送回复或修改工单。</span>
                </div>

                {/* Suggested classification proposal */}
                {proposal.classification && (
                  <label className="flex items-center justify-between gap-2 p-2.5 rounded-lg border border-slate-200">
                    <span className="flex items-center gap-1.5 text-xs text-slate-600">
                      <Tag size={13} className="text-slate-400" />
                      建议分类：
                      <span className="font-medium text-slate-900">
                        {categoryConfig[proposal.classification]?.label || proposal.classification}
                      </span>
                    </span>
                    <input
                      type="checkbox"
                      checked={includeClassification}
                      onChange={(e) => setIncludeClassification(e.target.checked)}
                      className="h-3.5 w-3.5 accent-violet-600"
                      aria-label="应用建议分类"
                    />
                  </label>
                )}

                {/* Suggested handling action proposal */}
                {proposal.suggestedAction && (
                  <label className="flex items-center justify-between gap-2 p-2.5 rounded-lg border border-slate-200">
                    <span className="flex items-center gap-1.5 text-xs text-slate-600">
                      <CheckCircle2 size={13} className="text-slate-400" />
                      建议操作：
                      <span className="font-medium text-slate-900">{describeAction(proposal.suggestedAction)}</span>
                    </span>
                    <input
                      type="checkbox"
                      checked={includeAction}
                      onChange={(e) => setIncludeAction(e.target.checked)}
                      className="h-3.5 w-3.5 accent-violet-600"
                      aria-label="应用建议操作"
                    />
                  </label>
                )}

                {/* Draft reply — editable before confirmation */}
                <div>
                  <p className="text-xs text-slate-500 mb-1">建议回复（可编辑）</p>
                  <textarea
                    value={draftText}
                    onChange={(e) => setDraftText(e.target.value)}
                    rows={6}
                    className="w-full text-sm text-slate-800 border border-slate-200 rounded-lg p-2.5 outline-none focus:border-violet-300 resize-y"
                  />
                  {proposal.generatedBy && (
                    <p className="text-[11px] text-slate-400 mt-1">
                      由 {proposal.generatedBy === 'ai' ? 'AI 模型' : '模板'} 生成
                    </p>
                  )}
                </div>

                {confirmError && (
                  <div className="flex items-start gap-2 p-2.5 rounded-lg bg-red-50 border border-red-100 text-xs text-red-600">
                    <AlertCircle size={14} className="mt-0.5 shrink-0" />
                    <span>{confirmError}</span>
                  </div>
                )}

                {/* Explicit confirmation controls (Req 9.4, 9.5) */}
                <div className="flex flex-wrap items-center gap-2">
                  <button
                    onClick={() => confirmProposal(true)}
                    disabled={confirming}
                    className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-white bg-violet-600 rounded-lg hover:bg-violet-700 transition-colors disabled:opacity-60"
                  >
                    {confirming ? <Loader2 size={13} className="animate-spin" /> : <Send size={13} />}
                    确认并发送回复
                  </button>
                  {(proposal.classification || proposal.suggestedAction) && (
                    <button
                      onClick={() => confirmProposal(false)}
                      disabled={confirming}
                      className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-60"
                    >
                      仅应用分类与操作
                    </button>
                  )}
                  <button
                    onClick={() => { setProposal(null); setConfirmError(null); }}
                    disabled={confirming}
                    className="px-3 py-2 text-xs font-medium text-slate-500 hover:text-slate-700 transition-colors disabled:opacity-60"
                  >
                    放弃建议
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      </SheetContent>
    </Sheet>
  );
}
