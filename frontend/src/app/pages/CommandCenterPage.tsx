import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  BookOpen,
  Bot,
  CheckCircle2,
  Clock3,
  Database,
  DollarSign,
  Link2,
  ListChecks,
  Package,
  Percent,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  Store,
  Target,
  TrendingUp,
  Upload,
  Workflow,
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
import { usePermissions } from '../lib/PermissionContext';
import { getChannelCapability } from '../lib/channelCapabilities';
import { buildNavCommands } from '../lib/commandPalette';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { OnboardingWizard } from '../components/onboarding/OnboardingWizard';

type Priority = 'urgent' | 'high' | 'medium' | 'low';

type Tone = 'default' | 'warning' | 'danger' | 'success';

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

const adsQuickActions = [
  {
    to: '/campaigns',
    label: '广告活动',
    description: 'Campaign、预算与投放状态',
    icon: Target,
  },
  {
    to: '/smart-diagnosis',
    label: '智能诊断',
    description: '快速定位高优先级异常',
    icon: Sparkles,
  },
  {
    to: '/automation-rules',
    label: '自动化规则',
    description: '出价、预算与运营规则',
    icon: Workflow,
  },
  {
    to: '/search-terms',
    label: '搜索词',
    description: '发现浪费和转化机会',
    icon: Search,
  },
  {
    to: '/keyword-library',
    label: '关键词库',
    description: '扩词、否词和关键词资产',
    icon: BookOpen,
  },
  {
    to: '/insight-agent',
    label: 'Insight Agent',
    description: '用 AI 查询经营与广告洞察',
    icon: Bot,
  },
] as const;

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
      detail:
        task?.description ||
        task?.taskType ||
        task?.task_type ||
        `创建时间：${formatDateTime(task?.createdAt ?? task?.created_at)}`,
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
          <div className="h-6 w-40 animate-pulse rounded bg-slate-200" />
          <div className="h-4 w-80 animate-pulse rounded bg-slate-100" />
        </div>
        <div className="h-9 w-24 animate-pulse rounded bg-slate-100" />
      </div>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6">
        {Array.from({ length: 6 }).map((_, index) => (
          <div key={index} className="rounded-lg border border-slate-200 bg-white p-4">
            <div className="mb-3 h-3 w-20 animate-pulse rounded bg-slate-200" />
            <div className="h-6 w-24 animate-pulse rounded bg-slate-200" />
          </div>
        ))}
      </div>
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="rounded-lg border border-slate-200 bg-white p-4">
          <ErpLoadingSkeleton rows={8} />
        </div>
        <div className="rounded-lg border border-slate-200 bg-white p-4">
          <ErpLoadingSkeleton rows={6} />
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
  tone?: Tone;
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
        <span className="text-xs font-medium text-slate-500">{label}</span>
        <span className={cn('flex h-8 w-8 items-center justify-center rounded-lg', toneClass)}>{icon}</span>
      </div>
      <div className="mt-3 text-2xl font-semibold tracking-tight text-slate-900">{value}</div>
      {detail && <div className="mt-1 truncate text-xs text-slate-500">{detail}</div>}
    </div>
  );
}

