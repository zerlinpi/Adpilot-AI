// AI托管 settings drawer (Req 50.1 / 50.2, 49.8 / 49.13 / 49.14 / 49.16 / 49.18).
//
// Presents the grouped, collapsible sections IN THE Req 50.1 ORDER:
//   优化目标 → 目标 ACoS → AI人格 → AI可执行动作 → 安全边界 → 决策频率 → 审批规则 → 提交预览
// On a small (mobile) viewport it renders as a FULL-SCREEN panel rather than a
// side drawer (Req 50.2), and the AI人格 preview stacks below the selector.
//
// Changing AI人格 is NEVER silent (Req 49.8): saving a personality change first
// shows a confirmation step that displays the affected-Campaign count (Req
// 49.13), lets the operator choose whether existing pending Operations continue
// under the previous personality or are recomputed under the new one (Req
// 49.14), requires a SECOND confirmation for a bulk change (Req 49.16), and —
// when the Active_Store is not write-capable — shows the
// "仅影响系统内建议，不会修改 Amazon" indication (Req 49.18).

import { useEffect, useMemo, useState } from 'react';
import { AlertCircle, ChevronDown, Loader2 } from 'lucide-react';

import { cn, formatPercent } from '../../lib/utils';
import { useIsMobile } from '../ui/use-mobile';
import { Sheet, SheetContent, SheetTitle } from '../ui/sheet';
import {
  OPTIMIZATION_GOAL_VALUES,
  OPTIMIZATION_GOAL_DISPLAY,
  AI_PERSONALITY_VALUES,
  AI_PERSONALITY_DISPLAY,
  translateMachineValue,
} from '../../lib/translateMachineValue';
import {
  PERSONALITY_META,
  derivePersonalityPreview,
  type AiPersonality,
  type PersonalityPolicyMap,
} from '../../lib/aiPersonality';
import {
  addHostingCanaryStore,
  disableHostingCanary,
  enableHostingCanary,
  fetchHostingCanary,
  fetchHostingConfig,
  removeHostingCanaryStore,
  saveHostingConfig,
  type HostingCanaryRollout,
  type HostingConfig,
  type HostingConfigInput,
} from '../../lib/api';
import { PersonalitySegmentControl } from './PersonalitySegmentControl';

export interface HostingSettings {
  /** Optimization_Goal machine value (profit_first / sales_growth / rank / clearance). */
  optimizationGoal: string;
  /** Target ACoS as a percentage figure (e.g. 25 for 25%), or null when unset. */
  targetAcos: number | null;
  /** Effective AI_Personality machine value. */
  personality: AiPersonality;
}

interface PersonalitySettingsDrawerProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  subtitle?: string;
  initial: HostingSettings;
  policies?: PersonalityPolicyMap;
  /** Whether the Active_Store is write-capable (Req 49.18). */
  writeCapable?: boolean;
  /** Number of Campaigns a personality change would affect (Req 49.13). */
  affectedCampaignCount?: number;
  /** When true, applying requires a second confirmation (bulk change, Req 49.16). */
  bulk?: boolean;
  /** Phase-gated executable adjustment types to list under AI可执行动作 (Req 54). */
  executableActions?: readonly string[];
  saving?: boolean;
  onSave: (next: HostingSettings, opts: { recomputeExisting: boolean }) => void | Promise<void>;
}

// ─── Ordered, collapsible section scaffolding (Req 50.1) ─────────────────────

type SectionKey =
  | 'goal'
  | 'targetAcos'
  | 'personality'
  | 'actions'
  | 'safety'
  | 'frequency'
  | 'approval'
  | 'preview';

const ACTION_LABELS: Record<string, string> = {
  bid: '竞价调整',
  budget: '预算调整',
  keyword: '关键词扩展',
  negative: '否定关键词',
};

