package com.adpilot.modules.tableview.filter;

import java.util.Optional;

/**
 * The set of operators an {@link FilterCondition} may use (Req 2.9). Each operator
 * carries the lowercase token used on the wire / JSON payload; {@link FilterFieldType}
 * declares which operators are valid for a given field type so an operator that does
 * not make sense for a field (for example {@code gt} on a boolean) is rejected during
 * validation (Req 2.10).
 */
public enum FilterOperator {
    EQ("eq"),
    NE("ne"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    CONTAINS("contains"),
    IN("in");

    private final String code;

    FilterOperator(String code) {
        this.code = code;
    }

    /** The lowercase token used on the wire / JSON payload. */
    public String code() {
        return code;
    }

    /** Resolve an operator from its wire token, or empty when unknown. */
    public static Optional<FilterOperator> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase();
        for (FilterOperator op : values()) {
            if (op.code.equals(normalized)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }
}
