// Google Ads 调价 — manual bid / budget / status adjustment
// (platform-workspace-rbac Req 7.2, 7.6).
//
// Lets an operator change a campaign's budget or status (or a keyword bid) on a
// connected Google Ads Store. Each change is submitted as a platform_mutation
// Operation (OperationSource MANUAL) routed through the Outbox to the
// GoogleAdsWriteConnector. While the resulting Operation is unsettled, the
// change is shown as a Pending_Overlay: the platform-confirmed (before) value
// alongside the requested (after) value with a 待生效 badge (Req 7.6).

import { useCallback, useEffect, useState } from 'react';
import { ArrowRight, Loader2, Target } from 'lucide-react';
import {
  adjustGoogleAds,
  fetchGoogleAdsCampaigns,
  type GoogleAdsCampaign,
  type GoogleAdsReadState,
} from '../../lib/api';
import { useStoreContext } from '../../lib/StoreContext';
import { GaConnectPrompt, GaErrorRetry, GaLoading, GaNoStore, GaPageHeader, GaPendingBadge, fmtNum } from './shared';

type ChangeType = 'budget' | 'status' | 'bid';

interface PendingChange {
  key: string;
  campaignName: string;
  changeType: ChangeType;
  before: string;
  after: string;
  syncState?: string | null;
}

const CHANGE_LABEL: Record<ChangeType, string> = {
  budget: '预算',
  status: '状态',
  bid: '出价',
};

