package com.adpilot.modules.tableview.filter;

import com.adpilot.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Property-based test for the rejection of invalid advanced-filter conditions.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 11: Invalid filter
 * conditions are rejected without changing displayed rows.
 *
 * <p>Validates: Requirements 2.10.
 *
 * <p>Requirement 2.10 says that when an operator submits an advanced-filter
 * condition with a missing field, a missing operator, or a value whose type does
 * not match the selected field, the table rejects the condition, leaves the
 * currently displayed rows unchanged, and indicates which part of the condition is
 * invalid. Server-side, this is enforced by {@link FilterValidator} (which throws a
 * {@link BusinessException} coded {@link FilterValidator#INVALID_CODE} whose message
 * names the offending part) and by {@link FilterTranslator}, which validates before
 * it builds any predicate so a rejected condition produces no query side effect and
 * the underlying result set is never touched.
 *
 * <p>This test generates conditions that are invalid in each distinct category —
 * a missing/blank field, an unknown field, a missing/blank operator, an operator
 * not valid for the field's type, a value whose type does not match the field, an
 * unknown operator, and the list/scalar/null value mismatches — and asserts, for
 * every generated condition, two things:
 * <ol>
 *   <li>{@link FilterValidator#validate} rejects it with a {@code FILTER_INVALID}
 *       {@link BusinessException} whose message identifies which part is invalid; and</li>
 *   <li>{@link FilterTranslator#apply} likewise rejects it and records <em>no</em>
 *       predicate on the query wrapper — captured by a {@link RecordingQueryWrapper} —
 *       proving no query side effect occurs and the displayed rows are left
 *       unchanged.</li>
 * </ol>
 */
class InvalidFilterRejectionPropertyTest {

    // --- a fixed, representative resource registry (one field per type) -------

    private static final List<String> STATUS_VALUES = List.of("ACTIVE", "PAUSED", "ARCHIVED");

    private static final Map<String, FilterFieldSpec> REGISTRY = FilterFieldSpec.registry(
            FilterFieldSpec.of("name", "c_name", FilterFieldType.TEXT),
            FilterFieldSpec.ofEnum("status", "c_status", new HashSet<>(STATUS_VALUES)),
            FilterFieldSpec.of("budget", "c_budget", FilterFieldType.NUMBER),
            FilterFieldSpec.of("startDate", "c_start", FilterFieldType.DATE),
            FilterFieldSpec.of("enabled", "c_enabled", FilterFieldType.BOOLEAN));

    /**
     * Feature: platform-ux-logistics-enhancements, Property 11: Invalid filter
     * conditions are rejected without changing displayed rows.
     *
     * <p>Validates: Requirements 2.10.
     *
     * <p>For any condition invalid in any category, validation throws a
     * {@code FILTER_INVALID} error naming the offending part, and translation
     * throws the same error while emitting no predicate (no query side effect).
     */
    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    void invalidConditionsAreRejectedAndEmitNoPredicate(@ForAll("invalidConditions") Invalid invalid) {
        FilterValidator validator = new FilterValidator();
        FilterTranslator translator = new FilterTranslator(validator);
        List<FilterCondition> conditions = List.of(invalid.condition);

        // 1) The validator rejects the condition, naming which part is invalid.
        BusinessException fromValidator = catchThrowableOfType(
                () -> validator.validate(conditions, REGISTRY), BusinessException.class);
        assertThat(fromValidator)
                .as("validator must reject the invalid condition")
                .isNotNull();
        assertThat(fromValidator.getCode()).isEqualTo(FilterValidator.INVALID_CODE);
        assertThat(fromValidator.getMessage())
                .as("rejection must identify which part of the condition is invalid")
                .contains("(" + invalid.expectedPart + ")");

        // 2) The translator rejects it too, and records NO predicate: the query is
        //    never touched, so the currently displayed rows are left unchanged.
        List<Predicate> predicates = new ArrayList<>();
        RecordingQueryWrapper<Object> wrapper = new RecordingQueryWrapper<>(predicates);
        BusinessException fromTranslator = catchThrowableOfType(
                () -> translator.apply(wrapper, conditions, REGISTRY), BusinessException.class);
        assertThat(fromTranslator)
                .as("translator must reject the invalid condition before building a predicate")
                .isNotNull();
        assertThat(fromTranslator.getCode()).isEqualTo(FilterValidator.INVALID_CODE);
        assertThat(predicates)
                .as("no predicate may be emitted for an invalid condition")
                .isEmpty();
    }

    // --- generators: one inherently-invalid condition per defect category -----

    /** An invalid condition paired with the part of it that should be flagged. */
    private static final class Invalid {
        final FilterCondition condition;
        final String expectedPart; // "field", "operator", or "value"

        Invalid(FilterCondition condition, String expectedPart) {
            this.condition = condition;
            this.expectedPart = expectedPart;
        }
    }

    @Provide
    Arbitrary<Invalid> invalidConditions() {
        return Arbitraries.oneOf(
                missingField(),
                unknownField(),
                missingOperator(),
                unknownOperator(),
                operatorNotValidForFieldType(),
                valueTypeMismatch(),
                listScalarMismatch(),
                nullValue());
    }

    /** A blank or absent field name — validation flags the field. */
    private Arbitrary<Invalid> missingField() {
        Arbitrary<String> blankField = Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.of("", "   ", "\t"));
        return blankField.map(f -> new Invalid(new FilterCondition(f, "eq", "x"), "field"));
    }

    /** A field name that is not in the registry — validation flags the field. */
    private Arbitrary<Invalid> unknownField() {
        Arbitrary<String> unknown = Arbitraries.of("unknown", "ghost", "price", "c_name", "Name");
        return unknown.map(f -> new Invalid(new FilterCondition(f, "eq", "x"), "field"));
    }

    /** A blank or absent operator on a valid field — validation flags the operator. */
    private Arbitrary<Invalid> missingOperator() {
        Arbitrary<String> blankOp = Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.of("", "  "));
        return blankOp.map(op -> new Invalid(new FilterCondition("name", op, "alpha"), "operator"));
    }

    /** An unrecognized operator token on a valid field — validation flags the operator. */
    private Arbitrary<Invalid> unknownOperator() {
        Arbitrary<String> badOp = Arbitraries.of("between", "like", "startswith", "==", "regex");
        return badOp.map(op -> new Invalid(new FilterCondition("budget", op, 5), "operator"));
    }

    /**
     * A recognized operator that is not permitted for the field's type (checked
     * before the value, so the value is kept type-appropriate). Validation flags
     * the operator.
     */
    private Arbitrary<Invalid> operatorNotValidForFieldType() {
        return Arbitraries.oneOf(
                // TEXT does not support ordering operators.
                Arbitraries.of("gt", "gte", "lt", "lte")
                        .map(op -> new Invalid(new FilterCondition("name", op, "alpha"), "operator")),
                // BOOLEAN supports only eq/ne.
                Arbitraries.of("gt", "gte", "lt", "lte", "contains", "in")
                        .map(op -> new Invalid(new FilterCondition("enabled", op, true), "operator")),
                // ENUM does not support ordering or contains.
                Arbitraries.of("gt", "gte", "lt", "lte", "contains")
                        .map(op -> new Invalid(new FilterCondition("status", op, "ACTIVE"), "operator")),
                // NUMBER / DATE do not support contains.
                Arbitraries.just(new Invalid(new FilterCondition("budget", "contains", 5), "operator")),
                Arbitraries.just(new Invalid(new FilterCondition("startDate", "contains", "2024-01-01"), "operator")));
    }

    /**
     * A value whose type does not match the field type, with a valid field and a
     * valid operator. Validation flags the value.
     */
    private Arbitrary<Invalid> valueTypeMismatch() {
        return Arbitraries.oneOf(
                // NUMBER given a non-numeric string.
                Combinators.combine(Arbitraries.of("eq", "ne", "gt", "lt"),
                                Arbitraries.of("abc", "12x", "--", "1.2.3"))
                        .as((op, v) -> new Invalid(new FilterCondition("budget", op, v), "value")),
                // DATE given a non-date string.
                Arbitraries.of("not-a-date", "2024-13-40", "abc", "01/02/2024")
                        .map(v -> new Invalid(new FilterCondition("startDate", "eq", v), "value")),
                // BOOLEAN given a non-boolean value.
                Arbitraries.<Object>of("maybe", "yes", "2", 5)
                        .map(v -> new Invalid(new FilterCondition("enabled", "eq", v), "value")),
                // TEXT given a non-string value.
                Arbitraries.<Object>of(5, 3.14, true)
                        .map(v -> new Invalid(new FilterCondition("name", "eq", v), "value")),
                // ENUM given a value outside the allowed set.
                Arbitraries.of("CANCELLED", "FOO", "active")
                        .map(v -> new Invalid(new FilterCondition("status", "eq", v), "value")),
                // ENUM given a non-string value.
                Arbitraries.<Object>of(7, false)
                        .map(v -> new Invalid(new FilterCondition("status", "eq", v), "value")));
    }

    /** A scalar operator given a list, or {@code in} given a non-list / empty list. */
    private Arbitrary<Invalid> listScalarMismatch() {
        return Arbitraries.oneOf(
                // Scalar operator with a list value.
                Arbitraries.of("eq", "ne", "gt", "lt")
                        .map(op -> new Invalid(
                                new FilterCondition("budget", op, new ArrayList<>(List.of(1, 2))), "value")),
                // IN with a non-list scalar value.
                Arbitraries.<Object>of("ACTIVE", 5, true)
                        .map(v -> new Invalid(new FilterCondition("status", "in", v), "value")),
                // IN with an empty list.
                Arbitraries.just(new Invalid(
                        new FilterCondition("budget", "in", new ArrayList<>()), "value")));
    }

    /** A null value on a valid field/operator — validation flags the value. */
    private Arbitrary<Invalid> nullValue() {
        return Arbitraries.of("name", "budget", "startDate", "enabled", "status")
                .map(field -> new Invalid(new FilterCondition(field, "eq", null), "value"));
    }

    // --- captured predicate + recording wrapper (mirrors the sibling test) ----

    /** One predicate the translator emitted: physical column, operator token, value. */
    private static final class Predicate {
        final String column;
        final String op;
        final Object value;

        Predicate(String column, String op, Object value) {
            this.column = column;
            this.op = op;
            this.value = value;
        }
    }

    /**
     * A {@link QueryWrapper} that records the comparison predicates applied to it
     * (including those applied to nested AND groups, via {@link #instance()})
     * instead of building SQL, so we can assert that an invalid condition emits
     * none.
     */
    private static final class RecordingQueryWrapper<T> extends QueryWrapper<T> {

        private final List<Predicate> predicates;

        RecordingQueryWrapper(List<Predicate> predicates) {
            this.predicates = predicates;
        }

        @Override
        protected QueryWrapper<T> instance() {
            return new RecordingQueryWrapper<>(predicates);
        }

        private QueryWrapper<T> record(boolean condition, String column, String op, Object value) {
            if (condition) {
                predicates.add(new Predicate(column, op, value));
            }
            return this;
        }

        @Override
        public QueryWrapper<T> eq(boolean condition, String column, Object val) {
            return record(condition, column, "eq", val);
        }

        @Override
        public QueryWrapper<T> ne(boolean condition, String column, Object val) {
            return record(condition, column, "ne", val);
        }

        @Override
        public QueryWrapper<T> gt(boolean condition, String column, Object val) {
            return record(condition, column, "gt", val);
        }

        @Override
        public QueryWrapper<T> ge(boolean condition, String column, Object val) {
            return record(condition, column, "gte", val);
        }

        @Override
        public QueryWrapper<T> lt(boolean condition, String column, Object val) {
            return record(condition, column, "lt", val);
        }

        @Override
        public QueryWrapper<T> le(boolean condition, String column, Object val) {
            return record(condition, column, "lte", val);
        }

        @Override
        public QueryWrapper<T> like(boolean condition, String column, Object val) {
            return record(condition, column, "contains", val);
        }

        @Override
        public QueryWrapper<T> in(boolean condition, String column, Collection<?> coll) {
            return record(condition, column, "in", new ArrayList<>(coll));
        }
    }
}
