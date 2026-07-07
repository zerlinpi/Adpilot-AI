import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import {
  AlertTriangle,
  ArrowRight,
  Bot,
  CheckCircle2,
  ClipboardList,
  Clock,
  Database,
  DollarSign,
  Eye,
  FileText,
  Filter,
  Link2,
  Loader2,
  Package,
  RefreshCw,
  Search,
  ShieldCheck,
  Upload,
  XCircle,
  Zap,
} from 'lucide-react';

import {
  completeTask,
  dismissTask,
  fetchAiNotifications,
  fetchHostingDashboardSummary,
  fetchPlatformConnections,
  fetchProducts,
  fetchRecommendations,
  fetchStoreSyncJobs,
  fetchTasks,
  fetchUploadJobs,
} from '../lib/api';
import { useStoreContext } from '../lib/StoreContext';
import { getChannelCapability } from '../lib/channelCapabilities';
import { cn } from '../lib/utils';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';

type Priority = 'urgent' | 'high' | 'medium' | 'low';
type ActionType =
  | 'ads'
  | 'keyword'
  | 'listing'
  | 'inventory'
  | 'profit'
  | 'upload'
  | 'sync'
  | 'connection'
  | 'approval'
  | 'notification';

interface ActionItem {
  id: string;
  taskId?: string;
  priority: Priority;
  type: ActionType;
  source: string;
  title: string;
  description?: string;
  expectedImpact?: string;
  suggestedAction?: string;
  href?: string;
}

interface ActionsData {
  tasks: any[];
  connections: any[];
  products: any[];
  uploadJobs: any[];
  failedSyncJobs: any[];
  recommendations: any[];
  notifications: any | null;
  hostingSummary: any | null;
  connectionsLoaded: boolean;
  productsLoaded: boolean;
}

const emptyData: ActionsData = {
  tasks: [],
  connections: [],
  products: [],
  uploadJobs: [],
  failedSyncJobs: [],
  recommendations: [],
  notifications: null,
  hostingSummary: null,
  connectionsLoaded: false,
  productsLoaded: false,
};

const priorityConfig: Record<Priority, { label: string; color: string; icon: typeof AlertTriangle }> = {
  urgent: { label: '紧急', color: 'bg-red-100 text-red-700 border-red-200', icon: AlertTriangle },
  high: { label: '高', color: 'bg-orange-100 text-orange-700 border-orange-200', icon: Zap },
  medium: { label: '中', color: 'bg-amber-100 text-amber-700 border-amber-200', icon: Clock },
  low: { label: '低', color: 'bg-blue-100 text-blue-700 border-blue-200', icon: Eye },
};

const typeConfig: Record<ActionType, { label: string; color: string; icon: typeof Zap }> = {
  ads: { label: '广告', color: 'bg-blue-50 text-blue-700', icon: Zap },
  keyword: { label: '关键词', color: 'bg-violet-50 text-violet-700', icon: Search },
  listing: { label: '商品内容', color: 'bg-emerald-50 text-emerald-700', icon: FileText },
  inventory: { label: '库存', color: 'bg-orange-50 text-orange-700', icon: Package },
  profit: { label: '利润', color: 'bg-green-50 text-green-700', icon: DollarSign },
  upload: { label: '渠道发布', color: 'bg-cyan-50 text-cyan-700', icon: Upload },
  sync: { label: '数据同步', color: 'bg-slate-100 text-slate-700', icon: Database },
  connection: { label: '接口连接', color: 'bg-indigo-50 text-indigo-700', icon: Link2 },
  approval: { label: '审批', color: 'bg-amber-50 text-amber-700', icon: ShieldCheck },
  notification: { label: 'AI 通知', color: 'bg-sky-50 text-sky-700', icon: Bot },
};

const priorityOrder: Record<Priority, number> = { urgent: 0, high: 1, medium: 2, low: 3 };

function asArray(value: any): any[] {
  if (Array.isArray(value)) return value;
  if (Array.isArray(value?.items)) return value.items;
  if (Array.isArray(value?.records)) return value.records;
  return [];
}

