import { useState } from 'react';
import type React from 'react';
import {
  Package,
  CheckCircle,
  XCircle,
  Eye,
  DollarSign,
  AlertTriangle,
  Sparkles,
  Clock,
  FileText,
  Loader2,
} from 'lucide-react';

import { useQueryClient } from '@tanstack/react-query';
import {
  fetchReplenishmentPlans,
  generateReplenishmentPlans,
  approveReplenishmentPlan,
  cancelReplenishmentPlan,
} from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { usePermissions } from '../lib/PermissionContext';
import { t } from '../i18n';
import { RecordModal, type RecordField } from '../components/ui/RecordModal';
import {
  cn,
  formatCurrency,
  formatDate,
  formatNumber,
} from '../lib/utils';

const PLAN_FIELDS: RecordField[] = [
  { key: 'sku', label: 'SKU' },
  { key: 'name', label: '产品名称' },
  { key: 'status', label: '状态' },
  { key: 'recommendedQty', label: '建议补货量' },
  { key: 'expectedStockoutDate', label: '预计断货日' },
  { key: 'expectedArrivalDate', label: '预计到货日' },
  { key: 'purchaseCost', label: '采购成本' },
  { key: 'shippingCost', label: '运输成本' },
];

// ─── Types ──────────────────────────────────────────────────────────
interface ReplenishmentPlan {
  id: string;
  sku: string;
  name: string;
  status: 'draft' | 'pending' | 'pending_approval' | 'approved' | 'cancelled' | 'rejected';
  recommendedQty: number;
  expectedStockoutDate: string;
  expectedArrivalDate: string;
  purchaseCost: number;
  shippingCost: number;
}

// ─── Status badge ───────────────────────────────────────────────────
function StatusBadge({ status }: { status: ReplenishmentPlan['status'] }) {
  const config: Record<string, { label: string; className: string }> = {
    draft: { label: '草稿', className: 'bg-slate-100 text-slate-600' },
    pending: { label: '待审批', className: 'bg-yellow-100 text-yellow-700' },
    pending_approval: { label: '待审批', className: 'bg-yellow-100 text-yellow-700' },
    approved: { label: '已批准', className: 'bg-emerald-100 text-emerald-700' },
    rejected: { label: '已拒绝', className: 'bg-red-100 text-red-700' },
    cancelled: { label: '已取消', className: 'bg-red-100 text-red-700' },
  };
  const c = config[status] ?? config.draft;
  return (
    <span className={cn('inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium', c.className)}>
      {c.label}
    </span>
  );
}

// ─── KPI Card ───────────────────────────────────────────────────────
function SummaryCard({
  label,
  value,
  icon: Icon,
  color,
  bgColor,
}: {
  label: string;
  value: string;
  icon: React.ComponentType<any>;
  color: string;
  bgColor: string;
}) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-5 flex flex-col gap-3 hover:shadow-sm transition-shadow">
      <div className="flex items-center gap-2">
        <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center', bgColor)}>
          <Icon size={16} className={color} />
        </div>
        <span className="text-sm font-medium text-slate-500">{label}</span>
      </div>
      <span className="text-2xl font-bold text-slate-900 tracking-tight">{value}</span>
    </div>
  );
}

