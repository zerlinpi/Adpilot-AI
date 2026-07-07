import * as React from 'react';
import { Search, Plus, X, SlidersHorizontal } from 'lucide-react';

import { cn } from '../../lib/utils';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import {
  ALL_SELECTION,
  type FilterCondition,
  type FilterFieldDef,
  type FilterState,
  type FilterOperator,
  type TypeSelectorDef,
} from './types';
import {
  OPERATORS_BY_TYPE,
  OPERATOR_LABELS,
  coerceConditionValue,
  validateCondition,
  type ConditionErrors,
} from './filterValidation';

/**
 * `FilterToolbar` — a standalone, importable table building block (Req 1.9).
 *
 * Presents and manages a table's filter controls (Req 1.3):
 *  - free-text search,
 *  - type selectors,
 *  - advanced-filter controls (field / operator / value conditions).
 *
 * Emits the resulting filter state via `onFilterChange` (Req 2.9). It does
 * NOT fetch data or evaluate filters itself — advanced filtering is evaluated
 * server-side (task 16.1); the toolbar only surfaces validation feedback and
 * emits state.
 *
 * Invalid advanced-filter conditions are surfaced inline and are never
 * committed to the emitted state, so the displayed rows are left unchanged
 * (Req 2.10).
 */
export interface FilterToolbarProps {
  /** Current committed filter state (controlled). */
  filterState: FilterState;
  /** Called with the next committed filter state. */
  onFilterChange: (next: FilterState) => void;
  /** Placeholder for the search input. */
  searchPlaceholder?: string;
  /** Type-selector controls to render. */
  typeSelectors?: TypeSelectorDef[];
  /** Fields available for advanced filtering. When empty, the advanced panel is hidden. */
  filterableFields?: FilterFieldDef[];
  /** Extra class names for the toolbar root. */
  className?: string;
}

interface DraftCondition {
  field: string;
  op: FilterOperator | '';
  value: string;
}

const selectClass =
  'h-9 rounded-md border border-input bg-input-background px-3 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50';

function emptyDraft(fields: FilterFieldDef[]): DraftCondition {
  const first = fields[0];
  return {
    field: first?.key ?? '',
    op: first ? OPERATORS_BY_TYPE[first.type][0] : '',
    value: '',
  };
}

