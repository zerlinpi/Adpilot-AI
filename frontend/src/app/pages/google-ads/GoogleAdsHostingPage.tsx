// Google Ads AI托管 — AI hosting control view (platform-workspace-rbac Req 8).
//
// Triggers one Google Ads AI-hosting pass for the selected Store. The engine
// reads performance over the resolved lookback window, produces candidate
// decisions, clamps them to the safety boundary, and routes accepted candidates
// through the platform-generic Optimization_Coordinator → approval/execution
// pipeline. This view surfaces the run summary (processed / skipped / failed /
// operations created) and the per-campaign skip reasons.

import { useState } from 'react';
import { Brain, Loader2, PlayCircle } from 'lucide-react';
import { runGoogleAdsHosting, type GoogleAdsHostingRunSummary } from '../../lib/api';
import { useStoreContext } from '../../lib/StoreContext';
import { GaErrorRetry, GaNoStore, GaPageHeader } from './shared';

function SummaryCard({ label, value, tone }: { label: string; value: number; tone?: 'default' | 'warn' | 'danger' | 'ok' }) {
  const toneCls =
    tone === 'ok' ? 'text-emerald-600'
      : tone === 'warn' ? 'text-amber-600'
        : tone === 'danger' ? 'text-red-600'
          : 'text-slate-900';
  return (
    <div className="bg-white rounded-xl border border-slate-200 px-5 py-4">
      <p className="text-xs font-medium text-slate-500">{label}</p>
      <p className={`text-2xl font-semibold mt-1 ${toneCls}`}>{value}</p>
    </div>
  );
}

export function GoogleAdsHostingPage() {
  const { storeId, stores, loading: storeLoading } = useStoreContext();
  const storeName = stores.find((s) => s.id === storeId)?.name;

  const [running, setRunning] = useState(false);
  const [summary, setSummary] = useState<GoogleAdsHostingRunSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function runPass() {
    if (!storeId) return;
    setRunning(true);
    setError(null);
    try {
      const res = await runGoogleAdsHosting(storeId);
      setSummary(res);
    } catch (err: any) {
      setError(err?.message || '运行 AI 托管失败');
    } finally {
      setRunning(false);
    }
  }

  const header = (
    <GaPageHeader
      title="Google Ads AI 托管"
      subtitle="按托管策略运行一次优化，决策将进入审批 / 执行管道"
      actions={
        <button
          onClick={runPass}
          disabled={running || !storeId}
          className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed"
        >
          {running ? <Loader2 size={16} className="animate-spin" /> : <PlayCircle size={16} />}
          {running ? '运行中…' : '运行一次优化'}
        </button>
      }
    />
  );

  if (storeLoading) return header;
  if (!storeId) return <div className="space-y-6">{header}<GaNoStore /></div>;

  return (
    <div className="space-y-6">
      {header}

      <div className="bg-blue-50 border border-blue-100 rounded-xl px-5 py-4 flex items-start gap-3">
        <Brain size={18} className="text-blue-500 mt-0.5 shrink-0" />
        <p className="text-sm text-blue-900">
          AI 托管会根据 {storeName ? `「${storeName}」` : '当前店铺'} 的 Google Ads 表现数据生成预算/出价/状态调整建议，
          并在安全边界内裁剪后交由统一的审批与执行管道处理。在 observe_only / recommend_only 模式下不会真正提交到 Google Ads。
        </p>
      </div>

      {error && <GaErrorRetry message={error} onRetry={runPass} />}

      {summary && !error && (
        <div className="space-y-6">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <SummaryCard label="已处理" value={summary.processed} />
            <SummaryCard label="已跳过" value={summary.skipped} tone="warn" />
            <SummaryCard label="失败" value={summary.failed} tone={summary.failed > 0 ? 'danger' : 'default'} />
            <SummaryCard label="生成操作数" value={summary.operationsCreated} tone="ok" />
          </div>

          {summary.skips && summary.skips.length > 0 && (
            <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
              <div className="px-5 py-3 border-b border-slate-100">
                <h2 className="text-sm font-semibold text-slate-700">跳过的广告系列</h2>
              </div>
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
                    <th className="px-4 py-3">广告系列 ID</th>
                    <th className="px-4 py-3">原因</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.skips.map((s, i) => (
                    <tr key={`${s.campaignId}-${i}`} className="border-b border-slate-50">
                      <td className="px-4 py-3 text-slate-700 break-all">{s.campaignId}</td>
                      <td className="px-4 py-3 text-slate-600">{s.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {summary.operationsCreated === 0 && summary.skips.length === 0 && summary.failed === 0 && (
            <p className="text-sm text-slate-500">本次运行未产生新的优化操作。</p>
          )}
        </div>
      )}
    </div>
  );
}
