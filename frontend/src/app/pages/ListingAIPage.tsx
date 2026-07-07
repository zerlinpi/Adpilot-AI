import { useState, useEffect, useCallback } from 'react';
import { useParams, Link } from 'react-router';
import {
  Wand2,
  Loader2,
  CheckCircle2,
  AlertTriangle,
  Info,
  Save,
  Download,
  Eye,
  RotateCcw,
  FileText,
  Target,
  Type,
  List,
  AlignLeft,
  Search,
  Users,
  Crosshair,
  ArrowLeft,
  Sparkles,
  ShieldCheck,
  BarChart3,
  RefreshCw,
  Upload,
} from 'lucide-react';
import { cn } from '../lib/utils';
import { useStoreContext } from '../lib/StoreContext';
import { getChannelCapability } from '../lib/channelCapabilities';
import { notify } from '../lib/toast';
import {
  fetchListingContent,
  generateListingDraft,
  scoreListing,
  checkListingCompliance,
  fetchKeywordMapping,
  fetchListingVersions,
  updateListingDraft,
  approveListingDraft,
} from '../lib/api';
import { PRODUCT_UPLOAD_ROUTE } from './ProductUploadPage';

// ─── Product upload entry point (Req 8.1, 8.2) ─────────────────────────────
//
// Builds the navigation target for the "上传到站点" action that carries the
// current product (and optionally the target marketplace) into the product
// upload workflow. The destination route reads this context from the query
// string on arrival (see `resolveUploadContext` in ProductUploadPage). Pure
// and exported for unit testing.

export function buildProductUploadHref(
  productId: string,
  marketplaceId?: string | null,
): string {
  const params = new URLSearchParams();
  if (productId) params.set('productId', productId);
  if (marketplaceId) params.set('marketplaceId', marketplaceId);
  const query = params.toString();
  return query ? `${PRODUCT_UPLOAD_ROUTE}?${query}` : PRODUCT_UPLOAD_ROUTE;
}

// ─── Product-id resolution (Req 7.1, 7.6) ──────────────────────────────────
//
// The Listing studio is reached at `products/:id/listing-ai`. The backend
// listing endpoints (`/api/listing-ai/products/{id}/...`) are UUID-guarded and
// return empty results for a non-UUID id, which surfaces as "ASIN: N/A" with
// non-functional actions. We therefore resolve the route param to a *valid*
// product id and treat anything else (the nav placeholder `new`, an empty
// param, or any non-UUID value) as "no product selected" so the page can
// prompt the specialist to pick a product instead of rendering "ASIN: N/A".

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Structured attribute fields for Entity SEO (must match the backend
// ENTITY_ATTRIBUTE_KEYS). These are the attributes COSMO / Rufus / Alexa+ use
// to match shopper intent, so completeness is encouraged in the editor.
const ENTITY_ATTRIBUTE_FIELDS: { key: string; label: string; placeholder: string }[] = [
  { key: 'Material', label: '材质', placeholder: '例如：304 不锈钢' },
  { key: 'Color', label: '颜色', placeholder: '例如：哑光黑' },
  { key: 'Size', label: '尺寸/规格', placeholder: '例如：500ml' },
  { key: 'Compatibility', label: '兼容性', placeholder: '例如：适配 MacBook Air' },
  { key: 'UseCase', label: '使用场景', placeholder: '例如：户外徒步、办公' },
  { key: 'TargetAudience', label: '目标人群', placeholder: '例如：老年犬、通勤人群' },
  { key: 'SpecialFeatures', label: '特性', placeholder: '例如：防漏、BPA-Free' },
];

/**
 * Resolve the Listing studio route param to a valid product id, or null when
 * no product is resolvable. Pure and exported for unit testing (Req 7.1, 7.6).
 */
export function resolveListingProductId(routeId: string | null | undefined): string | null {
  if (!routeId) return null;
  const trimmed = routeId.trim();
  if (!trimmed) return null;
  // `new` is the placeholder used by the sidebar entry; `product-1` was a
  // legacy hardcoded fallback. Neither identifies a real product.
  if (trimmed.toLowerCase() === 'new' || trimmed.toLowerCase() === 'product-1') return null;
  return UUID_RE.test(trimmed) ? trimmed : null;
}

// ─── Helper components ──────────────────────────────────────────────────────

function ScoreGauge({ score, size = 140 }: { score: number; size?: number }) {
  const radius = (size - 16) / 2;
  const circumference = 2 * Math.PI * radius;
  const progress = (score / 100) * circumference;
  const color =
    score >= 80 ? 'text-emerald-500' : score >= 60 ? 'text-amber-500' : 'text-red-500';
  const bgColor =
    score >= 80 ? 'stroke-emerald-100' : score >= 60 ? 'stroke-amber-100' : 'stroke-red-100';

  return (
    <div className="relative inline-flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth="8"
          className={bgColor}
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth="8"
          strokeLinecap="round"
          className={cn(color, 'transition-all duration-1000 ease-out')}
          strokeDasharray={circumference}
          strokeDashoffset={circumference - progress}
        />
      </svg>
      <div className="absolute flex flex-col items-center">
        <span className={cn('text-3xl font-bold', color)}>{score}</span>
        <span className="text-xs text-slate-400">/ 100</span>
      </div>
    </div>
  );
}

function ScoreBar({
  label,
  score,
  color,
  icon,
}: {
  label: string;
  score: number;
  color: string;
  icon: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm text-slate-700">
          {icon}
          <span>{label}</span>
        </div>
        <span className="text-sm font-medium text-slate-900">{score}</span>
      </div>
      <div className="h-2 rounded-full bg-slate-100 overflow-hidden">
        <div
          className={cn('h-full rounded-full transition-all duration-700 ease-out', color)}
          style={{ width: `${score}%` }}
        />
      </div>
    </div>
  );
}

function CharCounter({ current, max, unit = 'chars' }: { current: number; max: number; unit?: string }) {
  const ratio = current / max;
  const color =
    ratio > 1 ? 'text-red-500' : ratio > 0.9 ? 'text-amber-500' : 'text-slate-400';

  return (
    <span className={cn('text-xs tabular-nums', color)}>
      {current}/{max} {unit}
    </span>
  );
}

function VersionBadge({ status }: { status: string }) {
  const styles: Record<string, string> = {
    draft: 'bg-slate-100 text-slate-600 border-slate-200',
    approved: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    published: 'bg-blue-100 text-blue-700 border-blue-200',
  };
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-0.5 text-xs font-medium rounded-full border',
        styles[status] || styles.draft,
      )}
    >
      {status}
    </span>
  );
}