export function FilterToolbar({
  filterState,
  onFilterChange,
  searchPlaceholder = '搜索…',
  typeSelectors = [],
  filterableFields = [],
  className,
}: FilterToolbarProps) {
  const [showAdvanced, setShowAdvanced] = React.useState(false);
  const [drafts, setDrafts] = React.useState<DraftCondition[]>(() =>
    filterableFields.length > 0 ? [emptyDraft(filterableFields)] : [],
  );
  const [errors, setErrors] = React.useState<Record<number, ConditionErrors>>({});

  const fieldByKey = React.useMemo(() => {
    const map = new Map<string, FilterFieldDef>();
    filterableFields.forEach((f) => map.set(f.key, f));
    return map;
  }, [filterableFields]);

  // --- Search -------------------------------------------------------------
  const handleSearch = (value: string) => {
    onFilterChange({ ...filterState, search: value });
  };

  // --- Type selectors -----------------------------------------------------
  const handleTypeChange = (key: string, value: string) => {
    const next = { ...filterState.typeSelections };
    if (value === ALL_SELECTION) {
      delete next[key];
    } else {
      next[key] = value;
    }
    onFilterChange({ ...filterState, typeSelections: next });
  };

  // --- Advanced filter drafts --------------------------------------------
  const updateDraft = (index: number, patch: Partial<DraftCondition>) => {
    setDrafts((prev) =>
      prev.map((d, i) => {
        if (i !== index) return d;
        const merged = { ...d, ...patch };
        // When the field changes, reset the operator to the first valid one.
        if (patch.field && patch.field !== d.field) {
          const field = fieldByKey.get(patch.field);
          merged.op = field ? OPERATORS_BY_TYPE[field.type][0] : '';
        }
        return merged;
      }),
    );
  };

  const addDraft = () => {
    setDrafts((prev) => [...prev, emptyDraft(filterableFields)]);
  };

  const removeDraft = (index: number) => {
    setDrafts((prev) => prev.filter((_, i) => i !== index));
    setErrors((prev) => {
      const next: Record<number, ConditionErrors> = {};
      Object.entries(prev).forEach(([k, v]) => {
        const i = Number(k);
        if (i < index) next[i] = v;
        else if (i > index) next[i - 1] = v;
      });
      return next;
    });
  };

  /**
   * Validate every draft. Commit only the valid conditions to the emitted
   * filter state; keep invalid drafts in place with inline error markers so
   * the displayed rows are unchanged by an invalid condition (Req 2.10).
   */
  const applyAdvanced = () => {
    const nextErrors: Record<number, ConditionErrors> = {};
    const validConditions: FilterCondition[] = [];

    drafts.forEach((draft, index) => {
      const candidate = { field: draft.field, op: draft.op as FilterOperator, value: draft.value };
      const result = validateCondition(candidate, filterableFields);
      if (result.valid) {
        const field = fieldByKey.get(draft.field)!;
        validConditions.push({
          field: draft.field,
          op: draft.op as FilterOperator,
          value: coerceConditionValue(field.type, draft.op as FilterOperator, draft.value),
        });
      } else {
        nextErrors[index] = result.errors;
      }
    });

    setErrors(nextErrors);
    onFilterChange({ ...filterState, conditions: validConditions });
  };

  const clearAdvanced = () => {
    setDrafts(filterableFields.length > 0 ? [emptyDraft(filterableFields)] : []);
    setErrors({});
    onFilterChange({ ...filterState, conditions: [] });
  };

  const hasAdvanced = filterableFields.length > 0;
  const appliedCount = filterState.conditions.length;

  return (
    <div className={cn('flex flex-col gap-3', className)} data-slot="filter-toolbar">
      {/* Row: search + type selectors + advanced toggle */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-[200px] flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            aria-label="搜索"
            className="pl-9"
            placeholder={searchPlaceholder}
            value={filterState.search}
            onChange={(e) => handleSearch(e.target.value)}
          />
        </div>

        {typeSelectors.map((selector) => (
          <label key={selector.key} className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <span>{selector.label}</span>
            <select
              aria-label={selector.label}
              className={selectClass}
              value={filterState.typeSelections[selector.key] ?? ALL_SELECTION}
              onChange={(e) => handleTypeChange(selector.key, e.target.value)}
            >
              <option value={ALL_SELECTION}>{selector.allLabel ?? '全部'}</option>
              {selector.options.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </label>
        ))}

        {hasAdvanced && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setShowAdvanced((s) => !s)}
            aria-expanded={showAdvanced}
          >
            <SlidersHorizontal className="size-4" />
            高级筛选
            {appliedCount > 0 && (
              <span className="ml-1 rounded-full bg-primary px-1.5 text-xs text-primary-foreground">
                {appliedCount}
              </span>
            )}
          </Button>
        )}
      </div>

      {/* Advanced filter panel */}
      {hasAdvanced && showAdvanced && (
        <div className="rounded-md border border-border bg-card p-3" data-slot="advanced-filter-panel">
          <div className="flex flex-col gap-2">
            {drafts.map((draft, index) => {
              const field = fieldByKey.get(draft.field);
              const ops = field ? OPERATORS_BY_TYPE[field.type] : [];
              const rowErrors = errors[index] ?? {};
              return (
                <div key={index} className="flex flex-wrap items-start gap-2">
                  {/* Field */}
                  <div className="flex flex-col gap-1">
                    <select
                      aria-label="筛选字段"
                      aria-invalid={Boolean(rowErrors.field)}
                      className={cn(selectClass, rowErrors.field && 'border-destructive')}
                      value={draft.field}
                      onChange={(e) => updateDraft(index, { field: e.target.value })}
                    >
                      <option value="">选择字段</option>
                      {filterableFields.map((f) => (
                        <option key={f.key} value={f.key}>
                          {f.label}
                        </option>
                      ))}
                    </select>
                    {rowErrors.field && (
                      <span className="text-xs text-destructive">{rowErrors.field}</span>
                    )}
                  </div>

                  {/* Operator */}
                  <div className="flex flex-col gap-1">
                    <select
                      aria-label="操作符"
                      aria-invalid={Boolean(rowErrors.op)}
                      className={cn(selectClass, rowErrors.op && 'border-destructive')}
                      value={draft.op}
                      onChange={(e) => updateDraft(index, { op: e.target.value as FilterOperator })}
                    >
                      <option value="">操作符</option>
                      {ops.map((op) => (
                        <option key={op} value={op}>
                          {OPERATOR_LABELS[op]}
                        </option>
                      ))}
                    </select>
                    {rowErrors.op && (
                      <span className="text-xs text-destructive">{rowErrors.op}</span>
                    )}
                  </div>

                  {/* Value */}
                  <div className="flex flex-1 flex-col gap-1">
                    {field?.type === 'enum' && draft.op !== 'in' ? (
                      <select
                        aria-label="筛选值"
                        aria-invalid={Boolean(rowErrors.value)}
                        className={cn(selectClass, 'w-full', rowErrors.value && 'border-destructive')}
                        value={String(draft.value)}
                        onChange={(e) => updateDraft(index, { value: e.target.value })}
                      >
                        <option value="">选择值</option>
                        {(field.enumOptions ?? []).map((opt) => (
                          <option key={opt} value={opt}>
                            {opt}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <Input
                        aria-label="筛选值"
                        aria-invalid={Boolean(rowErrors.value)}
                        className={cn(rowErrors.value && 'border-destructive')}
                        type={field?.type === 'number' && draft.op !== 'in' ? 'number' : field?.type === 'date' ? 'date' : 'text'}
                        placeholder={draft.op === 'in' ? '多个值用逗号分隔' : '值'}
                        value={draft.value}
                        onChange={(e) => updateDraft(index, { value: e.target.value })}
                      />
                    )}
                    {rowErrors.value && (
                      <span className="text-xs text-destructive">{rowErrors.value}</span>
                    )}
                  </div>

                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="移除条件"
                    onClick={() => removeDraft(index)}
                  >
                    <X className="size-4" />
                  </Button>
                </div>
              );
            })}
          </div>

          <div className="mt-3 flex items-center gap-2">
            <Button type="button" variant="outline" size="sm" onClick={addDraft}>
              <Plus className="size-4" />
              添加条件
            </Button>
            <div className="flex-1" />
            <Button type="button" variant="ghost" size="sm" onClick={clearAdvanced}>
              清除
            </Button>
            <Button type="button" size="sm" onClick={applyAdvanced}>
              应用
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

export default FilterToolbar;