function CollapsibleSection({
  title,
  open,
  onToggle,
  children,
}: {
  title: string;
  open: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <section className="border-b border-slate-100 last:border-b-0">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full items-center justify-between px-1 py-3 text-left"
      >
        <span className="text-sm font-semibold text-slate-900">{title}</span>
        <ChevronDown size={16} className={cn('text-slate-400 transition-transform', open && 'rotate-180')} />
      </button>
      {open && <div className="pb-4 pt-0.5">{children}</div>}
    </section>
  );
}

function PersonalitySettingsDrawer({
  open,
  onClose,
  title = 'AI 托管设置',
  subtitle,
  initial,
  policies,
  writeCapable = true,
  affectedCampaignCount = 0,
  bulk = false,
  executableActions = ['bid'],
  saving = false,
  onSave,
}: PersonalitySettingsDrawerProps) {
  const isMobile = useIsMobile();
  const [draft, setDraft] = useState<HostingSettings>(initial);
  const [expanded, setExpanded] = useState<Set<SectionKey>>(
    () => new Set<SectionKey>(['goal', 'targetAcos', 'personality']),
  );
  // Personality-change confirmation flow (Req 49.8/49.13/49.14/49.16).
  const [confirming, setConfirming] = useState(false);
  const [recomputeExisting, setRecomputeExisting] = useState(false);
  const [bulkConfirmed, setBulkConfirmed] = useState(false);

  const personalityChanged = draft.personality !== initial.personality;
  const selectedPolicy = policies?.[draft.personality];
  const previewItems = useMemo(
    () => derivePersonalityPreview(draft.personality, selectedPolicy),
    [draft.personality, selectedPolicy],
  );

  if (!open) return null;

  function toggle(key: SectionKey) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  function update<K extends keyof HostingSettings>(key: K, value: HostingSettings[K]) {
    setDraft((prev) => ({ ...prev, [key]: value }));
  }

  function handlePrimary() {
    // A personality change is never applied silently — confirm first (Req 49.8).
    if (personalityChanged && !confirming) {
      setConfirming(true);
      return;
    }
    void onSave(draft, { recomputeExisting });
  }

  const primaryDisabled = saving || (confirming && bulk && !bulkConfirmed);

  return (
    <Sheet open={open} onOpenChange={(o) => { if (!o && !saving) onClose(); }}>
      <SheetContent
        side="right"
        aria-label={title}
        className={cn('gap-0 p-0 bg-white w-full max-w-full', !isMobile && 'sm:max-w-lg')}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <div>
            <SheetTitle className="text-lg font-semibold text-slate-900">{title}</SheetTitle>
            {subtitle && <p className="text-xs text-slate-500">{subtitle}</p>}
          </div>
        </div>

        {/* Not-write-capable indication (Req 49.18) */}
        {!writeCapable && (
          <div className="mx-5 mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
            仅影响系统内建议，不会修改 Amazon
          </div>
        )}

        {/* Ordered, collapsible sections */}
        <div className="flex-1 overflow-y-auto px-5">
          <CollapsibleSection title="优化目标" open={expanded.has('goal')} onToggle={() => toggle('goal')}>
            <div className="grid grid-cols-2 gap-2">
              {OPTIMIZATION_GOAL_VALUES.map((goal) => (
                <button
                  key={goal}
                  type="button"
                  onClick={() => update('optimizationGoal', goal)}
                  className={cn(
                    'rounded-lg border-2 px-3 py-2 text-sm font-medium transition-colors',
                    draft.optimizationGoal === goal
                      ? 'border-blue-500 bg-blue-50 text-blue-700'
                      : 'border-slate-200 text-slate-600 hover:border-slate-300',
                  )}
                >
                  {OPTIMIZATION_GOAL_DISPLAY[goal]}
                </button>
              ))}
            </div>
            <p className="mt-2 text-xs text-slate-400">
              优化目标决定 AI 追求的结果，与 AI人格（如何行动）和安全边界（硬性上限）相互独立。
            </p>
          </CollapsibleSection>

          <CollapsibleSection title="目标 ACoS" open={expanded.has('targetAcos')} onToggle={() => toggle('targetAcos')}>
            <div className="flex items-center gap-3">
              <input
                type="number"
                min={0}
                step={0.1}
                value={draft.targetAcos ?? ''}
                onChange={(e) => update('targetAcos', e.target.value === '' ? null : Number(e.target.value))}
                placeholder="例如：25"
                className="h-10 w-32 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              />
              <span className="text-sm text-slate-500">%</span>
            </div>
          </CollapsibleSection>

          <CollapsibleSection title="AI人格" open={expanded.has('personality')} onToggle={() => toggle('personality')}>
            <PersonalitySegmentControl
              value={draft.personality}
              onChange={(p) => update('personality', p)}
              policies={policies}
              previewLayout={isMobile ? 'below' : 'side'}
            />
          </CollapsibleSection>

          <CollapsibleSection title="AI可执行动作" open={expanded.has('actions')} onToggle={() => toggle('actions')}>
            <ul className="space-y-1.5">
              {executableActions.map((a) => (
                <li key={a} className="flex items-center gap-2 text-sm text-slate-700">
                  <span className="inline-block h-1.5 w-1.5 rounded-full bg-emerald-500" />
                  {ACTION_LABELS[a] ?? a}
                </li>
              ))}
            </ul>
            <p className="mt-2 text-xs text-slate-400">
              可执行动作随当前托管能力阶段开放，未开放的动作不会被生成或执行。
            </p>
          </CollapsibleSection>

          <CollapsibleSection title="安全边界" open={expanded.has('safety')} onToggle={() => toggle('safety')}>
            {selectedPolicy ? (
              <dl className="space-y-1.5 text-sm">
                <div className="flex justify-between">
                  <dt className="text-slate-500">竞价单次最高上调</dt>
                  <dd className="text-slate-800">
                    {selectedPolicy.maxBidIncreaseRatio != null
                      ? formatPercent(selectedPolicy.maxBidIncreaseRatio * 100, 0)
                      : '—'}
                  </dd>
                </div>
                <div className="flex justify-between">
                  <dt className="text-slate-500">日预算单次最高上调</dt>
                  <dd className="text-slate-800">
                    {selectedPolicy.maxDailyBudgetIncreaseRatio != null
                      ? formatPercent(selectedPolicy.maxDailyBudgetIncreaseRatio * 100, 0)
                      : '—'}
                  </dd>
                </div>
              </dl>
            ) : (
              <p className="text-xs text-slate-400">安全边界由后端策略提供，加载中…</p>
            )}
            <p className="mt-2 text-xs text-slate-400">
              安全边界为硬性上限，AI 永远不会突破，且始终优先于 AI人格。
            </p>
          </CollapsibleSection>

          <CollapsibleSection title="决策频率" open={expanded.has('frequency')} onToggle={() => toggle('frequency')}>
            <p className="text-sm text-slate-700">
              {selectedPolicy?.adjustmentCooldownHours != null
                ? `每 ${selectedPolicy.adjustmentCooldownHours} 小时最多评估并调整一次`
                : '决策频率由后端策略提供，加载中…'}
            </p>
          </CollapsibleSection>

          <CollapsibleSection title="审批规则" open={expanded.has('approval')} onToggle={() => toggle('approval')}>
            <p className="text-sm text-slate-700">
              {previewItems.find((i) => i.key === 'approval')?.value}
            </p>
          </CollapsibleSection>

          <CollapsibleSection title="提交预览" open={expanded.has('preview')} onToggle={() => toggle('preview')}>
            <dl className="space-y-1.5 text-sm">
              <div className="flex justify-between">
                <dt className="text-slate-500">优化目标</dt>
                <dd className="text-slate-800">
                  {translateMachineValue('Optimization_Goal', draft.optimizationGoal)}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">目标 ACoS</dt>
                <dd className="text-slate-800">{draft.targetAcos != null ? `${draft.targetAcos}%` : '—'}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">AI人格</dt>
                <dd className="text-slate-800">
                  {PERSONALITY_META[draft.personality].display}
                  {personalityChanged && (
                    <span className="ml-1 text-amber-600">
                      （由 {PERSONALITY_META[initial.personality].display} 变更）
                    </span>
                  )}
                </dd>
              </div>
            </dl>
          </CollapsibleSection>
        </div>

        {/* Personality-change confirmation (Req 49.8/49.13/49.14/49.16) */}
        {confirming && personalityChanged && (
          <div className="border-t border-slate-100 bg-slate-50 px-5 py-4">
            <p className="text-sm font-semibold text-slate-900">确认更改 AI人格</p>
            <p className="mt-1 text-xs text-slate-600">
              此更改将影响 <span className="font-semibold text-slate-900">{affectedCampaignCount}</span> 个广告活动。
            </p>

            <fieldset className="mt-3 space-y-2">
              <legend className="text-xs font-medium text-slate-500">已有待执行操作的处理方式</legend>
              <label className="flex items-start gap-2 text-sm text-slate-700">
                <input
                  type="radio"
                  name="recompute"
                  checked={!recomputeExisting}
                  onChange={() => setRecomputeExisting(false)}
                  className="mt-0.5"
                />
                <span>保持现有待执行操作在原人格下继续</span>
              </label>
              <label className="flex items-start gap-2 text-sm text-slate-700">
                <input
                  type="radio"
                  name="recompute"
                  checked={recomputeExisting}
                  onChange={() => setRecomputeExisting(true)}
                  className="mt-0.5"
                />
                <span>按新人格重新计算（未提交的操作将被取代，已提交的将走取消流程）</span>
              </label>
            </fieldset>

            {bulk && (
              <label className="mt-3 flex items-start gap-2 text-sm text-slate-700">
                <input
                  type="checkbox"
                  checked={bulkConfirmed}
                  onChange={(e) => setBulkConfirmed(e.target.checked)}
                  className="mt-0.5"
                />
                <span>我已确认对多个广告活动批量更改 AI人格</span>
              </label>
            )}
          </div>
        )}

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 border-t border-slate-100 px-5 py-4">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50"
          >
            取消
          </button>
          <button
            type="button"
            onClick={handlePrimary}
            disabled={primaryDisabled}
            className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60"
          >
            {saving && <Loader2 size={14} className="animate-spin" />}
            {confirming && personalityChanged ? '确认并保存' : '保存设置'}
          </button>
        </div>
      </SheetContent>
    </Sheet>
  );
}

