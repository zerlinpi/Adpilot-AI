package com.adpilot.modules.tableview.filter;

/**
 * A single advanced-filter condition: a logical field name, an operator token,
 * and a value (Req 2.9). This is the shared, resource-agnostic descriptor used by
 * the server-side query, CSV export, and "select all matching the current filter"
 * endpoints, so all three evaluate the same conditions identically over the full
 * result set.
 *
 * <p>{@code value} is bound from JSON as a loosely typed {@code Object} (a string,
 * number, boolean, or list); its concrete type is checked against the field's
 * declared {@link FilterFieldType} by {@link FilterValidator}, and it is coerced
 * to the matching Java type by {@link FilterTranslator}.</p>
 *
 * @param field the logical field name as exposed to the client (e.g. {@code status})
 * @param op    the operator token (eq, ne, gt, gte, lt, lte, contains, in)
 * @param value the comparison value; a scalar for most operators, a list for {@code in}
 */
public record FilterCondition(String field, String op, Object value) {
}
