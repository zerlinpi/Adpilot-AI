// AdPilot AI — TikTok Ads 监控面板 (read-only, embedded in the TikTok cockpit)
//
// Mirrors the Google Ads read views: campaigns + date-ranged performance, with
// the same honest tri-state handling — CONNECT_PROMPT (not bound) shows a
// connect guide, ERROR shows a readable reason + retry, OK renders the data.
// Read-only; TikTok Ads write/hosting is not yet wired and is not implied here.

import { useCallback, useEffect, useState } from 'react';
import { Megaphone, Link2, AlertTriangle, RefreshCw, Loader2 } from 'lucide-react';
import {
  fetchTikTokAdsCampaigns,
  fetchTikTokAdsReport,
  type TikTokAdsCampaign,
  type TikTokAdsPerformanceReport,
  type TikTokAdsReadState,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { cn, formatCurrency, formatNumber } from '../lib/utils';

function StatChip({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white px-3 py-2">
      <p className="text-[11px] text-slate-400">{label}</p>
      <p className="mt-0.5 text-sm font-semibold text-slate-800">{value}</p>
    </div>
  );
}

function ConnectPrompt({ message }: { message?: string | null }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 bg-white py-12 text-center">
      <Link2 size={40} className="mb-3 text-slate-300" />
      <p className="text-base font-medium text-slate-700">尚未连接 TikTok Ads</p>
      <p className="mt-1 max-w-md text-sm text-slate-500">
        {message || '请先为该 TikTok 店铺绑定 TikTok Ads 广告账号,连接后即可在此查看广告系列与绩效。'}
      </p>
    </div>
  );
}

function ErrorState({ message, onRetry }: { message?: string | null; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-red-100 bg-red-50/50 py-12 text-center">
      <AlertTriangle size={40} className="mb-3 text-red-300" />
      <p className="max-w-md text-sm text-slate-600">{message || '加载 TikTok Ads 数据失败。'}</p>
      <button
        onClick={onRetry}
        className="mt-4 inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
      >
        <RefreshCw size={14} /> 重试
      </button>
    </div>
  );
}

export function TikTokAdsPanel() {
  const { storeId } = useStoreId();
  const [state, setState] = useState<TikTokAdsReadState>('OK');
  const [message, setMessage] = useState<string | null>(null);
  const [campaigns, setCampaigns] = useState<TikTokAdsCampaign[]>([]);
  const [report, setReport] = useState<TikTokAdsPerformanceReport | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!storeId) return;
    setLoading(true);
    try {
      const [campaignsRes, reportRes] = await Promise.all([
        fetchTikTokAdsCampaigns(storeId),
        fetchTikTokAdsReport(storeId),
      ]);
      // The campaigns response drives the page state (connect/error/ok); both
      // endpoints share the same connection, so they agree on the state.
      setState(campaignsRes.state);
      setMessage(campaignsRes.message ?? reportRes.message ?? null);
      setCampaigns(campaignsRes.state === 'OK' ? campaignsRes.data ?? [] : []);
      setReport(reportRes.state === 'OK' ? reportRes.data ?? null : null);
    } catch (err: any) {
      setState('ERROR');
      setMessage(err?.message || '加载 TikTok Ads 数据失败');
    } finally {
      setLoading(false);
    }
  }, [storeId]);

  useEffect(() => {
    load();
  }, [load]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16 text-sm text-slate-400">
        <Loader2 size={18} className="mr-2 animate-spin" /> 加载 TikTok Ads 数据…
      </div>
    );
  }

  if (state === 'CONNECT_PROMPT') return <ConnectPrompt message={message} />;
  if (state === 'ERROR') return <ErrorState message={message} onRetry={load} />;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="inline-flex items-center gap-2 text-sm font-semibold text-slate-700">
          <Megaphone size={15} className="text-slate-400" /> TikTok Ads 监控
        </h2>
        <button
          onClick={load}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50"
        >
          <RefreshCw size={13} /> 刷新
        </button>
      </div>

      {/* Performance totals */}
      {report && (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
          <StatChip label="花费" value={formatCurrency(report.totalCost)} />
          <StatChip label="曝光" value={formatNumber(report.totalImpressions)} />
          <StatChip label="点击" value={formatNumber(report.totalClicks)} />
          <StatChip label="转化" value={formatNumber(report.totalConversions)} />
          <StatChip label="转化价值" value={formatCurrency(report.totalConversionValue)} />
        </div>
      )}

      {/* Campaigns table */}
      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-xs text-slate-500">
              <th className="px-4 py-2.5 font-medium">广告系列</th>
              <th className="px-4 py-2.5 font-medium">状态</th>
              <th className="px-4 py-2.5 text-right font-medium">花费</th>
              <th className="px-4 py-2.5 text-right font-medium">曝光</th>
              <th className="px-4 py-2.5 text-right font-medium">点击</th>
              <th className="px-4 py-2.5 text-right font-medium">转化</th>
            </tr>
          </thead>
          <tbody>
            {campaigns.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-slate-400">
                  该账号暂无广告系列数据。
                </td>
              </tr>
            ) : (
              campaigns.map((c) => (
                <tr key={c.campaignId} className="border-b border-slate-100 last:border-0">
                  <td className="px-4 py-2.5 font-medium text-slate-800">{c.name || c.campaignId}</td>
                  <td className="px-4 py-2.5">
                    <span className={cn(
                      'rounded-full px-2 py-0.5 text-[11px] font-medium',
                      (c.status || '').toUpperCase().includes('ENABLE') || (c.status || '').toUpperCase().includes('ACTIVE')
                        ? 'bg-emerald-50 text-emerald-700'
                        : 'bg-slate-100 text-slate-500',
                    )}>
                      {c.status || '—'}
                    </span>
                  </td>
                  <td className="px-4 py-2.5 text-right text-slate-700">{formatCurrency(c.cost)}</td>
                  <td className="px-4 py-2.5 text-right text-slate-700">{formatNumber(c.impressions)}</td>
                  <td className="px-4 py-2.5 text-right text-slate-700">{formatNumber(c.clicks)}</td>
                  <td className="px-4 py-2.5 text-right text-slate-700">{formatNumber(c.conversions)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default TikTokAdsPanel;