function SeverityBadge({ severity }: { severity: string }) {
  if (severity === 'error') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium rounded-full bg-red-100 text-red-700 border border-red-200">
        <AlertTriangle size={12} />
        错误
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium rounded-full bg-amber-100 text-amber-700 border border-amber-200">
      <Info size={12} />
      警告
    </span>
  );
}

// ─── Error State Component ──────────────────────────────────────────────────

function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center min-h-[60vh] gap-4">
      <div className="w-12 h-12 rounded-full bg-red-100 flex items-center justify-center">
        <AlertTriangle size={24} className="text-red-600" />
      </div>
      <div className="text-center">
        <p className="text-sm font-medium text-slate-900">加载 Listing 数据失败</p>
        <p className="text-sm text-slate-500 mt-1">{message}</p>
      </div>
      <button
        onClick={onRetry}
        className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm"
      >
        <RefreshCw size={16} />
        重试
      </button>
    </div>
  );
}

// ─── Select-a-Product Prompt (Req 7.6) ─────────────────────────────────────

function SelectProductPrompt() {
  return (
    <div className="flex flex-col items-center justify-center min-h-[60vh] gap-4">
      <div className="w-12 h-12 rounded-full bg-indigo-100 flex items-center justify-center">
        <Wand2 size={24} className="text-indigo-600" />
      </div>
      <div className="text-center">
        <p className="text-sm font-medium text-slate-900">请先选择一个产品</p>
        <p className="text-sm text-slate-500 mt-1">
          AI 商品内容工作室需要一个具体的产品才能生成、评分和合规检查。
        </p>
      </div>
      <Link
        to="/products"
        className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm"
      >
        <Search size={16} />
        前往商品列表选择产品
      </Link>
    </div>
  );
}

// ─── Main Page ──────────────────────────────────────────────────────────────

