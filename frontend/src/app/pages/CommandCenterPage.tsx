import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Bot,
  CheckCircle2,
  Clock3,
  Database,
  Link2,
  ListChecks,
  Package,
  RefreshCw,
  ShieldCheck,
  Store,
  Upload,
  XCircle,
} from 'lucide-react';

import { cn, formatCurrency, formatNumber, formatPercent } from '../lib/utils';
import {
  fetchAiNotifications,
  fetchHostingDashboardSummary,
  fetchHostingHealth,
  fetchPlatformConnections,
  fetchProducts,
  fetchRecommendations,
  fetchSalesOverview,
  fetchStoreSyncJobs,
  fetchTasks,
  fetchUploadJobs,
} from '../lib/api';
import type {
  AiNotificationOverview,
  HostingDashboardSummary,
  HostingHealth,
} from '../lib/api';
import { useStoreContext } from '../lib/StoreContext';
import { getChannelCapability } from '../lib/channelCapabilities';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { OnboardingWizard } from '../components/onboarding/OnboardingWizard';

type Priority = 'urgent' | 'high' | 'medium' | 'low';

interface WorkItem {
  id: string;
  priority: Priority;
  source: string;
  title: string;
  detail?: string;
  href: string;
  actionLabel: string;
  count?: number;
}

interface WorkbenchData {
  overview: any | null;
  tasks: any[];
  recommendations: any[];
  notifications: AiNotificationOverview | null;
  hostingSummary: HostingDashboardSummary | null;
  hostingHealth: HostingHealth | null;
  platformConnections: any[];
  failedSyncJobs: any[];
  products: any[];
  uploadJobs: any[];
  failures: { source: string; message: string }[];
}

const emptyData: WorkbenchData = {
  overview: null,
  tasks: [],
  recommendations: [],
  notifications: null,
  hostingSummary: null,
  hostingHealth: null,
  platformConnections: [],
  failedSyncJobs: [],
  products: [],
  uploadJobs: [],
  failures: [],
};

const priorityOrder: Record<Priority, number> = {
  urgent: 0,
  high: 1,
  medium: 2,
  low: 3,
};

const priorityClass: Record<Priority, string> = {
  urgent: 'border-red-200 bg-red-50 text-red-700',
  high: 'border-orange-200 bg-orange-50 text-orange-700',
  medium: 'border-amber-200 bg-amber-50 text-amber-700',
  low: 'border-slate-200 bg-slate-50 text-slate-600',
};

const priorityLabel: Record<Priority, string> = {
  urgent: '紧急',
  high: '高',
  medium: '中',
  low: '低',
};

function asArray<T = any>(value: any): T[] {
  if (Array.isArray(value)) return value as T[];
  if (Array.isArray(value?.items)) return value.items as T[];
  if (Array.isArray(value?.records)) return value.records as T[];
  return [];
}

function metricValue(metric: any): number {
  return Number(metric?.value ?? 0);
}

function stringifyError(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  return '接口请求失败';
}

