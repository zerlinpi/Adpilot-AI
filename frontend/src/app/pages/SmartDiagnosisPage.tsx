import { useState, useMemo, Fragment } from 'react';
import { Plus, Stethoscope, AlertTriangle, CheckCircle2, ChevronDown, ChevronUp } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import {
  fetchSmartDiagnosisTasks,
  createSmartDiagnosisTask,
  type SmartDiagnosisTask,
  type SmartDiagnosisCreateInput,
} from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { useStoreContext } from '../lib/StoreContext';
import { formatCurrency, formatPercent, cn } from '../lib/utils';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// ─── Label maps ──────────────────────────────────────────────────────
const frequencyLabelMap: Record<string, string> = {
  manual: '手动',
  daily: '每日',
  weekly: '每周',
};

const statusLabelMap: Record<string, string> = {
  pending: '待诊断',
  running: '诊断中',
  completed: '已完成',
  failed: '诊断失败',
};

const priorityLabelMap: Record<string, string> = {
  high: '高',
  medium: '中',
  low: '低',
};

function getStatusBadge(status: string): string {
  const styles: Record<string, string> = {
    pending: 'bg-slate-100 text-slate-500 border-slate-200',
    running: 'bg-blue-100 text-blue-700 border-blue-200',
    completed: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    failed: 'bg-red-100 text-red-700 border-red-200',
  };
  return styles[status] || styles.pending;
}

function getPriorityBadge(priority: string): string {
  const styles: Record<string, string> = {
    high: 'bg-red-100 text-red-700 border-red-200',
    medium: 'bg-amber-100 text-amber-700 border-amber-200',
    low: 'bg-slate-100 text-slate-500 border-slate-200',
  };
  return styles[priority] || styles.medium;
}

function getHealthColor(score: number): string {
  if (score >= 80) return 'text-emerald-600';
  if (score >= 50) return 'text-amber-600';
  return 'text-red-600';
}

// ─── Loading skeleton ────────────────────────────────────────────────
function DiagnosisSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="h-8 w-40 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-36 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="h-12 bg-slate-100 rounded animate-pulse" />
        ))}
      </div>
    </div>
  );
}

