import { useState, useEffect, useMemo } from 'react';
import { Bell, Settings, CheckCircle2, ThumbsUp, ThumbsDown, Zap, Inbox, Send } from 'lucide-react';
import {
  fetchAiNotifications,
  applyAiNotification,
  confirmAiNotification,
  rejectAiNotification,
  pushAiNotificationToFeishu,
  fetchAiNotificationConfig,
  updateAiNotificationConfig,
  type AiNotificationOverview,
  type AiNotificationCategory,
  type AiNotification,
  type AiNotificationConfig,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { cn } from '../lib/utils';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// ─── Label maps ──────────────────────────────────────────────────────
const resolutionLabelMap: Record<string, string> = {
  applied: '已优化',
  confirmed: '已确认',
  rejected: '已拒绝',
  dismissed: '已忽略',
};

function getResolutionBadge(resolution?: string | null): string {
  const styles: Record<string, string> = {
    applied: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    confirmed: 'bg-blue-100 text-blue-700 border-blue-200',
    rejected: 'bg-red-100 text-red-700 border-red-200',
    dismissed: 'bg-slate-100 text-slate-500 border-slate-200',
  };
  return (resolution && styles[resolution]) || styles.dismissed;
}

/** The 一键优化 (apply) action is for one-click optimization categories; the
 * 确认/拒绝 (confirm/reject) actions are for AI target-correction items. */
function isTargetCorrection(categoryKey: string): boolean {
  return categoryKey === 'target_correction';
}

// ─── Loading skeleton ────────────────────────────────────────────────
function NotificationsSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="h-8 w-40 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-36 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="h-20 bg-slate-100 rounded-xl animate-pulse" />
        ))}
      </div>
      <div className="bg-white rounded-xl border border-slate-200 p-4 space-y-3">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="h-14 bg-slate-100 rounded animate-pulse" />
        ))}
      </div>
    </div>
  );
}

// ─── Notification item row ───────────────────────────────────────────
function NotificationItem({
  item,
  categoryKey,
  onApply,
  onConfirm,
  onReject,
  onPush,
  busy,
  pushBusy,
  pushedId,
}: {
  item: AiNotification;
  categoryKey: string;
  onApply: (id: string) => void;
  onConfirm: (id: string) => void;
  onReject: (id: string) => void;
  onPush: (id: string) => void;
  busy: boolean;
  pushBusy: boolean;
  pushedId: string | null;
}) {
  const isClosed = item.state === 'closed';
  const targetCorrection = isTargetCorrection(categoryKey);

  const pushButton = (
    <button
      onClick={() => onPush(item.id)}
      disabled={pushBusy}
      title="推送到飞书"
      className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-slate-200 text-slate-600 text-xs font-medium hover:bg-slate-50 disabled:opacity-60 flex-shrink-0"
    >
      <Send size={13} />
      {pushedId === item.id ? '已推送' : '推送到飞书'}
    </button>
  );

  return (
    <div className="bg-white rounded-lg border border-slate-200 px-4 py-3 flex items-start gap-3">
      <Bell size={16} className={cn('flex-shrink-0 mt-0.5', isClosed ? 'text-slate-300' : 'text-blue-500')} />
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-slate-800">{item.title}</p>
        {item.subjectId && <p className="text-xs text-slate-400 mt-0.5">对象：{item.subjectId}</p>}
        {item.createdAt && <p className="text-xs text-slate-400 mt-0.5">{item.createdAt}</p>}
      </div>

      {isClosed ? (
        <div className="flex items-center gap-2 flex-shrink-0">
          {pushButton}
          <span
            className={cn(
              'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
              getResolutionBadge(item.resolution),
            )}
          >
            {resolutionLabelMap[item.resolution || 'dismissed'] || item.resolution}
          </span>
        </div>
      ) : targetCorrection ? (
        <div className="flex items-center gap-2 flex-shrink-0">
          {pushButton}
          <button
            onClick={() => onConfirm(item.id)}
            disabled={busy}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-medium hover:bg-blue-700 disabled:opacity-60"
          >
            <ThumbsUp size={13} />
            确认
          </button>
          <button
            onClick={() => onReject(item.id)}
            disabled={busy}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-slate-200 text-slate-600 text-xs font-medium hover:bg-slate-50 disabled:opacity-60"
          >
            <ThumbsDown size={13} />
            拒绝
          </button>
        </div>
      ) : (
        <div className="flex items-center gap-2 flex-shrink-0">
          {pushButton}
          <button
            onClick={() => onApply(item.id)}
            disabled={busy}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-medium hover:bg-blue-700 disabled:opacity-60"
          >
            <Zap size={13} />
            一键优化
          </button>
        </div>
      )}
    </div>
  );
}