function normalizeStatus(value: unknown): string {
  return String(value ?? '').trim().toLowerCase();
}

function isConnected(connection: any): boolean {
  return ['active', 'connected', 'authorized', 'configured', 'healthy', 'ok'].includes(
    normalizeStatus(connection?.status ?? connection?.connectionStatus),
  );
}

function taskPriority(value: unknown): Priority {
  const priority = normalizeStatus(value);
  return priority === 'urgent' || priority === 'high' || priority === 'medium' || priority === 'low'
    ? priority
    : 'medium';
}

function taskType(value: unknown): ActionType {
  const type = normalizeStatus(value);
  if (type in typeConfig) return type as ActionType;
  return 'ads';
}

function recommendationPriority(item: any): Priority {
  const risk = normalizeStatus(item?.riskLevel ?? item?.risk_level ?? item?.severity);
  if (['critical', 'urgent', 'high'].includes(risk)) return 'high';
  return risk === 'low' ? 'low' : 'medium';
}

function buildActions(data: ActionsData, connectionHref: string): ActionItem[] {
  const items: ActionItem[] = data.tasks
    .filter((task) => ['open', 'in_progress'].includes(normalizeStatus(task?.status)))
    .map((task, index) => ({
      id: 'task-' + (task?.id ?? index),
      taskId: task?.id,
      priority: taskPriority(task?.priority ?? task?.priorityLevel),
      type: taskType(task?.taskType ?? task?.task_type),
      source: '运营任务',
      title: task?.title || task?.name || '未命名任务',
      description: task?.description,
      expectedImpact: task?.expectedImpact,
      suggestedAction: task?.suggestedAction,
    }));

  if (data.connectionsLoaded && data.connections.length === 0) {
    items.push({
      id: 'connection-missing',
      priority: 'urgent',
      type: 'connection',
      source: '渠道连接',
      title: '当前店铺还没有可用的平台连接',
      description: '先连接店铺和广告账号，系统才能读取真实广告、商品、订单与库存数据。',
      href: connectionHref,
    });
  } else if (data.connectionsLoaded) {
    const disconnected = data.connections.filter((connection) => !isConnected(connection));
    if (disconnected.length > 0) {
      items.push({
        id: 'connection-unready',
        priority: 'high',
        type: 'connection',
        source: '渠道连接',
        title: disconnected.length + ' 个平台连接未就绪',
        description: disconnected
          .slice(0, 3)
          .map((connection) => connection?.connectionName || connection?.platform || '未命名连接')
          .join('、'),
        href: connectionHref,
      });
    }
  }

  if (data.failedSyncJobs.length > 0) {
    items.push({
      id: 'sync-failed',
      priority: 'high',
      type: 'sync',
      source: '数据同步',
      title: data.failedSyncJobs.length + ' 个同步任务失败',
      description: data.failedSyncJobs
        .slice(0, 3)
        .map((job) => job?.entityType || job?.syncType || '同步任务')
        .join('、'),
      href: '/platform-sync',
    });
  }

  if (data.productsLoaded && data.products.length === 0) {
    items.push({
      id: 'products-empty',
      priority: 'medium',
      type: 'listing',
      source: '商品运营',
      title: '当前店铺还没有商品数据',
      description: '同步或创建商品后，才能生成内容并进入渠道发布流程。',
      href: '/products',
    });
  }

  const failedUploads = data.uploadJobs.filter((job) => normalizeStatus(job?.status) === 'failed');
  if (failedUploads.length > 0) {
    items.push({
      id: 'upload-failed',
      priority: 'high',
      type: 'upload',
      source: '渠道发布',
      title: failedUploads.length + ' 个商品发布任务失败',
      description: '修正内容或渠道字段后重新校验。',
      href: '/product-upload',
    });
  }

  const pendingUploads = data.uploadJobs.filter((job) =>
    ['draft', 'ready', 'approved'].includes(normalizeStatus(job?.status)),
  );
  if (pendingUploads.length > 0) {
    items.push({
      id: 'upload-pending',
      priority: 'medium',
      type: 'upload',
      source: '渠道发布',
      title: pendingUploads.length + ' 个商品任务等待处理',
      description: '继续审核、批准或导出渠道资料。',
      href: '/product-upload',
    });
  }

  const awaitingApproval = Number(data.hostingSummary?.awaiting_approval_count ?? 0);
  if (awaitingApproval > 0) {
    items.push({
      id: 'hosting-approval',
      priority: 'high',
      type: 'approval',
      source: 'Amazon Ads AI 托管',
      title: awaitingApproval + ' 条广告决策等待审批',
      description: '决策尚未进入真实执行链路。',
      href: '/approvals',
    });
  }

  data.recommendations.slice(0, 8).forEach((item, index) => {
    items.push({
      id: 'recommendation-' + (item?.id ?? index),
      priority: recommendationPriority(item),
      type: 'ads',
      source: 'AI 广告建议',
      title: item?.title || item?.recommendationType || item?.type || '待确认广告优化建议',
      description: item?.reason || item?.description || item?.summary,
      href: '/recommendations',
    });
  });

  for (const category of data.notifications?.categories ?? []) {
    if (Number(category?.pendingCount ?? 0) > 0) {
      items.push({
        id: 'notification-' + category.key,
        priority: category.pendingCount >= 5 ? 'high' : 'medium',
        type: 'notification',
        source: 'AI 通知',
        title: (category.label || category.key) + '有 ' + category.pendingCount + ' 项待处理',
        description: category.pending?.[0]?.title,
        href: '/ai-notifications',
      });
    }
  }

  return items.sort((a, b) => priorityOrder[a.priority] - priorityOrder[b.priority]);
}