// ─── Loading skeleton ───────────────────────────────────────────────
function ReplenishmentSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <div className="h-8 w-56 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-72 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-52 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="bg-white rounded-xl border border-slate-200 p-5">
            <div className="h-4 w-24 bg-slate-200 rounded animate-pulse mb-3" />
            <div className="h-7 w-20 bg-slate-100 rounded animate-pulse" />
          </div>
        ))}
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-16 bg-slate-100 rounded-lg animate-pulse" />
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── Main Page ──────────────────────────────────────────────────────
export function ReplenishmentPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const { can } = usePermissions();
  const canManage = can('warehouse:manage');
  const queryClient = useQueryClient();
  const [generating, setGenerating] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [viewRow, setViewRow] = useState<ReplenishmentPlan | null>(null);
  // Page-level error raised by a failed "generate" action (preserves the
  // original behavior where a generate failure shows the full-page error state).
  const [genError, setGenError] = useState<string | null>(null);

  const plansKey = ['replenishment-plans', storeId] as const;
  const plansQuery = useApiQuery<ReplenishmentPlan[]>(
    plansKey,
    () => fetchReplenishmentPlans({ storeId: storeId! }).then((result: any) =>
      result?.plans ?? result?.items ?? (Array.isArray(result) ? result : [])),
    { enabled: !!storeId },
  );
  const plans = plansQuery.data ?? [];
  const loading = !!storeId && plansQuery.isLoading;
  const error = genError ?? (plansQuery.isError ? plansQuery.error?.message ?? '加载失败' : null);
  const loadData = () => { setGenError(null); return plansQuery.refetch(); };

  async function handleGenerate() {
    if (!storeId) return;
    try {
      setGenerating(true);
      setGenError(null);
      setActionError(null);
      // The backend creates plan rows from current inventory/forecast data and
      // returns the rows it created. Refetch the full list afterwards so the
      // table reflects both the new plans and any previously-existing ones
      // (Req: reflect result without a full reload).
      const created: any = await generateReplenishmentPlans(storeId);
      const createdList = Array.isArray(created)
        ? created
        : (created?.plans ?? created?.items ?? (created?.id ? [created] : []));
      if (Array.isArray(createdList) && createdList.length > 0) {
        // Optimistically surface the new plans immediately, then reconcile.
        queryClient.setQueryData<ReplenishmentPlan[]>(plansKey, (prev) => [...createdList, ...(prev ?? [])]);
      }
      await loadData();
    } catch (e: any) {
      setGenError(e?.message || '生成补货计划失败');
    } finally {
      setGenerating(false);
    }
  }

  async function handleApprove(id: string) {
    try {
      setActionLoading(id);
      setActionError(null);
      const updated = await approveReplenishmentPlan(id);
      queryClient.setQueryData<ReplenishmentPlan[]>(plansKey, (prev) => (prev ?? []).map((p) => (p.id === id ? updated : p)));
    } catch (e: any) {
      setActionError(e?.message || '批准补货计划失败');
    } finally {
      setActionLoading(null);
    }
  }

  async function handleCancel(id: string) {
    try {
      setActionLoading(id);
      setActionError(null);
      const updated = await cancelReplenishmentPlan(id);
      queryClient.setQueryData<ReplenishmentPlan[]>(plansKey, (prev) => (prev ?? []).map((p) => (p.id === id ? updated : p)));
    } catch (e: any) {
      setActionError(e?.message || '取消补货计划失败');
    } finally {
      setActionLoading(null);
    }
  }

  if (loading || storeLoading) return <ReplenishmentSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <AlertTriangle size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出现错误</p>
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
        <Package size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">
          请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
        </p>
      </div>
    );
  }

  const pendingCount = plans.filter((p) => p.status === 'pending' || p.status === 'pending_approval').length;
  const approvedCount = plans.filter((p) => p.status === 'approved').length;
  const totalQty = plans.reduce((sum, p) => sum + p.recommendedQty, 0);
  const totalCost = plans.reduce((sum, p) => sum + p.purchaseCost + p.shippingCost, 0);

  return (
    <div className="space-y-6">
      {/* ── Page Header ────────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">{t('pages.replenishment.title')}</h1>
          <p className="text-sm text-slate-500 mt-1">{t('pages.replenishment.subtitle')}</p>
        </div>
        {canManage && (
          <button
            onClick={handleGenerate}
            disabled={generating}
            className={cn(
              'inline-flex items-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium transition-colors',
              generating
                ? 'bg-blue-400 text-white cursor-not-allowed'
                : 'bg-blue-600 text-white hover:bg-blue-700',
            )}
          >
            {generating ? (
              <>
                <Loader2 size={16} className="animate-spin" />
                生成中...
              </>
            ) : (
              <>
                <Sparkles size={16} />
                生成智能补货计划
              </>
            )}
          </button>
        )}
      </div>

      {/* ── Summary Cards ──────────────────────────────────────────── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <SummaryCard
          label="待审批计划"
          value={String(pendingCount)}
          icon={Clock}
          color="text-yellow-600"
          bgColor="bg-yellow-50"
        />
        <SummaryCard
          label="已批准计划"
          value={String(approvedCount)}
          icon={CheckCircle}
          color="text-emerald-600"
          bgColor="bg-emerald-50"
        />
        <SummaryCard
          label="建议补货总量"
          value={formatNumber(totalQty)}
          icon={Package}
          color="text-blue-600"
          bgColor="bg-blue-50"
        />
        <SummaryCard
          label="预估成本"
          value={formatCurrency(totalCost)}
          icon={DollarSign}
          color="text-violet-600"
          bgColor="bg-violet-50"
        />
      </div>

      {/* ── Replenishment Plans Table ──────────────────────────────── */}
      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <div className="flex items-center gap-2 mb-4">
          <FileText size={18} className="text-slate-400" />
          <h2 className="text-base font-semibold text-slate-900">补货计划</h2>
          <span className="ml-auto text-xs text-slate-400">{plans.length} 个计划</span>
        </div>
        {actionError && (
          <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {actionError}
          </div>
        )}
        {plans.length === 0 ? (
          <div className="py-16 text-center">
            <Package size={40} className="mx-auto text-slate-300 mb-3" />
            <p className="text-sm text-slate-500 mb-4">暂无补货计划</p>
            {canManage && (
              <button
                onClick={handleGenerate}
                disabled={generating}
                className="inline-flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
              >
                <Sparkles size={14} />
                生成计划
              </button>
            )}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100">
                  <th className="text-left py-2 pr-4 font-medium text-slate-500">SKU</th>
                  <th className="text-left py-2 pr-4 font-medium text-slate-500">产品名称</th>
                  <th className="text-center py-2 pr-4 font-medium text-slate-500">状态</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">建议数量</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">预计缺货日</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">预计到货日</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">采购成本</th>
                  <th className="text-right py-2 pr-4 font-medium text-slate-500">运输成本</th>
                  <th className="text-center py-2 font-medium text-slate-500">操作</th>
                </tr>
              </thead>
              <tbody>
                {plans.map((plan) => (
                  <tr key={plan.id} className="border-b border-slate-50 last:border-0 hover:bg-slate-50/50 transition-colors">
                    <td className="py-3 pr-4 font-mono text-xs text-slate-600">{plan.sku}</td>
                    <td className="py-3 pr-4 font-medium text-slate-800">{plan.name}</td>
                    <td className="py-3 pr-4 text-center">
                      <StatusBadge status={plan.status} />
                    </td>
                    <td className="py-3 pr-4 text-right font-medium text-slate-700">
                      {formatNumber(plan.recommendedQty)} 件
                    </td>
                    <td className="py-3 pr-4 text-right text-slate-600">
                      {formatDate(plan.expectedStockoutDate)}
                    </td>
                    <td className="py-3 pr-4 text-right text-slate-600">
                      {formatDate(plan.expectedArrivalDate)}
                    </td>
                    <td className="py-3 pr-4 text-right text-slate-700">{formatCurrency(plan.purchaseCost)}</td>
                    <td className="py-3 pr-4 text-right text-slate-600">{formatCurrency(plan.shippingCost)}</td>
                    <td className="py-3">
                      <div className="flex items-center justify-center gap-1.5">
                        {canManage && ['draft', 'pending', 'pending_approval'].includes(plan.status) ? (
                          <>
                            <button
                              onClick={() => handleApprove(plan.id)}
                              disabled={actionLoading === plan.id}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 bg-emerald-50 text-emerald-700 hover:bg-emerald-100 rounded-md text-xs font-medium transition-colors disabled:opacity-50"
                              title="批准"
                            >
                              <CheckCircle size={13} />
                              批准
                            </button>
                            <button
                              onClick={() => handleCancel(plan.id)}
                              disabled={actionLoading === plan.id}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 bg-red-50 text-red-700 hover:bg-red-100 rounded-md text-xs font-medium transition-colors disabled:opacity-50"
                              title="取消"
                            >
                              <XCircle size={13} />
                              取消
                            </button>
                          </>
                        ) : null}
                        <button
                          onClick={() => setViewRow(plan)}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 bg-slate-50 text-slate-600 hover:bg-slate-100 rounded-md text-xs font-medium transition-colors"
                          title="详情"
                        >
                          <Eye size={13} />
                          详情
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <RecordModal
        open={!!viewRow}
        mode="view"
        title="补货计划详情"
        fields={PLAN_FIELDS}
        record={viewRow}
        onClose={() => setViewRow(null)}
      />
    </div>
  );
}