// ─── Config modal (前往AI通知配置) ────────────────────────────────────
const CORE_OPS_TOGGLES: { key: string; label: string }[] = [
  { key: 'budgetAlerts', label: '预算告警' },
  { key: 'acosSpikes', label: 'ACoS 异常波动' },
  { key: 'lowImpressions', label: '曝光不足' },
  { key: 'outOfBudget', label: '预算耗尽' },
];

function ConfigModal({
  storeId,
  initialConfig,
  onClose,
  onSaved,
}: {
  storeId: string;
  initialConfig: AiNotificationConfig | null;
  onClose: () => void;
  onSaved: (config: AiNotificationConfig) => void;
}) {
  const initial = (initialConfig?.config ?? {}) as Record<string, boolean>;
  const [toggles, setToggles] = useState<Record<string, boolean>>(() => {
    const base: Record<string, boolean> = {};
    for (const t of CORE_OPS_TOGGLES) {
      base[t.key] = initial[t.key] ?? true;
    }
    return base;
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSave() {
    setError(null);
    try {
      setSaving(true);
      const saved = await updateAiNotificationConfig({ storeId, config: toggles });
      onSaved(saved);
    } catch (err: any) {
      setError(err?.message || '保存配置失败');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !saving) onClose(); }}>
      <DialogContent className="block gap-0 p-0 w-full sm:max-w-lg rounded-xl border-0 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <DialogTitle className="text-lg font-semibold text-slate-900">AI 通知配置</DialogTitle>
        </div>

        <div className="px-5 py-4 space-y-3">
          <p className="text-sm text-slate-500">选择需要在「广告运营核心关注」中触发的通知项。</p>
          {CORE_OPS_TOGGLES.map((t) => (
            <label
              key={t.key}
              className="flex items-center justify-between rounded-lg border border-slate-200 px-3 py-2.5 cursor-pointer hover:bg-slate-50"
            >
              <span className="text-sm text-slate-700">{t.label}</span>
              <input
                type="checkbox"
                checked={toggles[t.key]}
                onChange={(e) => setToggles((prev) => ({ ...prev, [t.key]: e.target.checked }))}
                className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-400"
              />
            </label>
          ))}

          {error && <p className="text-sm text-red-500">{error}</p>}
        </div>

        <div className="flex items-center justify-end gap-3 border-t border-slate-100 px-5 py-4">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 rounded-lg border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50"
          >
            取消
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
          >
            {saving ? '保存中...' : '保存配置'}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

// ─── Main AI Notifications Page ──────────────────────────────────────
export function AiNotificationsPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const [overview, setOverview] = useState<AiNotificationOverview | null>(null);
  const [config, setConfig] = useState<AiNotificationConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const [showConfig, setShowConfig] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [pushBusyId, setPushBusyId] = useState<string | null>(null);
  const [pushedId, setPushedId] = useState<string | null>(null);

  useEffect(() => {
    if (storeId) loadData();
    else if (!storeLoading) setLoading(false);
  }, [storeId, storeLoading]);

  async function loadData() {
    if (!storeId) return;
    try {
      setLoading(true);
      setError(null);
      const [ov, cfg] = await Promise.all([
        fetchAiNotifications(storeId),
        fetchAiNotificationConfig(storeId).catch(() => null),
      ]);
      setOverview(ov);
      setConfig(cfg);
      // Default to the first category that has pending items, else the first.
      setActiveCategory((prev) => {
        if (prev && ov.categories.some((c) => c.key === prev)) return prev;
        const withPending = ov.categories.find((c) => c.pendingCount > 0);
        return (withPending ?? ov.categories[0])?.key ?? null;
      });
    } catch (err: any) {
      setError(err?.message || '加载 AI 通知失败');
    } finally {
      setLoading(false);
    }
  }

  const categories = overview?.categories ?? [];
  const current = useMemo<AiNotificationCategory | null>(
    () => categories.find((c) => c.key === activeCategory) ?? categories[0] ?? null,
    [categories, activeCategory],
  );

  /** Close an item via the given action, then move it from pending → closed in
   * place without a full reload, reflecting the returned result (Req 23.3, 23.4). */
  async function handleAction(
    id: string,
    action: (id: string) => Promise<AiNotification>,
  ) {
    try {
      setBusyId(id);
      const updated = await action(id);
      setOverview((prev) => {
        if (!prev) return prev;
        return {
          categories: prev.categories.map((cat) => {
            const found = cat.pending.find((n) => n.id === id);
            if (!found) return cat;
            const pending = cat.pending.filter((n) => n.id !== id);
            return {
              ...cat,
              pending,
              pendingCount: pending.length,
              closed: [updated, ...cat.closed],
              closedCount: cat.closedCount + 1,
            };
          }),
        };
      });
    } catch (err: any) {
      setError(err?.message || '操作失败');
    } finally {
      setBusyId(null);
    }
  }

  /** Push a notification to the store's bound Feishu chat(s) (item 19). */
  async function handlePush(id: string) {
    try {
      setPushBusyId(id);
      await pushAiNotificationToFeishu(id);
      setPushedId(id);
    } catch (err: any) {
      setError(err?.message || '推送到飞书失败');
    } finally {
      setPushBusyId(null);
    }
  }

  if (loading || storeLoading) return <NotificationsSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <Bell size={48} className="text-red-300 mb-4" />
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
        <Bell size={48} className="text-slate-300 mb-4" />
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
          <h1 className="text-2xl font-bold text-slate-900">AI 通知</h1>
          <p className="text-sm text-slate-500 mt-1">AI 提出的工作项，按类别处理一键优化与目标修正确认</p>
        </div>
        <button
          onClick={() => setShowConfig(true)}
          disabled={!storeId}
          className="inline-flex items-center gap-2 px-4 py-2.5 border border-slate-200 text-slate-700 rounded-lg text-sm font-medium hover:bg-slate-50 transition-colors disabled:opacity-60"
        >
          <Settings size={16} />
          前往 AI 通知配置
        </button>
      </div>

      {/* Category cards with pending/closed counts (Req 23.1) */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
        {categories.map((cat) => {
          const isActive = cat.key === current?.key;
          return (
            <button
              key={cat.key}
              onClick={() => setActiveCategory(cat.key)}
              className={cn(
                'text-left rounded-xl border px-4 py-3 transition-colors',
                isActive
                  ? 'border-blue-400 bg-blue-50/60 ring-1 ring-blue-100'
                  : 'border-slate-200 bg-white hover:border-slate-300',
              )}
            >
              <p className="text-sm font-medium text-slate-800">{cat.label}</p>
              <div className="flex items-center gap-3 mt-2 text-xs">
                <span className="inline-flex items-center gap-1 text-blue-600 font-semibold">
                  待处理 {cat.pendingCount}
                </span>
                <span className="inline-flex items-center gap-1 text-slate-400">
                  已结束 {cat.closedCount}
                </span>
              </div>
            </button>
          );
        })}
      </div>

      {/* Selected category: pending (待处理) + closed (已结束) lists (Req 23.2) */}
      {current && (
        <div className="space-y-5">
          <section>
            <h2 className="text-sm font-semibold text-slate-700 mb-2">待处理（{current.pendingCount}）</h2>
            {current.pending.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center bg-white rounded-xl border border-dashed border-slate-200">
                <Inbox size={36} className="text-slate-300 mb-3" />
                <p className="text-sm font-medium text-slate-500">暂无待处理通知</p>
                <p className="text-xs text-slate-400 mt-1">该类别下没有需要处理的项目</p>
              </div>
            ) : (
              <div className="space-y-2">
                {current.pending.map((item) => (
                  <NotificationItem
                    key={item.id}
                    item={item}
                    categoryKey={current.key}
                    busy={busyId === item.id}
                    pushBusy={pushBusyId === item.id}
                    pushedId={pushedId}
                    onApply={(id) => handleAction(id, applyAiNotification)}
                    onConfirm={(id) => handleAction(id, confirmAiNotification)}
                    onReject={(id) => handleAction(id, rejectAiNotification)}
                    onPush={handlePush}
                  />
                ))}
              </div>
            )}
          </section>

          <section>
            <h2 className="text-sm font-semibold text-slate-700 mb-2">已结束（{current.closedCount}）</h2>
            {current.closed.length === 0 ? (
              <div className="flex items-center gap-2 text-sm text-slate-400 px-1">
                <CheckCircle2 size={16} />
                暂无已结束通知
              </div>
            ) : (
              <div className="space-y-2">
                {current.closed.map((item) => (
                  <NotificationItem
                    key={item.id}
                    item={item}
                    categoryKey={current.key}
                    busy={false}
                    pushBusy={pushBusyId === item.id}
                    pushedId={pushedId}
                    onApply={() => { }}
                    onConfirm={() => { }}
                    onReject={() => { }}
                    onPush={handlePush}
                  />
                ))}
              </div>
            )}
          </section>
        </div>
      )}

      {showConfig && storeId && (
        <ConfigModal
          storeId={storeId}
          initialConfig={config}
          onClose={() => setShowConfig(false)}
          onSaved={(saved) => {
            setConfig(saved);
            setShowConfig(false);
          }}
        />
      )}
    </div>
  );
}
