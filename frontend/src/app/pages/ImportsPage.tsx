import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Upload,
  FileSpreadsheet,
  CheckCircle2,
  AlertTriangle,
  Loader2,
  Eye,
  RotateCcw,
  History,
  Columns3,
  ShieldCheck,
  FileSearch,
  ArrowRight,
  ArrowLeft,
  Trash2,
} from 'lucide-react';
import {
  uploadImportFile,
  previewImport,
  mapImport,
  validateImport,
  commitImport,
  fetchImports,
  fetchImportById,
  reanalyzeImport,
} from '../lib/api';
import { cn } from '../lib/utils';
import { Button } from '../components/ui/button';
import { useStoreId } from '../lib/useStoreId';

// 鈹€鈹€鈹€ Constants 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

const STEPS = [
  { num: 1, label: '上传 CSV', icon: Upload },
  { num: 2, label: '预览', icon: Eye },
  { num: 3, label: '字段映射', icon: Columns3 },
  { num: 4, label: '校验', icon: ShieldCheck },
  { num: 5, label: '结果', icon: CheckCircle2 },
] as const;

const REPORT_TYPES = [
  { value: 'sp_search_term', label: 'SP 搜索词' },
  { value: 'sp_targeting', label: 'SP 投放' },
  { value: 'sp_campaign', label: 'SP 广告活动' },
  { value: 'sp_advertised_product', label: 'SP 推广商品' },
  { value: 'sb_search_term', label: 'SB 搜索词' },
  { value: 'sd_targeting', label: 'SD 投放' },
] as const;

const MARKETPLACES = [
  { value: 'mp-1', label: 'US' },
  { value: 'mp-2', label: 'UK' },
  { value: 'mp-3', label: 'DE' },
  { value: 'mp-4', label: 'CA' },
] as const;

// Store selection now uses dynamic storeId from useStoreId hook

const REQUIRED_FIELDS = [
  'campaignName',
  'adGroupName',
  'impressions',
  'clicks',
  'spend',
  'sales',
  'orders',
  'keyword',
  'matchType',
  'targetingType',
] as const;

// 鈹€鈹€鈹€ Status badge color helper 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

function getStatusColor(status: string): string {
  switch (status) {
    case 'completed':
    case 'imported':
      return 'bg-emerald-50 text-emerald-700 border-emerald-200';
    case 'validating':
    case 'mapping':
    case 'uploading':
      return 'bg-blue-50 text-blue-700 border-blue-200';
    case 'failed':
    case 'error':
      return 'bg-red-50 text-red-700 border-red-200';
    case 'validated':
      return 'bg-violet-50 text-violet-700 border-violet-200';
    default:
      return 'bg-slate-50 text-slate-700 border-slate-200';
  }
}

// 鈹€鈹€鈹€ Stepper component 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

