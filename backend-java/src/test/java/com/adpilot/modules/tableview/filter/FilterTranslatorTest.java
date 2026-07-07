package com.adpilot.modules.tableview.filter;

import com.adpilot.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FilterTranslator} (Req 2.9): valid conditions are
 * combined with AND into a {@link QueryWrapper}; invalid conditions are rejected
 * before any predicate is built (Req 2.10).
 */
class FilterTranslatorTest {

    private final FilterTranslator translator = new FilterTranslator(new FilterValidator());

    private static final Map<String, FilterFieldSpec> REGISTRY = FilterFieldSpec.registry(
            FilterFieldSpec.of("name", "name", FilterFieldType.TEXT),
            FilterFieldSpec.of("cost", "cost", FilterFieldType.NUMBER),
            FilterFieldSpec.of("shipDate", "ship_date", FilterFieldType.DATE)
    );

    @Test
    void combinesMultipleConditionsWithAnd() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        translator.apply(wrapper, List.of(
                new FilterCondition("name", "contains", "box"),
                new FilterCondition("cost", "gte", 5)
        ), REGISTRY);

        String sql = wrapper.getTargetSql();
        // Both physical columns appear and are joined within a single AND group.
        assertThat(sql).contains("name");
        assertThat(sql).contains("cost");
        assertThat(sql.toUpperCase()).contains("AND");
        assertThat(wrapper.getParamNameValuePairs()).isNotEmpty();
    }

    @Test
    void translatesInOperatorToInClause() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        translator.apply(wrapper, List.of(
                new FilterCondition("cost", "in", List.of(1, 2, 3))
        ), REGISTRY);

        assertThat(wrapper.getTargetSql().toUpperCase()).contains("IN");
    }

    @Test
    void noConditionsLeavesWrapperEmpty() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        translator.apply(wrapper, List.of(), REGISTRY);
        assertThat(wrapper.getParamNameValuePairs()).isEmpty();
    }

    @Test
    void rejectsInvalidConditionBeforeBuildingPredicate() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        assertThatThrownBy(() -> translator.apply(wrapper, List.of(
                new FilterCondition("cost", "eq", "not-a-number")
        ), REGISTRY))
                .isInstanceOf(BusinessException.class);
        // Nothing was added to the wrapper.
        assertThat(wrapper.getParamNameValuePairs()).isEmpty();
    }
}
