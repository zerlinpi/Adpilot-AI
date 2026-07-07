package com.adpilot.modules.tableview.filter;

import com.adpilot.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Validates and translates advanced-filtering {@link FilterCondition}s into
 * predicates on a MyBatis-Plus {@link QueryWrapper}, combining them with AND
 * (Req 2.9, 2.10).
 *
 * <p>Every condition is first validated via {@link FilterValidator} (so an
 * invalid field/operator/value is rejected with a {@link BusinessException}
 * before any predicate is built and no rows are returned or changed). Valid
 * conditions are then applied <em>on top of</em> a wrapper that has already had
 * the requester's data scope applied (via
 * {@link com.adpilot.common.security.DataScopeService#applyScope}), so the
 * resulting query is {@code scope AND (condition1 AND condition2 ...)}. All
 * conditions are placed inside a single nested AND group so they never weaken the
 * scope predicate.</p>
 *
 * <p>Column names come exclusively from each field's {@link FilterFieldSpec};
 * client field names are never used as raw SQL, preventing column injection.</p>
 */
@Component
@RequiredArgsConstructor
public class FilterTranslator {

    private final FilterValidator filterValidator;

    /**
     * Validate and apply every condition in {@code conditions} to {@code wrapper},
     * combined with AND, resolving columns and coercing values via
     * {@code registry}.
     */
    public <T> void apply(QueryWrapper<T> wrapper, List<FilterCondition> conditions,
                          Map<String, FilterFieldSpec> registry) {
        if (wrapper == null) {
            throw new BusinessException(FilterValidator.INVALID_CODE, "A query wrapper is required");
        }
        // Reject invalid conditions before building any predicate (Req 2.10).
        filterValidator.validate(conditions, registry);
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        wrapper.and(group -> {
            for (FilterCondition condition : conditions) {
                applyOne(group, condition, registry);
            }
        });
    }

    private <T> void applyOne(QueryWrapper<T> group, FilterCondition condition,
                              Map<String, FilterFieldSpec> registry) {
        FilterFieldSpec spec = registry.get(condition.field());
        if (spec == null) {
            throw new BusinessException(FilterValidator.INVALID_CODE,
                    "unknown field '" + condition.field() + "'");
        }
        FilterOperator operator = FilterOperator.fromCode(condition.op())
                .orElseThrow(() -> new BusinessException(FilterValidator.INVALID_CODE,
                        "unknown operator '" + condition.op() + "'"));

        String column = spec.column();
        Object raw = condition.value();

        switch (operator) {
            case EQ -> group.eq(column, coerce(raw, spec));
            case NE -> group.ne(column, coerce(raw, spec));
            case GT -> group.gt(column, coerce(raw, spec));
            case GTE -> group.ge(column, coerce(raw, spec));
            case LT -> group.lt(column, coerce(raw, spec));
            case LTE -> group.le(column, coerce(raw, spec));
            case CONTAINS -> group.like(column, String.valueOf(coerce(raw, spec)));
            case IN -> group.in(column, coerceList(raw, spec));
        }
    }

    private List<Object> coerceList(Object raw, FilterFieldSpec spec) {
        if (!(raw instanceof Collection<?> collection)) {
            throw new BusinessException(FilterValidator.INVALID_CODE,
                    "operator 'in' requires a list for field '" + spec.field() + "'");
        }
        List<Object> coerced = new ArrayList<>(collection.size());
        for (Object item : collection) {
            coerced.add(coerce(item, spec));
        }
        return coerced;
    }

    /**
     * Coerce a JSON-deserialized value to the Java type matching the field, so
     * comparisons bind correctly (numbers as {@link BigDecimal}, dates as
     * {@link LocalDate}, booleans as {@link Boolean}, text/enum as String).
     */
    private Object coerce(Object value, FilterFieldSpec spec) {
        if (value == null) {
            return null;
        }
        return switch (spec.type()) {
            case TEXT, ENUM -> value.toString();
            case NUMBER -> toBigDecimal(value, spec);
            case DATE -> toLocalDate(value, spec);
            case BOOLEAN -> toBoolean(value);
        };
    }

    private BigDecimal toBigDecimal(Object value, FilterFieldSpec spec) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(FilterValidator.INVALID_CODE,
                    "field '" + spec.field() + "' expects a numeric value");
        }
    }

    private LocalDate toLocalDate(Object value, FilterFieldSpec spec) {
        if (value instanceof LocalDate ld) {
            return ld;
        }
        try {
            return LocalDate.parse(value.toString().trim());
        } catch (Exception e) {
            throw new BusinessException(FilterValidator.INVALID_CODE,
                    "field '" + spec.field() + "' expects a date value (yyyy-MM-dd)");
        }
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }
}