export function ListingAIPage() {
  const { id } = useParams();
  const { stores, storeId } = useStoreContext();
  const currentStore = stores.find((s) => s.id === storeId);
  const channel = getChannelCapability(currentStore?.platform);
  const resolvedProductId = resolveListingProductId(id);
  const productId = resolvedProductId ?? '';

  // Data states
  const [listingContent, setListingContent] = useState<any>(null);
  const [scores, setScores] = useState<any>(null);
  const [compliance, setCompliance] = useState<any>(null);
  const [keywordMapping, setKeywordMapping] = useState<any[]>([]);
  const [versions, setVersions] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [scoring, setScoring] = useState(false);
  const [checking, setChecking] = useState(false);
  const [saving, setSaving] = useState(false);
  const [approving, setApproving] = useState(false);
  // Action feedback now flows through the global toast channel (notify). This
  // thin shim keeps every existing call site unchanged — setActionMessage({type,
  // text}) routes to the matching toast, and setActionMessage(null) is a no-op.
  const setActionMessage = (m: { type: 'success' | 'error' | 'info'; text: string } | null) => {
    if (m) notify[m.type](m.text);
  };

  // Editing states
  const [title, setTitle] = useState('');
  const [bulletPoints, setBulletPoints] = useState<string[]>(['', '', '', '', '']);
  const [description, setDescription] = useState('');
  const [backendSearchTerms, setBackendSearchTerms] = useState('');
  const [productHighlights, setProductHighlights] = useState('');
  const [attributes, setAttributes] = useState<Record<string, string>>({});
  const [subjectMatter, setSubjectMatter] = useState('');
  const [intendedUse, setIntendedUse] = useState('');
  const [targetAudience, setTargetAudience] = useState('');

  // Winner keywords for highlighting
  const [winnerKeywords, setWinnerKeywords] = useState<string[]>([]);

  const applyListingData = useCallback((data: any) => {
    if (!data) return;
    setTitle(data.title || '');
    setBulletPoints(data.bulletPoints || ['', '', '', '', '']);
    setDescription(data.description || '');
    setBackendSearchTerms(data.backendSearchTerms || '');
    setProductHighlights(data.productHighlights || '');
    setAttributes(data.attributes && typeof data.attributes === 'object' ? data.attributes : {});
    setSubjectMatter(data.subjectMatter || '');
    setIntendedUse(data.intendedUse || '');
    setTargetAudience(data.targetAudience || '');
  }, []);

  // Fetch data on mount
  async function fetchData() {
    setLoading(true);
    setError(null);
    try {
      const [contentData, mappingData, versionsData] = await Promise.all([
        fetchListingContent(productId),
        fetchKeywordMapping(productId),
        fetchListingVersions(productId),
      ]);

      setListingContent(contentData);
      if (contentData) {
        applyListingData(contentData);
        if (contentData.scores) setScores(contentData.scores);
        if (contentData.compliance) setCompliance(contentData.compliance);
      }

      const mapping = Array.isArray(mappingData) ? mappingData : [];
      setKeywordMapping(mapping);
      setWinnerKeywords(mapping.filter((k: any) => k.placed).map((k: any) => k.keyword));

      setVersions(Array.isArray(versionsData) ? versionsData : []);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载 Listing 数据失败，请重试。');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (!resolvedProductId) {
      // No resolvable product — skip fetching and prompt the specialist to
      // select a product (Req 7.6).
      setLoading(false);
      return;
    }
    fetchData();
  }, [resolvedProductId]);

  // API actions
  async function handleGenerate() {
    setGenerating(true);
    setActionMessage(null);
    try {
      const data = await generateListingDraft(productId, {
        marketplaceId: listingContent?.marketplaceId || undefined,
        // Make the generation channel explicit so the backend produces
        // platform-appropriate content (Amazon byte rules vs. independent-site
        // SEO vs. TikTok hooks). Omitted for unknown platforms so the backend
        // resolves the family from the product's store.
        platform: channel.platform !== 'unknown' ? channel.platform : undefined,
      });
      if (data) {
        setListingContent(data);
        applyListingData(data);
        if (data.scores) setScores(data.scores);
      }
      setActionMessage({ type: 'success', text: `已生成${contentName}草稿，可继续评分或合规检查。` });
    } catch (err) {
      setActionMessage({
        type: 'error',
        text: err instanceof Error ? err.message : `生成${contentName}失败，请重试。`,
      });
    } finally {
      setGenerating(false);
    }
  }

  async function handleScore() {
    setScoring(true);
    setActionMessage(null);
    try {
      const data = await scoreListing(productId);
      setScores(data);
      setActionMessage({ type: 'success', text: `${contentName}评分已更新。` });
    } catch (err) {
      setActionMessage({
        type: 'error',
        text: err instanceof Error ? err.message : '评分失败，请重试。',
      });
    } finally {
      setScoring(false);
    }
  }

  async function handleComplianceCheck() {
    if (!listingContent) {
      setActionMessage({ type: 'info', text: `请先点击「生成${contentName}」生成草稿，然后再进行合规检查。` });
      return;
    }
    setChecking(true);
    setActionMessage(null);
    try {
      const data = await checkListingCompliance(productId);
      setCompliance(data);
      setActionMessage({ type: 'success', text: '合规检查已完成。' });
    } catch (err) {
      setActionMessage({
        type: 'error',
        text: err instanceof Error ? err.message : `合规检查失败，请确认已生成${contentName}草稿后重试。`,
      });
    } finally {
      setChecking(false);
    }
  }

  async function handleSaveDraft() {
    const draftId = listingContent?.id;
    if (!draftId) {
      setActionMessage({ type: 'info', text: `请先点击「生成${contentName}」生成草稿后再保存。` });
      return;
    }
    setSaving(true);
    setActionMessage(null);
    try {
      const updated = await updateListingDraft(draftId, {
        title,
        bulletPoints,
        description,
        backendSearchTerms,
        productHighlights,
        attributes,
        subjectMatter,
        intendedUse,
        targetAudience,
      });
      if (updated) {
        setListingContent(updated);
        if (updated.scores) setScores(updated.scores);
      }
      setActionMessage({ type: 'success', text: '草稿已保存。' });
    } catch (err) {
      setActionMessage({ type: 'error', text: err instanceof Error ? err.message : '保存草稿失败，请重试。' });
    } finally {
      setSaving(false);
    }
  }

  async function handleApprove() {
    const draftId = listingContent?.id;
    if (!draftId) {
      setActionMessage({ type: 'info', text: `请先点击「生成${contentName}」生成草稿后再批准。` });
      return;
    }
    setApproving(true);
    setActionMessage(null);
    try {
      await approveListingDraft(draftId);
      // Refresh versions after approval
      const versionsData = await fetchListingVersions(productId);
      setVersions(Array.isArray(versionsData) ? versionsData : []);
      setActionMessage({ type: 'success', text: `${contentName}已批准。` });
    } catch (err) {
      setActionMessage({ type: 'error', text: err instanceof Error ? err.message : '批准失败，请重试。' });
    } finally {
      setApproving(false);
    }
  }

  function handleExportJSON() {
    const data = {
      title,
      bulletPoints,
      description,
      backendSearchTerms,
      productHighlights,
      attributes,
      subjectMatter,
      intendedUse,
      targetAudience,
      scores,
      compliance,
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `listing-${productId}-export.json`;
    a.click();
    URL.revokeObjectURL(url);
  }

  function handleExportPreview() {
    const preview = [
      '=== LISTING PREVIEW ===',
      '',
      'TITLE:',
      title,
      '',
      'PRODUCT HIGHLIGHTS:',
      productHighlights,
      '',
      'BULLET POINTS:',
      ...bulletPoints.map((bp, i) => `${i + 1}. ${bp}`),
      '',
      'DESCRIPTION:',
      description,
      '',
      'BACKEND SEARCH TERMS:',
      backendSearchTerms,
      '',
      'SUBJECT MATTER:',
      subjectMatter,
      '',
      'INTENDED USE:',
      intendedUse,
      '',
      'TARGET AUDIENCE:',
      targetAudience,
    ].join('\n');

    const blob = new Blob([preview], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `listing-${productId}-preview.txt`;
    a.click();
    URL.revokeObjectURL(url);
  }

  function handleRestoreVersion(version: any) {
    applyListingData(version);
    setListingContent(version);
    setActionMessage({
      type: 'success',
      text: `已载入历史版本，可检查后点击「保存草稿」。`,
    });
  }

  function versionDate(version: any): Date | null {
    const raw = version?.createdAt || version?.updatedAt || version?.date;
    if (!raw) return null;
    const date = new Date(raw);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function versionScore(version: any): number | null {
    const score = version?.listingScore ?? version?.score;
    return typeof score === 'number' ? score : null;
  }

  function versionKey(version: any, index: number): string {
    return version?.id || version?.version || String(index);
  }

  function versionLabel(index: number): string {
    return `v${versions.length - index}`;
  }

  function versionDateLabel(version: any): string {
    const date = versionDate(version);
    if (!date) return '时间未知';
    return date.toLocaleDateString('zh-CN', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  function handleUnavailableRestore() {
    setActionMessage({
      type: 'info',
      text: '该历史版本缺少可恢复内容。',
    });
  }

  // Helper: highlight winner keywords in text
  function highlightKeywords(text: string): React.ReactNode {
    if (!winnerKeywords.length || !text) return text;
    const regex = new RegExp(`(${winnerKeywords.map((k) => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'gi');
    const parts = text.split(regex);
    return parts.map((part, i) => {
      if (winnerKeywords.some((k) => k.toLowerCase() === part.toLowerCase())) {
        return (
          <mark key={i} className="bg-yellow-200/70 text-yellow-900 rounded px-0.5">
            {part}
          </mark>
        );
      }
      return part;
    });
  }

  // Byte length for backend search terms
  function getByteLength(str: string): number {
    return new TextEncoder().encode(str).length;
  }

  const backendBytes = getByteLength(backendSearchTerms);
  const isAmazonChannel = channel.platform === 'amazon';
  const titleMax = isAmazonChannel ? 75 : 120;
  const titleLimitMessage = isAmazonChannel
    ? '标题超过 75 字符。亚马逊自 2026-07-27 起要求非媒介类标题不超过 75 字符，多余属性请填入下方「商品亮点」。'
    : `标题超过 ${titleMax} 字符。建议把多余卖点放入卖点描述或商品描述，便于 ${channel.channelLabel} 的页面和广告落地页展示。`;
  const titlePlaceholder = isAmazonChannel
    ? '品牌 + 核心产品词 + 一个关键差异点（≤ 75 字符）'
    : `品牌 + 核心产品词 + 核心卖点（≤ ${titleMax} 字符）`;
  const searchTermsLabel =
    isAmazonChannel
      ? '后台搜索词'
      : channel.platform === 'tiktok'
        ? '搜索关键词 / 标签'
        : 'SEO 关键词';
  const searchTermsCurrent = isAmazonChannel ? backendBytes : backendSearchTerms.length;
  const searchTermsMax = isAmazonChannel ? 250 : 500;
  const searchTermsUnit = isAmazonChannel ? 'bytes' : 'chars';
  const searchTermsPlaceholder = isAmazonChannel
    ? '输入后台搜索词...'
    : channel.platform === 'tiktok'
      ? '输入 TikTok Shop 搜索关键词或标签...'
      : '输入用于独立站 SEO、站内搜索和广告落地页的关键词...';
  const bulletLabel = isAmazonChannel ? '五点描述' : '卖点描述';
  const contentName = isAmazonChannel ? 'Listing' : '商品内容';

  // No resolvable product — prompt to select one instead of "ASIN: N/A" (Req 7.6)
  if (!resolvedProductId) {
    return <SelectProductPrompt />;
  }

  // Loading state
  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="w-8 h-8 text-indigo-500 animate-spin" />
          <p className="text-sm text-slate-500">加载 Listing 数据中...</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return <ErrorState message={error} onRetry={fetchData} />;
  }

  return (
    <div className="space-y-6 pb-24">
      {/* ── Page Header ─────────────────────────────────────────────── */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center w-10 h-10 rounded-xl bg-indigo-100 text-indigo-600">
            <Wand2 size={20} />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-900">AI 商品内容工作室</h1>
            <p className="text-sm text-slate-500 mt-0.5">
              {channel.contentStudioLabel} · {listingContent?.productName || '产品内容'}{' '}
              {isAmazonChannel ? <span className="text-slate-400">
                &middot; ASIN: {listingContent?.asin || 'N/A'}
              </span> : null}
            </p>
            {!channel.supportsDirectProductPublish && (
              <p className="mt-1 text-xs text-amber-600">{channel.note}</p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleGenerate}
            disabled={generating}
            className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {generating ? <Loader2 size={16} className="animate-spin" /> : <Sparkles size={16} />}
            生成{contentName}
          </button>
          <button
            onClick={handleScore}
            disabled={scoring}
            className="inline-flex items-center gap-2 px-4 py-2.5 bg-white text-slate-700 text-sm font-medium rounded-lg border border-slate-200 hover:bg-slate-50 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {scoring ? <Loader2 size={16} className="animate-spin" /> : <BarChart3 size={16} />}
            评分{contentName}
          </button>
          <button
            onClick={handleComplianceCheck}
            disabled={checking || !listingContent}
            title={!listingContent ? '请先生成 Listing 草稿' : undefined}
            className="inline-flex items-center gap-2 px-4 py-2.5 bg-white text-slate-700 text-sm font-medium rounded-lg border border-slate-200 hover:bg-slate-50 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {checking ? <Loader2 size={16} className="animate-spin" /> : <ShieldCheck size={16} />}
            合规检查
          </button>
        </div>
      </div>

      {/* ── Main 2-Column Layout ───────────────────────────────────── */}
      <div className="grid grid-cols-1 xl:grid-cols-5 gap-6">
        {/* Left Column: Editor (3/5) */}
        <div className="xl:col-span-3 space-y-6">
          {/* Title */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="flex items-center justify-between mb-2">
              <label className="flex items-center gap-2 text-sm font-medium text-slate-700">
                <Type size={14} />
                标题
              </label>
              <CharCounter current={title.length} max={titleMax} />
            </div>
            <textarea
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              rows={3}
              className={cn(
                'w-full px-3 py-2.5 text-sm text-slate-900 border rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors resize-none',
                title.length > titleMax ? 'border-red-300 bg-red-50/30' : 'border-slate-200',
              )}
              placeholder={titlePlaceholder}
            />
            {title.length > titleMax ? (
              <p className="mt-2 flex items-start gap-1.5 text-xs text-red-600">
                <AlertTriangle size={13} className="mt-0.5 shrink-0" />
                {titleLimitMessage}
              </p>
            ) : (
              <p className="mt-2 text-xs text-slate-400">
                AI 搜索时代建议：标题只放品牌、核心产品词和一个差异点，不要堆砌关键词。
              </p>
            )}
            {winnerKeywords.length > 0 && title && (
              <div className="mt-2 p-2.5 bg-slate-50 rounded-lg text-xs text-slate-600 leading-relaxed">
                <span className="text-slate-400 font-medium">关键词： </span>
                {highlightKeywords(title)}
              </div>
            )}
          </div>

          {/* Product Highlights (Amazon 2026 new module) */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="flex items-center justify-between mb-2">
              <label className="flex items-center gap-2 text-sm font-medium text-slate-700">
                <Sparkles size={14} />
                商品亮点
                <span className="text-[10px] font-normal text-indigo-500 bg-indigo-50 border border-indigo-100 rounded px-1.5 py-0.5">
                  新 · 可被搜索
                </span>
              </label>
              <CharCounter current={productHighlights.length} max={125} />
            </div>
            <textarea
              value={productHighlights}
              onChange={(e) => setProductHighlights(e.target.value)}
              rows={3}
              className={cn(
                'w-full px-3 py-2.5 text-sm text-slate-900 border rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors resize-none',
                productHighlights.length > 125 ? 'border-red-300 bg-red-50/30' : 'border-slate-200',
              )}
              placeholder="用逗号分隔的短语，例如：USB-C, 支持 PPS, 适配 MacBook Air, 不含数据线"
            />
            <p className="mt-2 text-xs text-slate-400">
              展示在标题下方，参与搜索索引。建议布局材质、核心功能、使用场景、目标人群、关键规格，采用短语而非完整句子。
            </p>
            {winnerKeywords.length > 0 && productHighlights && (
              <div className="mt-2 p-2.5 bg-slate-50 rounded-lg text-xs text-slate-600 leading-relaxed">
                {highlightKeywords(productHighlights)}
              </div>
            )}
          </div>

          {/* Structured Attributes (Entity SEO for COSMO / Rufus / Alexa+) */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="flex items-center gap-2 mb-1">
              <Target size={14} className="text-slate-500" />
              <span className="text-sm font-medium text-slate-700">结构化属性</span>
              <span className="text-[10px] font-normal text-indigo-500 bg-indigo-50 border border-indigo-100 rounded px-1.5 py-0.5">
                Entity SEO
              </span>
            </div>
            <p className="text-xs text-slate-400 mb-3">
              填得越全，AI 搜索（COSMO/Rufus/Alexa+）越能按用户意图匹配并推荐你的商品。
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {ENTITY_ATTRIBUTE_FIELDS.map((f) => (
                <div key={f.key}>
                  <label className="block text-xs font-medium text-slate-500 mb-1">{f.label}</label>
                  <input
                    type="text"
                    value={attributes[f.key] || ''}
                    onChange={(e) => setAttributes({ ...attributes, [f.key]: e.target.value })}
                    className="w-full px-3 py-2 text-sm text-slate-900 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors"
                    placeholder={f.placeholder}
                  />
                </div>
              ))}
            </div>
          </div>

          {/* Bullet Points */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="flex items-center gap-2 mb-3">
              <List size={14} className="text-slate-500" />
              <span className="text-sm font-medium text-slate-700">{bulletLabel}</span>
            </div>
            <div className="space-y-3">
              {bulletPoints.map((bp, idx) => (
                <div key={idx}>
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-xs font-medium text-slate-500">第 {idx + 1} 点</span>
                    <CharCounter current={bp.length} max={500} />
                  </div>
                  <textarea
                    value={bp}
                    onChange={(e) => {
                      const updated = [...bulletPoints];
                      updated[idx] = e.target.value;
                      setBulletPoints(updated);
                    }}
                    maxLength={500}
                    rows={2}
                    className="w-full px-3 py-2.5 text-sm text-slate-900 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors resize-none"
                    placeholder={`第 ${idx + 1} 点描述...`}
                  />
                  {winnerKeywords.length > 0 && bp && (
                    <div className="mt-1.5 p-2 bg-slate-50 rounded-lg text-xs text-slate-600 leading-relaxed">
                      {highlightKeywords(bp)}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* Description */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="flex items-center justify-between mb-2">
              <label className="flex items-center gap-2 text-sm font-medium text-slate-700">
                <AlignLeft size={14} />
                描述
              </label>
              <CharCounter current={description.length} max={2000} />
            </div>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              maxLength={2000}
              rows={6}
              className="w-full px-3 py-2.5 text-sm text-slate-900 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors resize-none"
              placeholder="输入产品描述..."
            />
            {winnerKeywords.length > 0 && description && (
              <div className="mt-2 p-2.5 bg-slate-50 rounded-lg text-xs text-slate-600 leading-relaxed">
                {highlightKeywords(description)}
              </div>
            )}
          </div>

          {/* Backend Search Terms */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="flex items-center justify-between mb-2">
              <label className="flex items-center gap-2 text-sm font-medium text-slate-700">
                <Search size={14} />
                {searchTermsLabel}
              </label>
              <CharCounter current={searchTermsCurrent} max={searchTermsMax} unit={searchTermsUnit} />
            </div>
            <textarea
              value={backendSearchTerms}
              onChange={(e) => setBackendSearchTerms(e.target.value)}
              rows={3}
              className="w-full px-3 py-2.5 text-sm text-slate-900 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors resize-none"
              placeholder={searchTermsPlaceholder}
            />
          </div>

          {/* Additional Fields */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <div>
                <label className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5">
                  <Target size={14} />
                  主题
                </label>
                <input
                  type="text"
                  value={subjectMatter}
                  onChange={(e) => setSubjectMatter(e.target.value)}
                  className="w-full px-3 py-2 text-sm text-slate-900 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors"
                  placeholder="例如：无线头戴式耳机"
                />
              </div>
              <div>
                <label className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5">
                  <Crosshair size={14} />
                  适用场景
                </label>
                <input
                  type="text"
                  value={intendedUse}
                  onChange={(e) => setIntendedUse(e.target.value)}
                  className="w-full px-3 py-2 text-sm text-slate-900 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors"
                  placeholder="例如：听音乐、通话、游戏"
                />
              </div>
              <div>
                <label className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5">
                  <Users size={14} />
                  目标受众
                </label>
                <input
                  type="text"
                  value={targetAudience}
                  onChange={(e) => setTargetAudience(e.target.value)}
                  className="w-full px-3 py-2 text-sm text-slate-900 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors"
                  placeholder="例如：音乐爱好者、通勤人群"
                />
              </div>
            </div>
          </div>
        </div>

        {/* Right Column: Scores & Analysis (2/5) */}
        <div className="xl:col-span-2 space-y-6">
          {/* Listing Score Card */}
          <div className="bg-white rounded-xl border border-slate-200 p-6 flex flex-col items-center">
            <h3 className="text-sm font-medium text-slate-700 mb-4">Listing 评分</h3>
            <ScoreGauge score={scores?.overall || 0} />
            <p className="text-xs text-slate-400 mt-3">
              {scores?.overall >= 80
                ? '优秀的 Listing！稍作调整即可更上一层楼。'
                : scores?.overall >= 60
                  ? '不错的开始。请查看下方建议。'
                  : '需要改进。请解决下方列出的问题。'}
            </p>
          </div>

          {/* Score Breakdown */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <h3 className="text-sm font-medium text-slate-700 mb-4">评分细分</h3>
            <div className="space-y-3.5">
              <ScoreBar
                label="SEO 评分"
                score={scores?.seo || 0}
                color="bg-blue-500"
                icon={<Search size={14} className="text-blue-500" />}
              />
              <ScoreBar
                label="合规性"
                score={scores?.compliance || 0}
                color={
                  (scores?.compliance || 0) >= 80
                    ? 'bg-emerald-500'
                    : (scores?.compliance || 0) >= 60
                      ? 'bg-amber-500'
                      : 'bg-red-500'
                }
                icon={<ShieldCheck size={14} className="text-slate-500" />}
              />
              <ScoreBar
                label="转化率"
                score={scores?.conversion || 0}
                color="bg-purple-500"
                icon={<Target size={14} className="text-purple-500" />}
              />
              <ScoreBar
                label="可读性"
                score={scores?.readability || 0}
                color="bg-teal-500"
                icon={<FileText size={14} className="text-teal-500" />}
              />
              <ScoreBar
                label="关键词覆盖"
                score={scores?.keywordCoverage || 0}
                color="bg-orange-500"
                icon={<Crosshair size={14} className="text-orange-500" />}
              />
            </div>
          </div>

          {/* Compliance Issues */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <h3 className="text-sm font-medium text-slate-700 mb-3">合规问题</h3>
            {compliance?.issues?.length ? (
              <div className="space-y-3">
                {compliance.issues.map((issue: any, idx: number) => (
                  <div
                    key={idx}
                    className={cn(
                      'p-3 rounded-lg border',
                      issue.severity === 'error'
                        ? 'border-red-200 bg-red-50/50'
                        : 'border-amber-200 bg-amber-50/50',
                    )}
                  >
                    <div className="flex items-start justify-between gap-2 mb-1.5">
                      <span className="text-xs font-medium text-slate-700">{issue.field}</span>
                      <SeverityBadge severity={issue.severity} />
                    </div>
                    <p className="text-xs text-slate-600 mb-1">{issue.message}</p>
                    <p className="text-xs text-slate-500 italic">
                      <span className="font-medium">建议：</span> {issue.suggestion}
                    </p>
                    <span className="inline-block mt-1.5 text-[10px] text-slate-400 uppercase tracking-wide">
                      规则：{issue.rule}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <div className="flex items-center gap-2 p-3 bg-emerald-50 rounded-lg border border-emerald-200">
                <CheckCircle2 size={16} className="text-emerald-500" />
                <span className="text-sm text-emerald-700">未发现合规问题</span>
              </div>
            )}
          </div>

          {/* Keyword Coverage Map */}
          <div className="bg-white rounded-xl border border-slate-200 p-5">
            <h3 className="text-sm font-medium text-slate-700 mb-3">关键词覆盖图</h3>
            <div className="space-y-1.5">
              {keywordMapping.slice(0, 10).map((kw: any, idx: number) => (
                <div
                  key={idx}
                  className={cn(
                    'flex items-center justify-between px-3 py-2 rounded-lg text-xs',
                    kw.placed ? 'bg-emerald-50 border border-emerald-100' : 'bg-slate-50 border border-slate-100',
                  )}
                >
                  <span className={cn('font-medium', kw.placed ? 'text-emerald-700' : 'text-slate-500')}>
                    {kw.keyword}
                  </span>
                  <span
                    className={cn(
                      'px-2 py-0.5 rounded-full text-[10px] font-medium',
                      kw.placed
                        ? 'bg-emerald-100 text-emerald-700'
                        : 'bg-slate-100 text-slate-500',
                    )}
                  >
                    {kw.field}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* ── Keyword Mapping Panel ───────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-sm font-medium text-slate-700 flex items-center gap-2">
            <Crosshair size={14} />
            推荐关键词布局
          </h3>
          <span className="text-xs text-slate-400">
            已布局 {keywordMapping.filter((k: any) => k.placed).length} /{' '}
            {keywordMapping.length}
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50/60">
                <th className="text-left font-medium text-slate-500 px-4 py-2.5">关键词</th>
                <th className="text-left font-medium text-slate-500 px-4 py-2.5">推荐字段</th>
                <th className="text-center font-medium text-slate-500 px-4 py-2.5">状态</th>
              </tr>
            </thead>
            <tbody>
              {keywordMapping.map((kw: any, idx: number) => (
                <tr
                  key={idx}
                  className={cn(
                    'border-b border-slate-50 transition-colors',
                    kw.placed ? 'bg-emerald-50/30' : 'hover:bg-slate-50/50',
                  )}
                >
                  <td className="px-4 py-2.5">
                    <span className={cn('text-sm', kw.placed ? 'text-emerald-700 font-medium' : 'text-slate-700')}>
                      {kw.keyword}
                    </span>
                  </td>
                  <td className="px-4 py-2.5">
                    <span className="inline-flex items-center px-2 py-0.5 text-xs font-medium rounded-full bg-indigo-100 text-indigo-700 border border-indigo-200">
                      {kw.field}
                    </span>
                  </td>
                  <td className="px-4 py-2.5 text-center">
                    {kw.placed ? (
                      <span className="inline-flex items-center gap-1 text-emerald-600">
                        <CheckCircle2 size={14} />
                        <span className="text-xs font-medium">已放置</span>
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-slate-400">
                        <AlertTriangle size={14} />
                        <span className="text-xs font-medium">缺失</span>
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* ── Version History ─────────────────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <h3 className="text-sm font-medium text-slate-700 mb-4 flex items-center gap-2">
          <RotateCcw size={14} />
          版本历史
        </h3>
        {versions.length ? (
          <div className="space-y-2">
            {versions.map((v: any, index: number) => {
              const score = versionScore(v);
              const canRestore = !!(v?.title || v?.description || v?.bulletPoints?.length);
              return (
                <div
                  key={versionKey(v, index)}
                  className="flex items-center justify-between px-4 py-3 rounded-lg border border-slate-100 hover:bg-slate-50/50 transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <span className="text-sm font-medium text-slate-700">{versionLabel(index)}</span>
                    <VersionBadge status={v.status} />
                    <span className="text-xs text-slate-400">
                      {versionDateLabel(v)}
                    </span>
                  </div>
                  <div className="flex items-center gap-3">
                    {score != null && (
                      <span
                        className={cn(
                          'text-sm font-semibold',
                          score >= 80
                            ? 'text-emerald-600'
                            : score >= 60
                              ? 'text-amber-600'
                              : 'text-red-500',
                        )}
                      >
                        {score}
                      </span>
                    )}
                    <button
                      onClick={() => (canRestore ? handleRestoreVersion(v) : handleUnavailableRestore())}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-600 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors"
                    >
                      <RotateCcw size={12} />
                      恢复
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          <p className="text-sm text-slate-400 text-center py-4">暂无版本历史</p>
        )}
      </div>

      {/* ── Sticky Action Bar ───────────────────────────────────────── */}
      <div className="fixed bottom-0 left-0 right-0 z-40 bg-white/80 backdrop-blur-md border-t border-slate-200">
        <div className="max-w-7xl mx-auto px-6 py-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ArrowLeft size={16} className="text-slate-400" />
            <span className="text-sm text-slate-500">产品 #{productId}</span>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={handleSaveDraft}
              disabled={saving}
              className="inline-flex items-center gap-2 px-4 py-2 bg-white text-slate-700 text-sm font-medium rounded-lg border border-slate-200 hover:bg-slate-50 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
              保存草稿
            </button>
            <button
              onClick={handleApprove}
              disabled={approving}
              className="inline-flex items-center gap-2 px-4 py-2 bg-emerald-600 text-white text-sm font-medium rounded-lg hover:bg-emerald-700 transition-colors shadow-sm disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {approving ? <Loader2 size={14} className="animate-spin" /> : <CheckCircle2 size={14} />}
              批准
            </button>
            {/* Product upload entry point — navigates to the upload workflow
                carrying the current product/marketplace context (Req 8.1, 8.2). */}
            <Link
              to={buildProductUploadHref(productId, listingContent?.marketplaceId)}
              className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm"
            >
              <Upload size={14} />
              渠道发布
            </Link>
            <div className="w-px h-6 bg-slate-200 mx-1" />
            <button
              onClick={handleExportJSON}
              className="inline-flex items-center gap-2 px-3 py-2 bg-white text-slate-600 text-sm font-medium rounded-lg border border-slate-200 hover:bg-slate-50 transition-colors"
            >
              <Download size={14} />
              导出 JSON
            </button>
            <button
              onClick={handleExportPreview}
              className="inline-flex items-center gap-2 px-3 py-2 bg-white text-slate-600 text-sm font-medium rounded-lg border border-slate-200 hover:bg-slate-50 transition-colors"
            >
              <Eye size={14} />
              导出预览
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
