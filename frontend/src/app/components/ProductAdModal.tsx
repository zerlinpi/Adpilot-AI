// AdPilot AI — 单产品广告创建弹窗 (multistore-ai-ads-operations)
//
// Per-product AI ad creation modal. Opened from a product / 商品管理 entry, it lets
// a non-expert operator create a keyword ad campaign for a single product by
// filling in a few understandable parameters (Req 1.2): 预算 / 预算类型 / AI 人格 /
// 是否托管 / 安全边界 (目标 ACoS、出价上下限、预算上下限) / 执行模式 (可不选).
//
// The "创建广告" entry is rendered ONLY when the logged-in account holds
// Platform_Access for the product's platform family AND both `advertising:view`
// and `advertising:manage` (Req 1.1). This is an experience-level gate; the
// authoritative deny is the backend 403 / validation on POST
// /api/product-ads/campaign.
//
// Before submitting, the form runs lightweight client-side validation (Req 1.4):
// non-positive budget / target ACoS / bid / budget bounds are rejected, as is any
// upper bound smaller than its lower bound. The backend re-validates and is the
// source of truth; its errors are surfaced inline. The execution mode may be left
// unselected — the backend then falls back to `observe_only` (Req 1.3).

import { useMemo, useState } from 'react';
import { Megaphone } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Switch } from './ui/switch';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './ui/select';
import { usePermissions } from '../lib/PermissionContext';
import { hasPlatformAccess } from '../lib/navVisibility';
import { AI_PERSONALITY_VALUES, AI_PERSONALITY_DISPLAY } from '../lib/translateMachineValue';
import { FlowGuide } from './onboarding/FlowGuide';
import {
  createProductAdCampaign,
  type ProductAdCampaignRequest,
  type ProductAdCampaignResult,
} from '../lib/api';

/** Budget_Type machine values with their fixed Chinese display copy. */
export const BUDGET_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'daily', label: '每日预算' },
  { value: 'lifetime', label: '总预算' },
];

/** Sentinel select value meaning "执行模式 not chosen" (backend uses observe_only, Req 1.3). */
export const EXECUTION_MODE_UNSET = '__unset__';

/** Execution_Mode machine values offered in the modal; the first is the "不选" option. */
export const EXECUTION_MODE_OPTIONS: { value: string; label: string }[] = [
  { value: EXECUTION_MODE_UNSET, label: '不选（默认：仅观察）' },
  { value: 'observe_only', label: '仅观察' },
  { value: 'recommend_only', label: '仅建议' },
  { value: 'approval_required', label: '需审批' },
  { value: 'auto_execute', label: '自动执行' },
];

/** The raw (string-backed) form state collected by the modal. */
export interface ProductAdFormValues {
  budget: string;
  budgetType: string;
  personality: string;
  hostingEnabled: boolean;
  targetAcos: string;
  bidMin: string;
  bidMax: string;
  budgetMin: string;
  budgetMax: string;
  /** {@link EXECUTION_MODE_UNSET} when the operator leaves it unselected. */
  executionMode: string;
}

/** A single field-level validation error surfaced next to its input. */
export interface ProductAdFieldError {
  field: keyof ProductAdFormValues | string;
  message: string;
}

/** Initial, empty-ish form values with sensible defaults. */
export function emptyProductAdForm(): ProductAdFormValues {
  return {
    budget: '',
    budgetType: 'daily',
    personality: 'balanced',
    hostingEnabled: false,
    targetAcos: '',
    bidMin: '',
    bidMax: '',
    budgetMin: '',
    budgetMax: '',
    executionMode: EXECUTION_MODE_UNSET,
  };
}

/** Parse an optional numeric field; blank/whitespace yields undefined. */
function parseOptionalNumber(raw: string): number | undefined {
  const trimmed = (raw ?? '').trim();
  if (trimmed === '') return undefined;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : NaN;
}

/**
 * Lightweight client-side validation mirroring the backend contract (Req 1.4):
 * the budget is required and must be positive; any supplied target ACoS, bid
 * bound, or budget bound must be positive; and each upper bound must be no
 * smaller than its lower bound. Returns one error per offending field; an empty
 * array means the form may be submitted. The backend re-validates regardless.
 */
