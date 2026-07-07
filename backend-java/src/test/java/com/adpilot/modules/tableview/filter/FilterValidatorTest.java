package com.adpilot.modules.tableview.filter;

import com.adpilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FilterValidator} (Req 2.10): each condition's field,
 * operator, and value are validated, and an invalid condition is rejected with a
 * message identifying which part is invalid.
 */
class FilterValidatorTest {

    private final FilterValidator validator = new FilterValidator();

    private static final Map<String, FilterFieldSpec> REGISTRY = FilterFieldSpec.registry(
            FilterFieldSpec.of("name", "name", FilterFieldType.TEXT),
            FilterFieldSpec.of("cost", "cost", FilterFieldType.NUMBER),
            FilterFieldSpec.of("shipDate", "ship_date", FilterFieldType.DATE),
            FilterFieldSpec.ofEnum("status", "status", Set.of("pending", "shipped")),
            FilterFieldSpec.of("active", "active", FilterFieldType.BOOLEAN)
    );

    @Test
    void acceptsValidConditions() {
        List<FilterCondition> conditions = List.of(
                new FilterCondition("name", "contains", "box"),
                new FilterCondition("cost", "gte", 10),
                new FilterCondition("shipDate", "lt", "2024-01-31"),
                new FilterCondition("status", "in", List.of("pending", "shipped")),
                new FilterCondition("active", "eq", true)
        );
        validator.validate(conditions, REGISTRY); // does not throw
    }

    @Test
    void acceptsNullOrEmptyFilters() {
        validator.validate(null, REGISTRY);
        validator.validate(List.of(), REGISTRY);
    }

    @Test
    void rejectsUnknownFieldIdentifyingField() {
        assertThatThrownBy(() -> validator.validate(
                List.of(new FilterCondition("nope", "eq", "x")), REGISTRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("(field)")
                .hasMessageContaining("nope");
    }

    @Test
    void rejectsMissingFieldIdentifyingField() {
        assertThatThrownBy(() -> validator.validate(
                List.of(new FilterCondition(null, "eq", "x")), REGISTRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("(field)");
    }

    @Test
    void rejectsUnknownOperatorIdentifyingOperator() {
        assertThatThrownBy(() -> validator.validate(
                List.of(new FilterCondition("name", "between", "x")), REGISTRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("(operator)")
                .hasMessageContaining("between");
    }

    @Test
    void rejectsOperatorNotValidForFieldType() {
        // gt is not valid for a text field
        assertThatThrownBy(() -> validator.validate(
                List.of(new FilterCondition("name", "gt", "x")), REGISTRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("(operator)")
                .hasMessageContaining("not valid for text");
    }

    @Test
    void rejectsValueTypeMismatchIdentifyingValue() {
        // a numeric field given a non-numeric text value
        assertThatThrownBy(() -> validator.validate(
                List.of(new FilterCondition("cost", "eq", "abc")), REGISTRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("(value)")
                .hasMessageContaining("numeric");
    }

    @Test
    void rejectsEnumValueOutsideAllowedSet() {
        assertThatThrownBy(() -> validator.validate(
                List.of(new FilterCondition("status", "eq", "cancelled")), REGISTRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("(value)");
    }

    @Test
    void rejectsInOperatorWithNonListValue() {
        assertThatThrownBy(() -> validator.validate(
                List.of(new FilterCondition("status", "in", "pending")), REGISTRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("(value)");
    }

    @Test
    void rejectsScalarOperatorWithListValue() {
        assertThatThrownBy(() -> validator.validate(
                List.of(new FilterCondition("cost", "eq", List.of(1, 2))), REGISTRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("(value)");
    }

    @Test
    void reportsTheOffendingConditionIndex() {
        List<FilterCondition> conditions = List.of(
                new FilterCondition("name", "eq", "ok"),
                new FilterCondition("cost", "eq", "not-a-number")
        );
        assertThatThrownBy(() -> validator.validate(conditions, REGISTRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("index 1");
    }
}
