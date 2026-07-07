import { useState, useEffect, useMemo } from 'react';
import { Plus, Filter, Zap, Link2, Trash2 } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import {
  fetchRuleTemplates,
  createRuleTemplate,
  bulkRuleTemplateOp,
  listRuleTemplateLinks,
  linkRuleTemplateObjectsBulk,
  unlinkRuleTemplateObject,
  fetchCampaigns,
  fetchStoreProductOptions,
  type RuleTemplate,
  type RuleTemplateCreateInput,
  type RuleTemplateLink,
  type CampaignVo,
  type StoreProductOption,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { useStoreContext } from '../lib/StoreContext';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';
import { Button } from '../components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// ─── Option catalogs (drive both the form and the human-readable summary) ──────

/** Template types (模板类型) operators can pick when creating a rule. */
const TEMPLATE_TYPES: { value: string; label: string }[] = [
  { value: 'bid_optimization', label: '竞价优化' },
  { value: 'budget_optimization', label: '预算优化' },
  { value: 'keyword_harvest', label: '关键词收割' },
  { value: 'negative_keyword', label: '关键词否定' },
  { value: 'ad_structure', label: '广告结构优化' },
];

/** Metrics available in a condition leaf — keys map to evaluator metric names. */
const METRIC_OPTIONS: { value: string; label: string }[] = [
  { value: 'acos', label: 'ACoS' },
  { value: 'tacos', label: 'TACoS' },
  { value: 'spend', label: '花费' },
  { value: 'sales', label: '销售额' },
  { value: 'orders', label: '订单数' },
  { value: 'clicks', label: '点击量' },
  { value: 'impressions', label: '曝光量' },
  { value: 'ctr', label: '点击率' },
  { value: 'conversion_rate', label: '转化率' },
];

/** Comparison operators accepted by the backend evaluator grammar. */
const OPERATOR_OPTIONS: { value: string; label: string }[] = [
  { value: 'gt', label: '>' },
  { value: 'gte', label: '≥' },
  { value: 'lt', label: '<' },
  { value: 'lte', label: '≤' },
  { value: 'eq', label: '=' },
  { value: 'ne', label: '≠' },
];

/** Action types and whether they take a numeric amount parameter. */
const ACTION_OPTIONS: { value: string; label: string; needsAmount: boolean; amountLabel?: string }[] = [
  { value: 'adjust_bid', label: '调整竞价', needsAmount: true, amountLabel: '竞价调整幅度 (%)' },
  { value: 'adjust_budget', label: '调整预算', needsAmount: true, amountLabel: '预算调整幅度 (%)' },
  { value: 'add_negative_keyword', label: '添加否定关键词', needsAmount: false },
  { value: 'pause', label: '暂停', needsAmount: false },
  { value: 'harvest_keyword', label: '收割关键词', needsAmount: false },
];

const TEMPLATE_TYPE_LABELS: Record<string, string> = Object.fromEntries(
  TEMPLATE_TYPES.map((t) => [t.value, t.label]),
);
const METRIC_LABELS: Record<string, string> = Object.fromEntries(
  METRIC_OPTIONS.map((m) => [m.value, m.label]),
);
const OPERATOR_LABELS: Record<string, string> = Object.fromEntries(
  OPERATOR_OPTIONS.map((o) => [o.value, o.label]),
);
const ACTION_LABELS: Record<string, string> = Object.fromEntries(
  ACTION_OPTIONS.map((a) => [a.value, a.label]),
);

const typeFilterOptions = ['全部', ...TEMPLATE_TYPES.map((t) => t.value)];
const statusFilterOptions = ['全部', 'enabled', 'disabled'];

/** Render the stored condition JSON as a short readable phrase. */
function summarizeCondition(condition: any): string {
  if (!condition || typeof condition !== 'object') return '—';
  if (condition.metric && condition.op) {
    const metric = METRIC_LABELS[condition.metric] || condition.metric;
    const op = OPERATOR_LABELS[condition.op] || condition.op;
    return `${metric} ${op} ${condition.value ?? ''}`.trim();
  }
  if (condition.op === 'and' || condition.op === 'or') {
    const joiner = condition.op === 'and' ? ' 且 ' : ' 或 ';
    return (condition.conditions || []).map(summarizeCondition).join(joiner) || condition.op;
  }
  if (condition.op === 'not') return `非(${summarizeCondition(condition.condition)})`;
  return '—';
}

/** Render the stored action JSON as a short readable phrase. */
function summarizeAction(action: any): string {
  if (!action || typeof action !== 'object' || !action.type) return '—';
  const label = ACTION_LABELS[action.type] || action.type;
  const amount = action.params?.amount;
  return amount != null ? `${label} (${amount})` : label;
}

// ─── Create Template Modal ─────────────────────────────────────────────────────
function CreateTemplateModal({
  storeId,
  onClose,
  onCreated,
}: {
  storeId: string;
  onClose: () => void;
  onCreated: (created: RuleTemplate) => void;
}) {
  const [name, setName] = useState('');
  const [templateType, setTemplateType] = useState(TEMPLATE_TYPES[0].value);
  const [metric, setMetric] = useState(METRIC_OPTIONS[0].value);
  const [operator, setOperator] = useState(OPERATOR_OPTIONS[0].value);
  const [threshold, setThreshold] = useState('');
  const [actionType, setActionType] = useState(ACTION_OPTIONS[0].value);
  const [actionAmount, setActionAmount] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const selectedAction = ACTION_OPTIONS.find((a) => a.value === actionType)!;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);
    setFormError(null);

    // FE validation (Req 9.3, 25.5): block submit on missing/invalid fields.
    if (!name.trim()) {
      setFieldError('请输入模板名称');
      return;
    }
    if (threshold.trim() === '' || Number.isNaN(Number(threshold))) {
      setFieldError('请输入有效的触发条件阈值');
      return;
    }
    if (selectedAction.needsAmount && (actionAmount.trim() === '' || Number.isNaN(Number(actionAmount)))) {
      setFieldError('请输入有效的执行动作数值');
      return;
    }

    const payload: RuleTemplateCreateInput = {
      storeId,
      name: name.trim(),
      templateType,
      // Condition leaf in the evaluator grammar: { metric, op, value }.
      condition: { metric, op: operator, value: Number(threshold) },
      // Action: { type, params }.
      action: {
        type: actionType,
        params: selectedAction.needsAmount ? { amount: Number(actionAmount) } : {},
      },
      status: 'enabled',
    };

    try {
      setSubmitting(true);
      const created = await createRuleTemplate(payload);
      onCreated(created);
    } catch (err: any) {
      setFormError(err?.message || '创建规则模板失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose(); }}>
      <DialogContent className="block gap-0 p-0 w-full sm:max-w-lg rounded-xl border-0 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <DialogTitle className="text-lg font-semibold text-slate-900">新建规则模板</DialogTitle>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">模板名称</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：高 ACoS 自动降价"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">模板类型</label>
            <select
              value={templateType}
              onChange={(e) => setTemplateType(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            >
              {TEMPLATE_TYPES.map((t) => (
                <option key={t.value} value={t.value}>{t.label}</option>
              ))}
            </select>
          </div>

          {/* Trigger condition */}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">触发条件</label>
            <div className="grid grid-cols-3 gap-2">
              <select
                value={metric}
                onChange={(e) => setMetric(e.target.value)}
                className="h-10 rounded-lg border border-slate-200 px-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              >
                {METRIC_OPTIONS.map((m) => (
                  <option key={m.value} value={m.value}>{m.label}</option>
                ))}
              </select>
              <select
                value={operator}
                onChange={(e) => setOperator(e.target.value)}
                className="h-10 rounded-lg border border-slate-200 px-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              >
                {OPERATOR_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
              <input
                type="number"
                step="any"
                value={threshold}
                onChange={(e) => setThreshold(e.target.value)}
                placeholder="阈值"
                className="h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              />
            </div>
          </div>

          {/* Action */}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">执行动作</label>
            <select
              value={actionType}
              onChange={(e) => setActionType(e.target.value)}
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            >
              {ACTION_OPTIONS.map((a) => (
                <option key={a.value} value={a.value}>{a.label}</option>
              ))}
            </select>
          </div>

          {selectedAction.needsAmount && (
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">{selectedAction.amountLabel}</label>
              <input
                type="number"
                step="any"
                value={actionAmount}
                onChange={(e) => setActionAmount(e.target.value)}
                placeholder="例如：-10"
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              />
            </div>
          )}

          {fieldError && <p className="text-sm text-red-500">{fieldError}</p>}
          {formError && <p className="text-sm text-red-500">{formError}</p>}

          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-lg border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 disabled:opacity-60"
            >
              {submitting ? '创建中...' : '创建'}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ─── Link Objects Modal (应用到 / 关联对象) ──────────────────────────────────────

const OBJECT_TYPE_LABELS: Record<string, string> = {
  campaign: '广告活动',
  product: '产品',
  target: '投放对象',
  keyword: '关键词',
};

/**
 * Manage which objects a rule template applies to (Req 25.3). Lets the operator
 * link MANY campaigns or products at once via POST /{id}/links, lists the
 * currently linked objects with their count, and supports unlinking. Linking a
 * product is resolved to the product's campaigns by the backend, so the
 * evaluator runs the rule against every campaign the product owns.
 */
function LinkObjectsModal({
  storeId,
  template,
  onClose,
  onChanged,
}: {
  storeId: string;
  template: RuleTemplate;
  onClose: () => void;
  onChanged: (templateId: string, linkedCount: number) => void;
}) {
  const [links, setLinks] = useState<RuleTemplateLink[]>([]);
  const [loadingLinks, setLoadingLinks] = useState(true);
  const [objectType, setObjectType] = useState<'campaign' | 'product'>('campaign');
  const [campaigns, setCampaigns] = useState<CampaignVo[]>([]);
  const [products, setProducts] = useState<StoreProductOption[]>([]);
  const [optionsLoading, setOptionsLoading] = useState(true);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [search, setSearch] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function reloadLinks() {
    try {
      setLoadingLinks(true);
      const result = await listRuleTemplateLinks(template.id);
      setLinks(result ?? []);
      onChanged(template.id, (result ?? []).length);
    } catch (e: any) {
      setError(e?.message || '加载关联对象失败');
    } finally {
      setLoadingLinks(false);
    }
  }

  useEffect(() => {
    reloadLinks();
    (async () => {
      try {
        setOptionsLoading(true);
        const [campaignPage, productList] = await Promise.all([
          fetchCampaigns({ storeId, pageSize: 500 }),
          fetchStoreProductOptions(storeId),
        ]);
        setCampaigns(campaignPage?.items ?? []);
        setProducts(productList ?? []);
      } catch (e: any) {
        setError(e?.message || '加载可关联对象失败');
      } finally {
        setOptionsLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [template.id, storeId]);

  // Campaign ids already linked, so the picker can hide / mark them.
  const linkedCampaignIds = useMemo(
    () => new Set(links.filter((l) => l.objectType === 'campaign').map((l) => l.objectId)),
    [links],
  );

  const pickerItems = useMemo(() => {
    const term = search.trim().toLowerCase();
    if (objectType === 'campaign') {
      return campaigns
        .filter((c) => !term || c.name.toLowerCase().includes(term))
        .map((c) => ({ id: c.id, label: c.name, hint: c.campaignType || '', linked: linkedCampaignIds.has(c.id) }));
    }
    return products
      .filter((p) => !term || p.name.toLowerCase().includes(term) || (p.asin || '').toLowerCase().includes(term))
      .map((p) => ({ id: p.id, label: p.name, hint: p.asin || p.sku || '', linked: false }));
  }, [objectType, campaigns, products, search, linkedCampaignIds]);

  function toggleSelect(id: string) {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }

  function switchType(t: 'campaign' | 'product') {
    setObjectType(t);
    setSelectedIds([]);
    setSearch('');
  }

  async function handleLink() {
    if (selectedIds.length === 0) return;
    try {
      setSaving(true);
      setError(null);
      await linkRuleTemplateObjectsBulk(
        template.id,
        selectedIds.map((objectId) => ({ objectType, objectId })),
      );
      setSelectedIds([]);
      await reloadLinks();
    } catch (e: any) {
      setError(e?.message || '关联失败');
    } finally {
      setSaving(false);
    }
  }

  async function handleUnlink(linkId: string) {
    try {
      setSaving(true);
      setError(null);
      await unlinkRuleTemplateObject(template.id, linkId);
      await reloadLinks();
    } catch (e: any) {
      setError(e?.message || '取消关联失败');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !saving) onClose(); }}>
      <DialogContent className="flex flex-col gap-0 p-0 w-full sm:max-w-2xl max-h-[90vh] rounded-xl border-0 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <div>
            <DialogTitle className="text-lg font-semibold text-slate-900">应用到 / 关联对象</DialogTitle>
            <p className="text-xs text-slate-500 mt-0.5">规则模板「{template.name}」将对每个关联对象生效</p>
          </div>
        </div>

        <div className="px-5 py-4 space-y-4 overflow-y-auto">
          {/* Current links */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-medium text-slate-700">已关联对象 ({links.length})</h3>
            </div>
            {loadingLinks ? (
              <p className="text-sm text-slate-400">加载中...</p>
            ) : links.length === 0 ? (
              <p className="text-sm text-slate-400">尚未关联任何对象，从下方选择并关联。</p>
            ) : (
              <ul className="space-y-1.5 max-h-40 overflow-y-auto">
                {links.map((link) => (
                  <li
                    key={link.id}
                    className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2 text-sm"
                  >
                    <span className="flex items-center gap-2 min-w-0">
                      <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-slate-100 text-slate-600 shrink-0">
                        {OBJECT_TYPE_LABELS[link.objectType] || link.objectType}
                      </span>
                      <span className="truncate text-slate-800">{link.objectName || link.objectId}</span>
                    </span>
                    <button
                      onClick={() => handleUnlink(link.id)}
                      disabled={saving}
                      className="text-slate-400 hover:text-red-600 disabled:opacity-50 shrink-0 ml-2"
                      aria-label="取消关联"
                      title="取消关联"
                    >
                      <Trash2 size={15} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* Picker */}
          <div className="border-t border-slate-100 pt-4">
            <div className="flex items-center gap-2 mb-2">
              <h3 className="text-sm font-medium text-slate-700">添加关联</h3>
              <div className="inline-flex rounded-lg border border-slate-200 overflow-hidden ml-auto text-xs">
                {(['campaign', 'product'] as const).map((t) => (
                  <button
                    key={t}
                    onClick={() => switchType(t)}
                    className={`px-3 py-1.5 ${objectType === t ? 'bg-indigo-600 text-white' : 'bg-white text-slate-600 hover:bg-slate-50'}`}
                  >
                    {OBJECT_TYPE_LABELS[t]}
                  </button>
                ))}
              </div>
            </div>

            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={objectType === 'campaign' ? '搜索广告活动名称' : '搜索产品名称 / ASIN'}
              className="w-full h-9 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 mb-2"
            />

            {optionsLoading ? (
              <p className="text-sm text-slate-400">加载可关联对象...</p>
            ) : pickerItems.length === 0 ? (
              <p className="text-sm text-slate-400">没有可关联的{OBJECT_TYPE_LABELS[objectType]}。</p>
            ) : (
              <ul className="max-h-48 overflow-y-auto space-y-1 border border-slate-100 rounded-lg p-2">
                {pickerItems.map((item) => (
                  <li key={item.id}>
                    <label
                      className={`flex items-center gap-2 px-2 py-1.5 rounded text-sm cursor-pointer hover:bg-slate-50 ${item.linked ? 'opacity-60' : ''}`}
                    >
                      <input
                        type="checkbox"
                        checked={selectedIds.includes(item.id)}
                        onChange={() => toggleSelect(item.id)}
                      />
                      <span className="truncate text-slate-800">{item.label}</span>
                      {item.hint && <span className="text-xs text-slate-400 ml-auto shrink-0">{item.hint}</span>}
                      {item.linked && (
                        <span className="text-xs text-emerald-600 shrink-0 ml-1">已关联</span>
                      )}
                    </label>
                  </li>
                ))}
              </ul>
            )}

            {objectType === 'product' && (
              <p className="text-xs text-slate-400 mt-1.5">关联产品时，系统会自动将规则应用到该产品的所有广告活动。</p>
            )}
          </div>

          {error && <p className="text-sm text-red-500">{error}</p>}
        </div>

        <div className="flex items-center justify-between gap-3 border-t border-slate-100 px-5 py-4">
          <span className="text-sm text-slate-500">已选 {selectedIds.length} 项</span>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-lg border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50"
            >
              关闭
            </button>
            <button
              type="button"
              onClick={handleLink}
              disabled={saving || selectedIds.length === 0}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 disabled:opacity-60"
            >
              <Link2 size={16} />
              {saving ? '处理中...' : `关联选中 (${selectedIds.length})`}
            </button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

// ─── Main page ─────────────────────────────────────────────────────────────────
export function AutomationRulesPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const { stores } = useStoreContext();
  const queryClient = useQueryClient();

  const [filterType, setFilterType] = useState('全部');
  const [filterStatus, setFilterStatus] = useState('全部');

  const [showCreate, setShowCreate] = useState(false);
  const [linkTarget, setLinkTarget] = useState<RuleTemplate | null>(null);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [bulkRunning, setBulkRunning] = useState(false);
  const [mutationError, setMutationError] = useState<string | null>(null);

  const storeName = useMemo(
    () => stores.find((s) => s.id === storeId)?.name ?? '-',
    [stores, storeId],
  );

  const rulesKey = ['rule-templates', storeId] as const;
  const rulesQuery = useApiQuery<RuleTemplate[]>(
    rulesKey,
    () => fetchRuleTemplates(storeId!).then((result) => result ?? []),
    { enabled: !!storeId },
  );
  const data = rulesQuery.data ?? [];
  const loading = !!storeId && rulesQuery.isLoading;
  const error = mutationError ?? (rulesQuery.isError ? rulesQuery.error?.message ?? '加载失败' : null);
  const fetchData = () => { setSelectedIds([]); setMutationError(null); return rulesQuery.refetch(); };

  const filteredData = useMemo(
    () =>
      data.filter((item) => {
        if (filterType !== '全部' && item.templateType !== filterType) return false;
        if (filterStatus !== '全部' && item.status !== filterStatus) return false;
        return true;
      }),
    [data, filterType, filterStatus],
  );

  function toggleSelect(id: string) {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }

  function toggleSelectAll() {
    const visibleIds = filteredData.map((t) => t.id);
    const allSelected = visibleIds.every((id) => selectedIds.includes(id));
    setSelectedIds(allSelected ? [] : visibleIds);
  }

  async function handleBulk(operation: 'enable' | 'disable' | 'delete') {
    if (selectedIds.length === 0) return;
    if (operation === 'delete' && !window.confirm(`确认删除选中的 ${selectedIds.length} 个规则模板？`)) {
      return;
    }
    try {
      setBulkRunning(true);
      const results = await bulkRuleTemplateOp(selectedIds, operation);
      const failed = results.filter((r) => !r.success);
      if (failed.length > 0) {
        setMutationError(`部分操作失败：${failed.map((f) => f.message).join('；')}`);
      }
      await fetchData();
    } catch (e: any) {
      setMutationError(e?.message || '批量操作失败');
    } finally {
      setBulkRunning(false);
    }
  }

  if (loading || storeLoading) return <ErpLoadingSkeleton />;
  if (error || storeError) return <ErpErrorState message={(storeError || error) as string} onRetry={fetchData} />;
  if (!storeId)
    return (
      <ErpEmptyState
        title="暂无可用店铺"
        description="请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。"
      />
    );

  const allVisibleSelected =
    filteredData.length > 0 && filteredData.every((t) => selectedIds.includes(t.id));

  return (
    <div className="space-y-4">
      <ErpPageHeader
        title="自动化规则"
        description={`共 ${data.length} 个规则模板`}
        actions={
          <Button onClick={() => setShowCreate(true)} disabled={!storeId} className="inline-flex items-center gap-2">
            <Plus size={16} />
            新建模板
          </Button>
        }
      />

      {/* Filters + bulk operations */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="flex items-center gap-1.5 text-sm text-slate-500">
          <Filter size={14} />
          筛选:
        </div>
        <select
          value={filterType}
          onChange={(e) => setFilterType(e.target.value)}
          className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
        >
          {typeFilterOptions.map((opt) => (
            <option key={opt} value={opt}>{opt === '全部' ? '全部类型' : TEMPLATE_TYPE_LABELS[opt] || opt}</option>
          ))}
        </select>
        <select
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value)}
          className="px-3 py-1.5 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
        >
          {statusFilterOptions.map((opt) => (
            <option key={opt} value={opt}>
              {opt === '全部' ? '全部状态' : opt === 'enabled' ? '启用' : '停用'}
            </option>
          ))}
        </select>
        <span className="text-sm text-slate-400">共 {filteredData.length} 个</span>

        {selectedIds.length > 0 && (
          <div className="flex items-center gap-2 ml-auto">
            <span className="text-sm text-slate-500">已选 {selectedIds.length} 项</span>
            <button
              onClick={() => handleBulk('enable')}
              disabled={bulkRunning}
              className="px-3 py-1.5 text-sm rounded-lg border border-emerald-200 text-emerald-700 hover:bg-emerald-50 disabled:opacity-60"
            >
              批量启用
            </button>
            <button
              onClick={() => handleBulk('disable')}
              disabled={bulkRunning}
              className="px-3 py-1.5 text-sm rounded-lg border border-amber-200 text-amber-700 hover:bg-amber-50 disabled:opacity-60"
            >
              批量停用
            </button>
            <button
              onClick={() => handleBulk('delete')}
              disabled={bulkRunning}
              className="px-3 py-1.5 text-sm rounded-lg border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-60"
            >
              批量删除
            </button>
          </div>
        )}
      </div>

      {/* Table */}
      {filteredData.length === 0 ? (
        <ErpEmptyState
          title="暂无规则模板"
          description="点击「新建模板」创建条件触发的自动化规则"
        />
      ) : (
        <div className="bg-white rounded-lg border overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-slate-50">
                <th className="px-4 py-3 w-10">
                  <input type="checkbox" checked={allVisibleSelected} onChange={toggleSelectAll} aria-label="全选" />
                </th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">模板名称</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">模板类型</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">店铺</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">触发条件</th>
                <th className="text-left px-4 py-3 font-medium text-slate-600">执行动作</th>
                <th className="text-center px-4 py-3 font-medium text-slate-600">关联对象数量</th>
                <th className="text-center px-4 py-3 font-medium text-slate-600">状态</th>
              </tr>
            </thead>
            <tbody>
              {filteredData.map((item) => (
                <tr key={item.id} className="border-b hover:bg-slate-50 transition-colors">
                  <td className="px-4 py-3">
                    <input
                      type="checkbox"
                      checked={selectedIds.includes(item.id)}
                      onChange={() => toggleSelect(item.id)}
                      aria-label={`选择 ${item.name}`}
                    />
                  </td>
                  <td className="px-4 py-3 font-medium text-slate-900">{item.name}</td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium bg-indigo-50 text-indigo-700">
                      <Zap size={12} />
                      {TEMPLATE_TYPE_LABELS[item.templateType] || item.templateType}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-slate-600">{storeName}</td>
                  <td className="px-4 py-3 text-slate-600 font-mono text-xs">{summarizeCondition(item.condition)}</td>
                  <td className="px-4 py-3 text-slate-700">{summarizeAction(item.action)}</td>
                  <td className="px-4 py-3 text-center">
                    <button
                      onClick={() => setLinkTarget(item)}
                      className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg border border-slate-200 text-xs font-medium text-indigo-600 hover:bg-indigo-50"
                      title="管理关联对象"
                    >
                      <Link2 size={13} />
                      管理 ({item.linkedObjectCount})
                    </button>
                  </td>
                  <td className="px-4 py-3 text-center">
                    <ErpStatusBadge status={item.status === 'enabled' ? 'active' : 'inactive'} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showCreate && storeId && (
        <CreateTemplateModal
          storeId={storeId}
          onClose={() => setShowCreate(false)}
          onCreated={(created) => {
            setShowCreate(false);
            // Reflect the new template without a full reload, then refresh to
            // pick up server-side fields (linked-object count, timestamps).
            queryClient.setQueryData<RuleTemplate[]>(rulesKey, (prev) => [created, ...(prev ?? [])]);
            fetchData();
          }}
        />
      )}

      {linkTarget && storeId && (
        <LinkObjectsModal
          storeId={storeId}
          template={linkTarget}
          onClose={() => setLinkTarget(null)}
          onChanged={(templateId, linkedCount) => {
            // Keep the row's 关联对象数量 in sync as links are added/removed.
            queryClient.setQueryData<RuleTemplate[]>(rulesKey, (prev) =>
              (prev ?? []).map((t) => (t.id === templateId ? { ...t, linkedObjectCount: linkedCount } : t)),
            );
          }}
        />
      )}
    </div>
  );
}
