package com.adpilot.modules.tableview.filter;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the result set produced by advanced filtering.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 10: Advanced filtering
 * returns exactly the matching rows.
 *
 * <p>Validates: Requirements 2.9.
 *
 * <p>Requirement 2.9 says that when an operator composes advanced-filter
 * conditions (each a field, an operator, and a value), the backend evaluates them
 * server-side and only the rows satisfying <em>all</em> active conditions in
 * combination are shown. The server-side evaluation is produced by
 * {@link FilterTranslator}, which validates every {@link FilterCondition} and then
 * maps it onto a MyBatis-Plus {@link QueryWrapper} predicate (correct physical
 * column, correct comparison operator, correctly coerced value), combining all
 * conditions with AND inside a single nested group.
 *
 * <p>There is no live database here, so the predicates the translator emits are
 * captured by a {@link RecordingQueryWrapper} and then evaluated in memory against
 * a generated dataset. The captured result set is compared, for every dataset and
 * every set of valid conditions, against an independent reference predicate that
 * walks the original conditions directly. The two sets must be identical: no row
 * that satisfies all conditions may be missing, and no row that fails any
 * condition may appear. Set equality enforces both halves of "exactly".
 */
class AdvancedFilteringResultsPropertyTest {

    // --- a fixed, representative resource registry (one field per type) -------

    private static final String COL_NAME = "c_name";
    private static final String COL_STATUS = "c_status";
    private static final String COL_BUDGET = "c_budget";
    private static final String COL_START = "c_start";
    private static final String COL_ENABLED = "c_enabled";

    private static final List<String> NAME_VALUES =
            List.of("alpha", "beta", "gamma", "delta", "alphabet", "zzz");
    private static final List<String> SUBSTRINGS =
            List.of("al", "a", "beta", "z", "delt", "x", "gamma", "");
    private static final List<String> STATUS_VALUES = List.of("ACTIVE", "PAUSED", "ARCHIVED");
    private static final List<String> DATE_STRINGS =
            List.of("2024-01-01", "2024-06-15", "2024-12-31", "2025-03-20");
    private static final List<LocalDate> DATE_VALUES =
            List.of(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-06-15"),
                    LocalDate.parse("2024-12-31"));

    private static final Map<String, FilterFieldSpec> REGISTRY = FilterFieldSpec.registry(
            FilterFieldSpec.of("name", COL_NAME, FilterFieldType.TEXT),
            FilterFieldSpec.ofEnum("status", COL_STATUS, new HashSet<>(STATUS_VALUES)),
            FilterFieldSpec.of("budget", COL_BUDGET, FilterFieldType.NUMBER),
            FilterFieldSpec.of("startDate", COL_START, FilterFieldType.DATE),
            FilterFieldSpec.of("enabled", COL_ENABLED, FilterFieldType.BOOLEAN));

    /**
     * Feature: platform-ux-logistics-enhancements, Property 10: Advanced filtering
     * returns exactly the matching rows.
     *
     * <p>Validates: Requirements 2.9.
     *
     * <p>For any dataset and any set of valid conditions combined with AND, the
     * rows selected by the translated predicates equal exactly the rows that
     * satisfy every condition in combination.
     */
    @Property(tries = 200)
    void advancedFilteringReturnsExactlyTheRowsMatchingAllConditions(
            @ForAll("datasets") List<Map<String, Object>> rows,
            @ForAll("conditionLists") List<FilterCondition> conditions) {

        FilterTranslator translator = new FilterTranslator(new FilterValidator());

        // Capture the predicates the translator builds for these valid conditions.
        List<Predicate> predicates = new ArrayList<>();
        RecordingQueryWrapper<Object> wrapper = new RecordingQueryWrapper<>(predicates);
        translator.apply(wrapper, conditions, REGISTRY);

        // Expected: rows the original conditions select (the reference semantics).
        Set<Integer> expected = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            if (matchesAllConditions(rows.get(i), conditions)) {
                expected.add(i);
            }
        }