// ─── Persisted-config mode (Req 12.1 / 12.2 / 12.5 / 12.6) ───────────────────
//
// When a `storeId` is supplied the drawer self-manages persistence: it loads the
// current hosting configuration on open (GET, Req 12.1), lets the operator edit
// the store-level settings — default_personality, execution_mode,
// auto_execute_threshold, emergency_auto_action_enabled, shadow_mode and the
// safety boundary overrides — and persists them on submit (PUT, Req 12.2),
// surfacing backend validation failures (HOSTING_INVALID_*) inline (Req 12.6).
// A null persisted value means the setting is inherited from a higher level
// (Req 12.5), shown with a "继承自上级默认" hint until the operator overrides it.

/** Execution_Mode machine values with their fixed Chinese display copy (Req 7, 12.6). */
const EXECUTION_MODE_OPTIONS: { value: string; label: string }[] = [
  { value: 'observe_only', label: '仅观察' },
  { value: 'recommend_only', label: '仅建议' },
  { value: 'approval_required', label: '需审批' },
  { value: 'auto_execute', label: '自动执行' },
];

/**
 * Curated subset of SafetyBoundaryLimit names exposed for store-level override
 * (Req 12.6). Any additional keys already present in the loaded config are
 * appended so an existing override is never silently dropped.
 */
