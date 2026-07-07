import { useState, useEffect, useCallback, useMemo } from 'react';
import { Plus, Library, Wheat, Ban, AlertCircle, BookOpen, Settings2, Tag } from 'lucide-react';
import {
  fetchKeywordLibraries,
  createKeywordLibrary,
  fetchKeywordInsights,
  harvestKeywordRecommendation,
  negateKeywordRecommendation,
  fetchProducts,
  fetchKeywordConfig,
  addKeywordConfig,
  type KeywordLibrary,
  type KeywordLibraryCreateInput,
  type KeywordConfig,
  type KeywordConfigCategory,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { cn } from '../lib/utils';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// ─── Library type label map (词库类型) ────────────────────────────────
const libraryTypeOptions: { value: string; label: string }[] = [
  { value: 'harvest', label: '收割词库' },
  { value: 'negative', label: '否定词库' },
  { value: 'brand', label: '品牌词库' },
  { value: 'competitor', label: '竞品词库' },
];

const libraryTypeLabelMap: Record<string, string> = libraryTypeOptions.reduce(
  (acc, o) => ({ ...acc, [o.value]: o.label }),
  {} as Record<string, string>,
);

function libraryTypeLabel(value?: string | null): string {
  if (!value) return '-';
  return libraryTypeLabelMap[value] || value;
}

const libraryTypeBadge: Record<string, string> = {
  harvest: 'bg-emerald-100 text-emerald-700 border-emerald-200',
  negative: 'bg-red-100 text-red-600 border-red-200',
  brand: 'bg-blue-100 text-blue-700 border-blue-200',
  competitor: 'bg-amber-100 text-amber-700 border-amber-200',
};

type TabKey = 'libraries' | 'recommendations' | 'config';

const tabs: { key: TabKey; label: string }[] = [
  { key: 'libraries', label: '词库' },
  { key: 'recommendations', label: '关键词推荐' },
  { key: 'config', label: '关键词与自动化配置' },
];

// ─── Keyword config categories (关键词与自动化配置) ───────────────────────
const configCategories: { key: KeywordConfigCategory; label: string; placeholder: string }[] = [
  { key: 'brand', label: '品牌关键词', placeholder: '输入品牌关键词，回车或逗号分隔' },
  { key: 'category', label: '品类关键词', placeholder: '输入品类关键词，回车或逗号分隔' },
  { key: 'competitorBrand', label: '竞品品牌', placeholder: '输入竞品品牌，回车或逗号分隔' },
  { key: 'competitorAsin', label: '竞品 ASIN', placeholder: '输入竞品 ASIN，回车或逗号分隔' },
];

interface ProductOption {
  id: string;
  label: string;
}

// ─── Loading skeleton ────────────────────────────────────────────────
function PageSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="h-8 w-40 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-28 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="h-12 bg-slate-100 rounded animate-pulse" />
        ))}
      </div>
    </div>
  );
}

function EmptyState({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <div className="text-slate-300 mb-4">{icon}</div>
      <p className="text-lg font-medium text-slate-500">{text}</p>
    </div>
  );
}