function formatDateTime(value: any): string {
  if (!value) return '暂无时间';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '暂无时间';
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function normalizeStatus(value: any): string {
  return String(value ?? '').trim().toLowerCase();
}

function isConnected(connection: any): boolean {
  const status = normalizeStatus(connection?.status ?? connection?.connectionStatus ?? connection?.state);
  return ['active', 'connected', 'authorized', 'ok', 'healthy'].includes(status);
}

function connectionName(connection: any): string {
  return (
    connection?.connectionName ||
    connection?.name ||
    connection?.storeName ||
    connection?.platform ||
    '未命名连接'
  );
}

function taskPriority(task: any): Priority {
  const raw = normalizeStatus(task?.priority ?? task?.priorityLevel);
  if (raw === 'urgent' || raw === 'high' || raw === 'medium' || raw === 'low') return raw;
  return 'medium';
}

function recommendationPriority(item: any): Priority {
  const risk = normalizeStatus(item?.riskLevel ?? item?.risk_level ?? item?.severity);
  if (risk === 'critical' || risk === 'urgent' || risk === 'high') return 'high';
  if (risk === 'low') return 'low';
  return 'medium';
}

function buildWorkItems(data: WorkbenchData, hasStore: boolean): WorkItem[] {
  const items: WorkItem[] = [];
  const connectionDataAvailable = !data.failures.some((failure) => failure.source === '平台连接');
  const productDataAvailable = !data.failures.some((failure) => failure.source === '商品');

  if (!hasStore) {
    items.push({
      id: 'store-required',
      priority: 'urgent',
      source: '店铺',
      title: '还没有可用店铺',
      detail: '需要先连接或选择店铺，后续经营指标、广告托管和同步任务才会按店铺读取。',
      href: '/stores',
      actionLabel: '去连接店铺',
    });
  }

  if (connectionDataAvailable && data.platformConnections.length === 0) {
    items.push({
      id: 'connection-required',
      priority: 'urgent',
      source: '接口连接',
      title: '还没有平台接口连接',
      detail: '当前没有读取广告、订单或商品数据的真实连接。请先在「连接与同步」页连接亚马逊广告账户。',
      href: '/data-sync',
      actionLabel: '去连接',
    });
  } else if (connectionDataAvailable) {
    const disconnected = data.platformConnections.filter((connection) => !isConnected(connection));
    if (disconnected.length > 0) {
      items.push({
        id: 'connection-disconnected',
        priority: 'high',
        source: '接口连接',
        title: `${disconnected.length} 个平台连接未就绪`,
        detail: disconnected.slice(0, 3).map(connectionName).join('、'),
        href: '/api-connections',
        actionLabel: '检查授权',
        count: disconnected.length,
      });
    }
  }

  if (data.failedSyncJobs.length > 0) {
    items.push({
      id: 'sync-failed',
      priority: 'high',
      source: '数据同步',
      title: `${data.failedSyncJobs.length} 个同步任务失败`,
      detail: data.failedSyncJobs
        .slice(0, 3)
        .map((job) => job?.syncType || job?.entityType || job?.platform || '同步任务')
        .join('、'),
      href: '/platform-sync',
      actionLabel: '查看失败',
      count: data.failedSyncJobs.length,
    });
  }

  if (hasStore && productDataAvailable && data.products.length === 0) {
    items.push({
      id: 'products-empty',
      priority: 'medium',
      source: '商品运营',
      title: '当前店铺还没有商品',
      detail: '先同步或创建商品，才能生成内容、校验并进入渠道发布流程。',
      href: '/products',
      actionLabel: '管理商品',
    });
  }

  const failedUploadJobs = data.uploadJobs.filter((job) => normalizeStatus(job?.status) === 'failed');
  if (failedUploadJobs.length > 0) {
    items.push({
      id: 'product-publish-failed',
      priority: 'high',
      source: '渠道发布',
      title: failedUploadJobs.length + ' 个商品发布任务校验失败',
      detail: '返回发布中心修正商品内容或渠道字段。',
      href: '/product-upload',
      actionLabel: '修正任务',
      count: failedUploadJobs.length,
    });
  }

  const actionableUploadJobs = data.uploadJobs.filter((job) =>
    ['ready', 'approved'].includes(normalizeStatus(job?.status)),
  );
  if (actionableUploadJobs.length > 0) {
    items.push({
      id: 'product-publish-ready',
      priority: 'medium',
      source: '渠道发布',
      title: actionableUploadJobs.length + ' 个商品任务等待批准或导出',
      detail: '任务已经通过基础流程，可继续审核、批准或导出渠道资料。',
      href: '/product-upload',
      actionLabel: '继续发布',
      count: actionableUploadJobs.length,
    });
  }
  const awaitingApproval = Number(data.hostingSummary?.awaiting_approval_count ?? 0);
  if (awaitingApproval > 0) {
    items.push({
      id: 'hosting-approval',
      priority: 'high',
      source: 'AI 托管',
      title: `${awaitingApproval} 条托管决策等待审批`,
      detail: '这些决策已生成但尚未进入真实执行链路。',
      href: '/approvals',
      actionLabel: '去审批',
      count: awaitingApproval,
    });
  }

  const hostingFailures = Number(data.hostingSummary?.failed_today_count ?? 0);
  if (hostingFailures > 0) {
    items.push({
      id: 'hosting-failures',
      priority: 'high',
      source: 'AI 托管',
      title: `今日 ${hostingFailures} 条托管执行失败`,
      detail: '需要检查 Amazon API、库存服务或同步链路状态。',
      href: '/platform-sync',
      actionLabel: '排查链路',
      count: hostingFailures,
    });
  }

  data.tasks.slice(0, 8).forEach((task, index) => {
    items.push({
      id: `task-${task?.id ?? task?.title ?? index}`,
      priority: taskPriority(task),
      source: '任务',
      title: task?.title || task?.name || '未命名任务',
      detail: task?.description || task?.taskType || task?.task_type || `创建时间：${formatDateTime(task?.createdAt ?? task?.created_at)}`,
      href: '/tasks',
      actionLabel: '处理任务',
    });
  });

  data.recommendations.slice(0, 8).forEach((item, index) => {
    items.push({
      id: `recommendation-${item?.id ?? item?.title ?? index}`,
      priority: recommendationPriority(item),
      source: 'AI 建议',
      title: item?.title || item?.recommendationType || item?.type || '待确认优化建议',
      detail: item?.reason || item?.description || item?.action || item?.summary,
      href: '/recommendations',
      actionLabel: '查看建议',
    });
  });

  for (const category of data.notifications?.categories ?? []) {
    if (category.pendingCount > 0) {
      items.push({
        id: `notification-${category.key}`,
        priority: category.pendingCount >= 5 ? 'high' : 'medium',
        source: 'AI 通知',
        title: `${category.label || category.key} 有 ${category.pendingCount} 项待处理`,
        detail: category.pending?.[0]?.title,
        href: '/ai-notifications',
        actionLabel: '处理通知',
        count: category.pendingCount,
      });
    }
  }

  return items.sort((a, b) => priorityOrder[a.priority] - priorityOrder[b.priority]);
}

function DashboardSkeleton() {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="space-y-2">
          <div className="h-6 w-36 rounded bg-slate-200 animate-pulse" />
          <div className="h-4 w-80 rounded bg-slate-100 animate-pulse" />
        </div>
        <div className="h-9 w-24 rounded bg-slate-100 animate-pulse" />
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-6 gap-3">
        {Array.from({ length: 6 }).map((_, index) => (
          <div key={index} className="rounded-lg border border-slate-200 bg-white p-4 animate-pulse">
            <div className="h-3 w-20 rounded bg-slate-200 mb-3" />
            <div className="h-6 w-16 rounded bg-slate-200" />
          </div>
        ))}
      </div>
      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_360px] gap-4">
        <div className="rounded-lg border border-slate-200 bg-white p-4">
          <ErpLoadingSkeleton rows={8} />
        </div>
        <div className="rounded-lg border border-slate-200 bg-white p-4">
          <ErpLoadingSkeleton rows={5} />
        </div>
      </div>
    </div>
  );
}

