// Google Ads 连接 — connection view (platform-workspace-rbac Req 5.5, 6.6).
//
// Shows whether the selected independent-site Store has an active Google Ads
// connection (derived from the read service's CONNECT_PROMPT vs OK/ERROR state)
// and lets the operator bind a Google Ads account to that Store via the
// Independent_Site_Connection_Wizard backend. Google Ads is always bound to an
// existing independent-site Store, never created as a standalone ad connection.

import { useCallback, useEffect, useState } from 'react';
import { CheckCircle2, Link2, Loader2 } from 'lucide-react';
import {
  bindGoogleAdsConnection,
  fetchGoogleAdsCampaigns,
  fetchPlatformFields,
} from '../../lib/api';
import { useStoreContext } from '../../lib/StoreContext';
import { GaLoading, GaNoStore, GaPageHeader } from './shared';

interface FieldSpec {
  key: string;
  label: string;
  required?: boolean;
  secret?: boolean;
  placeholder?: string;
}

type ConnStatus = 'connected' | 'not_connected' | 'unknown';

export function GoogleAdsConnectPage() {
  const { storeId, stores, loading: storeLoading } = useStoreContext();
  const storeName = stores.find((s) => s.id === storeId)?.name;

  const [status, setStatus] = useState<ConnStatus>('unknown');
  const [statusLoading, setStatusLoading] = useState(false);
  const [statusError, setStatusError] = useState<string | null>(null);

  const [fields, setFields] = useState<FieldSpec[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [connectionName, setConnectionName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Resolve connection status from the read service: CONNECT_PROMPT means no
  // active connection; OK/ERROR both imply a connection exists (Req 6.6).
  const loadStatus = useCallback(async () => {
    if (!storeId) return;
    setStatusLoading(true);
    setStatusError(null);
    try {
      const res = await fetchGoogleAdsCampaigns(storeId);
      setStatus(res.state === 'CONNECT_PROMPT' ? 'not_connected' : 'connected');
    } catch (err: any) {
      setStatusError(err?.message || '无法获取连接状态');
      setStatus('unknown');
    } finally {
      setStatusLoading(false);
    }
  }, [storeId]);

  // Load the Google Ads credential field schema once.
  useEffect(() => {
    let cancelled = false;
    fetchPlatformFields('google_ads')
      .then((list) => {
        if (!cancelled) setFields((Array.isArray(list) ? list : []) as FieldSpec[]);
      })
      .catch(() => {
        if (!cancelled) setFields([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    loadStatus();
  }, [loadStatus]);

  async function handleBind(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setSuccess(null);
    if (!storeId) {
      setFormError('请先选择一个独立站店铺');
      return;
    }
    for (const f of fields) {
      if (f.required && !values[f.key]?.trim()) {
        setFormError(`请填写必填项：${f.label}`);
        return;
      }
    }
    try {
      setSubmitting(true);
      await bindGoogleAdsConnection({
        storeId,
        connectionName: connectionName.trim() || undefined,
        config: values,
      });
      setSuccess('Google Ads 账号已绑定到当前店铺。');
      setValues({});
      setConnectionName('');
      await loadStatus();
    } catch (err: any) {
      setFormError(err?.message || '绑定 Google Ads 账号失败');
    } finally {
      setSubmitting(false);
    }
  }

  const header = (
    <GaPageHeader
      title="Google Ads 连接"
      subtitle="将 Google Ads 账号绑定到当前独立站店铺"
    />
  );

  if (storeLoading) return <GaLoading />;
  if (!storeId) return <div className="space-y-6">{header}<GaNoStore /></div>;

  return (
    <div className="space-y-6">
      {header}

      {/* Connection status */}
      <div className="bg-white rounded-xl border border-slate-200 px-5 py-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm font-medium text-slate-700">
              连接状态
              {storeName && <span className="text-slate-400 font-normal"> · {storeName}</span>}
            </p>
            <div className="mt-1 text-sm">
              {statusLoading ? (
                <span className="inline-flex items-center gap-1.5 text-slate-500">
                  <Loader2 size={14} className="animate-spin" /> 正在检查…
                </span>
              ) : status === 'connected' ? (
                <span className="inline-flex items-center gap-1.5 text-emerald-600 font-medium">
                  <CheckCircle2 size={15} /> 已连接 Google Ads
                </span>
              ) : status === 'not_connected' ? (
                <span className="inline-flex items-center gap-1.5 text-amber-600 font-medium">
                  <Link2 size={15} /> 未连接，请在下方绑定账号
                </span>
              ) : (
                <span className="text-slate-500">{statusError || '状态未知'}</span>
              )}
            </div>
          </div>
          <button
            onClick={loadStatus}
            disabled={statusLoading}
            className="px-3.5 py-2 rounded-lg border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-60"
          >
            刷新状态
          </button>
        </div>
      </div>

      {/* Bind form */}
      <form onSubmit={handleBind} className="bg-white rounded-xl border border-slate-200 p-5 space-y-4 max-w-2xl">
        <h2 className="text-base font-semibold text-slate-900">绑定 Google Ads 账号</h2>

        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1.5">连接名称（可选）</label>
          <input
            type="text"
            value={connectionName}
            onChange={(e) => setConnectionName(e.target.value)}
            placeholder="例如：独立站 Google Ads"
            className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
          />
        </div>

        {fields.length === 0 ? (
          <p className="text-sm text-slate-400">正在加载凭证字段…</p>
        ) : (
          fields.map((f) => (
            <div key={f.key}>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">
                {f.label} {f.required && <span className="text-red-500">*</span>}
              </label>
              <input
                type={f.secret ? 'password' : 'text'}
                value={values[f.key] ?? ''}
                onChange={(e) => setValues((prev) => ({ ...prev, [f.key]: e.target.value }))}
                placeholder={f.placeholder || ''}
                autoComplete="off"
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              />
            </div>
          ))
        )}

        {formError && <p className="text-sm text-red-500">{formError}</p>}
        {success && (
          <p className="text-sm text-emerald-600 inline-flex items-center gap-1.5">
            <CheckCircle2 size={15} /> {success}
          </p>
        )}

        <div className="flex justify-end pt-1">
          <button
            type="submit"
            disabled={submitting || fields.length === 0}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {submitting ? <Loader2 size={16} className="animate-spin" /> : <Link2 size={16} />}
            {submitting ? '绑定中…' : '绑定账号'}
          </button>
        </div>
      </form>
    </div>
  );
}
