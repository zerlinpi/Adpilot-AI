import { useState, useEffect, useCallback } from 'react';
import { RefreshCw, Link2, Loader2, CheckCircle2, XCircle, Bot, Info } from 'lucide-react';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import {
  fetchFeishuIntegrations,
  connectFeishuIntegration,
  updateFeishuIntegration,
  sendFeishuTestMessage,
  fetchStores,
  type FeishuIntegration,
} from '../lib/api';
import { cn } from '../lib/utils';
import { FlowGuide } from '../components/onboarding/FlowGuide';

interface ActionResult {
  success: boolean;
  message: string;
}

/**
 * Feishu integration — connects via App ID + App Secret (应用凭证).
 *
 * Each user/store can configure their own Feishu app credentials. The backend
 * obtains a tenant_access_token from the Feishu open platform using these
 * credentials to send messages to bound group chats.
 */
export function FeishuIntegrationPage() {
  const [loading, setLoading] = useState(true);
  const [stores, setStores] = useState<any[]>([]);
  const [integration, setIntegration] = useState<FeishuIntegration | null>(null);

  // App credential form
  const [storeId, setStoreId] = useState('');
  const [appId, setAppId] = useState('');
  const [appSecret, setAppSecret] = useState('');
  const [defaultChatId, setDefaultChatId] = useState('');
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState<ActionResult | null>(null);
  const [testing, setTesting] = useState(false);

  const connected = integration?.status === 'active';

  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const [integrations, storesData] = await Promise.all([
        fetchFeishuIntegrations(),
        fetchStores().catch(() => []),
      ]);
      setStores(Array.isArray(storesData) ? storesData : []);
      const list = Array.isArray(integrations) ? integrations : [];
      // Only load an app-type integration; never fall back to webhook.
      const app = list.find((i) => i.connectionType === 'app');
      const active = app ?? null;
      setIntegration(active);
      if (active) {
        setStoreId(active.storeId ?? '');
        setAppId(active.appId ?? '');
        setDefaultChatId(active.defaultChatId ?? '');
      }
    } catch {
      setIntegration(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  const handleConnect = useCallback(async () => {
    setResult(null);
    if (!appId.trim()) {
      setResult({ success: false, message: '请填写 App ID' });
      return;
    }
    if (!appSecret.trim() && !integration) {
      setResult({ success: false, message: '请填写 App Secret' });
      return;
    }
    setSaving(true);
    try {
      let saved: FeishuIntegration;
      const payload: any = {
        connectionType: 'app',
        appId: appId.trim(),
        defaultChatId: defaultChatId.trim() || null,
        storeId: storeId || null,
      };
      // Only send appSecret when the user entered one (update without re-entering keeps the old one)
      if (appSecret.trim()) {
        payload.appSecret = appSecret.trim();
      }
      if (integration?.id) {
        // Update existing integration (PUT)
        saved = await updateFeishuIntegration(integration.id, payload);
      } else {
        // Create new integration (POST)
        saved = await connectFeishuIntegration(payload);
      }
      setIntegration(saved);
      setAppSecret('');
      setResult({ success: true, message: '飞书应用已连接成功！点击「发送测试」验证。' });
    } catch (err: any) {
      setResult({ success: false, message: err?.message || '连接失败' });
    } finally {
      setSaving(false);
    }
  }, [storeId, appId, appSecret, defaultChatId, integration]);

  const handleTest = useCallback(async () => {
    if (!integration?.id) {
      setResult({ success: false, message: '请先连接飞书应用' });
      return;
    }
    setTesting(true);
    setResult(null);
    try {
      await sendFeishuTestMessage(integration.id);
      setResult({ success: true, message: '测试消息已发送，请到飞书群查看。' });
    } catch (err: any) {
      setResult({ success: false, message: err?.message || '发送失败' });
    } finally {
      setTesting(false);
    }
  }, [integration]);

  if (loading) {
    return (
      <div className="space-y-4">
        <ErpPageHeader title="飞书机器人" description="通过应用凭证连接飞书，接收 AI 诊断 / 异常提醒 / 日报周报" />
        <div className="bg-white rounded-lg border border-slate-200 p-5 animate-pulse">
          <div className="h-4 bg-slate-100 rounded w-40 mb-4" />
          <div className="h-16 bg-slate-50 rounded-lg" />
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="飞书机器人"
        description="通过应用凭证连接飞书，接收 AI 诊断 / 异常提醒 / 日报周报"
        actions={
          <button
            onClick={loadAll}
            className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium text-slate-700 bg-white border border-slate-200 hover:bg-slate-50 transition-colors"
          >
            <RefreshCw size={14} /> 刷新
          </button>
        }
      />

      {/* Connection status */}
      <div className={cn('flex items-center gap-3 px-4 py-3 rounded-lg border', connected ? 'bg-emerald-50 border-emerald-200' : 'bg-slate-50 border-slate-200')}>
        <div className={cn('w-2.5 h-2.5 rounded-full', connected ? 'bg-emerald-500' : 'bg-slate-400')} />
        <span className={cn('text-sm font-medium', connected ? 'text-emerald-700' : 'text-slate-600')}>
          {connected ? '飞书应用已连接' : '未连接'}
        </span>
        {integration?.status && <ErpStatusBadge status={integration.status} />}
        {integration?.appId && (
          <span className="text-xs text-slate-400 ml-auto">App ID: {integration.appId}</span>
        )}
      </div>

      {/* Step-by-step guidance: per-account, per-store Feishu sending (Req 8.3) */}
      <FlowGuide
        title="操作指引：飞书通知发送"
        intro="每个账号绑定自己的飞书应用，每个店铺的通知只进它自己的群"
        storageKey="feishu-integration"
        steps={[
          {
            title: '创建飞书企业自建应用',
            detail: '在飞书开放平台创建应用，复制 App ID 与 App Secret，并开启「以应用身份发消息」权限后发版。',
          },
          {
            title: '按店铺绑定凭证',
            detail:
              '在下方表单选择要绑定的店铺并填入凭证。每个账号绑定自己的飞书应用；某店铺的通知只会用该账号为它绑定的凭证发送，不跨账号、跨店铺复用。',
          },
          {
            title: '设置默认群 Chat ID',
            detail: '将机器人加入目标飞书群并复制群的 Chat ID 填入，通知即发送到该群会话。',
          },
          {
            title: '发送测试连接',
            detail: '点击「发送测试」用该绑定的凭证验证连通性，确认能正常收到消息。',
          },
        ]}
        note={
          <>
            若某店铺没有有效的飞书绑定，系统会<strong>跳过该店铺的发送并记录可读原因</strong>，不会报错中断其他店铺通知，也不会发到错误的会话。
          </>
        }
      />

      {/* How-to */}
      <div className="flex items-start gap-2 px-4 py-3 rounded-lg border border-blue-100 bg-blue-50 text-sm text-blue-800">
        <Info size={16} className="mt-0.5 flex-shrink-0" />
        <div>
          <p className="font-medium mb-1">连接步骤：</p>
          <ol className="list-decimal list-inside space-y-0.5 text-xs">
            <li>打开 <a href="https://open.feishu.cn/app" target="_blank" rel="noreferrer" className="underline">飞书开放平台</a> → 创建/选择企业自建应用</li>
            <li>在「凭证与基础信息」页面复制 <strong>App ID</strong> 和 <strong>App Secret</strong></li>
            <li>在「权限管理」中开启 <code>im:message:send_as_bot</code>（以应用身份发消息）权限并发版</li>
            <li>将机器人添加到目标飞书群，复制群的 <strong>Chat ID</strong>（可选，在群设置中查看）</li>
            <li>粘贴下方表单并点击「连接」</li>
          </ol>
        </div>
      </div>

      {/* App credential config */}
      <div className="bg-white rounded-lg border border-slate-200 p-5">
        <div className="flex items-center gap-2 mb-4">
          <Bot size={16} className="text-emerald-600" />
          <h2 className="text-sm font-semibold text-slate-900">应用凭证配置</h2>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">绑定店铺（可选）</label>
            <select
              value={storeId}
              onChange={(e) => setStoreId(e.target.value)}
              className="w-full h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-emerald-400 focus:ring-2 focus:ring-emerald-100"
            >
              <option value="">全局（不限店铺）</option>
              {stores.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">App ID<span className="text-red-500">*</span></label>
            <input
              type="text"
              value={appId}
              onChange={(e) => setAppId(e.target.value)}
              placeholder="cli_xxxxxxxxxxxxxxxx"
              className="w-full h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-emerald-400 focus:ring-2 focus:ring-emerald-100"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">App Secret<span className="text-red-500">*</span></label>
            <input
              type="password"
              value={appSecret}
              onChange={(e) => setAppSecret(e.target.value)}
              placeholder={integration?.appId ? '••••••••（已保存，重新输入可更新）' : '从飞书开放平台复制'}
              className="w-full h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-emerald-400 focus:ring-2 focus:ring-emerald-100"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">默认群 Chat ID（可选）</label>
            <input
              type="text"
              value={defaultChatId}
              onChange={(e) => setDefaultChatId(e.target.value)}
              placeholder="oc_xxxxxxxxxxxxxxxx"
              className="w-full h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-emerald-400 focus:ring-2 focus:ring-emerald-100"
            />
          </div>
        </div>
        <div className="mt-4 flex items-center gap-3">
          <button
            onClick={handleConnect}
            disabled={saving}
            className="inline-flex items-center gap-2 px-4 py-2 bg-emerald-600 text-white text-sm font-medium rounded-lg hover:bg-emerald-700 disabled:opacity-60"
          >
            {saving ? <Loader2 size={14} className="animate-spin" /> : <Link2 size={14} />}
            {connected ? '更新连接' : '连接飞书应用'}
          </button>
          {integration?.id && (
            <button
              onClick={handleTest}
              disabled={testing}
              className="inline-flex items-center gap-2 px-4 py-2 bg-white text-slate-700 text-sm font-medium rounded-lg border border-slate-200 hover:bg-slate-50 disabled:opacity-60"
            >
              {testing ? <Loader2 size={14} className="animate-spin" /> : <Bot size={14} />}
              发送测试
            </button>
          )}
          {result && (
            <span className={cn('inline-flex items-center gap-1.5 text-xs', result.success ? 'text-emerald-600' : 'text-red-600')}>
              {result.success ? <CheckCircle2 size={14} /> : <XCircle size={14} />}
              {result.message}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}
