// Google Ads 创建 — manual campaign creation (platform-workspace-rbac Req 7.1).
//
// Submits a new campaign as a platform_mutation Operation (OperationSource
// CREATION) routed through the Outbox to the GoogleAdsWriteConnector. The submit
// does NOT apply to Google Ads synchronously: the returned Operation is in an
// unsettled Sync_State, surfaced here as a pending change (Req 7.6). A 403 from a
// missing independent-site advertising permission is surfaced by the API client.

import { useState } from 'react';
import { CheckCircle2, Loader2, Sparkles } from 'lucide-react';
import { createGoogleAdsCampaign, type GoogleAdsOperationResult } from '../../lib/api';
import { useStoreContext } from '../../lib/StoreContext';
import { GaNoStore, GaPageHeader, GaPendingBadge, isUnsettled, syncStateLabel } from './shared';

export function GoogleAdsCreatePage() {
  const { storeId, stores, loading: storeLoading } = useStoreContext();
  const storeName = stores.find((s) => s.id === storeId)?.name;

  const [name, setName] = useState('');
  const [budget, setBudget] = useState('');
  const [status, setStatus] = useState('PAUSED');

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<GoogleAdsOperationResult | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setResult(null);
    if (!storeId) {
      setError('请先选择一个独立站店铺');
      return;
    }
    const trimmed = name.trim();
    if (!trimmed) {
      setError('请输入广告系列名称');
      return;
    }
    if (trimmed.length > 255) {
      setError('广告系列名称不能超过 255 个字符');
      return;
    }
    let budgetNum: number | undefined;
    if (budget.trim()) {
      budgetNum = Number(budget);
      if (Number.isNaN(budgetNum) || budgetNum < 0) {
        setError('预算必须是非负数字');
        return;
      }
    }

    try {
      setSubmitting(true);
      const res = await createGoogleAdsCampaign({
        storeId,
        name: trimmed,
        budget: budgetNum ?? null,
        status,
      });
      setResult(res);
      setName('');
      setBudget('');
    } catch (err: any) {
      setError(err?.message || '创建广告系列失败');
    } finally {
      setSubmitting(false);
    }
  }

  const header = (
    <GaPageHeader
      title="Google Ads 创建广告系列"
      subtitle={storeName ? `为「${storeName}」创建一个新的 Google Ads 广告系列` : '创建一个新的 Google Ads 广告系列'}
    />
  );

  if (storeLoading) return header;
  if (!storeId) return <div className="space-y-6">{header}<GaNoStore /></div>;

  return (
    <div className="space-y-6">
      {header}

      <form onSubmit={handleSubmit} className="bg-white rounded-xl border border-slate-200 p-5 space-y-4 max-w-2xl">
        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1.5">广告系列名称 <span className="text-red-500">*</span></label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="例如：Summer Sale - Search"
            className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">每日预算（可选）</label>
            <input
              type="number"
              min="0"
              step="0.01"
              value={budget}
              onChange={(e) => setBudget(e.target.value)}
              placeholder="例如：50"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">初始状态</label>
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 bg-white"
            >
              <option value="PAUSED">暂停（PAUSED）</option>
              <option value="ENABLED">启用（ENABLED）</option>
            </select>
          </div>
        </div>

        {error && <p className="text-sm text-red-500">{error}</p>}

        <div className="flex justify-end pt-1">
          <button
            type="submit"
            disabled={submitting}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {submitting ? <Loader2 size={16} className="animate-spin" /> : <Sparkles size={16} />}
            {submitting ? '提交中…' : '创建广告系列'}
          </button>
        </div>
      </form>

      {/* The create produced an unsettled Operation — surface it as a pending
          change alongside its lifecycle state (Req 7.6). */}
      {result && (
        <div className="bg-white rounded-xl border border-slate-200 p-5 max-w-2xl">
          <div className="flex items-center gap-2">
            <CheckCircle2 size={18} className="text-emerald-500" />
            <h2 className="text-base font-semibold text-slate-900">已提交创建请求</h2>
            {isUnsettled(result.syncState) && <GaPendingBadge label="待平台确认" />}
          </div>
          <p className="text-sm text-slate-500 mt-2">
            创建请求已进入写回队列，等待提交到 Google Ads。变更生效前将一直显示为待确认状态。
          </p>
          <dl className="mt-4 grid grid-cols-2 gap-y-2 text-sm">
            <dt className="text-slate-500">操作 ID</dt>
            <dd className="text-slate-800 break-all">{result.operationId || '-'}</dd>
            <dt className="text-slate-500">实体类型</dt>
            <dd className="text-slate-800">{result.entityType || '-'}</dd>
            <dt className="text-slate-500">同步状态</dt>
            <dd className="text-slate-800">{syncStateLabel(result.syncState)}</dd>
          </dl>
        </div>
      )}
    </div>
  );
}
