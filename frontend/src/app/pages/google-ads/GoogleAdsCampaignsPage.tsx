// Google Ads 列表 — campaign list view (platform-workspace-rbac Req 6.3).
//
// Retrieves the selected Store's Google Ads campaigns through the read service
// and renders name / status / budget / key metrics. Distinguishes the three
// read states: a connect prompt when no active connection (Req 6.6), an error
// indicator + retry when retrieval fails while leaving prior data unchanged
// (Req 6.5), and the campaign table on success.

import { useCallback, useEffect, useState } from 'react';
import { Megaphone, RefreshCw } from 'lucide-react';
import { fetchGoogleAdsCampaigns, type GoogleAdsCampaign, type GoogleAdsReadState } from '../../lib/api';
import { useStoreContext } from '../../lib/StoreContext';
import { cn } from '../../lib/utils';
import { GaConnectPrompt, GaEmpty, GaErrorRetry, GaLoading, GaNoStore, GaPageHeader, fmtNum } from './shared';

function statusBadge(status: string): string {
  const s = (status || '').toUpperCase();
  if (s === 'ENABLED' || s === 'ACTIVE') return 'bg-emerald-100 text-emerald-700 border-emerald-200';
  if (s === 'PAUSED') return 'bg-amber-100 text-amber-700 border-amber-200';
  if (s === 'REMOVED' || s === 'DISABLED') return 'bg-slate-100 text-slate-500 border-slate-200';
  return 'bg-slate-100 text-slate-600 border-slate-200';
}

export function GoogleAdsCampaignsPage() {
  const { storeId, loading: storeLoading } = useStoreContext();

  const [state, setState] = useState<GoogleAdsReadState | null>(null);
  const [campaigns, setCampaigns] = useState<GoogleAdsCampaign[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // A thrown request failure (transport/auth) — distinct from a read ERROR state
  // but surfaced the same way: an error indicator with a retry control (Req 6.5).
  const [requestError, setRequestError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!storeId) return;
    setLoading(true);
    setRequestError(null);
    try {
      const res = await fetchGoogleAdsCampaigns(storeId);
      setState(res.state);
      setMessage(res.message ?? null);
      // Leave previously displayed data unchanged on a non-OK result (Req 6.5).
      if (res.state === 'OK') setCampaigns(res.data ?? []);
    } catch (err: any) {
      setRequestError(err?.message || '加载 Google Ads 广告系列失败');
    } finally {
      setLoading(false);
    }
  }, [storeId]);

  useEffect(() => {
    load();
  }, [load]);

  const header = (
    <GaPageHeader
      title="Google Ads 广告系列"
      subtitle="独立站 Google Ads 广告系列与核心表现指标"
      actions={
        <button
          onClick={load}
          disabled={loading || !storeId}
          className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-60"
        >
          <RefreshCw size={15} className={cn(loading && 'animate-spin')} />
          刷新
        </button>
      }
    />
  );

  if (storeLoading) return <GaLoading />;
  if (!storeId) return <div className="space-y-6">{header}<GaNoStore /></div>;

  let body: React.ReactNode;
  if (loading && campaigns.length === 0 && !requestError) {
    body = <GaLoading label="正在加载广告系列…" />;
  } else if (requestError) {
    body = <GaErrorRetry message={requestError} onRetry={load} />;
  } else if (state === 'CONNECT_PROMPT') {
    body = <GaConnectPrompt message={message} />;
  } else if (state === 'ERROR') {
    body = <GaErrorRetry message={message} onRetry={load} />;
  } else if (campaigns.length === 0) {
    body = <GaEmpty icon={<Megaphone size={48} />} title="暂无广告系列" hint="该店铺的 Google Ads 账号下没有广告系列。" />;
  } else {
    body = (
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
              <th className="px-4 py-3">广告系列</th>
              <th className="px-4 py-3">状态</th>
              <th className="px-4 py-3 text-right">预算</th>
              <th className="px-4 py-3 text-right">展示</th>
              <th className="px-4 py-3 text-right">点击</th>
              <th className="px-4 py-3 text-right">花费</th>
              <th className="px-4 py-3 text-right">转化</th>
              <th className="px-4 py-3 text-right">转化价值</th>
            </tr>
          </thead>
          <tbody>
            {campaigns.map((c) => (
              <tr key={c.campaignId} className="border-b border-slate-50 hover:bg-slate-50/60">
                <td className="px-4 py-3 font-medium text-slate-900">{c.name || c.campaignId}</td>
                <td className="px-4 py-3">
                  <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border', statusBadge(c.status))}>
                    {c.status || '-'}
                  </span>
                </td>
                <td className="px-4 py-3 text-right text-slate-700">{fmtNum(c.budget, { currency: true })}</td>
                <td className="px-4 py-3 text-right text-slate-700">{fmtNum(c.impressions)}</td>
                <td className="px-4 py-3 text-right text-slate-700">{fmtNum(c.clicks)}</td>
                <td className="px-4 py-3 text-right text-slate-700">{fmtNum(c.cost, { currency: true })}</td>
                <td className="px-4 py-3 text-right text-slate-700">{fmtNum(c.conversions, { digits: 0 })}</td>
                <td className="px-4 py-3 text-right text-slate-700">{fmtNum(c.conversionValue, { currency: true })}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  return <div className="space-y-6">{header}{body}</div>;
}
