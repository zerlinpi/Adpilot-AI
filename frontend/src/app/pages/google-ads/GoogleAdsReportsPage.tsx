// Google Ads 报告 — performance report view (platform-workspace-rbac Req 6.4).
//
// Retrieves the selected Store's date-ranged Google Ads performance (per-day
// rows + range totals) through the read service. Same tri-state handling as the
// 列表 view: connect prompt (Req 6.6), error + retry (Req 6.5), or the report.

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import {
  fetchGoogleAdsReport,
  type GoogleAdsPerformanceReport,
  type GoogleAdsReadState,
} from '../../lib/api';
import { useStoreContext } from '../../lib/StoreContext';
import { cn } from '../../lib/utils';
import { GaConnectPrompt, GaErrorRetry, GaLoading, GaNoStore, GaPageHeader, fmtNum } from './shared';

function isoDaysAgo(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 px-5 py-4">
      <p className="text-xs font-medium text-slate-500">{label}</p>
      <p className="text-xl font-semibold text-slate-900 mt-1">{value}</p>
    </div>
  );
}

export function GoogleAdsReportsPage() {
  const { storeId, loading: storeLoading } = useStoreContext();

  const [from, setFrom] = useState<string>(isoDaysAgo(14));
  const [to, setTo] = useState<string>(isoDaysAgo(0));

  const [state, setState] = useState<GoogleAdsReadState | null>(null);
  const [report, setReport] = useState<GoogleAdsPerformanceReport | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [requestError, setRequestError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!storeId) return;
    setLoading(true);
    setRequestError(null);
    try {
      const res = await fetchGoogleAdsReport(storeId, { from, to });
      setState(res.state);
      setMessage(res.message ?? null);
      if (res.state === 'OK') setReport(res.data ?? null);
    } catch (err: any) {
      setRequestError(err?.message || '加载 Google Ads 报告失败');
    } finally {
      setLoading(false);
    }
  }, [storeId, from, to]);

  useEffect(() => {
    load();
  }, [storeId]); // eslint-disable-line react-hooks/exhaustive-deps

  const header = (
    <GaPageHeader
      title="Google Ads 报告"
      subtitle="按日期范围查看展示、点击、花费、转化与转化价值"
      actions={
        <div className="flex items-center gap-2">
          <input
            type="date"
            value={from}
            max={to}
            onChange={(e) => setFrom(e.target.value)}
            className="h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
          />
          <span className="text-slate-400 text-sm">至</span>
          <input
            type="date"
            value={to}
            min={from}
            onChange={(e) => setTo(e.target.value)}
            className="h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
          />
          <button
            onClick={load}
            disabled={loading || !storeId}
            className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
          >
            <RefreshCw size={15} className={cn(loading && 'animate-spin')} />
            查询
          </button>
        </div>
      }
    />
  );

  if (storeLoading) return <GaLoading />;
  if (!storeId) return <div className="space-y-6">{header}<GaNoStore /></div>;

  let body: React.ReactNode;
  if (loading && !report && !requestError) {
    body = <GaLoading label="正在加载报告…" />;
  } else if (requestError) {
    body = <GaErrorRetry message={requestError} onRetry={load} />;
  } else if (state === 'CONNECT_PROMPT') {
    body = <GaConnectPrompt message={message} />;
  } else if (state === 'ERROR') {
    body = <GaErrorRetry message={message} onRetry={load} />;
  } else if (report) {
    body = (
      <div className="space-y-6">
        <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
          <StatCard label="展示" value={fmtNum(report.totalImpressions)} />
          <StatCard label="点击" value={fmtNum(report.totalClicks)} />
          <StatCard label="花费" value={fmtNum(report.totalCost, { currency: true })} />
          <StatCard label="转化" value={fmtNum(report.totalConversions, { digits: 0 })} />
          <StatCard label="转化价值" value={fmtNum(report.totalConversionValue, { currency: true })} />
        </div>

        <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
                <th className="px-4 py-3">日期</th>
                <th className="px-4 py-3 text-right">展示</th>
                <th className="px-4 py-3 text-right">点击</th>
                <th className="px-4 py-3 text-right">花费</th>
                <th className="px-4 py-3 text-right">转化</th>
                <th className="px-4 py-3 text-right">转化价值</th>
              </tr>
            </thead>
            <tbody>
              {(report.rows ?? []).length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-10 text-center text-slate-400">所选范围内暂无数据</td>
                </tr>
              ) : (
                report.rows.map((r) => (
                  <tr key={r.date} className="border-b border-slate-50 hover:bg-slate-50/60">
                    <td className="px-4 py-3 text-slate-700">{r.date}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{fmtNum(r.impressions)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{fmtNum(r.clicks)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{fmtNum(r.cost, { currency: true })}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{fmtNum(r.conversions, { digits: 0 })}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{fmtNum(r.conversionValue, { currency: true })}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    );
  } else {
    body = <GaLoading label="正在加载报告…" />;
  }

  return <div className="space-y-6">{header}{body}</div>;
}
