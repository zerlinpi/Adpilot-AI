import { Link } from 'react-router';
import { useEffect, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  BarChart3,
  CheckCircle2,
  Database,
  Megaphone,
  Sparkles,
  Store,
} from 'lucide-react';
import { useStoreContext } from '../lib/StoreContext';
import { getChannelCapability } from '../lib/channelCapabilities';
import { fetchPlatformConnections } from '../lib/api';
import { adPlatformLabel, storeAdPlatform, storePlatformGroupLabel, storePlatformLabel } from '../lib/platformTaxonomy';
import { cn } from '../lib/utils';

interface PlatformConnection {
  id?: string;
  storeId?: string;
  platform?: string;
  platformId?: string;
  status?: string;
  hasConfig?: boolean;
}

const READY_CONNECTION_STATUSES = new Set(['connected']);

export function AdMonitorWorkspacePage() {
  const { stores, storeId } = useStoreContext();
  const [connections, setConnections] = useState<PlatformConnection[]>([]);
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const current = stores.find((s) => s.id === storeId);
  const capability = getChannelCapability(current?.platform);
  const adPlatform = current?.adPlatform ?? storeAdPlatform(current?.platform);
  const adConnection = connections.find((connection) => {
    const platform = connection.platformId || connection.platform;
    return platform === capability.adConnectionPlatform && (!current?.id || connection.storeId === current.id);
  });
  const hasAdCredentials = !!adConnection?.hasConfig || ['configured', 'connected'].includes(adConnection?.status ?? '');
  const monitorReady = capability.supportsAutomatedAdMonitoring && hasAdCredentials;
  const connectionStatusText = adConnection
    ? READY_CONNECTION_STATUSES.has(adConnection.status ?? '')
      ? '广告连接已验证'
      : `连接状态：${adConnection.status || '未连接'}`
    : '未找到当前店铺的广告连接';

  useEffect(() => {
    let cancelled = false;
    fetchPlatformConnections()
      .then((data) => {
        if (!cancelled) {
          setConnections(Array.isArray(data) ? data : []);
          setConnectionError(null);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setConnections([]);
          setConnectionError(error instanceof Error ? error.message : '读取平台连接失败');
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const primaryHref =
    capability.platform === 'amazon' && monitorReady
      ? '/dashboard'
      : capability.adConnectionPlatform !== 'none'
        ? `/data-sync?platform=${capability.adConnectionPlatform}`
        : '/data-sync';

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">AI 广告监控台</h1>
          <p className="mt-1 text-sm text-slate-500">
            按当前店铺渠道切换广告监控逻辑：Amazon 走 Amazon Ads，独立站走 Google Ads。
          </p>
        </div>
        <Link
          to={primaryHref}
          className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-blue-700"
        >
          <Megaphone size={15} />
          {capability.platform === 'amazon' && monitorReady ? '进入 Amazon Ads 看板' : `接入 ${adPlatformLabel(adPlatform)}`}
        </Link>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <div className="mb-3 flex items-center gap-2">
            <Store size={16} className="text-blue-600" />
            <h2 className="text-sm font-semibold text-slate-900">当前店铺</h2>
          </div>
          <p className="text-base font-semibold text-slate-900">{current?.name ?? '未选择店铺'}</p>
          <div className="mt-3 space-y-1 text-sm text-slate-500">
            <p>{storePlatformGroupLabel(current?.platform)}</p>
            <p>{storePlatformLabel(current?.platform)}</p>
            <p>广告账号：{adPlatformLabel(adPlatform)}</p>
          </div>
        </div>

        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <div className="mb-3 flex items-center gap-2">
            <Activity size={16} className={monitorReady ? 'text-emerald-600' : 'text-amber-600'} />
            <h2 className="text-sm font-semibold text-slate-900">{capability.adMonitorLabel}</h2>
          </div>
          <span
            className={cn(
              'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium',
              monitorReady ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-700',
            )}
          >
            {monitorReady ? <CheckCircle2 size={12} /> : <AlertTriangle size={12} />}
            {monitorReady ? '已接入自动监控' : '需要补齐真实广告数据连接'}
          </span>
          <p className="mt-3 text-sm leading-relaxed text-slate-500">{capability.note}</p>
          <p className={cn('mt-2 text-xs', hasAdCredentials ? 'text-emerald-600' : 'text-amber-600')}>
            {connectionError ? `连接状态读取失败：${connectionError}` : connectionStatusText}
          </p>
        </div>

        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <div className="mb-3 flex items-center gap-2">
            <Sparkles size={16} className="text-indigo-600" />
            <h2 className="text-sm font-semibold text-slate-900">AI 处理边界</h2>
          </div>
          <p className="text-sm leading-relaxed text-slate-500">
            AI 只能在已接入真实广告数据和真实写回能力的渠道上自动执行；未接入的渠道只做连接、导出和待办提示，不展示假指标。
          </p>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Link to="/data-sync" className="rounded-xl border border-slate-200 bg-white p-5 transition hover:border-blue-200 hover:bg-blue-50/30">
          <div className="flex items-center gap-3">
            <Database size={18} className="text-blue-600" />
            <div>
              <h3 className="text-sm font-semibold text-slate-900">连接与同步</h3>
              <p className="mt-0.5 text-xs text-slate-500">先接入渠道账号，再让监控任务读取真实数据。</p>
            </div>
          </div>
        </Link>
        <Link to="/reports" className="rounded-xl border border-slate-200 bg-white p-5 transition hover:border-blue-200 hover:bg-blue-50/30">
          <div className="flex items-center gap-3">
            <BarChart3 size={18} className="text-blue-600" />
            <div>
              <h3 className="text-sm font-semibold text-slate-900">报表与诊断</h3>
              <p className="mt-0.5 text-xs text-slate-500">已同步数据会进入报表和 AI 诊断链路。</p>
            </div>
          </div>
        </Link>
      </div>
    </div>
  );
}