const BOUNDARY_LIMIT_FIELDS: { key: string; label: string; step: number }[] = [
  { key: 'MAX_BID', label: '最高竞价', step: 0.01 },
  { key: 'MIN_BID', label: '最低竞价', step: 0.01 },
  { key: 'MAX_DAILY_BUDGET', label: '最高日预算', step: 1 },
  { key: 'MIN_DAILY_BUDGET', label: '最低日预算', step: 1 },
  { key: 'MAX_BID_ADJUSTMENT_RATIO', label: '单次竞价调整上限（倍）', step: 0.05 },
];

interface PersistedHostingSettingsDrawerProps {
  open: boolean;
  onClose: () => void;
  /** When present the drawer loads/persists the store's hosting config (Req 12.1, 12.2). */
  storeId: string;
  title?: string;
  subtitle?: string;
  /** Called with the saved configuration the backend echoes back on success (Req 12.2). */
  onSaved?: (config: HostingConfig) => void;
}

interface ConfigDraft {
  personality: string | null;
  executionMode: string | null;
  autoExecuteThreshold: number | null;
  emergencyAutoActionEnabled: boolean | null;
  shadowMode: boolean | null;
  boundaryOverrides: Record<string, number>;
}

function draftFromConfig(config: HostingConfig): ConfigDraft {
  return {
    personality: config.default_personality ?? null,
    executionMode: config.execution_mode ?? null,
    autoExecuteThreshold: config.auto_execute_threshold ?? null,
    emergencyAutoActionEnabled: config.emergency_auto_action_enabled ?? null,
    shadowMode: config.shadow_mode ?? null,
    boundaryOverrides: { ...(config.boundary_overrides ?? {}) },
  };
}

