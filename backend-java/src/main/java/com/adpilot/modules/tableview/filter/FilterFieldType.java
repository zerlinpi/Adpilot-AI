package com.adpilot.modules.tableview.filter;

import java.util.EnumSet;
import java.util.Set;

/**
 * The logical type of a filterable table field. Used by {@link FilterValidator}
 * to check that an operator is permitted for a field and that a supplied value has
 * a compatible type before a condition is translated into a query predicate by
 * {@link FilterTranslator} (Req 2.9, 2.10).
 */
public enum FilterFieldType {
    TEXT,
    NUMBER,
    DATE,
    BOOLEAN,
    ENUM;

    /** Operators valid for ordered (comparable) scalar fields. */
    private static final Set<FilterOperator> ORDERING =
            EnumSet.of(FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE);

    /** True when {@code operator} is valid for this field type. */
    public boolean supports(FilterOperator operator) {
        if (operator == null) {
            return false;
        }
        return switch (this) {
            case TEXT -> operator == FilterOperator.EQ
                    || operator == FilterOperator.NE
                    || operator == FilterOperator.CONTAINS
                    || operator == FilterOperator.IN;
            case ENUM -> operator == FilterOperator.EQ
                    || operator == FilterOperator.NE
                    || operator == FilterOperator.IN;
            case NUMBER, DATE -> operator == FilterOperator.EQ
                    || operator == FilterOperator.NE
                    || operator == FilterOperator.IN
                    || ORDERING.contains(operator);
            case BOOLEAN -> operator == FilterOperator.EQ
                    || operator == FilterOperator.NE;
        };
    }
}