export function validateProductAdForm(values: ProductAdFormValues): ProductAdFieldError[] {
  const errors: ProductAdFieldError[] = [];

  // Budget is required and must be a positive number.
  const budget = parseOptionalNumber(values.budget);
  if (budget === undefined) {
    errors.push({ field: 'budget', message: '请填写预算金额' });
  } else if (Number.isNaN(budget)) {
    errors.push({ field: 'budget', message: '预算必须是数字' });
  } else if (budget <= 0) {
    errors.push({ field: 'budget', message: '预算必须为正数' });
  }

  // Optional positive-only fields.
  const positiveOnly: { field: keyof ProductAdFormValues; label: string }[] = [
    { field: 'targetAcos', label: '目标 ACoS' },
    { field: 'bidMin', label: '最低出价' },
    { field: 'bidMax', label: '最高出价' },
    { field: 'budgetMin', label: '最低预算' },
    { field: 'budgetMax', label: '最高预算' },
  ];
  const parsed: Partial<Record<keyof ProductAdFormValues, number>> = {};
  for (const { field, label } of positiveOnly) {
    const value = parseOptionalNumber(values[field] as string);
    if (value === undefined) continue;
    if (Number.isNaN(value)) {
      errors.push({ field, message: `${label}必须是数字` });
    } else if (value <= 0) {
      errors.push({ field, message: `${label}必须为正数` });
    } else {
      parsed[field] = value;
    }
  }

  // Upper bound must not be smaller than its lower bound (only when both valid).
  if (parsed.bidMin !== undefined && parsed.bidMax !== undefined && parsed.bidMax < parsed.bidMin) {
    errors.push({ field: 'bidMax', message: '最高出价不能小于最低出价' });
  }
  if (
    parsed.budgetMin !== undefined &&
    parsed.budgetMax !== undefined &&
    parsed.budgetMax < parsed.budgetMin
  ) {
    errors.push({ field: 'budgetMax', message: '最高预算不能小于最低预算' });
  }

  return errors;
}

/**
 * Build the API request from validated form values. `budgetType` / `personality`
 * fall through as-is; optional numeric fields are omitted when blank; the
 * execution mode is omitted when left unselected so the backend applies its
 * `observe_only` default (Req 1.3).
 */
export function toProductAdRequest(
  values: ProductAdFormValues,
  target: { storeId: string; productId?: string; parentAsin?: string },
): ProductAdCampaignRequest {
  const num = (raw: string) => {
    const v = parseOptionalNumber(raw);
    return v === undefined || Number.isNaN(v) ? undefined : v;
  };
  return {
    storeId: target.storeId,
    productId: target.productId,
    parentAsin: target.parentAsin,
    budget: Number(values.budget),
    budgetType: values.budgetType || undefined,
    personality: values.personality || undefined,
    hostingEnabled: values.hostingEnabled,
    targetAcos: num(values.targetAcos),
    bidMin: num(values.bidMin),
    bidMax: num(values.bidMax),
    budgetMin: num(values.budgetMin),
    budgetMax: num(values.budgetMax),
    executionMode:
      values.executionMode && values.executionMode !== EXECUTION_MODE_UNSET
        ? values.executionMode
        : undefined,
  };
}

export interface ProductAdModalProps {
  /** Store the product belongs to (scopes the created campaign). */
  storeId: string;
  /** Product /商品 identifier to link the campaign to (Req 1.5). */
  productId?: string;
  /** Parent ASIN to link the campaign to, when product is keyed by ASIN. */
  parentAsin?: string;
  /** Display name shown in the dialog header for context. */
  productName?: string;
  /** Platform family of the product's store; gates the entry (Req 1.1). */
  platformFamily: string;
  /** Optional callback invoked with the backend result on success (Req 1.10). */
  onCreated?: (result: ProductAdCampaignResult) => void;
  /** Optional override of the entry button label. */
  triggerLabel?: string;
}

/**
 * Permission-gated per-product ad creation entry + modal. Renders a "创建广告"
 * button only when the account may act on this product's platform family and
 * holds `advertising:view` + `advertising:manage` (Req 1.1); clicking it opens
 * the parameter-collection dialog (Req 1.2).
 */
