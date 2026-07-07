import React, { useState, useEffect } from 'react';
import { useSearchParams, useLocation } from 'react-router';
import { useStoreId } from '../lib/useStoreId';
import {
  Package,
  Globe,
  FileText,
  CheckCircle2,
  ShieldCheck,
  PartyPopper,
  ChevronRight,
  ChevronLeft,
  Loader2,
  AlertCircle,
  Download,
  Eye,
  XCircle,
  RefreshCw,
  Sparkles,
  Check,
  AlertTriangle,
  Clock,
  Upload,
} from 'lucide-react';
import { cn, formatCurrency } from '../lib/utils';
import { RecordModal, type RecordField } from '../components/ui/RecordModal';
import { useStoreContext } from '../lib/StoreContext';
import { getChannelCapability } from '../lib/channelCapabilities';

const UPLOAD_JOB_FIELDS: RecordField[] = [
  { key: 'productName', label: '产品' },
  { key: 'marketplace', label: '站点' },
  { key: 'status', label: '状态' },
  { key: 'createdAt', label: '创建时间' },
  { key: 'id', label: '任务 ID' },
];
import {
  fetchProducts,
  fetchUploadJobs,
  createUploadJob,
  validateUploadJob,
  approveUploadJob,
  exportUploadJob,
  fetchListingVersions,
  fetchMarketplaces,
  generateListingDraft,
  updateListingDraft,
  publishUploadJob,
} from '../lib/api';
import { notify } from '../lib/toast';

const STEPS = [
  { id: 1, label: '选择产品', icon: Package },
  { id: 2, label: '站点', icon: Globe },
  { id: 3, label: 'Listing 内容', icon: FileText },
  { id: 4, label: '校验', icon: ShieldCheck },
  { id: 5, label: '审核与批准', icon: CheckCircle2 },
  { id: 6, label: '完成', icon: PartyPopper },
];

// Total number of backend validation checks; used to render a "passed" count
// from the job's error list (the backend reports failures, not a passed count).
const TOTAL_VALIDATION_CHECKS = 9;

const statusColors: Record<string, string> = {
  draft: 'bg-slate-100 text-slate-700',
  validating: 'bg-blue-100 text-blue-700',
  ready: 'bg-emerald-100 text-emerald-700',
  submitted: 'bg-amber-100 text-amber-700',
  exported: 'bg-blue-100 text-blue-700',
  success: 'bg-green-100 text-green-700',
  failed: 'bg-red-100 text-red-700',
  approved: 'bg-emerald-100 text-emerald-700',
  cancelled: 'bg-slate-100 text-slate-500',
  pending_review: 'bg-amber-100 text-amber-700',
};

function StatusBadge({ status }: { status: string }) {
  return (
    <span className={cn('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium', statusColors[status] || 'bg-slate-100 text-slate-700')}>
      {(status || '').replace(/_/g, ' ')}
    </span>
  );
}

/** UTF-8 byte length of a string (Amazon backend search-term limits are in bytes). */
function byteLength(s: string): number {
  try {
    return new TextEncoder().encode(s).length;
  } catch {
    return s.length;
  }
}

// ─── Upload navigation context resolution (Req 8.1, 8.2) ───────────────────

export interface UploadContext {
  productId: string;
  marketplaceId: string;
}

/** Minimal read-only view of a URLSearchParams-like source. */
export interface UploadContextParams {
  get(key: string): string | null;
}

/**
 * The route the product upload entry point navigates to (Req 8.1). Centralized
 * so the entry control and tests agree on the destination.
 */
export const PRODUCT_UPLOAD_ROUTE = '/product-upload';

/**
 * Resolve the product/marketplace context carried into the upload workflow on
 * navigation (Req 8.2). Query params take precedence over router location
 * state; absent values resolve to empty strings. Pure and exported for unit
 * testing.
 */
export function resolveUploadContext(
  params: UploadContextParams,
  navState?: { productId?: string; marketplaceId?: string } | null,
): UploadContext {
  return {
    productId: params.get('productId') || navState?.productId || '',
    marketplaceId: params.get('marketplaceId') || navState?.marketplaceId || '',
  };
}

interface ListingForm {
  id?: string;
  title: string;
  bulletPoints: string[];
  description: string;
  backendSearchTerms: string;
}

/** Ensure exactly 5 editable bullet slots (Amazon allows up to 5). */
function normalizeBullets(bullets: any): string[] {
  const arr = Array.isArray(bullets) ? bullets.map((b) => (b == null ? '' : String(b))) : [];
  const out = arr.slice(0, 5);
  while (out.length < 5) out.push('');
  return out;
}