function Stepper({ currentStep }: { currentStep: number }) {
  return (
    <div className="flex items-center gap-2 w-full">
      {STEPS.map((s, i) => {
        const Icon = s.icon;
        const isActive = currentStep === s.num;
        const isCompleted = currentStep > s.num;

        return (
          <div key={s.num} className="flex items-center flex-1 min-w-0">
            <div className="flex items-center gap-2 min-w-0">
              <div
                className={cn(
                  'w-9 h-9 rounded-full flex items-center justify-center text-sm font-semibold flex-shrink-0 transition-colors',
                  isCompleted
                    ? 'bg-emerald-500 text-white'
                    : isActive
                      ? 'bg-indigo-600 text-white'
                      : 'bg-slate-100 text-slate-400',
                )}
              >
                {isCompleted ? (
                  <CheckCircle2 size={18} />
                ) : (
                  <Icon size={16} />
                )}
              </div>
              <span
                className={cn(
                  'text-sm font-medium truncate hidden sm:block',
                  isCompleted
                    ? 'text-emerald-600'
                    : isActive
                      ? 'text-slate-900'
                      : 'text-slate-400',
                )}
              >
                {s.label}
              </span>
            </div>
            {i < STEPS.length - 1 && (
              <div
                className={cn(
                  'flex-1 h-0.5 mx-2 rounded-full transition-colors',
                  currentStep > s.num ? 'bg-emerald-400' : 'bg-slate-200',
                )}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

// 鈹€鈹€鈹€ File size formatter 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

// 鈹€鈹€鈹€ Main Component 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

export function ImportsPage() {
  const { storeId: defaultStoreId, loading: storeLoading } = useStoreId();
  const [step, setStep] = useState(1);
  const [storeId, setStoreId] = useState('');
  const [marketplaceId, setMarketplaceId] = useState('mp-1');
  const [reportType, setReportType] = useState('sp_search_term');
  const [file, setFile] = useState<File | null>(null);
  const [importJob, setImportJob] = useState<any>(null);
  const [previewData, setPreviewData] = useState<any>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [validationResult, setValidationResult] = useState<any>(null);
  const [importResult, setImportResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [importHistory, setImportHistory] = useState<any[]>([]);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [dragActive, setDragActive] = useState(false);
  const [reanalyzingId, setReanalyzingId] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  // 鈹€鈹€鈹€ Initialize storeId from hook 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
  useEffect(() => {
    if (defaultStoreId && !storeId) {
      setStoreId(defaultStoreId);
    }
  }, [defaultStoreId, storeId]);

  // 鈹€鈹€鈹€ Load import history 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

  const loadHistory = useCallback(async () => {
    try {
      setHistoryLoading(true);
      const data = await fetchImports(storeId);
      setImportHistory(Array.isArray(data) ? data : []);
    } catch {
      // History is non-critical; silently show empty
      setImportHistory([]);
    } finally {
      setHistoryLoading(false);
    }
  }, [storeId]);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  // 鈹€鈹€鈹€ Drag handlers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

  const handleDrag = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActive(true);
    } else if (e.type === 'dragleave') {
      setDragActive(false);
    }
  }, []);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDragActive(false);
    if (e.dataTransfer.files?.[0]) {
      setFile(e.dataTransfer.files[0]);
    }
  }, []);

  const handleFileSelect = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      if (e.target.files?.[0]) {
        setFile(e.target.files[0]);
      }
    },
    [],
  );

  // 鈹€鈹€鈹€ Step 1: Upload & Preview 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

  const handleUploadAndPreview = async () => {
    if (!file) return;
    try {
      setLoading(true);
      setError(null);

      const formData = new FormData();
      formData.append('file', file);
      formData.append('storeId', storeId);
      formData.append('marketplaceId', marketplaceId);
      formData.append('reportType', reportType);

      const job = await uploadImportFile(formData);
      setImportJob(job);

      const preview = await previewImport(job.id);
      setPreviewData(preview);

      // Auto-detect mapping from CSV headers
      if (preview?.columns) {
        const autoMap: Record<string, string> = {};
        const headers = preview.columns.map((c: string) => c.toLowerCase().replace(/[^a-z0-9]/g, ''));
        for (const field of REQUIRED_FIELDS) {
          const fieldNorm = field.toLowerCase().replace(/[^a-z0-9]/g, '');
          const match = preview.columns.find(
            (_col: string, i: number) =>
              headers[i].includes(fieldNorm) || fieldNorm.includes(headers[i]),
          );
          if (match) {
            autoMap[field] = match;
          }
        }
        setMapping(autoMap);
      }

      setStep(2);
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传失败');
    } finally {
      setLoading(false);
    }
  };

  // 鈹€鈹€鈹€ Step 3: Validate 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

  const handleMapAndValidate = async () => {
    if (!importJob?.id) return;
    try {
      setLoading(true);
      setError(null);

      await mapImport(importJob.id, mapping);
      const result = await validateImport(importJob.id);
      setValidationResult(result);
      setStep(4);
    } catch (err) {
      setError(err instanceof Error ? err.message : '校验失败');
    } finally {
      setLoading(false);
    }
  };

  // 鈹€鈹€鈹€ Step 4: Commit 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

  const handleCommit = async () => {
    if (!importJob?.id) return;
    try {
      setLoading(true);
      setError(null);

      const result = await commitImport(importJob.id);
      setImportResult(result);
      setStep(5);
      loadHistory();
    } catch (err) {
      setError(err instanceof Error ? err.message : '导入失败');
    } finally {
      setLoading(false);
    }
  };

  // 鈹€鈹€鈹€ Re-analyze 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

  const handleReanalyze = async (id: string) => {
    try {
      setReanalyzingId(id);
      await reanalyzeImport(id);
      await loadHistory();
    } catch (err) {
      setError(err instanceof Error ? err.message : '重新分析失败');
    } finally {
      setReanalyzingId(null);
    }
  };

  // 鈹€鈹€鈹€ Reset stepper 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

  const resetStepper = () => {
    setStep(1);
    setFile(null);
    setImportJob(null);
    setPreviewData(null);
    setMapping({});
    setValidationResult(null);
    setImportResult(null);
    setError(null);
  };

  // 鈹€鈹€鈹€ Estimated rows from file size 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

  const estimatedRows = file
    ? Math.max(1, Math.round(file.size / 120) - 1)
    : 0;

  // 鈹€鈹€鈹€ Render 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">
            CSV 导入中心
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            导入 Amazon 广告 CSV 报告以进行关键词分析
          </p>
        </div>
        {step > 1 && (
          <Button variant="outline" onClick={resetStepper}>
            <RotateCcw size={14} />
            开始新导入
          </Button>
        )}
      </div>

      {/* Stepper */}
      <div className="bg-white rounded-xl border border-slate-200 p-6">
        <Stepper currentStep={step} />
      </div>

      {/* Error banner */}
      {error && (
        <div className="bg-red-50 border border-red-200 rounded-xl p-4 flex items-start gap-3">
          <AlertTriangle className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" />
          <div className="flex-1">
            <p className="text-sm font-medium text-red-800">{error}</p>
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setError(null)}
            className="text-red-600 hover:text-red-700 hover:bg-red-100"
          >
            关闭
          </Button>
        </div>
      )}

      {/* Step content */}
      <div className="bg-white rounded-xl border border-slate-200 p-6">
        {/* 鈹€鈹€ Step 1: Upload CSV 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ */}
        {step === 1 && (
          <div className="space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-slate-900 mb-1">
                上传 CSV 文件
              </h2>
              <p className="text-sm text-slate-500">
                选择您的 Amazon 广告报告 CSV，开始导入流程。
              </p>
            </div>

            {/* Dropdowns */}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  店铺
                </label>
                <select
                  value={storeId}
                  onChange={(e) => setStoreId(e.target.value)}
                  className="w-full h-9 px-3 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
                >
                  {storeId ? (
                    <option value={storeId}>{storeId}</option>
                  ) : (
                    <option value="" disabled>{storeLoading ? '加载店铺中...' : '暂无可用店铺'}</option>
                  )}
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  站点
                </label>
                <select
                  value={marketplaceId}
                  onChange={(e) => setMarketplaceId(e.target.value)}
                  className="w-full h-9 px-3 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
                >
                  {MARKETPLACES.map((m) => (
                    <option key={m.value} value={m.value}>
                      {m.label}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  报告类型
                </label>
                <select
                  value={reportType}
                  onChange={(e) => setReportType(e.target.value)}
                  className="w-full h-9 px-3 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
                >
                  {REPORT_TYPES.map((r) => (
                    <option key={r.value} value={r.value}>
                      {r.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {/* Drop zone */}
            <div
              onDragEnter={handleDrag}
              onDragLeave={handleDrag}
              onDragOver={handleDrag}
              onDrop={handleDrop}
              onClick={() => fileInputRef.current?.click()}
              className={cn(
                'border-2 border-dashed rounded-xl p-10 text-center cursor-pointer transition-colors',
                dragActive
                  ? 'border-indigo-400 bg-indigo-50'
                  : 'border-slate-200 hover:border-slate-300 hover:bg-slate-50',
              )}
            >
              <input
                ref={fileInputRef}
                type="file"
                accept=".csv,.txt"
                onChange={handleFileSelect}
                className="hidden"
              />
              <div className="w-14 h-14 bg-slate-100 rounded-full flex items-center justify-center mx-auto mb-4">
                <FileSpreadsheet className="w-7 h-7 text-slate-400" />
              </div>
              {file ? (
                <div>
                  <p className="text-sm font-medium text-slate-900">
                    {file.name}
                  </p>
                  <p className="text-xs text-slate-500 mt-1">
                    {formatFileSize(file.size)} &middot; ~{estimatedRows.toLocaleString()}{' '}
                    行
                  </p>
                </div>
              ) : (
                <div>
                  <p className="text-sm font-medium text-slate-700">
                    将 CSV 文件拖拽到此处
                  </p>
                  <p className="text-xs text-slate-400 mt-1">
                    或点击浏览文件
                  </p>
                </div>
              )}
            </div>

            {/* File info (selected) */}
            {file && (
              <div className="flex items-center gap-3 bg-slate-50 rounded-lg p-3">
                <FileSpreadsheet className="w-5 h-5 text-emerald-500 flex-shrink-0" />
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-slate-900 truncate">
                    {file.name}
                  </p>
                  <p className="text-xs text-slate-500">
                    {formatFileSize(file.size)} &middot; ~{estimatedRows.toLocaleString()}{' '}
                    预计行数
                  </p>
                </div>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setFile(null);
                  }}
                  className="text-slate-400 hover:text-red-500 transition-colors"
                >
                  <Trash2 size={16} />
                </button>
              </div>
            )}

            {/* Upload button */}
            <div className="flex justify-end">
              <Button
                onClick={handleUploadAndPreview}
                disabled={!file || loading}
              >
                {loading ? (
                  <>
                    <Loader2 className="animate-spin" size={16} />
                    上传中...
                  </>
                ) : (
                  <>
                    上传并预览
                    <ArrowRight size={16} />
                  </>
                )}
              </Button>
            </div>
          </div>
        )}

        {/* 鈹€鈹€ Step 2: Preview 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ */}
        {step === 2 && previewData && (
          <div className="space-y-6">
            <div className="flex items-start justify-between">
              <div>
                <h2 className="text-lg font-semibold text-slate-900 mb-1">
                  预览数据
                </h2>
                <p className="text-sm text-slate-500">
                  显示前 {Math.min(20, previewData.rows?.length ?? 0)} 行 / 共{' '}
                  <span className="font-medium text-slate-700">
                    {previewData.totalRows?.toLocaleString() ?? previewData.rows?.length ?? 0}
                  </span>{' '}
                  行 &middot;{' '}
                  <span className="font-medium text-slate-700">
                    {previewData.columns?.length ?? 0}
                  </span>{' '}
                  列（已检测）
                </p>
              </div>
            </div>

            {/* Detected columns */}
            <div>
              <h3 className="text-sm font-medium text-slate-700 mb-2">
                检测到的列
              </h3>
              <div className="flex flex-wrap gap-1.5">
                {(previewData.columns ?? []).map((col: string) => (
                  <span
                    key={col}
                    className="inline-flex px-2.5 py-1 text-xs font-medium bg-slate-100 text-slate-700 rounded-md border border-slate-200"
                  >
                    {col}
                  </span>
                ))}
              </div>
            </div>

            {/* Preview table */}
            <div className="overflow-x-auto border border-slate-200 rounded-lg">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-slate-50 border-b border-slate-200">
                    <th className="px-3 py-2.5 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider w-12">
                      #
                    </th>
                    {(previewData.columns ?? []).map((col: string) => (
                      <th
                        key={col}
                        className="px-3 py-2.5 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider whitespace-nowrap"
                      >
                        {col}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {(previewData.rows ?? []).slice(0, 20).map(
                    (row: Record<string, any>, i: number) => (
                      <tr key={i} className="hover:bg-slate-50">
                        <td className="px-3 py-2 text-xs text-slate-400">
                          {i + 1}
                        </td>
                        {(previewData.columns ?? []).map((col: string) => (
                          <td
                            key={col}
                            className="px-3 py-2 text-sm text-slate-700 whitespace-nowrap max-w-[200px] truncate"
                          >
                            {row[col] ?? ''}
                          </td>
                        ))}
                      </tr>
                    ),
                  )}
                </tbody>
              </table>
            </div>

            {/* Navigation */}
            <div className="flex items-center justify-between">
              <Button variant="outline" onClick={() => setStep(1)}>
                <ArrowLeft size={16} />
                上一步
              </Button>
              <Button onClick={() => setStep(3)}>
                下一步：字段映射
                <ArrowRight size={16} />
              </Button>
            </div>
          </div>
        )}

        {/* 鈹€鈹€ Step 3: Map Fields 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ */}
        {step === 3 && previewData && (
          <div className="space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-slate-900 mb-1">
                字段映射
              </h2>
              <p className="text-sm text-slate-500">
                将您的 CSV 列映射到所需字段，已自动检测的映射会预先填充。
              </p>
            </div>

            <div className="space-y-3">
              {REQUIRED_FIELDS.map((field) => (
                <div
                  key={field}
                  className="flex items-center gap-4 p-3 bg-slate-50 rounded-lg border border-slate-200"
                >
                  <div className="w-48 flex-shrink-0">
                    <span className="text-sm font-medium text-slate-900">
                      {field}
                    </span>
                  </div>
                  <ArrowRight size={14} className="text-slate-400 flex-shrink-0" />
                  <div className="flex-1">
                    <select
                      value={mapping[field] ?? ''}
                      onChange={(e) =>
                        setMapping((prev) => ({
                          ...prev,
                          [field]: e.target.value,
                        }))
                      }
                      className="w-full h-9 px-3 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
                    >
                      <option value="">-- 选择列 --</option>
                      {(previewData.columns ?? []).map((col: string) => (
                        <option key={col} value={col}>
                          {col}
                        </option>
                      ))}
                    </select>
                  </div>
                  {mapping[field] && (
                    <CheckCircle2
                      size={16}
                      className="text-emerald-500 flex-shrink-0"
                    />
                  )}
                </div>
              ))}
            </div>

            {/* Navigation */}
            <div className="flex items-center justify-between">
              <Button variant="outline" onClick={() => setStep(2)}>
                <ArrowLeft size={16} />
                上一步
              </Button>
              <Button onClick={handleMapAndValidate} disabled={loading}>
                {loading ? (
                  <>
                    <Loader2 className="animate-spin" size={16} />
                    校验中...
                  </>
                ) : (
                  <>
                    校验
                    <ArrowRight size={16} />
                  </>
                )}
              </Button>
            </div>
          </div>
        )}

        {/* 鈹€鈹€ Step 4: Validate 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ */}
        {step === 4 && validationResult && (
          <div className="space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-slate-900 mb-1">
                校验结果
              </h2>
              <p className="text-sm text-slate-500">
                导入前请检查校验结果。
              </p>
            </div>

            {/* Stats cards */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
              <div className="bg-slate-50 border border-slate-200 rounded-xl p-4 text-center">
                <p className="text-2xl font-bold text-slate-900">
                  {(validationResult.totalRows ?? 0).toLocaleString()}
                </p>
                <p className="text-xs text-slate-500 mt-1">总行数</p>
              </div>
              <div className="bg-emerald-50 border border-emerald-200 rounded-xl p-4 text-center">
                <p className="text-2xl font-bold text-emerald-700">
                  {(validationResult.validRows ?? 0).toLocaleString()}
                </p>
                <p className="text-xs text-emerald-600 mt-1">有效行</p>
              </div>
              <div className="bg-red-50 border border-red-200 rounded-xl p-4 text-center">
                <p className="text-2xl font-bold text-red-700">
                  {(validationResult.invalidRows ?? 0).toLocaleString()}
                </p>
                <p className="text-xs text-red-600 mt-1">无效行</p>
              </div>
              <div className="bg-yellow-50 border border-yellow-200 rounded-xl p-4 text-center">
                <p className="text-2xl font-bold text-yellow-700">
                  {(validationResult.duplicateRows ?? 0).toLocaleString()}
                </p>
                <p className="text-xs text-yellow-600 mt-1">重复行</p>
              </div>
            </div>

            {/* Error list */}
            {validationResult.errors?.length > 0 && (
              <div className="border border-red-200 rounded-xl overflow-hidden">
                <div className="bg-red-50 px-4 py-2.5 border-b border-red-200">
                  <h3 className="text-sm font-semibold text-red-800">
                    错误（{validationResult.errors.length}）
                  </h3>
                </div>
                <div className="max-h-64 overflow-y-auto divide-y divide-slate-100">
                  {validationResult.errors.map(
                    (err: { row?: number; message?: string }, i: number) => (
                      <div
                        key={i}
                        className="px-4 py-2.5 flex items-start gap-3"
                      >
                        <span className="inline-flex items-center justify-center w-6 h-6 rounded bg-red-100 text-red-700 text-xs font-semibold flex-shrink-0">
                          {err.row ?? '?'}
                        </span>
                        <p className="text-sm text-slate-700">
                          {err.message ?? '未知错误'}
                        </p>
                      </div>
                    ),
                  )}
                </div>
              </div>
            )}

            {/* Navigation */}
            <div className="flex items-center justify-between">
              <Button variant="outline" onClick={() => setStep(3)}>
                <ArrowLeft size={16} />
                上一步
              </Button>
              <Button
                onClick={handleCommit}
                disabled={loading || (validationResult.validRows ?? 0) === 0}
              >
                {loading ? (
                  <>
                    <Loader2 className="animate-spin" size={16} />
                    导入中...
                  </>
                ) : (
                  <>
                    导入{' '}
                    {(validationResult.validRows ?? 0).toLocaleString()} 行
                    <ArrowRight size={16} />
                  </>
                )}
              </Button>
            </div>
          </div>
        )}

        {/* 鈹€鈹€ Step 5: Results 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ */}
        {step === 5 && importResult && (
          <div className="space-y-6">
            <div className="text-center py-4">
              <div className="w-16 h-16 bg-emerald-100 rounded-full flex items-center justify-center mx-auto mb-4">
                <CheckCircle2 className="w-8 h-8 text-emerald-600" />
              </div>
              <h2 className="text-lg font-semibold text-slate-900 mb-1">
                导入完成
              </h2>
              <p className="text-sm text-slate-500">
                您的 CSV 数据已成功导入并处理。
              </p>
            </div>

            {/* Summary cards */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
              <div className="bg-indigo-50 border border-indigo-200 rounded-xl p-4 text-center">
                <p className="text-2xl font-bold text-indigo-700">
                  {(importResult.rowsImported ?? 0).toLocaleString()}
                </p>
                <p className="text-xs text-indigo-600 mt-1">已导入行</p>
              </div>
              <div className="bg-violet-50 border border-violet-200 rounded-xl p-4 text-center">
                <p className="text-2xl font-bold text-violet-700">
                  {(importResult.searchTermsCreated ?? 0).toLocaleString()}
                </p>
                <p className="text-xs text-violet-600 mt-1">
                  已创建搜索词
                </p>
              </div>
              <div className="bg-emerald-50 border border-emerald-200 rounded-xl p-4 text-center">
                <p className="text-2xl font-bold text-emerald-700">
                  {(importResult.performanceRecordsUpdated ?? 0).toLocaleString()}
                </p>
                <p className="text-xs text-emerald-600 mt-1">
                  绩效记录
                </p>
              </div>
              <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-center">
                <p className="text-2xl font-bold text-amber-700">
                  {(importResult.keywordsDiscovered ?? 0).toLocaleString()}
                </p>
                <p className="text-xs text-amber-600 mt-1">
                  已发现关键词
                </p>
              </div>
            </div>

            {/* Actions */}
            <div className="flex items-center justify-center gap-3">
              <Button variant="outline" onClick={resetStepper}>
                <Upload size={14} />
                再导入一个
              </Button>
              <Button
                onClick={() => {
                  if (importJob?.id) handleReanalyze(importJob.id);
                }}
                disabled={reanalyzingId === importJob?.id}
              >
                {reanalyzingId === importJob?.id ? (
                  <>
                    <Loader2 className="animate-spin" size={14} />
                    重新分析中...
                  </>
                ) : (
                  <>
                    <RotateCcw size={14} />
                    重新分析关键词
                  </>
                )}
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* 鈹€鈹€ Import History 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ */}
      <div className="bg-white rounded-xl border border-slate-200 p-6">
        <div className="flex items-center gap-2 mb-4">
          <History size={18} className="text-slate-500" />
          <h2 className="text-lg font-semibold text-slate-900">
            导入历史
          </h2>
        </div>

        {historyLoading ? (
          <div className="space-y-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <div
                key={i}
                className="h-14 bg-slate-50 rounded-lg animate-pulse"
              />
            ))}
          </div>
        ) : importHistory.length === 0 ? (
          <div className="text-center py-10">
            <FileSearch className="w-10 h-10 text-slate-300 mx-auto mb-3" />
            <p className="text-sm text-slate-500">暂无导入记录</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200">
                  <th className="px-3 py-2.5 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    文件名
                  </th>
                  <th className="px-3 py-2.5 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    报告类型
                  </th>
                  <th className="px-3 py-2.5 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    状态
                  </th>
                  <th className="px-3 py-2.5 text-right text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    总计
                  </th>
                  <th className="px-3 py-2.5 text-right text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    有效
                  </th>
                  <th className="px-3 py-2.5 text-right text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    无效
                  </th>
                  <th className="px-3 py-2.5 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    日期
                  </th>
                  <th className="px-3 py-2.5 text-right text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    操作
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {importHistory.map((item: any) => (
                  <tr key={item.id} className="hover:bg-slate-50">
                    <td className="px-3 py-3">
                      <div className="flex items-center gap-2">
                        <FileSpreadsheet className="w-4 h-4 text-slate-400 flex-shrink-0" />
                        <span className="text-sm font-medium text-slate-900 truncate max-w-[200px]">
                          {item.fileName ?? '未知文件'}
                        </span>
                      </div>
                    </td>
                    <td className="px-3 py-3">
                      <span className="text-sm text-slate-600">
                        {REPORT_TYPES.find(
                          (r) => r.value === item.reportType,
                        )?.label ?? item.reportType}
                      </span>
                    </td>
                    <td className="px-3 py-3">
                      <span
                        className={cn(
                          'inline-flex px-2.5 py-0.5 text-xs font-medium rounded-full border capitalize',
                          getStatusColor(item.status),
                        )}
                      >
                        {item.status}
                      </span>
                    </td>
                    <td className="px-3 py-3 text-right text-sm text-slate-700">
                      {(item.totalRows ?? 0).toLocaleString()}
                    </td>
                    <td className="px-3 py-3 text-right text-sm text-emerald-700 font-medium">
                      {(item.validRows ?? 0).toLocaleString()}
                    </td>
                    <td className="px-3 py-3 text-right text-sm text-red-600">
                      {(item.invalidRows ?? 0).toLocaleString()}
                    </td>
                    <td className="px-3 py-3 text-sm text-slate-500 whitespace-nowrap">
                      {item.createdAt
                        ? new Date(item.createdAt).toLocaleDateString()
                        : '-'}
                    </td>
                    <td className="px-3 py-3 text-right">
                      <div className="flex items-center justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => fetchImportById(item.id)}
                        >
                          <Eye size={14} />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => handleReanalyze(item.id)}
                          disabled={reanalyzingId === item.id}
                        >
                          {reanalyzingId === item.id ? (
                            <Loader2 className="animate-spin" size={14} />
                          ) : (
                            <RotateCcw size={14} />
                          )}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