        // Actual: rows the translator's emitted predicates select.
        Set<Integer> actual = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            if (matchesAllPredicates(rows.get(i), predicates)) {
                actual.add(i);
            }
        }

        // Exactly the matching rows: no missing matches, no extra rows.
        assertThat(actual).isEqualTo(expected);
    }

    // --- reference evaluation over the original conditions --------------------

    private static boolean matchesAllConditions(Map<String, Object> row, List<FilterCondition> conditions) {
        for (FilterCondition condition : conditions) {
            String column = REGISTRY.get(condition.field()).column();
            if (!evaluate(condition.op(), row.get(column), condition.value())) {
                return false;
            }
        }
        return true;
    }

    // --- evaluation over the predicates the translator actually emitted -------

    private static boolean matchesAllPredicates(Map<String, Object> row, List<Predicate> predicates) {
        for (Predicate predicate : predicates) {
            if (!evaluate(predicate.op, row.get(predicate.column), predicate.value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Shared semantics oracle: evaluate one operator against a stored value and a
     * condition value, normalising the condition value to the stored value's type
     * (so a wire-form value and the translator's coerced form evaluate identically).
     */
    private static boolean evaluate(String op, Object stored, Object conditionValue) {
        switch (op) {
            case "eq":
                return scalarEquals(stored, conditionValue);
            case "ne":
                return !scalarEquals(stored, conditionValue);
            case "gt":
                return compare(stored, conditionValue) > 0;
            case "gte":
                return compare(stored, conditionValue) >= 0;
            case "lt":
                return compare(stored, conditionValue) < 0;
            case "lte":
                return compare(stored, conditionValue) <= 0;
            case "contains":
                return String.valueOf(stored).contains(String.valueOf(conditionValue));
            case "in":
                for (Object element : (Collection<?>) conditionValue) {
                    if (scalarEquals(stored, element)) {
                        return true;
                    }
                }
                return false;
            default:
                throw new IllegalArgumentException("unexpected operator: " + op);
        }
    }

    private static boolean scalarEquals(Object stored, Object conditionValue) {
        Object normalized = normalize(stored, conditionValue);
        if (stored instanceof Comparable && normalized != null
                && stored.getClass().equals(normalized.getClass())) {
            return compare(stored, normalized) == 0;
        }
        return Objects.equals(stored, normalized);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object stored, Object conditionValue) {
        Object normalized = normalize(stored, conditionValue);
        return ((Comparable) stored).compareTo(normalized);
    }

    /** Coerce a condition value to the stored value's runtime type for comparison. */
    private static Object normalize(Object stored, Object conditionValue) {
        if (conditionValue == null) {
            return null;
        }
        if (stored instanceof BigDecimal) {
            return toBigDecimal(conditionValue);
        }
        if (stored instanceof LocalDate) {
            return conditionValue instanceof LocalDate ld
                    ? ld : LocalDate.parse(conditionValue.toString().trim());
        }
        if (stored instanceof Boolean) {
            return conditionValue instanceof Boolean b
                    ? b : Boolean.parseBoolean(conditionValue.toString().trim());
        }
        return conditionValue.toString();
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(value.toString().trim());
    }

    // --- generators -----------------------------------------------------------

    /** A dataset of 1-20 rows, each carrying a typed value for every field. */
    @Provide
    Arbitrary<List<Map<String, Object>>> datasets() {
        Arbitrary<Map<String, Object>> row = Combinators.combine(
                        Arbitraries.of(NAME_VALUES),
                        Arbitraries.of(STATUS_VALUES),
                        Arbitraries.integers().between(0, 5).map(BigDecimal::valueOf),
                        Arbitraries.of(DATE_VALUES),
                        Arbitraries.of(Boolean.TRUE, Boolean.FALSE))
                .as((name, status, budget, start, enabled) -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put(COL_NAME, name);
                    map.put(COL_STATUS, status);
                    map.put(COL_BUDGET, budget);
                    map.put(COL_START, start);
                    map.put(COL_ENABLED, enabled);
                    return map;
                });
        return row.list().ofMinSize(1).ofMaxSize(20);
    }

    /** 0-4 valid conditions, combined with AND by the translator. */
    @Provide
    Arbitrary<List<FilterCondition>> conditionLists() {
        return anyCondition().list().ofMinSize(0).ofMaxSize(4);
    }

    private Arbitrary<FilterCondition> anyCondition() {
        // Wire-form condition values: integers for numbers, ISO strings for dates,
        // strings for text/enum, booleans for booleans — each valid for its field
        // type so the translator never rejects them (the premise is "valid").
        Arbitrary<Object> nameValue = Arbitraries.of(NAME_VALUES).map(s -> (Object) s);
        Arbitrary<Object> substring = Arbitraries.of(SUBSTRINGS).map(s -> (Object) s);
        Arbitrary<Object> statusValue = Arbitraries.of(STATUS_VALUES).map(s -> (Object) s);
        Arbitrary<Object> budgetValue = Arbitraries.integers().between(0, 6).map(i -> (Object) i);
        Arbitrary<Object> dateValue = Arbitraries.of(DATE_STRINGS).map(s -> (Object) s);
        Arbitrary<Object> boolValue = Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(b -> (Object) b);

        return Arbitraries.oneOf(
                // TEXT: eq, ne, contains, in
                eqOrNe("name", nameValue),
                scalar("name", "contains", substring),
                in("name", nameValue),
                // ENUM: eq, ne, in
                eqOrNe("status", statusValue),
                in("status", statusValue),
                // NUMBER: eq, ne, ordering, in
                eqOrNe("budget", budgetValue),
                ordering("budget", budgetValue),
                in("budget", budgetValue),
                // DATE: eq, ne, ordering, in
                eqOrNe("startDate", dateValue),
                ordering("startDate", dateValue),
                in("startDate", dateValue),
                // BOOLEAN: eq, ne
                eqOrNe("enabled", boolValue));
    }

    private Arbitrary<FilterCondition> eqOrNe(String field, Arbitrary<Object> value) {
        return Combinators.combine(Arbitraries.of("eq", "ne"), value)
                .as((op, v) -> new FilterCondition(field, op, v));
    }

    private Arbitrary<FilterCondition> ordering(String field, Arbitrary<Object> value) {
        return Combinators.combine(Arbitraries.of("gt", "gte", "lt", "lte"), value)
                .as((op, v) -> new FilterCondition(field, op, v));
    }

    private Arbitrary<FilterCondition> scalar(String field, String op, Arbitrary<Object> value) {
        return value.map(v -> new FilterCondition(field, op, v));
    }

    private Arbitrary<FilterCondition> in(String field, Arbitrary<Object> element) {
        return element.list().ofMinSize(1).ofMaxSize(3)
                .map(list -> new FilterCondition(field, "in", new ArrayList<Object>(list)));
    }

    // --- captured predicate + recording wrapper -------------------------------

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
     * instead of building SQL, so the translation can be evaluated in memory.
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