export function ProductUploadPage({ embedded = false }: { embedded?: boolean } = {}) {
  const { storeId } = useStoreId();
  const { stores } = useStoreContext();
  const [searchParams] = useSearchParams();
  const location = useLocation();
  // Context carried into the workflow on navigation (Req 8.2): a selected
  // product (and optionally a target marketplace) may be passed via query
  // params (?productId=...&marketplaceId=...) or router location state.
  const navState = (location.state as { productId?: string; marketplaceId?: string } | null) || null;
  const { productId: incomingProductId, marketplaceId: incomingMarketplace } =
    resolveUploadContext(searchParams, navState);
  const [contextApplied, setContextApplied] = useState(false);
  const [step, setStep] = useState(1);
  const [products, setProducts] = useState<any[]>([]);
  const [marketplaces, setMarketplaces] = useState<any[]>([]);
  const [selectedProduct, setSelectedProduct] = useState<any>(null);
  // The selected marketplace's real UUID id (required by the backend).
  const [selectedMarketplace, setSelectedMarketplace] = useState<string>('');
  const [listingVersions, setListingVersions] = useState<any[]>([]);
  const [listingForm, setListingForm] = useState<ListingForm | null>(null);
  const [generating, setGenerating] = useState(false);
  const [uploadJob, setUploadJob] = useState<any>(null);
  const [validationResult, setValidationResult] = useState<any>(null);
  const [exportData, setExportData] = useState<any>(null);
  const [publishResult, setPublishResult] = useState<{ ok: boolean; message: string; job?: any } | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploadJobs, setUploadJobs] = useState<any[]>([]);
  const [viewJob, setViewJob] = useState<any | null>(null);
  const [exportFormat, setExportFormat] = useState<'json' | 'csv' | 'flat_file'>('json');
  const currentStore = stores.find((s) => s.id === storeId);
  const channel = getChannelCapability(currentStore?.platform);
  const steps = STEPS.map((s) => {
    if (s.id === 2) return { ...s, label: channel.platform === 'amazon' ? '站点' : '渠道' };
    if (s.id === 3) return { ...s, label: channel.contentStudioLabel };
    return s;
  });

  // Fetch products + upload jobs for the selected store; re-fetch on change.
  useEffect(() => {
    if (!storeId) return;
    loadProducts();
    loadUploadJobs();
  }, [storeId]);

  // Marketplaces are global reference data (real UUIDs) — load once.
  useEffect(() => {
    loadMarketplaces();
  }, []);

  useEffect(() => {
    if (channel.platform === 'amazon') return;
    if (!selectedMarketplace && currentStore?.marketplaceId) {
      setSelectedMarketplace(currentStore.marketplaceId);
    }
  }, [channel.platform, currentStore?.marketplaceId, selectedMarketplace]);

  // When navigated here with a product context, pre-select that product and
  // advance the workflow so the operator lands directly in the upload flow
  // (Req 8.2 carry context, Req 8.3 render workflow on arrival).
  useEffect(() => {
    if (contextApplied || !incomingProductId || products.length === 0) return;
    const match = products.find(
      (p) => p.id === incomingProductId || p.asin === incomingProductId,
    );
    if (match) {
      setSelectedProduct(match);
      if (incomingMarketplace) {
        const mp = marketplaces.find(
          (m) => m.id === incomingMarketplace || m.code === incomingMarketplace,
        );
        if (mp) setSelectedMarketplace(mp.id);
      }
      setStep(2);
      loadListingVersions(match.id);
    }
    setContextApplied(true);
  }, [products, marketplaces, incomingProductId, incomingMarketplace, contextApplied]);

  async function loadProducts() {
    if (!storeId) return;
    try {
      const data = await fetchProducts(storeId);
      setProducts(Array.isArray(data) ? data : (data as any)?.items || []);
    } catch (err: any) {
      setError(err.message || '加载产品失败');
      setProducts([]);
    }
  }

  async function loadMarketplaces() {
    try {
      const data = await fetchMarketplaces();
      setMarketplaces(Array.isArray(data) ? data : []);
    } catch {
      setMarketplaces([]);
    }
  }

  async function loadUploadJobs() {
    if (!storeId) return;
    try {
      const data = await fetchUploadJobs(storeId);
      setUploadJobs(Array.isArray(data) ? data : (data as any)?.items || []);
    } catch {
      setUploadJobs([]);
    }
  }

  async function loadListingVersions(productId: string) {
    try {
      const data = await fetchListingVersions(productId);
      const versions = Array.isArray(data) ? data : [];
      setListingVersions(versions);
      // Pre-fill the editor from the most recent version, if any.
      if (versions.length > 0 && !listingForm) {
        loadVersionIntoForm(versions[0]);
      }
    } catch {
      setListingVersions([]);
    }
  }

  function loadVersionIntoForm(version: any) {
    setListingForm({
      id: version.id,
      title: version.title || '',
      bulletPoints: normalizeBullets(version.bulletPoints),
      description: version.description || '',
      backendSearchTerms: version.backendSearchTerms || '',
    });
  }

  // ─── AI generate Amazon-compliant listing content (Req 3) ──────────────────
  async function handleGenerate() {
    if (!selectedProduct || !selectedMarketplace) return;
    setGenerating(true);
    setError(null);
    try {
      const data = await generateListingDraft(selectedProduct.id, {
        marketplaceId: selectedMarketplace,
        targetAudience: 'general consumers',
      });
      if (!data) {
        throw new Error(`无法为该产品生成${contentName}`);
      }
      setListingForm({
        id: data.id,
        title: data.title || '',
        bulletPoints: normalizeBullets(data.bulletPoints),
        description: data.description || '',
        backendSearchTerms: data.backendSearchTerms || '',
      });
      // Refresh version history so the new draft shows up.
      loadListingVersions(selectedProduct.id);
    } catch (err: any) {
      setError(err.message || `生成${contentName}失败`);
    } finally {
      setGenerating(false);
    }
  }

  function updateForm(patch: Partial<ListingForm>) {
    setListingForm((prev) => (prev ? { ...prev, ...patch } : prev));
  }

  function updateBullet(index: number, value: string) {
    setListingForm((prev) => {
      if (!prev) return prev;
      const bullets = [...prev.bulletPoints];
      bullets[index] = value;
      return { ...prev, bulletPoints: bullets };
    });
  }

  async function handleCreateUploadJob() {
    if (!selectedProduct || !selectedMarketplace || !listingForm) return;
    setLoading(true);
    setError(null);
    try {
      // Persist edits to the listing draft so validation uses the final content.
      if (listingForm.id) {
        await updateListingDraft(listingForm.id, {
          title: listingForm.title,
          bulletPoints: listingForm.bulletPoints.filter((b) => b.trim() !== ''),
          description: listingForm.description,
          backendSearchTerms: listingForm.backendSearchTerms,
        });
      }
      const data = await createUploadJob({
        storeId: storeId || '',
        productId: selectedProduct.id,
        marketplaceId: selectedMarketplace,
        uploadMethod: channel.directPublishMethod ?? channel.productUploadMethod,
      });
      setUploadJob(data);
      setValidationResult(null);
      setStep(4);
    } catch (err: any) {
      setError(err.message || '创建上传任务失败');
    } finally {
      setLoading(false);
    }
  }

  async function handleValidate() {
    if (!uploadJob) return;
    setLoading(true);
    setError(null);
    try {
      const job = await validateUploadJob(uploadJob.id);
      setUploadJob(job);
      // The backend reports a status (ready/failed) and a "; "-joined error
      // message; derive a structured result for the UI.
      const errors =
        job.status === 'failed' && job.errorMessage
          ? String(job.errorMessage).split('; ').filter(Boolean)
          : [];
      setValidationResult({
        checksPassed: Math.max(0, TOTAL_VALIDATION_CHECKS - errors.length),
        errorsCount: errors.length,
        warningsCount: 0,
        errors,
        warnings: [],
      });
    } catch (err: any) {
      setError(err.message || '校验失败');
    } finally {
      setLoading(false);
    }
  }

  async function handleApprove() {
    if (!uploadJob) return;
    setLoading(true);
    setError(null);
    try {
      const job = await approveUploadJob(uploadJob.id);
      setUploadJob(job);
      setStep(6);
      loadUploadJobs();
    } catch (err: any) {
      setError(err.message || '审批失败');
    } finally {
      setLoading(false);
    }
  }

  async function handleExport(format: 'json' | 'csv' | 'flat_file') {
    if (!uploadJob) return;
    setExportFormat(format);
    setLoading(true);
    setError(null);
    try {
      const data = await exportUploadJob(uploadJob.id, format);
      setExportData(data);
      // Trigger download.
      const text = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
      const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `product-upload-${uploadJob.id}.${format === 'flat_file' ? 'txt' : format}`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err: any) {
      setError(err.message || '导出失败');
    } finally {
      setLoading(false);
    }
  }

  // ─── Direct publish to the connected store (Shopify / WooCommerce) ─────────
  // Makes a real product-create call via the backend. Honesty principle: the
  // backend never fakes success — on failure we surface the readable reason.
  async function handlePublish(jobId: string) {
    if (!jobId) return;
    setLoading(true);
    setError(null);
    setPublishResult(null);
    try {
      const job = await publishUploadJob(jobId);
      const ok = job?.status === 'published' || job?.status === 'success';
      let pid = '';
      if (job?.response && typeof job.response === 'string') {
        try {
          pid = JSON.parse(job.response).platform_product_id || '';
        } catch {
          pid = '';
        }
      }
      if (uploadJob && uploadJob.id === jobId) setUploadJob(job);
      setPublishResult({
        ok,
        message: ok
          ? `已成功发布到 ${channel.channelLabel}${pid ? `（平台商品 ID: ${pid}）` : ''}。`
          : `发布未成功：${job?.errorMessage || '请检查店铺连接与凭据后重试。'}`,
        job,
      });
      if (ok) {
        notify.success(`已发布到 ${channel.channelLabel}`, pid ? `平台商品 ID: ${pid}` : undefined);
      } else {
        notify.error('发布未成功', job?.errorMessage || '请检查店铺连接与凭据后重试。');
      }
      loadUploadJobs();
    } catch (err: any) {
      setPublishResult({ ok: false, message: err.message || '发布失败，请稍后重试。' });
      notify.error('发布失败', err?.message || '请稍后重试。');
    } finally {
      setLoading(false);
    }
  }

  function resetUpload() {
    setStep(1);
    setSelectedProduct(null);
    setSelectedMarketplace('');
    setListingForm(null);
    setListingVersions([]);
    setUploadJob(null);
    setValidationResult(null);
    setExportData(null);
    setPublishResult(null);
    setError(null);
  }

  function goToHistory() {
    resetUpload();
    setTimeout(() => {
      document.getElementById('upload-history')?.scrollIntoView({ behavior: 'smooth' });
    }, 100);
  }

  const selectedMarketplaceData = marketplaces.find((m) => m.id === selectedMarketplace);
  const displayedMarketplaces =
    channel.platform === 'amazon'
      ? marketplaces
      : marketplaces.filter((m) => m.id === currentStore?.marketplaceId);
  const channelOptions = displayedMarketplaces.length > 0 ? displayedMarketplaces : marketplaces;
  const canProceedFromValidation = validationResult && validationResult.errorsCount === 0;
  const titleLen = listingForm?.title.length ?? 0;
  const backendBytes = listingForm ? byteLength(listingForm.backendSearchTerms) : 0;
  const searchTermsCount = channel.platform === 'amazon'
    ? backendBytes
    : (listingForm?.backendSearchTerms.length ?? 0);
  const searchTermsLimit = channel.platform === 'amazon' ? 250 : 500;
  const searchTermsUnit = channel.platform === 'amazon' ? '字节' : '字符';
  const searchTermsLabel =
    channel.platform === 'amazon'
      ? '后台搜索词'
      : channel.platform === 'tiktok'
        ? '搜索关键词 / 标签'
        : 'SEO 关键词';
  const searchTermsPlaceholder =
    channel.platform === 'amazon'
      ? '空格分隔的搜索关键词（不含逗号）'
      : channel.platform === 'tiktok'
        ? '输入 TikTok Shop 搜索关键词或标签'
        : '输入用于独立站 SEO、站内搜索和广告落地页的关键词';
  const bulletLabel = channel.platform === 'amazon' ? '五点描述' : '卖点描述';
  const contentName = channel.platform === 'amazon' ? 'Listing' : '商品内容';
  const publishActionLabel = channel.supportsDirectProductPublish ? '发布任务' : '导出任务';
  const filledBullets = listingForm ? listingForm.bulletPoints.filter((b) => b.trim() !== '').length : 0;
  const canCreateJob = !!listingForm && listingForm.title.trim() !== '' && filledBullets >= 1 && listingForm.description.trim() !== '';

  return (
    <div className={embedded ? '' : 'min-h-screen bg-slate-50 p-6 lg:p-8'}>
      <div className={embedded ? 'space-y-8' : 'mx-auto max-w-6xl space-y-8'}>
        {/* Header */}
        <div>
          <h1 className="text-3xl font-bold text-slate-900">渠道产品发布中心</h1>
          <p className="mt-1 text-slate-500">
            {channel.channelLabel} · {channel.productPublishLabel}。AI 先优化标题和描述，再按渠道校验、审核和导出；只有接入真实发布 connector 后才会直写店铺。
          </p>
          {!channel.supportsDirectProductPublish && (
            <p className="mt-2 inline-flex rounded-full bg-amber-50 px-3 py-1 text-xs font-medium text-amber-700">
              {channel.note}
            </p>
          )}
        </div>

        {/* Error Banner */}
        {error && (
          <div className="flex items-center gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            <AlertCircle className="h-4 w-4 shrink-0" />
            <span>{error}</span>
            <button onClick={() => setError(null)} className="ml-auto text-amber-600 hover:text-amber-800">
              <XCircle className="h-4 w-4" />
            </button>
          </div>
        )}

        {/* Stepper Progress */}
        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="flex items-center justify-between">
            {steps.map((s, idx) => {
              const Icon = s.icon;
              const isActive = step === s.id;
              const isCompleted = step > s.id;
              return (
                <React.Fragment key={s.id}>
                  <button
                    onClick={() => isCompleted && setStep(s.id)}
                    className={cn(
                      'flex flex-col items-center gap-2 transition-all',
                      isCompleted && 'cursor-pointer',
                      !isActive && !isCompleted && 'opacity-40'
                    )}
                    disabled={!isCompleted}
                  >
                    <div
                      className={cn(
                        'flex h-10 w-10 items-center justify-center rounded-full border-2 transition-all',
                        isActive && 'border-blue-600 bg-blue-600 text-white',
                        isCompleted && 'border-emerald-500 bg-emerald-500 text-white',
                        !isActive && !isCompleted && 'border-slate-300 bg-white text-slate-400'
                      )}
                    >
                      {isCompleted ? <Check className="h-5 w-5" /> : <Icon className="h-5 w-5" />}
                    </div>
                    <span className={cn('text-xs font-medium', isActive ? 'text-blue-600' : isCompleted ? 'text-emerald-600' : 'text-slate-400')}>
                      {s.label}
                    </span>
                  </button>
                  {idx < steps.length - 1 && (
                    <div className={cn('mx-2 h-0.5 flex-1', step > s.id ? 'bg-emerald-400' : 'bg-slate-200')} />
                  )}
                </React.Fragment>
              );
            })}
          </div>
        </div>

        {/* Step Content */}
        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          {/* Step 1: Select Product */}
          {step === 1 && (
            <div className="space-y-4">
              <h2 className="text-xl font-semibold text-slate-900">选择产品</h2>
              <p className="text-sm text-slate-500">选择您想要发布到 {channel.channelLabel} 的产品。</p>
              {products.length === 0 ? (
                <div className="rounded-lg border border-dashed border-slate-300 p-8 text-center">
                  <Package className="mx-auto h-8 w-8 text-slate-400" />
                  <p className="mt-2 text-sm text-slate-500">该店铺暂无产品。</p>
                </div>
              ) : (
                <div className="grid gap-4 sm:grid-cols-2">
                  {products.map((product) => (
                    <button
                      key={product.id}
                      onClick={() => setSelectedProduct(product)}
                      className={cn(
                        'rounded-xl border-2 p-5 text-left transition-all hover:shadow-md',
                        selectedProduct?.id === product.id ? 'border-blue-500 bg-blue-50' : 'border-slate-200 hover:border-slate-300'
                      )}
                    >
                      <div className="flex items-start justify-between">
                        <div>
                          <h3 className="font-semibold text-slate-900">{product.name}</h3>
                          <p className="mt-1 text-sm text-slate-500">SKU: {product.sku}</p>
                          {channel.platform === 'amazon' && product.asin ? (
                            <p className="text-sm text-slate-500">ASIN: {product.asin}</p>
                          ) : null}
                        </div>
                        <StatusBadge status={product.status} />
                      </div>
                      <div className="mt-3 flex items-center gap-4 text-sm">
                        <span className="font-medium text-slate-900">{formatCurrency(product.price)}</span>
                        <span className="text-slate-500">{(product.inventory ?? 0).toLocaleString()} 件</span>
                      </div>
                    </button>
                  ))}
                </div>
              )}
              <div className="flex justify-end pt-4">
                <button
                  onClick={() => {
                    if (selectedProduct) {
                      setStep(2);
                      loadListingVersions(selectedProduct.id);
                    }
                  }}
                  disabled={!selectedProduct}
                  className={cn(
                    'flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm font-medium transition-all',
                    selectedProduct ? 'bg-blue-600 text-white hover:bg-blue-700' : 'bg-slate-200 text-slate-400 cursor-not-allowed'
                  )}
                >
                  下一步 <ChevronRight className="h-4 w-4" />
                </button>
              </div>
            </div>
          )}

          {/* Step 2: Select channel / marketplace */}
          {step === 2 && (
            <div className="space-y-4">
              <h2 className="text-xl font-semibold text-slate-900">
                {channel.platform === 'amazon' ? '选择站点' : '选择发布渠道'}
              </h2>
              <p className="text-sm text-slate-500">
                {channel.platform === 'amazon'
                  ? '选择要刊登产品的亚马逊站点。'
                  : `当前店铺将按 ${channel.channelLabel} 的商品发布链路处理。`}
              </p>
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                {channelOptions.map((mp) => (
                  <button
                    key={mp.id}
                    onClick={() => setSelectedMarketplace(mp.id)}
                    className={cn(
                      'rounded-xl border-2 p-5 text-center transition-all hover:shadow-md',
                      selectedMarketplace === mp.id ? 'border-blue-500 bg-blue-50' : 'border-slate-200 hover:border-slate-300'
                    )}
                  >
                    <div className="mx-auto flex h-10 w-10 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
                      <Globe className="h-5 w-5" />
                    </div>
                    <h3 className="mt-2 font-semibold text-slate-900">{mp.name}</h3>
                    <p className="text-sm text-slate-500">{mp.currency}</p>
                  </button>
                ))}
              </div>
              <div className="flex justify-between pt-4">
                <button onClick={() => setStep(1)} className="flex items-center gap-2 rounded-lg border border-slate-200 px-5 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50">
                  <ChevronLeft className="h-4 w-4" /> 上一步
                </button>
                <button
                  onClick={() => selectedMarketplace && setStep(3)}
                  disabled={!selectedMarketplace}
                  className={cn(
                    'flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm font-medium transition-all',
                    selectedMarketplace ? 'bg-blue-600 text-white hover:bg-blue-700' : 'bg-slate-200 text-slate-400 cursor-not-allowed'
                  )}
                >
                  下一步 <ChevronRight className="h-4 w-4" />
                </button>
              </div>
            </div>
          )}

          {/* Step 3: Generate / Edit Listing content */}
          {step === 3 && (
            <div className="space-y-5">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h2 className="text-xl font-semibold text-slate-900">{contentName}</h2>
                  <p className="mt-1 text-sm text-slate-500">
                    使用 AI 为 <span className="font-medium text-slate-700">{selectedProduct?.name}</span> 生成适配 {channel.channelLabel} 的标题、卖点、商品描述和关键词，并可在提交前编辑。
                  </p>
                </div>
                <button
                  onClick={handleGenerate}
                  disabled={generating}
                  className="flex shrink-0 items-center gap-2 rounded-lg bg-violet-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-violet-700 disabled:opacity-50"
                >
                  {generating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
                  {listingForm ? '重新生成' : 'AI 生成'}
                </button>
              </div>

              {/* Existing versions */}
              {listingVersions.length > 0 && (
                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                  <p className="mb-2 text-xs font-medium text-slate-500">历史版本（点击载入编辑）</p>
                  <div className="flex flex-wrap gap-2">
                    {listingVersions.map((v, i) => (
                      <button
                        key={v.id ?? i}
                        onClick={() => loadVersionIntoForm(v)}
                        className={cn(
                          'flex items-center gap-1.5 rounded-md border px-2.5 py-1 text-xs transition-colors',
                          listingForm?.id === v.id
                            ? 'border-blue-500 bg-blue-50 text-blue-700'
                            : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-100'
                        )}
                      >
                        <FileText className="h-3 w-3" />
                        v{listingVersions.length - i}
                        {v.createdAt ? <span className="text-slate-400">· {v.createdAt}</span> : null}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {!listingForm ? (
                <div className="rounded-lg border border-dashed border-slate-300 p-10 text-center">
                  <Sparkles className="mx-auto h-8 w-8 text-violet-400" />
                  <p className="mt-2 text-sm text-slate-500">
                    点击「AI 生成」创建{contentName}。AI 未启用时将自动使用合规模板生成。
                  </p>
                </div>
              ) : (
                <div className="space-y-5">
                  {/* Title */}
                  <div>
                    <div className="flex items-center justify-between">
                      <label className="text-sm font-medium text-slate-700">标题</label>
                      <span className={cn('text-xs', titleLen > 200 ? 'text-red-500' : 'text-slate-400')}>{titleLen} / 200</span>
                    </div>
                    <textarea
                      value={listingForm.title}
                      onChange={(e) => updateForm({ title: e.target.value })}
                      rows={2}
                      className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                      placeholder="产品标题"
                    />
                  </div>

                  {/* Bullet points */}
                  <div>
                    <div className="flex items-center justify-between">
                      <label className="text-sm font-medium text-slate-700">{bulletLabel}</label>
                      <span className="text-xs text-slate-400">{filledBullets} / 5</span>
                    </div>
                    <div className="mt-1 space-y-2">
                      {listingForm.bulletPoints.map((b, i) => (
                        <div key={i} className="flex items-start gap-2">
                          <span className="mt-2 text-xs font-semibold text-slate-400">{i + 1}.</span>
                          <textarea
                            value={b}
                            onChange={(e) => updateBullet(i, e.target.value)}
                            rows={1}
                            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                            placeholder={`卖点 ${i + 1}`}
                          />
                        </div>
                      ))}
                    </div>
                  </div>

                  {/* Description */}
                  <div>
                    <label className="text-sm font-medium text-slate-700">商品描述</label>
                    <textarea
                      value={listingForm.description}
                      onChange={(e) => updateForm({ description: e.target.value })}
                      rows={5}
                      className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                      placeholder="商品描述"
                    />
                  </div>

                  {/* Backend search terms */}
                  <div>
                    <div className="flex items-center justify-between">
                      <label className="text-sm font-medium text-slate-700">{searchTermsLabel}</label>
                      <span className={cn('text-xs', searchTermsCount > searchTermsLimit ? 'text-red-500' : 'text-slate-400')}>
                        {searchTermsCount} / {searchTermsLimit} {searchTermsUnit}
                      </span>
                    </div>
                    <textarea
                      value={listingForm.backendSearchTerms}
                      onChange={(e) => updateForm({ backendSearchTerms: e.target.value })}
                      rows={2}
                      className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                      placeholder={searchTermsPlaceholder}
                    />
                  </div>
                </div>
              )}

              <div className="flex justify-between pt-2">
                <button onClick={() => setStep(2)} className="flex items-center gap-2 rounded-lg border border-slate-200 px-5 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50">
                  <ChevronLeft className="h-4 w-4" /> 上一步
                </button>
                <button
                  onClick={handleCreateUploadJob}
                  disabled={!canCreateJob || loading}
                  className={cn(
                    'flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm font-medium transition-all',
                    canCreateJob && !loading ? 'bg-blue-600 text-white hover:bg-blue-700' : 'bg-slate-200 text-slate-400 cursor-not-allowed'
                  )}
                >
                  {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                  创建{publishActionLabel} <ChevronRight className="h-4 w-4" />
                </button>
              </div>
            </div>
          )}

          {/* Step 4: Validate */}
          {step === 4 && (
            <div className="space-y-6">
              <h2 className="text-xl font-semibold text-slate-900">校验{contentName}</h2>
              <p className="text-sm text-slate-500">在导出或发布前对您的{contentName}运行校验检查。</p>

              {!validationResult ? (
                <div className="flex flex-col items-center gap-4 py-8">
                  <ShieldCheck className="h-16 w-16 text-slate-300" />
                  <p className="text-slate-500">点击下方按钮开始校验。</p>
                  <button
                    onClick={handleValidate}
                    disabled={loading}
                    className="flex items-center gap-2 rounded-lg bg-blue-600 px-6 py-3 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                  >
                    {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <ShieldCheck className="h-4 w-4" />}
                    运行校验
                  </button>
                </div>
              ) : (
                <div className="space-y-4">
                  {/* Summary */}
                  <div className="flex items-center gap-6 rounded-lg border border-slate-200 bg-slate-50 p-4">
                    <div className="flex items-center gap-2 text-emerald-600">
                      <Check className="h-5 w-5" />
                      <span className="font-medium">{validationResult.checksPassed} 项检查通过</span>
                    </div>
                    {validationResult.errorsCount > 0 && (
                      <div className="flex items-center gap-2 text-red-600">
                        <XCircle className="h-5 w-5" />
                        <span className="font-medium">{validationResult.errorsCount} 个错误</span>
                      </div>
                    )}
                    {validationResult.warningsCount > 0 && (
                      <div className="flex items-center gap-2 text-amber-600">
                        <AlertTriangle className="h-5 w-5" />
                        <span className="font-medium">{validationResult.warningsCount} 个警告</span>
                      </div>
                    )}
                  </div>

                  {/* Errors */}
                  {validationResult.errors?.length > 0 && (
                    <div className="rounded-lg border border-red-200 bg-red-50 p-4">
                      <h3 className="flex items-center gap-2 font-semibold text-red-800">
                        <XCircle className="h-4 w-4" /> 错误
                      </h3>
                      <ul className="mt-2 space-y-1">
                        {validationResult.errors.map((err: string, idx: number) => (
                          <li key={idx} className="flex items-start gap-2 text-sm text-red-700">
                            <span className="mt-0.5 text-red-500">-</span>
                            {err}
                          </li>
                        ))}
                      </ul>
                      <button
                        onClick={() => setStep(3)}
                        className="mt-3 inline-flex items-center gap-1.5 rounded-md border border-red-200 bg-white px-3 py-1.5 text-xs font-medium text-red-700 hover:bg-red-50"
                      >
                        <ChevronLeft className="h-3 w-3" /> 返回修改内容
                      </button>
                    </div>
                  )}

                  {/* Warnings */}
                  {validationResult.warnings?.length > 0 && (
                    <div className="rounded-lg border border-amber-200 bg-amber-50 p-4">
                      <h3 className="flex items-center gap-2 font-semibold text-amber-800">
                        <AlertTriangle className="h-4 w-4" /> 警告
                      </h3>
                      <ul className="mt-2 space-y-1">
                        {validationResult.warnings.map((warn: string, idx: number) => (
                          <li key={idx} className="flex items-start gap-2 text-sm text-amber-700">
                            <span className="mt-0.5 text-amber-500">-</span>
                            {warn}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              )}

              <div className="flex justify-between pt-4">
                <button onClick={() => setStep(3)} className="flex items-center gap-2 rounded-lg border border-slate-200 px-5 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50">
                  <ChevronLeft className="h-4 w-4" /> 上一步
                </button>
                <button
                  onClick={() => setStep(5)}
                  disabled={!canProceedFromValidation}
                  className={cn(
                    'flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm font-medium transition-all',
                    canProceedFromValidation ? 'bg-blue-600 text-white hover:bg-blue-700' : 'bg-slate-200 text-slate-400 cursor-not-allowed'
                  )}
                >
                  继续 <ChevronRight className="h-4 w-4" />
                </button>
              </div>
            </div>
          )}

          {/* Step 5: Review & Approve */}
          {step === 5 && (
            <div className="space-y-6">
              <h2 className="text-xl font-semibold text-slate-900">审核与批准</h2>
              <p className="text-sm text-slate-500">在提交前审核您的{contentName}详情。</p>

              <div className="grid gap-6 lg:grid-cols-2">
                {/* Product Info */}
                <div className="rounded-lg border border-slate-200 p-5">
                  <h3 className="font-semibold text-slate-900">产品信息</h3>
                  <div className="mt-3 space-y-2 text-sm">
                    <div className="flex justify-between">
                      <span className="text-slate-500">名称</span>
                      <span className="font-medium text-slate-900">{selectedProduct?.name}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-slate-500">SKU</span>
                      <span className="font-medium text-slate-900">{selectedProduct?.sku}</span>
                    </div>
                    {channel.platform === 'amazon' && selectedProduct?.asin ? (
                      <div className="flex justify-between">
                        <span className="text-slate-500">ASIN</span>
                        <span className="font-medium text-slate-900">{selectedProduct.asin}</span>
                      </div>
                    ) : null}
                    <div className="flex justify-between">
                      <span className="text-slate-500">价格</span>
                      <span className="font-medium text-slate-900">{selectedProduct && formatCurrency(selectedProduct.price)}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-slate-500">库存</span>
                      <span className="font-medium text-slate-900">{(selectedProduct?.inventory ?? 0).toLocaleString()} 件</span>
                    </div>
                  </div>
                </div>

                {/* Marketplace */}
                <div className="rounded-lg border border-slate-200 p-5">
                  <h3 className="font-semibold text-slate-900">{channel.platform === 'amazon' ? '站点' : '渠道'}</h3>
                  <div className="mt-3 flex items-center gap-3">
                    <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
                      <Globe className="h-5 w-5" />
                    </div>
                    <div>
                      <p className="font-medium text-slate-900">{selectedMarketplaceData?.name}</p>
                      <p className="text-sm text-slate-500">{selectedMarketplaceData?.currency}</p>
                    </div>
                  </div>
                </div>

                {/* Listing Preview */}
                <div className="rounded-lg border border-slate-200 p-5 lg:col-span-2">
                  <h3 className="font-semibold text-slate-900">{contentName}预览</h3>
                  <div className="mt-3 space-y-3 text-sm">
                    <div>
                      <p className="text-slate-500">标题</p>
                      <p className="font-medium text-slate-900">{listingForm?.title}</p>
                    </div>
                    <div>
                      <p className="text-slate-500">{bulletLabel}</p>
                      <ul className="mt-1 space-y-1 text-slate-700">
                        {listingForm?.bulletPoints.filter((b) => b.trim() !== '').map((b, i) => (
                          <li key={i}>- {b}</li>
                        ))}
                      </ul>
                    </div>
                    <div>
                      <p className="text-slate-500">商品描述</p>
                      <p className="text-slate-700">{listingForm?.description}</p>
                    </div>
                    {listingForm?.backendSearchTerms ? (
                      <div>
                        <p className="text-slate-500">{searchTermsLabel}</p>
                        <p className="text-slate-700">{listingForm.backendSearchTerms}</p>
                      </div>
                    ) : null}
                  </div>
                </div>

                {/* Validation Status */}
                <div className="rounded-lg border border-slate-200 p-5">
                  <h3 className="font-semibold text-slate-900">校验状态</h3>
                  <div className="mt-3 flex items-center gap-2">
                    {validationResult?.errorsCount === 0 ? (
                      <>
                        <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                        <span className="text-sm font-medium text-emerald-700">全部校验通过</span>
                      </>
                    ) : (
                      <>
                        <XCircle className="h-5 w-5 text-red-500" />
                        <span className="text-sm font-medium text-red-700">发现 {validationResult?.errorsCount} 个问题</span>
                      </>
                    )}
                  </div>
                </div>

                {/* Task status */}
                <div className="rounded-lg border border-slate-200 p-5">
                  <h3 className="font-semibold text-slate-900">任务状态</h3>
                  <div className="mt-3">
                    <StatusBadge status={uploadJob?.status ?? 'ready'} />
                  </div>
                </div>
              </div>

              {/* Action Buttons */}
              <div className="flex flex-wrap items-center justify-between gap-3 pt-4">
                <button onClick={() => setStep(4)} className="flex items-center gap-2 rounded-lg border border-slate-200 px-5 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50">
                  <ChevronLeft className="h-4 w-4" /> 上一步
                </button>
                <button onClick={handleApprove} disabled={loading} className="flex items-center gap-2 rounded-lg bg-emerald-600 px-6 py-2.5 text-sm font-medium text-white hover:bg-emerald-700 disabled:opacity-50">
                  {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}
                  {channel.supportsDirectProductPublish ? '批准并发布' : '批准并生成导出'}
                </button>
              </div>
            </div>
          )}

          {/* Step 6: Complete */}
          {step === 6 && (
            <div className="space-y-6">
              <div className="flex flex-col items-center gap-4 py-8 text-center">
                <div className="flex h-20 w-20 items-center justify-center rounded-full bg-emerald-100">
                  <PartyPopper className="h-10 w-10 text-emerald-600" />
                </div>
                <h2 className="text-2xl font-bold text-slate-900">{channel.supportsDirectProductPublish ? '发布任务已提交！' : '导出任务已批准！'}</h2>
                <p className="max-w-md text-slate-500">
                  您为 <span className="font-medium text-slate-700">{selectedProduct?.name}</span> 创建的{contentName}已批准，当前会按 {channel.channelLabel} 支持的真实链路生成可导出的发布资料。
                </p>
              </div>

              {/* Direct publish to the connected store (Shopify / WooCommerce) */}
              {channel.supportsDirectProductPublish && (
                <div className="rounded-lg border border-emerald-200 bg-emerald-50/50 p-5">
                  <h3 className="flex items-center gap-2 font-semibold text-slate-900">
                    <Upload className="h-4 w-4 text-emerald-600" /> 直接发布到 {channel.channelLabel}
                  </h3>
                  <p className="mt-1 text-sm text-slate-500">
                    通过已连接的店铺 API 真实创建商品。发布失败时会显示平台返回的真实原因，不会伪造成功。
                  </p>
                  <button
                    onClick={() => uploadJob && handlePublish(uploadJob.id)}
                    disabled={loading || !uploadJob}
                    className="mt-3 flex items-center gap-2 rounded-lg bg-emerald-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-emerald-700 disabled:opacity-50"
                  >
                    {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
                    立即发布
                  </button>
                  {publishResult && (
                    <div
                      className={cn(
                        'mt-3 flex items-start gap-2 rounded-lg border px-4 py-3 text-sm',
                        publishResult.ok
                          ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
                          : 'border-amber-200 bg-amber-50 text-amber-800'
                      )}
                    >
                      {publishResult.ok ? (
                        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
                      ) : (
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                      )}
                      <span>{publishResult.message}</span>
                    </div>
                  )}
                </div>
              )}

              {/* Export */}
              <div className="rounded-lg border border-slate-200 p-5">
                <h3 className="font-semibold text-slate-900">导出上传数据</h3>
                <p className="mt-1 text-sm text-slate-500">下载用于 {channel.channelLabel} 的{contentName}数据。</p>
                <div className="mt-3 flex flex-wrap gap-3">
                  <button onClick={() => handleExport('json')} disabled={loading} className="flex items-center gap-2 rounded-lg border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50">
                    <Download className="h-4 w-4" /> 导出 JSON
                  </button>
                  <button onClick={() => handleExport('csv')} disabled={loading} className="flex items-center gap-2 rounded-lg border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50">
                    <Download className="h-4 w-4" /> 导出 CSV
                  </button>
                  <button onClick={() => handleExport('flat_file')} disabled={loading} className="flex items-center gap-2 rounded-lg border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50">
                    <Download className="h-4 w-4" /> {channel.platform === 'amazon' ? '导出平铺文件' : '导出渠道文件'}
                  </button>
                </div>
              </div>

              {/* Exported Data Preview */}
              {exportData && (
                <div className="rounded-lg border border-slate-200 p-5">
                  <h3 className="font-semibold text-slate-900">导出数据预览（{exportFormat}）</h3>
                  <pre className="mt-3 max-h-64 overflow-auto rounded-lg bg-slate-900 p-4 text-xs text-slate-100">
                    {typeof exportData === 'string' ? exportData : JSON.stringify(exportData, null, 2)}
                  </pre>
                </div>
              )}

              <div className="flex justify-center gap-4">
                <button onClick={resetUpload} className="flex items-center gap-2 rounded-lg border border-slate-200 px-5 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50">
                  <Upload className="h-4 w-4" /> 再处理一个产品
                </button>
                <button onClick={goToHistory} className="flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-blue-700">
                  <Eye className="h-4 w-4" /> 查看任务历史
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Upload History Section */}
        <div id="upload-history" className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-xl font-semibold text-slate-900">上传历史</h2>
              <p className="mt-1 text-sm text-slate-500">追踪您过往的产品上传任务。</p>
            </div>
            <button onClick={loadUploadJobs} className="flex items-center gap-2 rounded-lg border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50">
              <RefreshCw className="h-4 w-4" /> 刷新
            </button>
          </div>

          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-slate-200">
                  <th className="pb-3 pr-4 font-medium text-slate-500">产品</th>
                  <th className="pb-3 pr-4 font-medium text-slate-500">站点</th>
                  <th className="pb-3 pr-4 font-medium text-slate-500">状态</th>
                  <th className="pb-3 pr-4 font-medium text-slate-500">创建时间</th>
                  <th className="pb-3 font-medium text-slate-500">操作</th>
                </tr>
              </thead>
              <tbody>
                {uploadJobs.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="py-8 text-center text-slate-400">
                      <Clock className="mx-auto mb-2 h-8 w-8" />
                      暂无上传任务。请在上方开始您的首次上传。
                    </td>
                  </tr>
                ) : (
                  uploadJobs.map((job: any, idx: number) => (
                    <tr key={job.id ?? idx} className="border-b border-slate-100 last:border-0">
                      <td className="py-3 pr-4 font-medium text-slate-900">{job.productName ?? 'N/A'}</td>
                      <td className="py-3 pr-4 text-slate-600">{job.marketplace ?? 'N/A'}</td>
                      <td className="py-3 pr-4">
                        <StatusBadge status={job.status ?? 'draft'} />
                      </td>
                      <td className="py-3 pr-4 text-slate-500">{job.createdAt ?? 'N/A'}</td>
                      <td className="py-3">
                        <div className="flex items-center gap-2">
                          <button
                            onClick={() => setViewJob(job)}
                            className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100 hover:text-slate-700" title="查看">
                            <Eye className="h-4 w-4" />
                          </button>
                          {(job.uploadMethod === 'shopify_api' || job.uploadMethod === 'woocommerce_api' || job.uploadMethod === 'tiktok_shop_api') && (
                            <button
                              onClick={() => handlePublish(job.id)}
                              disabled={loading}
                              className="rounded-md p-1.5 text-emerald-600 hover:bg-emerald-50 disabled:opacity-50" title="直接发布到店铺">
                              <Upload className="h-4 w-4" />
                            </button>
                          )}
                          <button
                            onClick={() => {
                              const blob = new Blob([JSON.stringify(job, null, 2)], { type: 'application/json' });
                              const url = URL.createObjectURL(blob);
                              const a = document.createElement('a');
                              a.href = url;
                              a.download = `upload-job-${job.id ?? 'export'}.json`;
                              a.click();
                              URL.revokeObjectURL(url);
                            }}
                            className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100 hover:text-slate-700" title="导出">
                            <Download className="h-4 w-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <RecordModal
        open={!!viewJob}
        mode="view"
        title="上传任务详情"
        fields={UPLOAD_JOB_FIELDS}
        record={viewJob}
        onClose={() => setViewJob(null)}
      />
    </div>
  );
}
