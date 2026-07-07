import type {
  FilterCondition,
  FilterFieldDef,
  FilterFieldType,
  FilterOperator,
} from './types';

/**
 * Operators permitted for each field type. Used both to populate the operator
 * dropdown in `FilterToolbar` and to validate submitted advanced-filter
 * conditions (Req 2.10).
 */
export const OPERATORS_BY_TYPE: Record<FilterFieldType, FilterOperator[]> = {
  text: ['contains', 'eq', 'ne'],
  number: ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'in'],
  enum: ['eq', 'ne', 'in'],
  date: ['eq', 'ne', 'gt', 'gte', 'lt', 'lte'],
};

/** Human-readable labels for operators. */
export const OPERATOR_LABELS: Record<FilterOperator, string> = {
  eq: '等于',
  ne: '不等于',
  gt: '大于',
  gte: '大于等于',
  lt: '小于',
  lte: '小于等于',
  contains: '包含',
  in: '属于',
};

/** Which parts of a condition can be flagged invalid. */
export interface ConditionErrors {
  field?: string;
  op?: string;
  value?: string;
}

/** Result of validating a single draft condition. */
export interface ConditionValidation {
  valid: boolean;
  errors: ConditionErrors;
}

function isBlank(value: unknown): boolean {
  return value === undefined || value === null || value === '';
}

/**
 * Validate the `value` of a condition against the selected field's type.
 * Returns an error message when the value's type does not match, otherwise
 * `undefined`.
 */
function validateValue(
  type: FilterFieldType,
  op: FilterOperator,
  value: unknown,
  field: FilterFieldDef,
): string | undefined {
  // The `in` operator accepts a comma-separated list; validate each member.
  if (op === 'in') {
    const members = String(value)
      .split(',')
      .map((m) => m.trim())
      .filter((m) => m.length > 0);
    if (members.length === 0) {
      return '请至少输入一个值';
    }
    if (type === 'number') {
      const bad = members.find((m) => Number.isNaN(Number(m)));
      return bad === undefined ? undefined : `值的类型必须为数字: "${bad}"`;
    }
    if (type === 'enum') {
      const allowed = field.enumOptions ?? [];
      const bad = members.find((m) => !allowed.includes(m));
      return bad === undefined ? undefined : `值不在允许的选项中: "${bad}"`;
    }
    return undefined;
  }

  switch (type) {
    case 'text':
      return typeof value === 'string' && value.length > 0
        ? undefined
        : '值的类型必须为文本';
    case 'number':
      return !isBlank(value) && !Number.isNaN(Number(value))
        ? undefined
        : '值的类型必须为数字';
    case 'enum': {
      const allowed = field.enumOptions ?? [];
      return typeof value === 'string' && allowed.includes(value)
        ? undefined
        : '值不在允许的选项中';
    }
    case 'date': {
      if (isBlank(value)) return '值的类型必须为日期';
      const t = Date.parse(String(value));
      return Number.isNaN(t) ? '值的类型必须为日期' : undefined;
    }
    default:
      return '未知的字段类型';
  }
}

/**
 * Validate a single advanced-filter condition against the available fields.
 *
 * Rejects (Req 2.10):
 *  - a missing field (or a field not in the available set),
 *  - a missing operator (or one not valid for the field type),
 *  - a value whose type does not match the selected field.
 */
export function validateCondition(
  condition: Partial<FilterCondition>,
  fields: FilterFieldDef[],
): ConditionValidation {
  const errors: ConditionErrors = {};

  const field = condition.field
    ? fields.find((f) => f.key === condition.field)
    : undefined;

  if (isBlank(condition.field)) {
    errors.field = '请选择字段';
  } else if (!field) {
    errors.field = '未知的字段';
  }

  if (isBlank(condition.op)) {
    errors.op = '请选择操作符';
  } else if (field && !OPERATORS_BY_TYPE[field.type].includes(condition.op as FilterOperator)) {
    errors.op = '操作符不适用于该字段';
  }

  // Only validate the value once a field is known and the operator is usable.
  if (field && !errors.op) {
    const valueError = validateValue(
      field.type,
      condition.op as FilterOperator,
      condition.value,
      field,
    );
    if (valueError) errors.value = valueError;
  }

  return { valid: Object.keys(errors).length === 0, errors };
}

/**
 * The outcome of partitioning a set of advanced-filter conditions into those
 * whose field is server-side filterable (persisted) and those that are not.
 */
export interface FilterFieldPartition {
  /** Conditions whose `field` is a persisted, server-filterable field. */
  accepted: FilterCondition[];
  /** Conditions referencing a field that is NOT persisted/filterable. */
  rejected: FilterCondition[];
}

/**
 * Partition advanced-filter conditions by whether their field is server-side
 * filterable (i.e. present in the table's declared, persisted filter fields).
 *
 * A list filter that references a field not persisted on the queried records
 * must NOT be silently applied (which would yield unfiltered results) — it is
 * rejected so the table can surface a validation error instead and forward only
 * the persisted conditions to the server (Req 15.5, 15.6, 15.7).
 *
 * Pure: no React, no globals. `persistedFields` is the set of filterable field
 * keys the table declares; an empty/absent set rejects nothing (the table has
 * not declared a persisted-field constraint).
 */
export function partitionFilterFields(
  conditions: FilterCondition[],
  persistedFields: Iterable<string> | undefined,
): FilterFieldPartition {
  if (persistedFields === undefined) {
    return { accepted: [...conditions], rejected: [] };
  }
  const allowed = new Set(persistedFields);
  if (allowed.size === 0) {
    return { accepted: [...conditions], rejected: [] };
  }
  const accepted: FilterCondition[] = [];
  const rejected: FilterCondition[] = [];
  for (const c of conditions) {
    if (allowed.has(c.field)) accepted.push(c);
    else rejected.push(c);
  }
  return { accepted, rejected };
}

/**
 * Human-readable validation message naming the unsupported filter fields, or
 * `null` when nothing was rejected. Mirrors the backend's reject-with-a-named
 * field behavior (Req 15.6) on the client so the operator is told which filter
 * is unsupported rather than seeing silently unfiltered rows.
 */
export function unsupportedFilterMessage(
  rejected: FilterCondition[],
): string | null {
  if (rejected.length === 0) return null;
  const fields = [...new Set(rejected.map((c) => c.field))].join('、');
  return `不支持的筛选字段（未持久化，无法服务端筛选）：${fields}`;
}

/**
 * Coerce a validated condition's raw string value into the typed value sent to
 * the backend. Assumes the condition has already passed `validateCondition`.
 */
export function coerceConditionValue(
  type: FilterFieldType,
  op: FilterOperator,
  raw: unknown,
): unknown {
  if (op === 'in') {
    const members = String(raw)
      .split(',')
      .map((m) => m.trim())
      .filter((m) => m.length > 0);
    return type === 'number' ? members.map((m) => Number(m)) : members;
  }
  if (type === 'number') return Number(raw);
  return raw;
}