export function ProductAdModal({
  storeId,
  productId,
  parentAsin,
  productName,
  platformFamily,
  onCreated,
  triggerLabel = '创建广告',
}: ProductAdModalProps) {
  const { can, platformAccess } = usePermissions();

  // Experience-level gate (Req 1.1). The backend 403 is the real boundary.
  const canSeeEntry =
    hasPlatformAccess(platformFamily, platformAccess) &&
    can('advertising:view') &&
    can('advertising:manage');

  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<ProductAdFormValues>(emptyProductAdForm);
  const [errors, setErrors] = useState<ProductAdFieldError[]>([]);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const errorByField = useMemo(() => {
    const map: Record<string, string> = {};
    for (const e of errors) {
      if (!(e.field in map)) map[e.field as string] = e.message;
    }
    return map;
  }, [errors]);

  if (!canSeeEntry) return null;

  function patch<K extends keyof ProductAdFormValues>(key: K, value: ProductAdFormValues[K]) {
    setValues((prev) => ({ ...prev, [key]: value }));
  }

  function resetAndClose() {
    setValues(emptyProductAdForm());
    setErrors([]);
    setSubmitError(null);
    setOpen(false);
  }

  async function handleSubmit() {
    const found = validateProductAdForm(values);
    setErrors(found);
    setSubmitError(null);
    if (found.length > 0) return;

    setSubmitting(true);
    try {
      const result = await createProductAdCampaign(
        toProductAdRequest(values, { storeId, productId, parentAsin }),
      );
      onCreated?.(result);
      resetAndClose();
    } catch (err: unknown) {
      // Surface backend validation / 403 / other failures inline (Req 1.4, 1.8).
      setSubmitError(err instanceof Error ? err.message : '创建广告失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  }

  const fieldError = (field: keyof ProductAdFormValues) =>
    errorByField[field] ? (
      <p className="mt-1 text-xs text-red-600">{errorByField[field]}</p>
    ) : null;

  return (
    <>
      <Button
        type="button"
        size="sm"
        variant="outline"
        onClick={() => setOpen(true)}
        className="gap-1.5"
      >
        <Megaphone className="size-4" />
        {triggerLabel}
      </Button>

      <Dialog
        open={open}
        onOpenChange={(next) => {
          if (!next) resetAndClose();
          else setOpen(true);
        }}
      >
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>创建产品广告</DialogTitle>
            <DialogDescription>
              {productName ? `为「${productName}」` : '为该产品'}
              创建一个关键词广告活动。填几个能看懂的参数即可，无需理解底层广告结构。
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-5 py-1">
            {/* Step-by-step guidance: single-product ad creation (Req 8.3) */}
            <FlowGuide
              title="操作指引：单产品广告创建"
              intro="填几个能看懂的参数即可，无需理解底层广告结构"
              storageKey="product-ad-modal"
              steps={[
                {
                  title: '填写预算与预算类型',
                  detail: '设置该产品广告的预算金额，并选择每日预算或总预算。',
                },
                {
                  title: '选择 AI 人格',
                  detail: '决定 AI 优化风格（保守 / 均衡 / 激进）。',
                },
                {
                  title: '设置安全边界（可选）',
                  detail: '设定目标 ACoS、出价上下限、预算上下限等硬约束，AI 调整不会越过这些上下限；留空表示沿用上级默认。',
                },
                {
                  title: '选择是否托管与执行模式',
                  detail: '开启托管后 AI 按所选人格与安全边界托管该活动；执行模式可不选，默认为「仅观察」。',
                },
                {
                  title: '提交创建',
                  detail: '系统会创建关键词广告活动并关联到该产品；对亚马逊的写入经异步队列提交，不会即时同步调用。',
                },
              ]}
            />

            {/* 预算 + 预算类型 */}
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <Label htmlFor="pa-budget">
                  预算金额 <span className="text-red-500">*</span>
                </Label>
                <Input
                  id="pa-budget"
                  type="number"
                  min={0}
                  step="0.01"
                  inputMode="decimal"
                  value={values.budget}
                  onChange={(e) => patch('budget', e.target.value)}
                  placeholder="例如 50"
                  aria-invalid={!!errorByField.budget}
                />
                {fieldError('budget')}
              </div>
              <div>
                <Label htmlFor="pa-budget-type">预算类型</Label>
                <Select
                  value={values.budgetType}
                  onValueChange={(v) => patch('budgetType', v)}
                >
                  <SelectTrigger id="pa-budget-type">
                    <SelectValue placeholder="选择预算类型" />
                  </SelectTrigger>
                  <SelectContent>
                    {BUDGET_TYPE_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            {/* AI 人格 + 执行模式 */}
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <Label htmlFor="pa-personality">AI 人格</Label>
                <Select
                  value={values.personality}
                  onValueChange={(v) => patch('personality', v)}
                >
                  <SelectTrigger id="pa-personality">
                    <SelectValue placeholder="选择 AI 人格" />
                  </SelectTrigger>
                  <SelectContent>
                    {AI_PERSONALITY_VALUES.map((p) => (
                      <SelectItem key={p} value={p}>
                        {AI_PERSONALITY_DISPLAY[p]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="pa-exec-mode">执行模式（可不选）</Label>
                <Select
                  value={values.executionMode}
                  onValueChange={(v) => patch('executionMode', v)}
                >
                  <SelectTrigger id="pa-exec-mode">
                    <SelectValue placeholder="不选（默认：仅观察）" />
                  </SelectTrigger>
                  <SelectContent>
                    {EXECUTION_MODE_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            {/* 是否托管 */}
            <div className="flex items-center justify-between rounded-lg border border-slate-200 px-4 py-3">
              <div>
                <Label htmlFor="pa-hosting" className="cursor-pointer">
                  开启 AI 托管
                </Label>
                <p className="mt-0.5 text-xs text-slate-500">
                  开启后 AI 将按所选人格与安全边界托管该活动。
                </p>
              </div>
              <Switch
                id="pa-hosting"
                checked={values.hostingEnabled}
                onCheckedChange={(v) => patch('hostingEnabled', v)}
              />
            </div>

            {/* 安全边界 */}
            <div className="space-y-3 rounded-lg border border-slate-200 p-4">
              <div>
                <p className="text-sm font-medium text-slate-700">安全边界</p>
                <p className="text-xs text-slate-500">
                  设定硬约束，AI 调整不会越过这些上下限。留空表示沿用上级默认。
                </p>
              </div>
              <div>
                <Label htmlFor="pa-acos">目标 ACoS（%）</Label>
                <Input
                  id="pa-acos"
                  type="number"
                  min={0}
                  step="0.1"
                  inputMode="decimal"
                  value={values.targetAcos}
                  onChange={(e) => patch('targetAcos', e.target.value)}
                  placeholder="例如 25"
                  aria-invalid={!!errorByField.targetAcos}
                />
                {fieldError('targetAcos')}
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <Label htmlFor="pa-bid-min">最低出价</Label>
                  <Input
                    id="pa-bid-min"
                    type="number"
                    min={0}
                    step="0.01"
                    inputMode="decimal"
                    value={values.bidMin}
                    onChange={(e) => patch('bidMin', e.target.value)}
                    aria-invalid={!!errorByField.bidMin}
                  />
                  {fieldError('bidMin')}
                </div>
                <div>
                  <Label htmlFor="pa-bid-max">最高出价</Label>
                  <Input
                    id="pa-bid-max"
                    type="number"
                    min={0}
                    step="0.01"
                    inputMode="decimal"
                    value={values.bidMax}
                    onChange={(e) => patch('bidMax', e.target.value)}
                    aria-invalid={!!errorByField.bidMax}
                  />
                  {fieldError('bidMax')}
                </div>
                <div>
                  <Label htmlFor="pa-budget-min">最低预算</Label>
                  <Input
                    id="pa-budget-min"
                    type="number"
                    min={0}
                    step="1"
                    inputMode="decimal"
                    value={values.budgetMin}
                    onChange={(e) => patch('budgetMin', e.target.value)}
                    aria-invalid={!!errorByField.budgetMin}
                  />
                  {fieldError('budgetMin')}
                </div>
                <div>
                  <Label htmlFor="pa-budget-max">最高预算</Label>
                  <Input
                    id="pa-budget-max"
                    type="number"
                    min={0}
                    step="1"
                    inputMode="decimal"
                    value={values.budgetMax}
                    onChange={(e) => patch('budgetMax', e.target.value)}
                    aria-invalid={!!errorByField.budgetMax}
                  />
                  {fieldError('budgetMax')}
                </div>
              </div>
            </div>

            {submitError && (
              <p role="alert" className="text-sm text-red-600">
                {submitError}
              </p>
            )}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={resetAndClose} disabled={submitting}>
              取消
            </Button>
            <Button type="button" onClick={handleSubmit} disabled={submitting}>
              {submitting ? '创建中…' : '创建广告'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

export default ProductAdModal;