export function TodayActionsPage() {
  const { stores, storeId, loading: storeLoading } = useStoreContext();
  const currentStore = stores.find((store) => store.id === storeId);
  const capability = getChannelCapability(currentStore?.platform);
  const isAmazonStore = capability.platform === 'amazon';
  const connectionHref =
    capability.adConnectionPlatform === 'none'
      ? '/data-sync'
      : '/data-sync?platform=' + capability.adConnectionPlatform;

  const [data, setData] = useState<ActionsData>(emptyData);
  const [loading, setLoading] = useState(true);
  const [failures, setFailures] = useState<string[]>([]);
  const [priorityFilter, setPriorityFilter] = useState('all');
  const [typeFilter, setTypeFilter] = useState('all');
  const [actingId, setActingId] = useState<string | null>(null);

  const loadActions = useCallback(async () => {
    if (!storeId) {
      setData(emptyData);
      setLoading(false);
      return;
    }

    setLoading(true);
    const errors: string[] = [];
    const guarded = async <T,>(label: string, promise: Promise<T>, fallback: T): Promise<T> => {
      try {
        return await promise;
      } catch (error) {
        errors.push(label + '：' + (error instanceof Error ? error.message : '读取失败'));
        return fallback;
      }
    };

    const [
      tasksResponse,
      connectionsResponse,
      productsResponse,
      uploadJobsResponse,
      failedSyncResponse,
      recommendationsResponse,
      notificationsResponse,
      hostingSummaryResponse,
    ] = await Promise.all([
      guarded('运营任务', fetchTasks({ storeId, status: 'open', pageSize: 100 }), []),
      guarded('平台连接', fetchPlatformConnections(), null),
      guarded('商品', fetchProducts(storeId), null),
      guarded('发布任务', fetchUploadJobs(storeId), []),
      guarded('同步任务', fetchStoreSyncJobs(storeId, { status: 'failed', pageSize: 100 }), []),
      isAmazonStore
        ? guarded('AI 建议', fetchRecommendations({ storeId, status: 'pending' }), [])
        : Promise.resolve([]),
      isAmazonStore
        ? guarded('AI 通知', fetchAiNotifications(storeId), null)
        : Promise.resolve(null),
      isAmazonStore
        ? guarded('托管审批', fetchHostingDashboardSummary(storeId), null)
        : Promise.resolve(null),
    ]);

    setData({
      tasks: asArray(tasksResponse),
      connections: asArray(connectionsResponse).filter((connection) => connection?.storeId === storeId),
      products: asArray(productsResponse),
      uploadJobs: asArray(uploadJobsResponse),
      failedSyncJobs: asArray(failedSyncResponse),
      recommendations: asArray(recommendationsResponse),
      notifications: notificationsResponse,
      hostingSummary: hostingSummaryResponse,
      connectionsLoaded: connectionsResponse !== null,
      productsLoaded: productsResponse !== null,
    });
    setFailures(errors);
    setLoading(false);
  }, [isAmazonStore, storeId]);

  useEffect(() => {
    if (!storeLoading) loadActions();
  }, [loadActions, storeLoading]);

  const actions = useMemo(() => buildActions(data, connectionHref), [connectionHref, data]);
  const filteredActions = actions.filter((item) => {
    if (priorityFilter !== 'all' && item.priority !== priorityFilter) return false;
    if (typeFilter !== 'all' && item.type !== typeFilter) return false;
    return true;
  });

  const handleComplete = async (taskId: string) => {
    setActingId(taskId);
    try {
      await completeTask(taskId);
      setData((previous) => ({
        ...previous,
        tasks: previous.tasks.map((task) =>
          task.id === taskId ? { ...task, status: 'completed' } : task,
        ),
      }));
    } catch (error) {
      setFailures([error instanceof Error ? error.message : '完成任务失败']);
    } finally {
      setActingId(null);
    }
  };

  const handleDismiss = async (taskId: string) => {
    setActingId(taskId);
    try {
      await dismissTask(taskId);
      setData((previous) => ({
        ...previous,
        tasks: previous.tasks.map((task) =>
          task.id === taskId ? { ...task, status: 'dismissed' } : task,
        ),
      }));
    } catch (error) {
      setFailures([error instanceof Error ? error.message : '忽略任务失败']);
    } finally {
      setActingId(null);
    }
  };

  if (loading || storeLoading) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="今日运营待办" description="广告、商品、发布与同步任务" />
        <ErpLoadingSkeleton rows={8} />
      </div>
    );
  }

  if (!storeId) {
    return (
      <ErpEmptyState
        title="暂无可用店铺"
        description="请先选择或连接店铺，再查看运营待办。"
        icon={<ClipboardList size={24} className="text-slate-400" />}
      />
    );
  }

  const counts = {
    urgent: actions.filter((item) => item.priority === 'urgent').length,
    high: actions.filter((item) => item.priority === 'high').length,
    medium: actions.filter((item) => item.priority === 'medium').length,
    low: actions.filter((item) => item.priority === 'low').length,
  };

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="今日运营待办"
        description={(currentStore?.name ?? '当前店铺') + ' · 广告、商品、发布与同步统一处理'}
        actions={
          <button
            onClick={loadActions}
            className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            <RefreshCw size={14} />
            刷新
          </button>
        }
      />

      {failures.length > 0 && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" />
          <div>
            <p className="font-medium">部分待办数据暂时不可用</p>
            <p className="mt-0.5 text-xs text-amber-700">{failures.slice(0, 3).join('；')}</p>
          </div>
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {([
          ['urgent', '紧急', counts.urgent, 'text-red-700 bg-red-50'],
          ['high', '高优', counts.high, 'text-orange-700 bg-orange-50'],
          ['medium', '中等', counts.medium, 'text-amber-700 bg-amber-50'],
          ['low', '低优', counts.low, 'text-blue-700 bg-blue-50'],
        ] as const).map(([key, label, count, color]) => (
          <button
            key={key}
            onClick={() => setPriorityFilter(priorityFilter === key ? 'all' : key)}
            className={cn(
              'rounded-lg border p-3 text-center transition-colors',
              priorityFilter === key ? 'border-slate-400 ring-1 ring-slate-300' : 'border-slate-200',
              color,
            )}
          >
            <p className="text-2xl font-semibold">{count}</p>
            <p className="mt-0.5 text-xs text-slate-500">{label}</p>
          </button>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Filter size={14} className="text-slate-400" />
        <div className="flex flex-wrap items-center gap-1 rounded-lg bg-slate-100 p-0.5">
          <button
            onClick={() => setTypeFilter('all')}
            className={cn(
              'rounded-md px-2.5 py-1 text-xs font-medium',
              typeFilter === 'all' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500',
            )}
          >
            全部
          </button>
          {Object.entries(typeConfig).map(([key, config]) => (
            <button
              key={key}
              onClick={() => setTypeFilter(key)}
              className={cn(
                'rounded-md px-2.5 py-1 text-xs font-medium',
                typeFilter === key ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700',
              )}
            >
              {config.label}
            </button>
          ))}
        </div>
      </div>

      {filteredActions.length === 0 ? (
        <ErpEmptyState
          title={actions.length === 0 ? '今天没有待处理事项' : '当前筛选没有结果'}
          description={actions.length === 0 ? '广告、商品、发布与同步链路均未返回待处理状态。' : '清除筛选后查看其他运营事项。'}
          icon={<CheckCircle2 size={24} className="text-emerald-500" />}
        />
      ) : (
        <div className="divide-y divide-slate-100 rounded-lg border border-slate-200 bg-white">
          {filteredActions.map((item) => {
            const priority = priorityConfig[item.priority];
            const type = typeConfig[item.type];
            const PriorityIcon = priority.icon;
            const TypeIcon = type.icon;
            const isActing = actingId === item.taskId;

            return (
              <div key={item.id} className="grid gap-3 px-4 py-4 hover:bg-slate-50 md:grid-cols-[auto_minmax(0,1fr)_auto] md:items-center">
                <PriorityIcon
                  size={18}
                  className={item.priority === 'urgent' ? 'text-red-500' : item.priority === 'high' ? 'text-orange-500' : 'text-slate-400'}
                />
                <div className="min-w-0">
                  <div className="mb-1 flex flex-wrap items-center gap-2">
                    <span className={cn('rounded border px-2 py-0.5 text-xs font-medium', priority.color)}>
                      {priority.label}
                    </span>
                    <span className={cn('inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium', type.color)}>
                      <TypeIcon size={11} />
                      {type.label}
                    </span>
                    <span className="text-xs text-slate-400">{item.source}</span>
                  </div>
                  <h3 className="text-sm font-medium text-slate-900">{item.title}</h3>
                  {item.description && <p className="mt-0.5 text-xs text-slate-500">{item.description}</p>}
                  {item.expectedImpact && <p className="mt-1 text-xs text-emerald-600">预期效果：{item.expectedImpact}</p>}
                  {item.suggestedAction && <p className="mt-1 text-xs text-blue-600">建议操作：{item.suggestedAction}</p>}
                </div>
                <div className="flex items-center gap-2 md:justify-end">
                  {item.taskId ? (
                    <>
                      <button
                        onClick={() => handleComplete(item.taskId!)}
                        disabled={isActing}
                        className="inline-flex items-center gap-1 rounded-lg border border-emerald-200 bg-emerald-50 px-2.5 py-1.5 text-xs font-medium text-emerald-700 hover:bg-emerald-100 disabled:opacity-50"
                      >
                        {isActing ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle2 size={12} />}
                        完成
                      </button>
                      <button
                        onClick={() => handleDismiss(item.taskId!)}
                        disabled={isActing}
                        className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-50"
                      >
                        <XCircle size={12} />
                        忽略
                      </button>
                    </>
                  ) : (
                    <Link
                      to={item.href || '/'}
                      className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
                    >
                      处理
                      <ArrowRight size={13} />
                    </Link>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}