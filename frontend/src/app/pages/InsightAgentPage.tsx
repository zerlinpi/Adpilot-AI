import { useState, useEffect, useMemo } from 'react';
import { Sparkles, Send, Lightbulb, AlertTriangle, Crown, ListChecks, History, Trash2 } from 'lucide-react';
import {
  submitInsightQuery,
  fetchInsightSuggestions,
  fetchSavedInsights,
  deleteSavedInsight,
  type InsightResult,
  type SavedInsight,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { useStoreContext } from '../lib/StoreContext';
import { cn } from '../lib/utils';

// Selectable analysis sources (Req 24.3).
const ANALYSIS_SOURCES: { value: string; label: string }[] = [
  { value: 'all', label: '全部数据' },
  { value: 'ads', label: '广告数据' },
  { value: 'listing', label: 'Listing 数据' },
  { value: 'keyword', label: '关键词数据' },
];

// Fallback prompts used if the suggestions endpoint is unavailable (Req 24.2).
const FALLBACK_PROMPTS = [
  '分析我的产品线表现',
  '对比最近30天的广告表现',
  '生成本周店铺总结',
  '诊断一个高ACoS的广告活动',
  '检查我的AI托管广告组',
];

export function InsightAgentPage() {
  const { storeId } = useStoreId();
  const { stores } = useStoreContext();
  const [prompts, setPrompts] = useState<string[]>(FALLBACK_PROMPTS);
  const [query, setQuery] = useState('');
  const [source, setSource] = useState('all');
  const [premium, setPremium] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<InsightResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  // The query that produced the current error, so the operator can resubmit (Req 24.4).
  const [lastQuery, setLastQuery] = useState('');

  // Saved-insights history (item 16): persists across reloads, manual delete.
  const [saved, setSaved] = useState<SavedInsight[]>([]);
  const [savedLoading, setSavedLoading] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const storeName = useMemo(
    () => stores.find((s) => s.id === storeId)?.name ?? '-',
    [stores, storeId],
  );

  useEffect(() => {
    let active = true;
    fetchInsightSuggestions()
      .then((list) => {
        if (active && list && list.length > 0) setPrompts(list);
      })
      .catch(() => {
        // Keep the fallback prompts on failure.
      });
    return () => {
      active = false;
    };
  }, []);

  // Load saved insights whenever the active store changes (item 16).
  useEffect(() => {
    loadSaved();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storeId]);

  async function loadSaved() {
    setSavedLoading(true);
    try {
      const list = await fetchSavedInsights(storeId);
      setSaved(list ?? []);
    } catch {
      // History is non-blocking; keep whatever was loaded before.
    } finally {
      setSavedLoading(false);
    }
  }

  async function handleDelete(id: string) {
    setDeletingId(id);
    try {
      await deleteSavedInsight(id);
      setSaved((prev) => prev.filter((s) => s.id !== id));
    } catch (err: any) {
      setError(err?.message || '删除洞察失败');
    } finally {
      setDeletingId(null);
    }
  }

  async function runQuery(text: string) {
    const q = text.trim();
    if (!q || submitting) return;
    setSubmitting(true);
    setError(null);
    setLastQuery(q);
    try {
      const res = await submitInsightQuery({ query: q, storeId, source, premium });
      setResult(res);
      // The backend persisted this insight; refresh the saved history (item 16).
      loadSaved();
    } catch (err: any) {
      setResult(null);
      setError(err?.message || '分析请求失败，请重试。');
    } finally {
      setSubmitting(false);
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    runQuery(query);
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
            <Sparkles size={22} className="text-blue-600" />
            Insight Agent
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            对话式 AI 数据分析师 · 当前店铺：{storeName}
          </p>
        </div>
      </div>

      {/* Suggested prompts (Req 24.2) */}
      <div className="bg-white rounded-xl border border-slate-200 p-4">
        <div className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-3">
          <Lightbulb size={16} className="text-amber-500" />
          建议的提问
        </div>
        <div className="flex flex-wrap gap-2">
          {prompts.map((p, i) => (
            <button
              key={i}
              type="button"
              onClick={() => {
                setQuery(p);
                runQuery(p);
              }}
              disabled={submitting}
              className="px-3 py-1.5 rounded-full border border-slate-200 text-sm text-slate-600 hover:border-blue-300 hover:bg-blue-50 hover:text-blue-700 transition-colors disabled:opacity-60"
            >
              {p}
            </button>
          ))}
        </div>
      </div>

      {/* Query form with source selector + Premium toggle (Req 24.3) */}
      <form onSubmit={handleSubmit} className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
        <textarea
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="用自然语言提出你的数据问题，例如：最近一周哪些广告活动的 ACoS 偏高？"
          rows={3}
          className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 resize-y"
        />
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-2">
            <label className="text-sm text-slate-600">分析来源</label>
            <select
              value={source}
              onChange={(e) => setSource(e.target.value)}
              className="h-9 rounded-lg border border-slate-200 px-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            >
              {ANALYSIS_SOURCES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </div>

          <button
            type="button"
            onClick={() => setPremium((v) => !v)}
            aria-pressed={premium}
            className={cn(
              'inline-flex items-center gap-1.5 px-3 h-9 rounded-lg border text-sm font-medium transition-colors',
              premium
                ? 'border-amber-300 bg-amber-50 text-amber-700'
                : 'border-slate-200 text-slate-600 hover:bg-slate-50',
            )}
          >
            <Crown size={15} />
            Premium {premium ? '已开启' : '已关闭'}
          </button>

          <button
            type="submit"
            disabled={submitting || !query.trim()}
            className="ml-auto inline-flex items-center gap-2 px-4 h-9 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-60"
          >
            <Send size={15} />
            {submitting ? '分析中...' : '提交分析'}
          </button>
        </div>
      </form>

      {/* Error with resubmit (Req 24.4) */}
      {error && (
        <div className="bg-red-50 border border-red-200 rounded-xl p-4 flex items-start gap-3">
          <AlertTriangle size={18} className="text-red-500 flex-shrink-0 mt-0.5" />
          <div className="flex-1">
            <p className="text-sm font-medium text-red-700">分析请求失败</p>
            <p className="text-sm text-red-600 mt-0.5">{error}</p>
          </div>
          <button
            type="button"
            onClick={() => runQuery(lastQuery)}
            disabled={submitting}
            className="px-3 py-1.5 rounded-lg bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-60"
          >
            重新提交
          </button>
        </div>
      )}

      {/* Result */}
      {result && !error && (
        <div className="bg-white rounded-xl border border-slate-200 p-5 space-y-4">
          <div className="flex items-center gap-2 text-sm text-slate-500">
            <Sparkles size={15} className="text-blue-500" />
            <span>洞察结果</span>
            {result.premium && (
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 text-xs border border-amber-200">
                <Crown size={11} /> Premium
              </span>
            )}
            <span className="ml-auto text-xs text-slate-400">
              来源：{ANALYSIS_SOURCES.find((s) => s.value === result.source)?.label ?? result.source}
              {result.generatedBy === 'ai' ? ' · AI 模型' : ' · 数据摘要'}
            </span>
          </div>

          <div className="text-sm text-slate-700 whitespace-pre-wrap leading-relaxed">{result.insights}</div>

          {result.recommendedActions && result.recommendedActions.length > 0 && (
            <div className="border-t border-slate-100 pt-4">
              <div className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-2">
                <ListChecks size={16} className="text-emerald-500" />
                推荐的下一步操作
              </div>
              <ul className="space-y-1.5">
                {result.recommendedActions.map((a, i) => (
                  <li key={i} className="flex items-start gap-2 text-sm text-slate-600">
                    <span className="mt-1.5 w-1.5 h-1.5 rounded-full bg-blue-400 flex-shrink-0" />
                    <span>{a}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {/* Saved insights history (item 16): persisted, manual delete */}
      <div className="bg-white rounded-xl border border-slate-200 p-5 space-y-4">
        <div className="flex items-center gap-2 text-sm font-medium text-slate-700">
          <History size={16} className="text-slate-500" />
          已保存的洞察
          <span className="text-xs font-normal text-slate-400">（保存至手动删除）</span>
          {savedLoading && <span className="text-xs font-normal text-slate-400">加载中…</span>}
        </div>

        {saved.length === 0 ? (
          <p className="text-sm text-slate-400">暂无已保存的洞察。提交分析后会自动保存到这里。</p>
        ) : (
          <ul className="space-y-3">
            {saved.map((s) => (
              <li key={s.id} className="rounded-lg border border-slate-200 p-4">
                <div className="flex items-start gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-sm font-medium text-slate-800">{s.query}</span>
                      {s.premium && (
                        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 text-xs border border-amber-200">
                          <Crown size={11} /> Premium
                        </span>
                      )}
                      <span className="text-xs text-slate-400">
                        {ANALYSIS_SOURCES.find((x) => x.value === s.source)?.label ?? s.source}
                        {s.generatedBy === 'ai' ? ' · AI 模型' : ' · 数据摘要'}
                      </span>
                      {s.createdAt && <span className="text-xs text-slate-400 ml-auto">{s.createdAt}</span>}
                    </div>
                    <div className="text-sm text-slate-600 whitespace-pre-wrap leading-relaxed mt-2">
                      {s.insights}
                    </div>
                    {s.recommendedActions && s.recommendedActions.length > 0 && (
                      <ul className="mt-2 space-y-1">
                        {s.recommendedActions.map((a, i) => (
                          <li key={i} className="flex items-start gap-2 text-xs text-slate-500">
                            <span className="mt-1.5 w-1 h-1 rounded-full bg-blue-300 flex-shrink-0" />
                            <span>{a}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => handleDelete(s.id)}
                    disabled={deletingId === s.id}
                    title="删除"
                    aria-label="删除"
                    className="flex-shrink-0 inline-flex items-center justify-center w-8 h-8 rounded-lg border border-slate-200 text-slate-400 hover:text-red-600 hover:border-red-300 hover:bg-red-50 transition-colors disabled:opacity-60"
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

export default InsightAgentPage;