// ─── Create Diagnosis Task Modal ─────────────────────────────────────
function CreateTaskModal({
  storeId,
  onClose,
  onCreated,
}: {
  storeId: string;
  onClose: () => void;
  onCreated: (created: SmartDiagnosisTask) => void;
}) {
  const [parentAsin, setParentAsin] = useState('');
  const [updateFrequency, setUpdateFrequency] = useState('manual');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);
    setFormError(null);

    const asin = parentAsin.trim();
    if (!asin) {
      setFieldError('请输入父 ASIN');
      return;
    }
    if (asin.length > 20) {
      setFieldError('父 ASIN 不能超过 20 个字符');
      return;
    }

    const payload: SmartDiagnosisCreateInput = {
      storeId,
      parentAsin: asin,
      updateFrequency,
    };

    try {
      setSubmitting(true);
      const created = await createSmartDiagnosisTask(payload);
      onCreated(created);
    } catch (err: any) {
      setFormError(err?.message || '创建诊断任务失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose(); }}>
      <DialogContent className="block gap-0 w-full sm:max-w-lg rounded-xl border-0 bg-white p-0 shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <DialogTitle className="text-lg font-semibold text-slate-900">新建诊断任务</DialogTitle>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">父 ASIN</label>
            <input
              type="text"
              value={parentAsin}
              onChange={(e) => setParentAsin(e.target.value)}
              placeholder="例如：B08XYZ1234"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">更新频率</label>
            <select
              value={updateFrequency}
              onChange={(e) => setUpdateFrequency(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            >
              <option value="manual">手动</option>
              <option value="daily">每日</option>
              <option value="weekly">每周</option>
            </select>
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
              {submitting ? '诊断中...' : '开始诊断'}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ─── Diagnosis result detail (expandable) ────────────────────────────
function DiagnosisDetail({ task }: { task: SmartDiagnosisTask }) {
  const r = task.result;
  if (!r) {
    return (
      <div className="px-4 py-3 text-sm text-slate-500 bg-slate-50/60">
        {task.status === 'failed' ? '诊断未能完成，请重试。' : '暂无诊断结果。'}
      </div>
    );
  }

  // Insufficient-data outcome (item 20): show a clear explanation instead of a
  // misleading "healthy" score.
  if (r.dataAvailable === false) {
    return (
      <div className="px-4 py-4 bg-amber-50/60 flex items-start gap-3">
        <AlertTriangle size={18} className="text-amber-500 flex-shrink-0 mt-0.5" />
        <div>
          <p className="text-sm font-medium text-amber-800">数据不足，无法生成有效诊断</p>
          <p className="text-sm text-amber-700 mt-0.5">
            {r.summary || '该商品暂无可分析的广告结构数据，请先同步或导入广告数据后再诊断。'}
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-4 bg-slate-50/60 space-y-4">
      {/* Diagnosis summary */}
      {r.summary && (
        <p className="text-sm text-slate-700 bg-white rounded-lg border border-slate-200 px-3 py-2">{r.summary}</p>
      )}

      {/* Summary metrics */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="bg-white rounded-lg border border-slate-200 px-3 py-2">
          <p className="text-xs text-slate-500">健康评分</p>
          <p className={cn('text-lg font-semibold', getHealthColor(r.healthScore))}>{r.healthScore}</p>
        </div>
        <div className="bg-white rounded-lg border border-slate-200 px-3 py-2">
          <p className="text-xs text-slate-500">广告活动数量</p>
          <p className="text-lg font-semibold text-slate-800">
            {r.enabledCampaignCount}/{r.campaignCount}
          </p>
        </div>
        <div className="bg-white rounded-lg border border-slate-200 px-3 py-2">
          <p className="text-xs text-slate-500">整体 ACOS</p>
          <p className="text-lg font-semibold text-slate-800">{formatPercent(r.acos)}</p>
        </div>
        <div className="bg-white rounded-lg border border-slate-200 px-3 py-2">
          <p className="text-xs text-slate-500">花费 / 销售额</p>
          <p className="text-sm font-semibold text-slate-800">
            {formatCurrency(r.totalSpend)} / {formatCurrency(r.totalSales)}
          </p>
        </div>
      </div>

      {/* Issue breakdown + list */}
      <div className="flex items-center gap-3 text-xs text-slate-600">
        <span className="inline-flex items-center gap-1">
          <span className="w-2 h-2 rounded-full bg-red-500" /> 高 {r.highCount}
        </span>
        <span className="inline-flex items-center gap-1">
          <span className="w-2 h-2 rounded-full bg-amber-500" /> 中 {r.mediumCount}
        </span>
        <span className="inline-flex items-center gap-1">
          <span className="w-2 h-2 rounded-full bg-slate-400" /> 低 {r.lowCount}
        </span>
        <span className="ml-auto text-slate-500">共 {r.issueCount} 项诊断结果</span>
      </div>

      {r.issues && r.issues.length > 0 ? (
        <div className="space-y-2">
          {r.issues.map((issue, idx) => (
            <div key={idx} className="bg-white rounded-lg border border-slate-200 px-3 py-2 flex items-start gap-3">
              <AlertTriangle size={16} className="text-amber-500 flex-shrink-0 mt-0.5" />
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-slate-800">{issue.title}</span>
                  <span
                    className={cn(
                      'inline-flex items-center px-1.5 py-0.5 rounded-full text-[10px] font-medium border',
                      getPriorityBadge(issue.priority),
                    )}
                  >
                    {priorityLabelMap[issue.priority] || issue.priority}
                  </span>
                </div>
                {issue.description && <p className="text-xs text-slate-500 mt-0.5">{issue.description}</p>}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="flex items-center gap-2 text-sm text-emerald-600">
          <CheckCircle2 size={16} />
          未发现广告结构问题
        </div>
      )}
    </div>
  );
}

// ─── Main Smart Diagnosis Page ───────────────────────────────────────
export function SmartDiagnosisPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const { stores } = useStoreContext();
  const queryClient = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);

  const storeName = useMemo(
    () => stores.find((s) => s.id === storeId)?.name ?? '-',
    [stores, storeId],
  );

  const tasksKey = ['smart-diagnosis-tasks', storeId] as const;
  const tasksQuery = useApiQuery<SmartDiagnosisTask[]>(
    tasksKey,
    () => fetchSmartDiagnosisTasks(storeId!).then((result) => result ?? []),
    { enabled: !!storeId },
  );
  const tasks = tasksQuery.data ?? [];
  const loading = !!storeId && tasksQuery.isLoading;
  const error = tasksQuery.isError ? tasksQuery.error?.message ?? '加载诊断任务失败' : null;
  const loadData = () => tasksQuery.refetch();

  if (loading || storeLoading) return <DiagnosisSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Stethoscope size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出错了</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">{storeError || error}</p>
        <button
          onClick={loadData}
          className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
        >
          重试
        </button>
      </div>
    );
  }

  if (!storeId) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Stethoscope size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">
          请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">智能诊断</h1>
          <p className="text-sm text-slate-500 mt-1">按商品（父 ASIN）诊断广告结构，识别并处理结构性问题</p>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          disabled={!storeId}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors shadow-sm disabled:opacity-60"
        >
          <Plus size={16} />
          新建诊断任务
        </button>
      </div>

      {/* Tasks Table / Empty State */}
      {tasks.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <Stethoscope size={48} className="text-slate-300 mb-4" />
          <p className="text-lg font-medium text-slate-500">暂无诊断任务</p>
          <p className="text-sm text-slate-400 mt-1">点击「新建诊断任务」开始诊断商品广告结构</p>
        </div>
      ) : (
        <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-xs font-medium text-slate-500 uppercase tracking-wider">
                <th className="px-4 py-3">商品 (父 ASIN)</th>
                <th className="px-4 py-3">店铺</th>
                <th className="px-4 py-3">更新频率</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3 text-right">健康评分</th>
                <th className="px-4 py-3">最近诊断时间</th>
                <th className="px-4 py-3 text-right">诊断结果</th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((t) => {
                const isOpen = expanded === t.id;
                return (
                  <Fragment key={t.id}>
                    <tr className="border-b border-slate-50 hover:bg-slate-50/60">
                      <td className="px-4 py-3 font-medium text-slate-900">{t.parentAsin}</td>
                      <td className="px-4 py-3 text-slate-600">{storeName}</td>
                      <td className="px-4 py-3 text-slate-600">
                        {frequencyLabelMap[t.updateFrequency] || t.updateFrequency}
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
                            getStatusBadge(t.status),
                          )}
                        >
                          {statusLabelMap[t.status] || t.status}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right">
                        {t.result && t.result.dataAvailable !== false ? (
                          <span className={cn('font-semibold', getHealthColor(t.result.healthScore))}>
                            {t.result.healthScore}
                          </span>
                        ) : (
                          <span className="text-slate-400">-</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-slate-600">{t.lastDiagnosedAt || '-'}</td>
                      <td className="px-4 py-3 text-right">
                        <button
                          onClick={() => setExpanded(isOpen ? null : t.id)}
                          className="inline-flex items-center gap-1 text-blue-600 hover:text-blue-700 text-sm font-medium"
                        >
                          {isOpen ? '收起' : '查看'}
                          {isOpen ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                        </button>
                      </td>
                    </tr>
                    {isOpen && (
                      <tr key={`${t.id}-detail`}>
                        <td colSpan={7} className="p-0">
                          <DiagnosisDetail task={t} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {showCreate && storeId && (
        <CreateTaskModal
          storeId={storeId}
          onClose={() => setShowCreate(false)}
          onCreated={(created) => {
            setShowCreate(false);
            // Reflect the new task without a full reload, then expand its result.
            queryClient.setQueryData<SmartDiagnosisTask[]>(tasksKey, (prev) => [created, ...(prev ?? [])]);
            setExpanded(created.id);
          }}
        />
      )}
    </div>
  );
}
