package com.adpilot.modules.tableview.filter;

import com.adpilot.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Validates advanced-filtering conditions against a resource's field registry
 * before any query is built (Req 2.10).
 *
 * <p>For each condition the validator checks, in order, that the field exists,
 * the operator is recognised and valid for the field's type, and the submitted
 * value's type matches the field type. The first violation throws a
 * {@link BusinessException} (code {@code FILTER_INVALID}) whose message names the
 * offending condition (by zero-based index) and the part that is invalid — the
 * field, the operator, or the value — so the caller can indicate exactly which
 * part of the condition is wrong while leaving the current rows unchanged.</p>
 */
@Component
public class FilterValidator {

    /** Error code raised for any invalid filter condition (Req 2.10). */
    public static final String INVALID_CODE = "FILTER_INVALID";

    /**
     * Validate every condition against {@code registry}. Returns silently when
     * all conditions are valid (or the list is null/empty); otherwise throws a
     * {@link BusinessException} identifying the first invalid part.
     */
    public void validate(List<FilterCondition> conditions, Map<String, FilterFieldSpec> registry) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        if (registry == null || registry.isEmpty()) {
            throw new BusinessException(INVALID_CODE, "No filterable fields are defined for this resource");
        }
        for (int i = 0; i < conditions.size(); i++) {
            validateOne(i, conditions.get(i), registry);
        }
    }

    private void validateOne(int index, FilterCondition condition, Map<String, FilterFieldSpec> registry) {
        if (condition == null) {
            throw invalid(index, "condition", "the condition is missing");
        }

        // 1) field present and known
        String field = condition.field();
        if (field == null || field.isBlank()) {
            throw invalid(index, "field", "a field is required");
        }
        FilterFieldSpec spec = registry.get(field);
        if (spec == null) {
            throw invalid(index, "field", "unknown field '" + field + "'");
        }

        // 2) operator present, recognised, and valid for the field type
        String op = condition.op();
        if (op == null || op.isBlank()) {
            throw invalid(index, "operator", "an operator is required");
        }
        FilterOperator operator = FilterOperator.fromCode(op)
                .orElseThrow(() -> invalid(index, "operator", "unknown operator '" + op + "'"));
        if (!spec.type().supports(operator)) {
            throw invalid(index, "operator",
                    "operator '" + operator.code() + "' is not valid for "
                            + spec.type().name().toLowerCase() + " field '" + field + "'");
        }

        // 3) value present and type-compatible with the field
        validateValue(index, condition.value(), operator, spec);
    }

    private void validateValue(int index, Object value, FilterOperator operator, FilterFieldSpec spec) {
        if (value == null) {
            throw invalid(index, "value", "a value is required for field '" + spec.field() + "'");
        }

        if (operator == FilterOperator.IN) {
            if (!(value instanceof Collection<?> collection)) {
                throw invalid(index, "value",
                        "operator 'in' requires a list of values for field '" + spec.field() + "'");
            }
            if (collection.isEmpty()) {
                throw invalid(index, "value",
                        "operator 'in' requires a non-empty list for field '" + spec.field() + "'");
            }
            int element = 0;
            for (Object item : collection) {
                validateScalar(index, item, spec, "value[" + element + "]");
                element++;
            }
            return;
        }

        // Non-IN operators take a single scalar value.
        if (value instanceof Collection<?>) {
            throw invalid(index, "value",
                    "operator '" + operator.code() + "' requires a single value, not a list, for field '"
                            + spec.field() + "'");
        }
        validateScalar(index, value, spec, "value");
    }

    private void validateScalar(int index, Object value, FilterFieldSpec spec, String part) {
        if (value == null) {
            throw invalid(index, part, "a value is required for field '" + spec.field() + "'");
        }
        switch (spec.type()) {
            case TEXT -> {
                if (!(value instanceof String)) {
                    throw invalid(index, part, "field '" + spec.field() + "' expects a text value");
                }
            }
            case NUMBER -> {
                if (!isNumeric(value)) {
                    throw invalid(index, part, "field '" + spec.field() + "' expects a numeric value");
                }
            }
            case DATE -> {
                if (!isDate(value)) {
                    throw invalid(index, part,
                            "field '" + spec.field() + "' expects a date value (yyyy-MM-dd)");
                }
            }
            case BOOLEAN -> {
                if (!isBoolean(value)) {
                    throw invalid(index, part, "field '" + spec.field() + "' expects a boolean value");
                }
            }
            case ENUM -> {
                if (!(value instanceof String stringValue)) {
                    throw invalid(index, part, "field '" + spec.field() + "' expects a text value");
                } else if (!spec.enumOptions().isEmpty() && !spec.enumOptions().contains(stringValue)) {
                    throw invalid(index, part,
                            "field '" + spec.field() + "' expects one of " + spec.enumOptions());
                }
            }
            default -> throw invalid(index, part, "field '" + spec.field() + "' has an unsupported type");
        }
    }

    private static boolean isNumeric(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String s) {
            try {
                new BigDecimal(s.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isDate(Object value) {
        if (value instanceof String s) {
            try {
                LocalDate.parse(s.trim());
                return true;
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isBoolean(Object value) {
        if (value instanceof Boolean) {
            return true;
        }
        if (value instanceof String s) {
            String t = s.trim().toLowerCase();
            return t.equals("true") || t.equals("false");
        }
        return false;
    }

    private static BusinessException invalid(int index, String part, String reason) {
        return new BusinessException(INVALID_CODE,
                "Invalid filter condition at index " + index + " (" + part + "): " + reason);
    }
}