function MetricTile({
  label,
  value,
  detail,
  icon,
  tone = 'default',
}: {
  label: string;
  value: string | number;
  detail?: string;
  icon: React.ReactNode;
  tone?: 'default' | 'warning' | 'danger' | 'success';
}) {
  const toneClass = {
    default: 'bg-slate-50 text-slate-600',
    warning: 'bg-amber-50 text-amber-700',
    danger: 'bg-red-50 text-red-700',
    success: 'bg-emerald-50 text-emerald-700',
  }[tone];

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs text-slate-500">{label}</span>
        <span className={cn('flex h-8 w-8 items-center justify-center rounded-lg', toneClass)}>{icon}</span>
      </div>
      <div className="mt-3 text-2xl font-semibold text-slate-900">{value}</div>
      {detail && <div className="mt-1 text-xs text-slate-500 truncate">{detail}</div>}
    </div>
  );
}

function WorkItemRow({ item }: { item: WorkItem }) {
  return (
    <div className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 px-4 py-3 hover:bg-slate-50">
      <span className={cn('rounded border px-2 py-0.5 text-[11px] font-medium', priorityClass[item.priority])}>
        {priorityLabel[item.priority]}
      </span>
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-400">{item.source}</span>
          {item.count !== undefined && (
            <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{item.count}</span>
          )}
        </div>
        <p className="mt-0.5 truncate text-sm font-medium text-slate-900">{item.title}</p>
        {item.detail && <p className="mt-0.5 truncate text-xs text-slate-500">{item.detail}</p>}
      </div>
      <Link
        to={item.href}
        className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
      >
        {item.actionLabel}
        <ArrowRight size={13} />
      </Link>
    </div>
  );
}