export function GoogleAdsAdjustPage() {
  const { storeId, loading: storeLoading } = useStoreContext();

  const [readState, setReadState] = useState<GoogleAdsReadState | null>(null);
  const [campaigns, setCampaigns] = useState<GoogleAdsCampaign[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [requestError, setRequestError] = useState<string | null>(null);

  const [campaignId, setCampaignId] = useState('');
  const [changeType, setChangeType] = useState<ChangeType>('budget');
  const [afterValue, setAfterValue] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Submitted-but-unsettled changes, newest first (the Pending_Overlay, Req 7.6).
  const [pending, setPending] = useState<PendingChange[]>([]);

  const load = useCallback(async () => {
    if (!storeId) return;
    setLoading(true);
    setRequestError(null);
    try {
      const res = await fetchGoogleAdsCampaigns(storeId);
      setReadState(res.state);
      setMessage(res.message ?? null);
      if (res.state === 'OK') setCampaigns(res.data ?? []);
    } catch (err: any) {
      setRequestError(err?.message || '加载广告系列失败');
    } finally {
      setLoading(false);
    }
  }, [storeId]);

  useEffect(() => {
    load();
  }, [load]);

  const selected = campaigns.find((c) => c.campaignId === campaignId);

  function currentValue(): string {
    if (!selected) return '';
    if (changeType === 'budget') return selected.budget != null ? String(selected.budget) : '';
    if (changeType === 'status') return selected.status || '';
    return '';
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!storeId) {
      setFormError('请先选择一个独立站店铺');
      return;
    }
    if (!campaignId) {
      setFormError('请选择要调整的广告系列');
      return;
    }
    const after = afterValue.trim();
    if (!after) {
      setFormError('请输入新的值');
      return;
    }
    if ((changeType === 'budget' || changeType === 'bid')) {
      const n = Number(after);
      if (Number.isNaN(n) || n < 0) {
        setFormError('预算/出价必须是非负数字');
        return;
      }
    }
    if (changeType === 'status' && !['ENABLED', 'PAUSED'].includes(after.toUpperCase())) {
      setFormError('状态必须为 ENABLED 或 PAUSED');
      return;
    }

    const before = currentValue();
    try {
      setSubmitting(true);
      const res = await adjustGoogleAds({
        storeId,
        entityType: 'campaign',
        entityId: campaignId,
        changeType,
        beforeValue: before || undefined,
        afterValue: changeType === 'status' ? after.toUpperCase() : after,
      });
      setPending((prev) => [
        {
          key: res.operationId || `${campaignId}-${changeType}-${Date.now()}`,
          campaignName: selected?.name || campaignId,
          changeType,
          before: before || '-',
          after: changeType === 'status' ? after.toUpperCase() : after,
          syncState: res.syncState,
        },
        ...prev,
      ]);
      setAfterValue('');
    } catch (err: any) {
      setFormError(err?.message || '提交调整失败');
    } finally {
      setSubmitting(false);
    }
  }

  const header = <GaPageHeader title="Google Ads 调价" subtitle="调整广告系列预算 / 状态或关键词出价" />;

  if (storeLoading) return <GaLoading />;
  if (!storeId) return <div className="space-y-6">{header}<GaNoStore /></div>;

  let body: React.ReactNode;
  if (loading && campaigns.length === 0 && !requestError) {
    body = <GaLoading label="正在加载广告系列…" />;
  } else if (requestError) {
    body = <GaErrorRetry message={requestError} onRetry={load} />;
  } else if (readState === 'CONNECT_PROMPT') {
    body = <GaConnectPrompt message={message} />;
  } else if (readState === 'ERROR') {
    body = <GaErrorRetry message={message} onRetry={load} />;
  } else {
    body = (
      <div className="space-y-6">
        <form onSubmit={handleSubmit} className="bg-white rounded-xl border border-slate-200 p-5 space-y-4 max-w-2xl">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">广告系列 <span className="text-red-500">*</span></label>
            <select
              value={campaignId}
              onChange={(e) => setCampaignId(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 bg-white"
            >
              <option value="">请选择广告系列</option>
              {campaigns.map((c) => (
                <option key={c.campaignId} value={c.campaignId}>
                  {c.name || c.campaignId}
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">调整类型</label>
              <select
                value={changeType}
                onChange={(e) => {
                  setChangeType(e.target.value as ChangeType);
                  setAfterValue('');
                }}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 bg-white"
              >
                <option value="budget">预算</option>
                <option value="status">状态</option>
                <option value="bid">出价</option>
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">当前值</label>
              <div className="w-full h-10 rounded-lg border border-slate-100 bg-slate-50 px-3 text-sm text-slate-600 flex items-center">
                {currentValue() || <span className="text-slate-400">—</span>}
              </div>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">新的值 <span className="text-red-500">*</span></label>
            {changeType === 'status' ? (
              <select
                value={afterValue}
                onChange={(e) => setAfterValue(e.target.value)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 bg-white"
              >
                <option value="">请选择状态</option>
                <option value="ENABLED">ENABLED</option>
                <option value="PAUSED">PAUSED</option>
              </select>
            ) : (
              <input
                type="number"
                min="0"
                step="0.01"
                value={afterValue}
                onChange={(e) => setAfterValue(e.target.value)}
                placeholder={changeType === 'budget' ? '例如：80' : '例如：1.20'}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              />
            )}
          </div>

          {formError && <p className="text-sm text-red-500">{formError}</p>}

          <div className="flex justify-end pt-1">
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {submitting ? <Loader2 size={16} className="animate-spin" /> : <Target size={16} />}
              {submitting ? '提交中…' : '提交调整'}
            </button>
          </div>
        </form>

        {/* Pending_Overlay — unsettled changes show before → after + 待生效 (Req 7.6). */}
        {pending.length > 0 && (
          <div className="bg-white rounded-xl border border-slate-200 overflow-hidden max-w-3xl">
            <div className="px-5 py-3 border-b border-slate-100">
              <h2 className="text-sm font-semibold text-slate-700">待生效的变更</h2>
            </div>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
                  <th className="px-4 py-3">广告系列</th>
                  <th className="px-4 py-3">类型</th>
                  <th className="px-4 py-3">变更</th>
                  <th className="px-4 py-3">状态</th>
                </tr>
              </thead>
              <tbody>
                {pending.map((p) => (
                  <tr key={p.key} className="border-b border-slate-50">
                    <td className="px-4 py-3 font-medium text-slate-900">{p.campaignName}</td>
                    <td className="px-4 py-3 text-slate-700">{CHANGE_LABEL[p.changeType]}</td>
                    <td className="px-4 py-3 text-slate-700">
                      <span className="inline-flex items-center gap-2">
                        <span className="text-slate-400">
                          {p.changeType === 'budget' ? fmtNum(Number(p.before) || null, { currency: true }) : p.before}
                        </span>
                        <ArrowRight size={13} className="text-slate-300" />
                        <span className="font-medium text-slate-800">
                          {p.changeType === 'budget' ? fmtNum(Number(p.after) || null, { currency: true }) : p.after}
                        </span>
                      </span>
                    </td>
                    <td className="px-4 py-3"><GaPendingBadge /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    );
  }

  return <div className="space-y-6">{header}{body}</div>;
}