function PerformanceMetric({
  label,
  value,
  detail,
  icon,
}: {
  label: string;
  value: string;
  detail: string;
  icon: React.ReactNode;
}) {
  return (
    <div className="min-w-0 border-r border-slate-100 px-4 py-4 last:border-r-0">
      <div className="flex items-center gap-2 text-xs font-medium text-slate-500">
        <span className="text-slate-400">{icon}</span>
        {label}
      </div>
      <div className="mt-2 truncate text-xl font-semibold tracking-tight text-slate-950">{value}</div>
      <div className="mt-1 truncate text-[11px] text-slate-400">{detail}</div>
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
  const { can } = usePermissions();
  const [data, setData] = useState<WorkbenchData>(emptyData);
  const [loading, setLoading] = useState(true);

  const selectedStore = stores.find((store) => store.id === storeId);
  const channel = getChannelCapability(selectedStore?.platform);
  const isAmazonStore = channel.platform === 'amazon';

  const visibleRoutes = useMemo(() => new Set(buildNavCommands(can).map((command) => command.to)), [can]);
  const quickActions = useMemo(
    () => adsQuickActions.filter((action) => visibleRoutes.has(action.to)),
    [visibleRoutes],
  );

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
    if (!storeLoading) loadData();
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
  const adSalesShare = totalSales > 0 ? adSales / totalSales : 0;

  const connectionDataAvailable = !data.failures.some((failure) => failure.source === '平台连接');
  const productDataAvailable = !data.failures.some((failure) => failure.source === '商品');
  const uploadDataAvailable = !data.failures.some((failure) => failure.source === '发布任务');
  const notificationCount = (data.notifications?.categories ?? []).reduce((sum, category) => sum + category.pendingCount, 0);
  const disconnectedCount = data.platformConnections.filter((connection) => !isConnected(connection)).length;
  const connectedCount = data.platformConnections.length - disconnectedCount;
  const awaitingApproval = Number(data.hostingSummary?.awaiting_approval_count ?? 0);
  const hostingFailures = Number(data.hostingSummary?.failed_today_count ?? 0);
  const estimatedSavings7d = Number(data.hostingSummary?.estimated_savings_7d ?? 0);
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
        title={isAmazonStore ? 'Amazon Ads 运营指挥台' : '今日运营工作台'}
        description={
          isAmazonStore
            ? `${selectedStore?.name ?? '当前店铺'} · 先看广告结果，再处理异常与自动化优化`
            : `${selectedStore?.name ?? '当前店铺'} · ${channel.channelLabel} · 广告、商品、发布与同步统一处理`
        }
        actions={
          <div className="flex items-center gap-2">
            <OnboardingWizard />
            {isAmazonStore && visibleRoutes.has('/campaigns') ? (
              <Link
                to="/campaigns"
                className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                <Target size={14} />
                广告活动
              </Link>
            ) : (
              <Link
                to="/dashboard"
                className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
              >
                <Activity size={14} />
                经营分析
              </Link>
            )}
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

      {isAmazonStore && (
        <section className="overflow-hidden rounded-xl border border-slate-200 bg-white">
          <div className="flex flex-col gap-3 border-b border-slate-200 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-sm font-semibold text-slate-950">广告经营概览</h2>
                <span className="rounded bg-blue-50 px-2 py-0.5 text-[11px] font-medium text-blue-700">Amazon Ads</span>
              </div>
              <p className="mt-0.5 text-xs text-slate-500">把广告结果放在操作入口之前，减少在多个报表间来回切换。</p>
            </div>
            <div className="flex items-center gap-3 text-xs">
              <Link to="/dashboard" className="font-medium text-slate-600 hover:text-slate-900">
                查看经营分析
              </Link>
              {visibleRoutes.has('/campaigns') && (
                <Link to="/campaigns" className="inline-flex items-center gap-1 font-medium text-blue-600 hover:text-blue-700">
                  进入广告活动
                  <ArrowRight size={13} />
                </Link>
              )}
            </div>
          </div>
          <div className="grid grid-cols-2 divide-y divide-slate-100 sm:grid-cols-3 lg:grid-cols-6 lg:divide-y-0">
            <PerformanceMetric
              label="总销售额"
              value={formatCurrency(totalSales, currency)}
              detail="当前经营周期"
              icon={<DollarSign size={14} />}
            />
            <PerformanceMetric
              label="广告销售额"
              value={formatCurrency(adSales, currency)}
              detail={`${formatPercent(adSalesShare)} 销售来自广告`}
              icon={<TrendingUp size={14} />}
            />
            <PerformanceMetric
              label="广告花费"
              value={formatCurrency(adSpend, currency)}
              detail="已归因广告支出"
              icon={<DollarSign size={14} />}
            />
            <PerformanceMetric
              label="ACoS"
              value={formatPercent(acos)}
              detail="广告花费 / 广告销售额"
              icon={<Percent size={14} />}
            />
            <PerformanceMetric
              label="ROAS"
              value={`${roas.toFixed(2)}x`}
              detail="广告销售额 / 广告花费"
              icon={<TrendingUp size={14} />}
            />
            <PerformanceMetric
              label="预计节省 7 天"
              value={formatCurrency(estimatedSavings7d, currency)}
              detail="AI 托管估算"
              icon={<Sparkles size={14} />}
            />
          </div>
        </section>
      )}

      <div className={cn('grid grid-cols-1 gap-3 sm:grid-cols-2', isAmazonStore ? 'xl:grid-cols-4' : 'xl:grid-cols-6')}>
        <MetricTile
          label="待处理项"
          value={formatNumber(totalPending)}
          detail={totalPending > 0 ? '按风险和阻塞程度排序' : '当前无阻塞事项'}
          icon={<ListChecks size={16} />}
          tone={totalPending > 0 ? 'warning' : 'success'}
        />
        {!isAmazonStore && (
          <MetricTile
            label="商品"
            value={productDataAvailable ? formatNumber(data.products.length) : '-'}
            detail={productDataAvailable ? `${activeProducts} 个在售` : '商品数据暂不可用'}
            icon={<Package size={16} />}
            tone={productDataAvailable && data.products.length > 0 ? 'default' : 'warning'}
          />
        )}
        {!isAmazonStore && (
          <MetricTile
            label="发布队列"
            value={uploadDataAvailable ? formatNumber(pendingUploadJobs) : '-'}
            detail={uploadDataAvailable ? `${failedUploadJobs} 个失败` : '发布数据暂不可用'}
            icon={<Upload size={16} />}
            tone={failedUploadJobs > 0 ? 'danger' : pendingUploadJobs > 0 ? 'warning' : 'success'}
          />
        )}
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
          detail={isAmazonStore ? 'AI 托管决策待确认' : '当前渠道无托管审批'}
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

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
        <div className="rounded-lg border border-slate-200 bg-white">
          <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
            <div>
              <h2 className="text-sm font-semibold text-slate-900">优先处理</h2>
              <p className="text-xs text-slate-500">把异常、审批、AI 建议和同步问题合并到一个队列</p>
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
          {isAmazonStore && quickActions.length > 0 && (
            <div className="rounded-lg border border-slate-200 bg-white">
              <div className="border-b border-slate-200 px-4 py-3">
                <h2 className="text-sm font-semibold text-slate-900">广告优化入口</h2>
                <p className="mt-0.5 text-xs text-slate-500">高频操作不再依赖侧边栏逐级查找</p>
              </div>
              <div className="grid grid-cols-2 gap-px bg-slate-100">
                {quickActions.map((action) => {
                  const Icon = action.icon;
                  return (
                    <Link
                      key={action.to}
                      to={action.to}
                      className="group bg-white p-3 hover:bg-slate-50"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-50 text-slate-500 group-hover:bg-blue-50 group-hover:text-blue-600">
                          <Icon size={15} />
                        </span>
                        <ArrowRight size={13} className="text-slate-300 group-hover:text-blue-500" />
                      </div>
                      <p className="mt-2 text-sm font-medium text-slate-900">{action.label}</p>
                      <p className="mt-0.5 line-clamp-1 text-[11px] text-slate-500">{action.description}</p>
                    </Link>
                  );
                })}
              </div>
            </div>
          )}

          {isAmazonStore ? (
            <div className="rounded-lg border border-slate-200 bg-white">
              <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
                <div>
                  <h2 className="text-sm font-semibold text-slate-900">AI 托管链路</h2>
                  <p className="mt-0.5 text-xs text-slate-500">审批、执行与依赖健康状态</p>
                </div>
                <span className="inline-flex items-center gap-1 rounded border border-slate-200 px-2 py-0.5 text-xs text-slate-600">
                  <Clock3 size={12} />
                  {formatDateTime(data.hostingHealth?.checked_at)}
                </span>
              </div>
              <div className="px-4 py-3">
                <div className="grid grid-cols-3 gap-3 border-b border-slate-100 pb-3">
                  <div>
                    <p className="text-xs text-slate-500">待审批</p>
                    <p className="mt-1 text-lg font-semibold text-slate-900">{formatNumber(awaitingApproval)}</p>
                  </div>
                  <div>
                    <p className="text-xs text-slate-500">今日失败</p>
                    <p className={cn('mt-1 text-lg font-semibold', hostingFailures > 0 ? 'text-red-600' : 'text-slate-900')}>
                      {formatNumber(hostingFailures)}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-slate-500">7 天节省</p>
                    <p className="mt-1 truncate text-lg font-semibold text-slate-900">
                      {formatCurrency(estimatedSavings7d, currency)}
                    </p>
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
              {visibleRoutes.has('/approvals') && (
                <Link
                  to="/approvals"
                  className="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-sm font-medium text-slate-700 hover:bg-slate-50"
                >
                  查看托管审批
                  <ArrowRight size={14} />
                </Link>
              )}
            </div>
          ) : (
            <div className="rounded-lg border border-slate-200 bg-white">
              <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
                <h2 className="text-sm font-semibold text-slate-900">{channel.adMonitorLabel}</h2>
                {channel.adConnectionPlatform !== 'none' && (
                  <Link
                    to={`/data-sync?platform=${channel.adConnectionPlatform}`}
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

          <div className="rounded-lg border border-slate-200 bg-white">
            <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
              <div>
                <h2 className="text-sm font-semibold text-slate-900">业务链路状态</h2>
                <p className="mt-0.5 text-xs text-slate-500">商品、发布、同步与接口连接压缩到一处</p>
              </div>
              <Link to="/data-sync" className="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700">
                数据同步
                <ArrowRight size={13} />
              </Link>
            </div>
            <div className="grid grid-cols-2 gap-x-4 gap-y-3 px-4 py-3">
              <div>
                <p className="text-xs text-slate-500">商品 / 在售</p>
                <p className="mt-1 text-base font-semibold text-slate-900">
                  {productDataAvailable ? `${formatNumber(data.products.length)} / ${formatNumber(activeProducts)}` : '-'}
                </p>
              </div>
              <div>
                <p className="text-xs text-slate-500">待发布 / 失败</p>
                <p className="mt-1 text-base font-semibold text-slate-900">
                  {uploadDataAvailable ? `${formatNumber(pendingUploadJobs)} / ${formatNumber(failedUploadJobs)}` : '-'}
                </p>
              </div>
              <div>
                <p className="text-xs text-slate-500">可用连接</p>
                <p className="mt-1 text-base font-semibold text-slate-900">
                  {connectionDataAvailable ? formatNumber(connectedCount) : '-'}
                </p>
              </div>
              <div>
                <p className="text-xs text-slate-500">同步失败</p>
                <p className={cn('mt-1 text-base font-semibold', data.failedSyncJobs.length > 0 ? 'text-red-600' : 'text-slate-900')}>
                  {formatNumber(data.failedSyncJobs.length)}
                </p>
              </div>
            </div>
            <div className="grid grid-cols-2 border-t border-slate-100">
              <Link to="/products" className="flex items-center justify-between border-r border-slate-100 px-4 py-3 text-xs font-medium text-slate-700 hover:bg-slate-50">
                商品
                <Package size={14} />
              </Link>
              <Link to="/product-upload" className="flex items-center justify-between px-4 py-3 text-xs font-medium text-slate-700 hover:bg-slate-50">
                发布
                <Upload size={14} />
              </Link>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