/** Hint shown next to a setting whose persisted value is null (inherited, Req 12.5). */
function InheritedHint({ inherited }: { inherited: boolean }) {
  if (!inherited) return null;
  return <span className="ml-2 text-xs font-normal text-slate-400">继承自上级默认</span>;
}

export function PersistedHostingSettingsDrawer({
  open,
  onClose,
  storeId,
  title = 'AI 托管设置',
  subtitle,
  onSaved,
}: PersistedHostingSettingsDrawerProps) {
  const isMobile = useIsMobile();
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [draft, setDraft] = useState<ConfigDraft | null>(null);
  const [original, setOriginal] = useState<HostingConfig | null>(null);
  const [canary, setCanary] = useState<HostingCanaryRollout | null>(null);
  const [canaryLoading, setCanaryLoading] = useState(false);
  const [canarySaving, setCanarySaving] = useState(false);
  const [canaryError, setCanaryError] = useState<string | null>(null);

  // Load the current store config whenever the drawer opens (Req 12.1).
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setCanaryLoading(true);
    setLoadError(null);
    setSaveError(null);
    setCanaryError(null);
    fetchHostingConfig(storeId)
      .then((config) => {
        if (cancelled) return;
        setOriginal(config);
        setDraft(draftFromConfig(config));
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoadError(err instanceof Error ? err.message : '加载托管配置失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    fetchHostingCanary(storeId)
      .then((state) => {
        if (cancelled) return;
        setCanary(state);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setCanaryError(err instanceof Error ? err.message : '加载灰度发布配置失败');
      })
      .finally(() => {
        if (!cancelled) setCanaryLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, storeId]);

  if (!open) return null;

  function patch<K extends keyof ConfigDraft>(key: K, value: ConfigDraft[K]) {
    setDraft((prev) => (prev ? { ...prev, [key]: value } : prev));
  }

  function setBoundary(key: string, value: number | null) {
    setDraft((prev) => {
      if (!prev) return prev;
      const next = { ...prev.boundaryOverrides };
      if (value == null || Number.isNaN(value)) delete next[key];
      else next[key] = value;
      return { ...prev, boundaryOverrides: next };
    });
  }

  // Build the PUT body from the non-null (set) fields only; the backend overlays
  // just the supplied keys, so leaving a field inherited means omitting it.
  function buildInput(d: ConfigDraft): HostingConfigInput {
    const input: HostingConfigInput = {};
    if (d.personality != null) input.default_personality = d.personality;
    if (d.executionMode != null) input.execution_mode = d.executionMode;
    if (d.autoExecuteThreshold != null) input.auto_execute_threshold = d.autoExecuteThreshold;
    if (d.emergencyAutoActionEnabled != null) input.emergency_auto_action_enabled = d.emergencyAutoActionEnabled;
    if (d.shadowMode != null) input.shadow_mode = d.shadowMode;
    if (Object.keys(d.boundaryOverrides).length > 0) input.boundary_overrides = d.boundaryOverrides;
    return input;
  }

  async function handleSave() {
    if (!draft) return;
    setSaving(true);
    setSaveError(null);
    try {
      const saved = await saveHostingConfig(storeId, buildInput(draft));
      // Reflect the backend-confirmed values (Req 12.2) before notifying/closing.
      setOriginal(saved);
      setDraft(draftFromConfig(saved));
      onSaved?.(saved);
      onClose();
    } catch (err: unknown) {
      // Surface HOSTING_INVALID_* (and any other) validation failures inline (Req 12.6).
      setSaveError(err instanceof Error ? err.message : '保存托管配置失败');
    } finally {
      setSaving(false);
    }
  }

  async function updateCanary(action: 'enable' | 'disable' | 'add-store' | 'remove-store') {
    setCanarySaving(true);
    setCanaryError(null);
    try {
      const next =
        action === 'enable'
          ? await enableHostingCanary(storeId)
          : action === 'disable'
            ? await disableHostingCanary(storeId)
            : action === 'add-store'
              ? await addHostingCanaryStore(storeId)
              : await removeHostingCanaryStore(storeId);
      setCanary(next);
    } catch (err: unknown) {
      setCanaryError(err instanceof Error ? err.message : '保存灰度发布配置失败');
    } finally {
      setCanarySaving(false);
    }
  }

  const boundaryFields = useMemo(() => {
    const known = new Set(BOUNDARY_LIMIT_FIELDS.map((f) => f.key));
    const extra = Object.keys(draft?.boundaryOverrides ?? {})
      .filter((k) => !known.has(k))
      .map((k) => ({ key: k, label: k, step: 0.01 }));
    return [...BOUNDARY_LIMIT_FIELDS, ...extra];
  }, [draft]);

  const currentStoreInCanary = !!canary?.store_ids?.includes(storeId);

  return (
    <Sheet open={open} onOpenChange={(o) => { if (!o && !saving) onClose(); }}>
      <SheetContent
        side="right"
        aria-label={title}
        className={cn('gap-0 p-0 bg-white w-full max-w-full', !isMobile && 'sm:max-w-lg')}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <div>
            <SheetTitle className="text-lg font-semibold text-slate-900">{title}</SheetTitle>
            {subtitle && <p className="text-xs text-slate-500">{subtitle}</p>}
          </div>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-5">
          {loading && (
            <div className="flex items-center gap-2 py-10 text-sm text-slate-500">
              <Loader2 size={16} className="animate-spin" />
              正在加载托管配置…
            </div>
          )}

          {!loading && loadError && (
            <div className="my-6 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
              <AlertCircle size={16} className="mt-0.5 shrink-0" />
              <span>{loadError}</span>
            </div>
          )}

          {!loading && !loadError && draft && (
            <>
              <CollapsibleSection title="AI人格" open onToggle={() => { }}>
                <div className="grid grid-cols-3 gap-2">
                  {AI_PERSONALITY_VALUES.map((p) => (
                    <button
                      key={p}
                      type="button"
                      onClick={() => patch('personality', p)}
                      className={cn(
                        'rounded-lg border-2 px-3 py-2 text-sm font-medium transition-colors',
                        draft.personality === p
                          ? 'border-blue-500 bg-blue-50 text-blue-700'
                          : 'border-slate-200 text-slate-600 hover:border-slate-300',
                      )}
                    >
                      {AI_PERSONALITY_DISPLAY[p]}
                    </button>
                  ))}
                </div>
                <InheritedHint inherited={original?.default_personality == null} />
              </CollapsibleSection>

              <CollapsibleSection title="执行模式" open onToggle={() => { }}>
                <div className="grid grid-cols-2 gap-2">
                  {EXECUTION_MODE_OPTIONS.map((mode) => (
                    <button
                      key={mode.value}
                      type="button"
                      onClick={() => patch('executionMode', mode.value)}
                      className={cn(
                        'rounded-lg border-2 px-3 py-2 text-sm font-medium transition-colors',
                        draft.executionMode === mode.value
                          ? 'border-blue-500 bg-blue-50 text-blue-700'
                          : 'border-slate-200 text-slate-600 hover:border-slate-300',
                      )}
                    >
                      {mode.label}
                    </button>
                  ))}
                </div>
                <InheritedHint inherited={original?.execution_mode == null} />
              </CollapsibleSection>

              <CollapsibleSection title="自动执行阈值" open onToggle={() => { }}>
                <div className="flex items-center gap-3">
                  <input
                    type="number"
                    min={0}
                    max={1}
                    step={0.01}
                    aria-label="自动执行阈值"
                    value={draft.autoExecuteThreshold ?? ''}
                    onChange={(e) =>
                      patch('autoExecuteThreshold', e.target.value === '' ? null : Number(e.target.value))
                    }
                    placeholder="0.0 – 1.0"
                    className="h-10 w-32 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
                  />
                  <span className="text-xs text-slate-400">风险分低于该阈值的决策才会自动执行</span>
                </div>
                <InheritedHint inherited={original?.auto_execute_threshold == null} />
              </CollapsibleSection>

              <CollapsibleSection title="紧急自动处置" open onToggle={() => { }}>
                <label className="flex items-center gap-2 text-sm text-slate-700">
                  <input
                    type="checkbox"
                    checked={draft.emergencyAutoActionEnabled ?? false}
                    onChange={(e) => patch('emergencyAutoActionEnabled', e.target.checked)}
                  />
                  <span>触发安全边界时允许 AI 自动执行紧急处置</span>
                </label>
                <InheritedHint inherited={original?.emergency_auto_action_enabled == null} />
              </CollapsibleSection>

              <CollapsibleSection title="影子模式" open onToggle={() => { }}>
                <label className="flex items-center gap-2 text-sm text-slate-700">
                  <input
                    type="checkbox"
                    checked={draft.shadowMode ?? false}
                    onChange={(e) => patch('shadowMode', e.target.checked)}
                  />
                  <span>仅生成 AI 决策，不向 Amazon 写入任何更改</span>
                </label>
                <InheritedHint inherited={original?.shadow_mode == null} />
              </CollapsibleSection>

              <CollapsibleSection title="灰度发布" open onToggle={() => { }}>
                {canaryLoading ? (
                  <div className="flex items-center gap-2 text-sm text-slate-500">
                    <Loader2 size={14} className="animate-spin" />
                    正在加载灰度状态...
                  </div>
                ) : (
                  <div className="space-y-3">
                    <div className="flex items-center justify-between gap-3">
                      <div>
                        <p className="text-sm font-medium text-slate-800">
                          {canary?.enabled ? '已启用组织灰度' : '未启用组织灰度'}
                        </p>
                        <p className="text-xs text-slate-400">
                          启用后，只有灰度名单内店铺会进入自动执行链路。
                        </p>
                      </div>
                      <button
                        type="button"
                        disabled={canarySaving || canaryLoading}
                        onClick={() => updateCanary(canary?.enabled ? 'disable' : 'enable')}
                        className={cn(
                          'rounded-lg px-3 py-2 text-sm font-medium disabled:opacity-60',
                          canary?.enabled
                            ? 'border border-slate-200 text-slate-600 hover:bg-slate-50'
                            : 'bg-blue-600 text-white hover:bg-blue-700',
                        )}
                      >
                        {canary?.enabled ? '关闭灰度' : '启用灰度'}
                      </button>
                    </div>

                    <div className="flex items-center justify-between gap-3 rounded-lg border border-slate-100 px-3 py-2">
                      <div>
                        <p className="text-sm text-slate-700">当前店铺</p>
                        <p className="text-xs text-slate-400">
                          {currentStoreInCanary ? '已在灰度名单内' : '不在灰度名单内'}
                        </p>
                      </div>
                      <button
                        type="button"
                        disabled={canarySaving || canaryLoading}
                        onClick={() => updateCanary(currentStoreInCanary ? 'remove-store' : 'add-store')}
                        className="rounded-lg border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-60"
                      >
                        {currentStoreInCanary ? '移出名单' : '加入名单'}
                      </button>
                    </div>

                    <p className="text-xs text-slate-400">
                      灰度名单共 {canary?.store_ids?.length ?? 0} 个店铺。
                    </p>
                  </div>
                )}
              </CollapsibleSection>

              <CollapsibleSection title="安全边界覆盖" open onToggle={() => { }}>
                <p className="mb-2 text-xs text-slate-400">
                  仅可收紧（不可放宽）上级边界，留空表示沿用上级默认。
                </p>
                <div className="space-y-2">
                  {boundaryFields.map((field) => (
                    <div key={field.key} className="flex items-center justify-between gap-3">
                      <span className="text-sm text-slate-700">{field.label}</span>
                      <input
                        type="number"
                        step={field.step}
                        aria-label={field.label}
                        value={draft.boundaryOverrides[field.key] ?? ''}
                        onChange={(e) =>
                          setBoundary(field.key, e.target.value === '' ? null : Number(e.target.value))
                        }
                        placeholder="—"
                        className="h-9 w-28 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
                      />
                    </div>
                  ))}
                </div>
              </CollapsibleSection>
            </>
          )}
        </div>

        {/* Inline backend-validation error (Req 12.6) */}
        {saveError && (
          <div
            role="alert"
            className="mx-5 mb-2 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
          >
            <AlertCircle size={16} className="mt-0.5 shrink-0" />
            <span>{saveError}</span>
          </div>
        )}

        {canaryError && (
          <div
            role="alert"
            className="mx-5 mb-2 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
          >
            <AlertCircle size={16} className="mt-0.5 shrink-0" />
            <span>{canaryError}</span>
          </div>
        )}

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 border-t border-slate-100 px-5 py-4">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50"
          >
            取消
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={saving || loading || !!loadError || !draft}
            className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60"
          >
            {saving && <Loader2 size={14} className="animate-spin" />}
            保存设置
          </button>
        </div>
      </SheetContent>
    </Sheet>
  );
}

// ─── Public dispatcher ───────────────────────────────────────────────────────
//
// Backwards-compatible entry point. Without a `storeId` it renders the existing
// personality/optimization-goal drawer driven by the caller's `onSave` callback.
// With a `storeId` it renders the self-persisting store-config drawer (Req 12).

export type HostingSettingsDrawerProps =
  | (PersonalitySettingsDrawerProps & { storeId?: undefined })
  | PersistedHostingSettingsDrawerProps;

export function HostingSettingsDrawer(props: HostingSettingsDrawerProps) {
  if (props.storeId) {
    return <PersistedHostingSettingsDrawer {...(props as PersistedHostingSettingsDrawerProps)} />;
  }
  return <PersonalitySettingsDrawer {...(props as PersonalitySettingsDrawerProps)} />;
}

export default HostingSettingsDrawer;