// ─── Create Library Modal ────────────────────────────────────────────
function CreateLibraryModal({
  storeId,
  products,
  onClose,
  onCreated,
}: {
  storeId: string;
  products: ProductOption[];
  onClose: () => void;
  onCreated: (created: KeywordLibrary) => void;
}) {
  const [name, setName] = useState('');
  const [libraryType, setLibraryType] = useState(libraryTypeOptions[0].value);
  const [keywordsText, setKeywordsText] = useState('');
  const [selectedProducts, setSelectedProducts] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  function toggleProduct(id: string) {
    setSelectedProducts((prev) =>
      prev.includes(id) ? prev.filter((p) => p !== id) : [...prev, id],
    );
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);
    setFormError(null);

    if (!name.trim()) {
      setFieldError('请输入词库名称');
      return;
    }
    if (!libraryType) {
      setFieldError('请选择词库类型');
      return;
    }

    const keywords = keywordsText
      .split(/[\n,]/)
      .map((k) => k.trim())
      .filter((k) => k.length > 0);

    const payload: KeywordLibraryCreateInput = {
      storeId,
      name: name.trim(),
      libraryType,
      productIds: selectedProducts.length > 0 ? selectedProducts : undefined,
      keywords: keywords.length > 0 ? keywords : undefined,
    };

    try {
      setSubmitting(true);
      const created = await createKeywordLibrary(payload);
      onCreated(created);
    } catch (err: any) {
      setFormError(err?.message || '创建词库失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose(); }}>
      <DialogContent className="block gap-0 w-full sm:max-w-lg rounded-xl border-0 bg-white p-0 shadow-xl max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4 sticky top-0 bg-white">
          <DialogTitle className="text-lg font-semibold text-slate-900">创建词库</DialogTitle>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">词库名称</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：核心收割词"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">词库类型</label>
            <select
              value={libraryType}
              onChange={(e) => setLibraryType(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            >
              {libraryTypeOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">关联商品（可选）</label>
            {products.length === 0 ? (
              <p className="text-sm text-slate-400">当前店铺暂无商品</p>
            ) : (
              <div className="max-h-32 overflow-y-auto border border-slate-200 rounded-lg p-2 space-y-1">
                {products.map((p) => (
                  <label key={p.id} className="flex items-center gap-2 text-sm text-slate-700 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={selectedProducts.includes(p.id)}
                      onChange={() => toggleProduct(p.id)}
                      className="rounded border-slate-300"
                    />
                    {p.label}
                  </label>
                ))}
              </div>
            )}
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">关键词（可选，每行或逗号分隔一个）</label>
            <textarea
              value={keywordsText}
              onChange={(e) => setKeywordsText(e.target.value)}
              rows={4}
              placeholder="keyword one&#10;keyword two"
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
          </div>

          {fieldError && <p className="text-sm text-red-500">{fieldError}</p>}
          {formError && <p className="text-sm text-red-500">{formError}</p>}

          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-lg border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
            >
              {submitting ? '创建中...' : '创建'}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ─── Libraries table (词库) ──────────────────────────────────────────
function LibrariesTable({ rows }: { rows: KeywordLibrary[] }) {
  if (rows.length === 0) {
    return <EmptyState icon={<Library size={48} />} text="暂无词库，点击「创建词库」开始" />;
  }
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
            <th className="px-4 py-3">词库名称</th>
            <th className="px-4 py-3">词库类型</th>
            <th className="px-4 py-3 text-right">关联商品</th>
            <th className="px-4 py-3 text-right">关键词数量</th>
            <th className="px-4 py-3">上次执行</th>
            <th className="px-4 py-3">下次执行</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((lib) => (
            <tr key={lib.id} className="border-b border-slate-50 hover:bg-slate-50/60">
              <td className="px-4 py-3 font-medium text-slate-900">{lib.name}</td>
              <td className="px-4 py-3">
                <span
                  className={cn(
                    'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
                    libraryTypeBadge[lib.libraryType] || 'bg-slate-100 text-slate-600 border-slate-200',
                  )}
                >
                  {libraryTypeLabel(lib.libraryType)}
                </span>
              </td>
              <td className="px-4 py-3 text-right text-slate-700">{lib.associatedProductCount}</td>
              <td className="px-4 py-3 text-right text-slate-700">{lib.keywordCount}</td>
              <td className="px-4 py-3 text-slate-500">{lib.lastRunAt || '尚未执行'}</td>
              <td className="px-4 py-3 text-slate-500">{lib.nextRunAt || '未排程'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ─── Recommendations table (关键词推荐) ──────────────────────────────
function RecommendationsTable({
  rows,
  onHarvest,
  onNegate,
  pendingId,
}: {
  rows: any[];
  onHarvest: (id: string) => void;
  onNegate: (id: string) => void;
  pendingId: string | null;
}) {
  if (rows.length === 0) {
    return <EmptyState icon={<BookOpen size={48} />} text="暂无关键词推荐" />;
  }
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
            <th className="px-4 py-3">关键词</th>
            <th className="px-4 py-3">分类</th>
            <th className="px-4 py-3">建议</th>
            <th className="px-4 py-3">原因</th>
            <th className="px-4 py-3 text-center">操作</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const busy = pendingId === r.id;
            return (
              <tr key={r.id} className="border-b border-slate-50 hover:bg-slate-50/60 align-top">
                <td className="px-4 py-3 font-medium text-slate-900">{r.text}</td>
                <td className="px-4 py-3 text-slate-600">{r.segment || '-'}</td>
                <td className="px-4 py-3 text-slate-600">{r.recommendedAction || '-'}</td>
                <td className="px-4 py-3 text-slate-500 max-w-md">{r.reason || '-'}</td>
                <td className="px-4 py-3">
                  <div className="flex items-center justify-center gap-2">
                    <button
                      onClick={() => onHarvest(r.id)}
                      disabled={busy}
                      title="收割为关键词"
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-md text-xs font-medium text-emerald-700 bg-emerald-50 hover:bg-emerald-100 border border-emerald-200 disabled:opacity-50"
                    >
                      <Wheat size={13} />
                      收割
                    </button>
                    <button
                      onClick={() => onNegate(r.id)}
                      disabled={busy}
                      title="添加为否定关键词"
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-md text-xs font-medium text-red-600 bg-red-50 hover:bg-red-100 border border-red-200 disabled:opacity-50"
                    >
                      <Ban size={13} />
                      否定
                    </button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ─── Keyword & Automation Config panel (关键词与自动化配置) ──────────────
function ConfigCategoryCard({
  label,
  placeholder,
  terms,
  onAdd,
  busy,
}: {
  label: string;
  placeholder: string;
  terms: string[];
  onAdd: (terms: string[]) => void;
  busy: boolean;
}) {
  const [value, setValue] = useState('');

  function submit() {
    const parsed = value
      .split(/[\n,]/)
      .map((t) => t.trim())
      .filter((t) => t.length > 0);
    if (parsed.length === 0) return;
    onAdd(parsed);
    setValue('');
  }

  return (
    <div className="bg-white rounded-xl border border-slate-200 p-5">
      <div className="flex items-center gap-2 mb-3">
        <Tag size={16} className="text-blue-600" />
        <h3 className="text-sm font-semibold text-slate-900">{label}</h3>
        <span className="text-xs text-slate-400">({terms.length})</span>
      </div>

      <div className="flex items-center gap-2 mb-3">
        <input
          type="text"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              submit();
            }
          }}
          placeholder={placeholder}
          className="flex-1 h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
        />
        <button
          onClick={submit}
          disabled={busy || value.trim().length === 0}
          className="inline-flex items-center gap-1 px-3 h-9 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-50"
        >
          <Plus size={14} />
          添加
        </button>
      </div>

      {terms.length === 0 ? (
        <p className="text-sm text-slate-400">暂无配置</p>
      ) : (
        <div className="flex flex-wrap gap-1.5">
          {terms.map((t) => (
            <span
              key={t}
              className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium bg-slate-100 text-slate-700 border border-slate-200"
            >
              {t}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

function ConfigPanel({
  config,
  onAdd,
  pendingCategory,
}: {
  config: KeywordConfig | null;
  onAdd: (category: KeywordConfigCategory, terms: string[]) => void;
  pendingCategory: KeywordConfigCategory | null;
}) {
  return (
    <div className="space-y-4">
      <div className="flex items-start gap-2 text-sm text-slate-500">
        <Settings2 size={16} className="text-slate-400 mt-0.5 shrink-0" />
        <p>配置店铺级种子词，用于驱动关键词收割、否定与自动化。</p>
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {configCategories.map((c) => (
          <ConfigCategoryCard
            key={c.key}
            label={c.label}
            placeholder={c.placeholder}
            terms={(config?.[c.key] as string[]) ?? []}
            busy={pendingCategory === c.key}
            onAdd={(terms) => onAdd(c.key, terms)}
          />
        ))}
      </div>
    </div>
  );
}

// ─── Main Keyword Library Page ───────────────────────────────────────
export function KeywordLibraryPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const [activeTab, setActiveTab] = useState<TabKey>('libraries');

  const [libraries, setLibraries] = useState<KeywordLibrary[]>([]);
  const [recommendations, setRecommendations] = useState<any[]>([]);
  const [products, setProducts] = useState<ProductOption[]>([]);
  const [config, setConfig] = useState<KeywordConfig | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [pendingCategory, setPendingCategory] = useState<KeywordConfigCategory | null>(null);
  const [actionMessage, setActionMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const loadAll = useCallback(async () => {
    if (!storeId) return;
    try {
      setLoading(true);
      setError(null);
      const [libs, recs, prods, cfg] = await Promise.all([
        fetchKeywordLibraries(storeId),
        fetchKeywordInsights(storeId, { status: 'open', pageSize: 100 }),
        fetchProducts(storeId),
        fetchKeywordConfig(storeId).catch(() => null),
      ]);
      setLibraries(libs ?? []);
      setRecommendations(recs ?? []);
      setProducts(
        (prods ?? []).map((p: any) => ({
          id: p.id,
          label: p.asin ? `${p.title || p.name || p.asin} (${p.asin})` : p.title || p.name || p.id,
        })),
      );
      setConfig(cfg);
    } catch (err: any) {
      setError(err?.message || '加载词库数据失败');
    } finally {
      setLoading(false);
    }
  }, [storeId]);

  useEffect(() => {
    if (storeId) {
      loadAll();
    } else if (!storeLoading) {
      // Store list resolved but no store is selected/available — stop showing the
      // skeleton forever; render the no-store guidance below instead.
      setLoading(false);
    }
  }, [storeId, storeLoading, loadAll]);

  function flashMessage(type: 'success' | 'error', text: string) {
    setActionMessage({ type, text });
    setTimeout(() => setActionMessage(null), 4000);
  }

  async function handleHarvest(id: string) {
    setPendingId(id);
    try {
      await harvestKeywordRecommendation(id);
      setRecommendations((prev) => prev.filter((r) => r.id !== id));
      flashMessage('success', '已收割为关键词');
      // Refresh libraries since a harvest may add a library item.
      fetchKeywordLibraries(storeId).then((libs) => setLibraries(libs ?? [])).catch(() => { });
    } catch (err: any) {
      flashMessage('error', err?.message || '收割失败');
    } finally {
      setPendingId(null);
    }
  }

  async function handleNegate(id: string) {
    setPendingId(id);
    try {
      await negateKeywordRecommendation(id);
      setRecommendations((prev) => prev.filter((r) => r.id !== id));
      flashMessage('success', '已添加为否定关键词');
    } catch (err: any) {
      flashMessage('error', err?.message || '否定失败');
    } finally {
      setPendingId(null);
    }
  }

  async function handleAddConfig(category: KeywordConfigCategory, terms: string[]) {
    if (!storeId) return;
    setPendingCategory(category);
    try {
      const updated = await addKeywordConfig(storeId, category, terms);
      setConfig(updated);
      flashMessage('success', '配置已更新');
    } catch (err: any) {
      flashMessage('error', err?.message || '保存配置失败');
    } finally {
      setPendingCategory(null);
    }
  }

  const activeBody = useMemo(() => {
    switch (activeTab) {
      case 'libraries':
        return <LibrariesTable rows={libraries} />;
      case 'recommendations':
        return (
          <RecommendationsTable
            rows={recommendations}
            onHarvest={handleHarvest}
            onNegate={handleNegate}
            pendingId={pendingId}
          />
        );
      case 'config':
        return (
          <ConfigPanel
            config={config}
            onAdd={handleAddConfig}
            pendingCategory={pendingCategory}
          />
        );
      default:
        return null;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, libraries, recommendations, pendingId, config, pendingCategory]);

  if (loading || storeLoading) return <PageSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Library size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出错了</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">{storeError || error}</p>
        <button
          onClick={loadAll}
          className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
        >
          重试
        </button>
      </div>
    );
  }

  // Store context resolved but the user has no accessible/selected store — show
  // clear guidance instead of an empty page or an endless skeleton.
  if (!storeId) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Library size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">
          请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用关键词词库。
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">关键词词库</h1>
          <p className="text-sm text-slate-500 mt-1">组织关键词词库，处理关键词推荐</p>
        </div>
        {activeTab === 'libraries' && (
          <button
            onClick={() => setShowCreate(true)}
            disabled={!storeId}
            className="inline-flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors shadow-sm disabled:opacity-60"
          >
            <Plus size={16} />
            创建词库
          </button>
        )}
      </div>

      {/* Action Toast */}
      {actionMessage && (
        <div
          className={cn(
            'flex items-center gap-3 px-4 py-3 rounded-lg border',
            actionMessage.type === 'success'
              ? 'bg-emerald-50 border-emerald-200 text-emerald-700'
              : 'bg-red-50 border-red-200 text-red-700',
          )}
        >
          <AlertCircle size={16} className="shrink-0" />
          <p className="text-sm">{actionMessage.text}</p>
          <button onClick={() => setActionMessage(null)} className="ml-auto text-sm font-medium underline">
            关闭
          </button>
        </div>
      )}

      {/* Tabs */}
      <div className="flex items-center gap-1 border-b border-slate-200">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              'px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors',
              activeTab === tab.key
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-slate-500 hover:text-slate-700',
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeBody}

      {showCreate && storeId && (
        <CreateLibraryModal
          storeId={storeId}
          products={products}
          onClose={() => setShowCreate(false)}
          onCreated={(created) => {
            setShowCreate(false);
            setLibraries((prev) => [created, ...prev]);
            loadAll();
          }}
        />
      )}
    </div>
  );
}