function StatusDot({ ok }: { ok: boolean }) {
  return ok ? <CheckCircle2 size={15} className="text-emerald-600" /> : <XCircle size={15} className="text-red-500" />;
}

function DependencyStatus({ name, status }: { name: string; status: string }) {
  const normalized = normalizeStatus(status);
  const ok = ['healthy', 'ok', 'up', 'available'].includes(normalized);
  const warning = ['degraded', 'warning', 'slow'].includes(normalized);
  const label = {
    healthy: '正常',
    ok: '正常',
    up: '正常',
    available: '正常',
    degraded: '需检查',
    warning: '需检查',
    slow: '需检查',
    unavailable: '不可用',
  }[normalized] ?? (status || '未知');

  return (
    <div className="flex items-center justify-between gap-3 py-2">
      <span className="truncate text-sm text-slate-600">{name}</span>
      <span
        className={cn(
          'rounded border px-2 py-0.5 text-xs',
          ok && 'border-emerald-200 bg-emerald-50 text-emerald-700',
          warning && 'border-amber-200 bg-amber-50 text-amber-700',
          !ok && !warning && 'border-red-200 bg-red-50 text-red-700',
        )}
      >
        {label}
      </span>
    </div>
  );
}

export function CommandCenterPage() {
  const { stores, storeId, loading: storeLoading, error: storeError, reload: reloadStores } = useStoreContext();
  const [data, setData] = useState<WorkbenchData>(emptyData);
  const [loading, setLoading] = useState(true);

  const selectedStore = stores.find((store) => store.id === storeId);
  const channel = getChannelCapability(selectedStore?.platform);
  const isAmazonStore = channel.platform === 'amazon';

  const loadData = useCallback(async () => {
    setLoading(true);
    const failures: WorkbenchData['failures'] = [];

    const guarded = async <T,>(source: string, promise: Promise<T>, fallback: T): Promise<T> => {
      try {
        return await promise;
      } catch (error) {
        failures.push({ source, message: stringifyError(error) });
        return fallback;
      }
    };

    const [
      overview,
      tasksResponse,
      recommendations,
      notifications,
      hostingSummary,
      hostingHealth,
      platformConnections,
      failedSyncJobs,
      productsResponse,
      uploadJobsResponse,
    ] = await Promise.all([
      guarded('经营指标', fetchSalesOverview(storeId ? { storeId } : undefined), null),
      guarded('任务', fetchTasks(storeId ? { storeId, status: 'open', pageSize: 20 } : { status: 'open', pageSize: 20 }), []),
      storeId && isAmazonStore ? guarded('AI 建议', fetchRecommendations({ storeId, status: 'pending' }), []) : Promise.resolve([]),
      storeId && isAmazonStore ? guarded('AI 通知', fetchAiNotifications(storeId), null) : Promise.resolve(null),
      storeId && isAmazonStore ? guarded('托管概览', fetchHostingDashboardSummary(storeId), null) : Promise.resolve(null),
      isAmazonStore ? guarded('托管依赖', fetchHostingHealth(), null) : Promise.resolve(null),
      guarded('平台连接', fetchPlatformConnections(), []),
      storeId ? guarded('同步任务', fetchStoreSyncJobs(storeId, { status: 'failed', pageSize: 100 }), []) : Promise.resolve([]),
      storeId ? guarded('商品', fetchProducts(storeId), []) : Promise.resolve([]),
      storeId ? guarded('发布任务', fetchUploadJobs(storeId), []) : Promise.resolve([]),
    ]);

    const scopedConnections = storeId
      ? asArray(platformConnections).filter((connection) => connection?.storeId === storeId)
      : asArray(platformConnections);

    setData({
      overview,
      tasks: asArray(tasksResponse),
      recommendations: asArray(recommendations),
      notifications,
      hostingSummary,
      hostingHealth,
      platformConnections: scopedConnections,
      failedSyncJobs: asArray(failedSyncJobs),
      products: asArray(productsResponse),
      uploadJobs: asArray(uploadJobsResponse),
      failures,
    });
    setLoading(false);
  }, [isAmazonStore, storeId]);

  useEffect(() => {
    if (!storeLoading) {
      loadData();
    }
  }, [loadData, storeLoading]);

  const workItems = useMemo(() => buildWorkItems(data, Boolean(storeId)), [data, storeId]);

  if (storeLoading || loading) return <DashboardSkeleton />;

  if (!storeId && stores.length === 0) {
    return (
      <div className="space-y-4">
        <ErpPageHeader
          title="今日运营工作台"
          description="连接店铺后，系统会按真实接口数据汇总广告、商品、发布与同步待办。"
          actions={
            <button
              onClick={reloadStores}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
            >
              <RefreshCw size={14} />
              重新加载
            </button>
          }
        />
        <div className="rounded-lg border border-slate-200 bg-white">
          <ErpEmptyState
            title="暂无可用店铺"
            description={storeError || '请先连接店铺或让管理员分配店铺权限。'}
            icon={<Store size={24} className="text-slate-400" />}
            action={
              <Link
                to="/stores"
                className="inline-flex items-center gap-2 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800"
              >
                连接店铺
                <ArrowRight size={14} />
              </Link>
            }
          />
        </div>
      </div>
    );
  }

  const overview = data.overview ?? {};
  const currency = overview.currency ?? 'USD';
  const totalSales = metricValue(overview.totalSales);
  const adSpend = metricValue(overview.adSpend);
  const adSales = metricValue(overview.adSales);
  const acos = metricValue(overview.acos);
  const roas = adSpend > 0 ? adSales / adSpend : 0;
  const connectionDataAvailable = !data.failures.some((failure) => failure.source === '平台连接');
  const productDataAvailable = !data.failures.some((failure) => failure.source === '商品');
  const uploadDataAvailable = !data.failures.some((failure) => failure.source === '发布任务');
  const notificationCount = (data.notifications?.categories ?? []).reduce((sum, category) => sum + category.pendingCount, 0);
  const disconnectedCount = data.platformConnections.filter((connection) => !isConnected(connection)).length;
  const connectedCount = data.platformConnections.length - disconnectedCount;
  const awaitingApproval = Number(data.hostingSummary?.awaiting_approval_count ?? 0);
  const activeProducts = data.products.filter((product) => normalizeStatus(product?.status) === 'active').length;
  const pendingUploadJobs = data.uploadJobs.filter((job) =>
    ['draft', 'ready', 'approved'].includes(normalizeStatus(job?.status)),
  ).length;
  const failedUploadJobs = data.uploadJobs.filter((job) => normalizeStatus(job?.status) === 'failed').length;
  const totalPending =
    data.tasks.length +
    data.recommendations.length +
    notificationCount +
    awaitingApproval +
    pendingUploadJobs +
    failedUploadJobs +
    data.failedSyncJobs.length +
    disconnectedCount +
    (connectionDataAvailable && data.platformConnections.length === 0 ? 1 : 0);

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="今日运营工作台"
        description={`${selectedStore?.name ?? '当前店铺'} · ${channel.channelLabel} · 广告、商品、发布与同步统一处理`}
        actions={
          <div className="flex items-center gap-2">
            <OnboardingWizard />
            <Link
              to="/dashboard"
              className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
            >
              <Activity size={14} />
              经营分析
            </Link>
            <button
              onClick={loadData}
              className="inline-flex items-center gap-2 rounded-lg bg-slate-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-slate-800"
            >
              <RefreshCw size={14} />
              刷新
            </button>
          </div>
        }
      />

      {data.failures.length > 0 && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          <div className="flex items-start gap-2">
            <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" />
            <div>
              <p className="font-medium">部分数据暂时不可用</p>
              <p className="mt-0.5 text-xs text-amber-700">
                {data.failures.slice(0, 3).map((failure) => `${failure.source}: ${failure.message}`).join('；')}
              </p>
            </div>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-6">
        <MetricTile
          label="待处理项"
          value={formatNumber(totalPending)}
          detail={totalPending > 0 ? '来自真实接口状态' : '当前无阻塞事项'}
          icon={<ListChecks size={16} />}
          tone={totalPending > 0 ? 'warning' : 'success'}
        />
        <MetricTile
          label="商品"
          value={productDataAvailable ? formatNumber(data.products.length) : '-'}
          detail={productDataAvailable ? activeProducts + ' 个在售' : '商品数据暂不可用'}
          icon={<Package size={16} />}
          tone={productDataAvailable && data.products.length > 0 ? 'default' : 'warning'}
        />
        <MetricTile
          label="发布队列"
          value={uploadDataAvailable ? formatNumber(pendingUploadJobs) : '-'}
          detail={uploadDataAvailable ? failedUploadJobs + ' 个失败' : '发布数据暂不可用'}
          icon={<Upload size={16} />}
          tone={failedUploadJobs > 0 ? 'danger' : pendingUploadJobs > 0 ? 'warning' : 'success'}
        />
        <MetricTile
          label="同步异常"
          value={formatNumber(data.failedSyncJobs.length)}
          detail={`${disconnectedCount} 个连接未就绪`}
          icon={<Database size={16} />}
          tone={data.failedSyncJobs.length > 0 || disconnectedCount > 0 ? 'danger' : 'success'}
        />
        <MetricTile
          label="审批队列"
          value={formatNumber(awaitingApproval)}
          detail={isAmazonStore ? 'Amazon Ads AI 托管待确认' : '当前渠道无托管审批'}
          icon={<ShieldCheck size={16} />}
          tone={awaitingApproval > 0 ? 'warning' : 'success'}
        />
        <MetricTile
          label="广告连接"
          value={connectionDataAvailable ? formatNumber(connectedCount) : '-'}
          detail={connectionDataAvailable ? channel.adMonitorLabel : '连接状态暂不可用'}
          icon={<Bot size={16} />}
          tone={connectedCount > 0 ? 'success' : 'warning'}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="rounded-lg border border-slate-200 bg-white">
          <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
            <div>
              <h2 className="text-sm font-semibold text-slate-900">优先处理</h2>
              <p className="text-xs text-slate-500">按风险和阻塞程度排序</p>
            </div>
            <Link to="/today-actions" className="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700">
              全部待办
              <ArrowRight size={13} />
            </Link>
          </div>
          {workItems.length === 0 ? (
            <ErpEmptyState
              title="今天没有待处理事项"
              description="广告、商品、发布、审批、同步和接口连接都没有返回待处理状态。"
              icon={<CheckCircle2 size={24} className="text-emerald-500" />}
            />
          ) : (
            <div className="divide-y divide-slate-100">
              {workItems.slice(0, 12).map((item) => (
                <WorkItemRow key={item.id} item={item} />
              ))}
            </div>
          )}
        </div>

        <div className="space-y-4">
          <div className="rounded-lg border border-slate-200 bg-white">
            <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
              <h2 className="text-sm font-semibold text-slate-900">接口连接</h2>
              <Link to="/api-connections" className="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700">
                管理
                <ArrowRight size={13} />
              </Link>
            </div>
            {!connectionDataAvailable ? (
              <div className="px-4 py-6 text-sm text-slate-500">连接状态暂不可用。</div>
            ) : data.platformConnections.length === 0 ? (
              <div className="px-4 py-6 text-sm text-slate-500">暂无平台连接。</div>
            ) : (
              <div className="divide-y divide-slate-100">
                {data.platformConnections.slice(0, 6).map((connection, index) => {
                  const ok = isConnected(connection);
                  return (
                    <div key={connection?.id ?? index} className="flex items-center gap-3 px-4 py-3">
                      <StatusDot ok={ok} />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium text-slate-800">{connectionName(connection)}</p>
                        <p className="truncate text-xs text-slate-500">
                          {connection?.platform || 'unknown'} · {connection?.marketplaceCode || connection?.marketplace || '未设置站点'}
                        </p>
                      </div>
                      <span className="text-xs text-slate-400">{ok ? '可用' : '未就绪'}</span>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          <div className="rounded-lg border border-slate-200 bg-white">
            <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
              <h2 className="text-sm font-semibold text-slate-900">商品与发布</h2>
              <Link to="/products" className="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700">
                管理商品
                <ArrowRight size={13} />
              </Link>
            </div>
            <div className="grid grid-cols-2 gap-3 px-4 py-3">
              <div>
                <p className="text-xs text-slate-500">商品总数</p>
                <p className="mt-1 text-lg font-semibold text-slate-900">{productDataAvailable ? formatNumber(data.products.length) : '-'}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500">在售商品</p>
                <p className="mt-1 text-lg font-semibold text-slate-900">{productDataAvailable ? formatNumber(activeProducts) : '-'}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500">待发布</p>
                <p className="mt-1 text-lg font-semibold text-slate-900">{uploadDataAvailable ? formatNumber(pendingUploadJobs) : '-'}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500">发布失败</p>
                <p className="mt-1 text-lg font-semibold text-slate-900">{uploadDataAvailable ? formatNumber(failedUploadJobs) : '-'}</p>
              </div>
            </div>
            <Link
              to="/product-upload"
              className="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              <span className="inline-flex items-center gap-2">
                <Upload size={15} />
                {channel.productPublishLabel}
              </span>
              <ArrowRight size={14} />
            </Link>
          </div>

          {isAmazonStore ? (
            <div className="rounded-lg border border-slate-200 bg-white">
              <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
                <h2 className="text-sm font-semibold text-slate-900">Amazon Ads 托管链路</h2>
                <span className="inline-flex items-center gap-1 rounded border border-slate-200 px-2 py-0.5 text-xs text-slate-600">
                  <Clock3 size={12} />
                  {formatDateTime(data.hostingHealth?.checked_at)}
                </span>
              </div>
              <div className="px-4 py-3">
                <div className="grid grid-cols-2 gap-3 border-b border-slate-100 pb-3">
                  <div>
                    <p className="text-xs text-slate-500">预计节省 7 天</p>
                    <p className="mt-1 text-lg font-semibold text-slate-900">
                      {formatCurrency(Number(data.hostingSummary?.estimated_savings_7d ?? 0), currency)}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-slate-500">ACoS</p>
                    <p className="mt-1 text-lg font-semibold text-slate-900">{formatPercent(acos)}</p>
                  </div>
                  <div>
                    <p className="text-xs text-slate-500">广告销售额</p>
                    <p className="mt-1 text-lg font-semibold text-slate-900">{formatCurrency(adSales, currency)}</p>
                  </div>
                  <div>
                    <p className="text-xs text-slate-500">ROAS</p>
                    <p className="mt-1 text-lg font-semibold text-slate-900">{roas.toFixed(2)}x</p>
                  </div>
                </div>
                {data.hostingHealth?.dependencies ? (
                  <div className="pt-2">
                    {Object.entries(data.hostingHealth.dependencies).map(([name, status]) => (
                      <DependencyStatus key={name} name={name} status={status} />
                    ))}
                  </div>
                ) : (
                  <div className="flex items-center gap-2 pt-3 text-sm text-slate-500">
                    <Link2 size={15} />
                    托管健康检查接口未返回数据
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div className="rounded-lg border border-slate-200 bg-white">
              <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
                <h2 className="text-sm font-semibold text-slate-900">{channel.adMonitorLabel}</h2>
                {channel.adConnectionPlatform !== 'none' && (
                  <Link
                    to={'/data-sync?platform=' + channel.adConnectionPlatform}
                    className="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700"
                  >
                    管理连接
                    <ArrowRight size={13} />
                  </Link>
                )}
              </div>
              <div className="flex items-start gap-3 px-4 py-4">
                <StatusDot ok={connectedCount > 0} />
                <div>
                  <p className="text-sm font-medium text-slate-800">
                    {connectedCount > 0 ? '广告数据连接可用' : '广告数据连接未就绪'}
                  </p>
                  <p className="mt-1 text-xs leading-5 text-slate-500">
                    {channel.supportsAutomatedAdMonitoring && connectedCount > 0
                      ? '已接入自动监控链路'
                      : '当前仅展示真实连接状态，不会模拟广告监控结果'}
                  </p>
                </div>
              </div>
            </div>
          )}

          <div className="rounded-lg border border-slate-200 bg-white p-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <p className="text-xs text-slate-500">总销售额</p>
                <p className="mt-1 text-base font-semibold text-slate-900">{formatCurrency(totalSales, currency)}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500">广告花费</p>
                <p className="mt-1 text-base font-semibold text-slate-900">{formatCurrency(adSpend, currency)}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
