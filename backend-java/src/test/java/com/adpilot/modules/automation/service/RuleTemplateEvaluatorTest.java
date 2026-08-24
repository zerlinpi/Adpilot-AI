package com.adpilot.modules.automation.service;

import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.automation.entity.AutomationExecutionEntity;
import com.adpilot.modules.automation.entity.AutomationRuleTemplateEntity;
import com.adpilot.modules.automation.entity.AutomationRuleTemplateLinkEntity;
import com.adpilot.modules.automation.mapper.AutomationExecutionMapper;
import com.adpilot.modules.automation.mapper.AutomationRuleTemplateLinkMapper;
import com.adpilot.modules.automation.mapper.AutomationRuleTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the scheduled condition-to-action evaluator. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleTemplateEvaluatorTest {

    @Mock private AutomationRuleTemplateMapper templateMapper;
    @Mock private AutomationRuleTemplateLinkMapper linkMapper;
    @Mock private PerformanceDailyMapper performanceDailyMapper;
    @Mock private AutomationExecutionMapper automationExecutionMapper;

    private RuleTemplateEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new RuleTemplateEvaluator(templateMapper, linkMapper,
                performanceDailyMapper, automationExecutionMapper, new ObjectMapper());
    }

    private AutomationRuleTemplateEntity template(String conditionJson, String actionJson) {
        return AutomationRuleTemplateEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("High ACoS bid down")
                .templateType("bid")
                .conditionJson(conditionJson)
                .actionJson(actionJson)
                .status(AutomationRuleTemplateEntity.STATUS_ENABLED)
                .build();
    }

    private AutomationRuleTemplateLinkEntity link(UUID templateId, UUID campaignId) {
        return AutomationRuleTemplateLinkEntity.builder()
                .id(UUID.randomUUID())
                .templateId(templateId)
                .objectType(AutomationRuleTemplateLinkEntity.OBJECT_CAMPAIGN)
                .objectId(campaignId)
                .build();
    }

    private PerformanceDailyEntity perf(UUID campaignId, BigDecimal spend, BigDecimal sales) {
        return PerformanceDailyEntity.builder()
                .id(UUID.randomUUID())
                .campaignId(campaignId)
                .date(LocalDate.now())
                .impressions(1000L)
                .clicks(100)
                .spend(spend)
                .sales(sales)
                .orders(10)
                .build();
    }

    private void stubSingleTemplate(AutomationRuleTemplateEntity tpl,
                                    UUID campaignId,
                                    PerformanceDailyEntity performance) {
        when(templateMapper.selectList(any())).thenReturn(List.of(tpl));
        when(linkMapper.selectList(any())).thenReturn(List.of(link(tpl.getId(), campaignId)));
        when(performanceDailyMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(performance));
    }

    @Test
    void appliesActionWhenConditionTrue() {
        AutomationRuleTemplateEntity tpl = template(
                "{\"metric\":\"acos\",\"op\":\"gt\",\"value\":25}",
                "{\"type\":\"bid_adjustment\",\"params\":{\"deltaPct\":-10}}");
        UUID campaignId = UUID.randomUUID();
        stubSingleTemplate(tpl, campaignId,
                perf(campaignId, new BigDecimal("50"), new BigDecimal("100")));

        RuleTemplateEvaluator.EvaluationSummary summary = evaluator.runOnce();

        assertThat(summary.getActionsApplied()).isEqualTo(1);
        ArgumentCaptor<AutomationExecutionEntity> captor =
                ArgumentCaptor.forClass(AutomationExecutionEntity.class);
        verify(automationExecutionMapper).insert(captor.capture());
        AutomationExecutionEntity exec = captor.getValue();
        assertThat(exec.getActionType()).isEqualTo("bid_adjustment");
        assertThat(exec.getStatus()).isEqualTo("applied");
        assertThat(exec.getSource()).isEqualTo("rule_template");
        assertThat(exec.getEntityId()).isEqualTo(campaignId);
    }

    @Test
    void doesNotApplyActionWhenConditionFalse() {
        AutomationRuleTemplateEntity tpl = template(
                "{\"metric\":\"acos\",\"op\":\"gt\",\"value\":25}",
                "{\"type\":\"bid_adjustment\",\"params\":{\"deltaPct\":-10}}");
        UUID campaignId = UUID.randomUUID();
        stubSingleTemplate(tpl, campaignId,
                perf(campaignId, new BigDecimal("10"), new BigDecimal("100")));

        RuleTemplateEvaluator.EvaluationSummary summary = evaluator.runOnce();

        assertThat(summary.getActionsApplied()).isZero();
        verify(automationExecutionMapper, never()).insert(any());
    }

    @Test
    void frontendConversionRateMetricAliasTriggersCorrectly() {
        AutomationRuleTemplateEntity tpl = template(
                "{\"metric\":\"conversion_rate\",\"op\":\"gte\",\"value\":10}",
                "{\"type\":\"pause\"}");
        UUID campaignId = UUID.randomUUID();
        stubSingleTemplate(tpl, campaignId,
                perf(campaignId, new BigDecimal("20"), new BigDecimal("100")));

        RuleTemplateEvaluator.EvaluationSummary summary = evaluator.runOnce();

        assertThat(summary.getActionsApplied()).isEqualTo(1);
        verify(automationExecutionMapper).insert(any());
    }

    @Test
    void zeroSpendRoasUsesSentinelInsteadOfArtificialDivisor() {
        AutomationRuleTemplateEntity tpl = template(
                "{\"metric\":\"roas\",\"op\":\"gt\",\"value\":0}",
                "{\"type\":\"pause\"}");
        UUID campaignId = UUID.randomUUID();
        stubSingleTemplate(tpl, campaignId,
                perf(campaignId, BigDecimal.ZERO, new BigDecimal("100")));

        RuleTemplateEvaluator.EvaluationSummary summary = evaluator.runOnce();

        assertThat(summary.getActionsApplied()).isZero();
        verify(automationExecutionMapper, never()).insert(any());
    }

    @Test
    void cpcAliasUsesSharedMetricDefinition() {
        AutomationRuleTemplateEntity tpl = template(
                "{\"metric\":\"cpc\",\"op\":\"eq\",\"value\":0.5}",
                "{\"type\":\"pause\"}");
        UUID campaignId = UUID.randomUUID();
        stubSingleTemplate(tpl, campaignId,
                perf(campaignId, new BigDecimal("50"), new BigDecimal("100")));

        RuleTemplateEvaluator.EvaluationSummary summary = evaluator.runOnce();

        assertThat(summary.getActionsApplied()).isEqualTo(1);
        verify(automationExecutionMapper).insert(any());
    }

    @Test
    void doesNotFireWhenObjectHasNoMetrics() {
        AutomationRuleTemplateEntity tpl = template(
                "{\"metric\":\"acos\",\"op\":\"gt\",\"value\":25}",
                "{\"type\":\"bid_adjustment\"}");
        UUID campaignId = UUID.randomUUID();
        when(templateMapper.selectList(any())).thenReturn(List.of(tpl));
        when(linkMapper.selectList(any())).thenReturn(List.of(link(tpl.getId(), campaignId)));
        when(performanceDailyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        RuleTemplateEvaluator.EvaluationSummary summary = evaluator.runOnce();

        assertThat(summary.getActionsApplied()).isZero();
        verify(automationExecutionMapper, never()).insert(any());
    }

    @Test
    void isolatesPerTemplateFailureFromBadConditionJson() {
        AutomationRuleTemplateEntity bad = template("not-json", "{\"type\":\"x\"}");
        AutomationRuleTemplateEntity good = template(
                "{\"metric\":\"acos\",\"op\":\"gt\",\"value\":25}",
                "{\"type\":\"bid_adjustment\"}");
        UUID campaignId = UUID.randomUUID();
        when(templateMapper.selectList(any())).thenReturn(List.of(bad, good));
        when(linkMapper.selectList(any())).thenReturn(List.of(link(good.getId(), campaignId)));
        when(performanceDailyMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(perf(campaignId, new BigDecimal("50"), new BigDecimal("100"))));

        RuleTemplateEvaluator.EvaluationSummary summary = evaluator.runOnce();

        assertThat(summary.getTemplatesProcessed()).isEqualTo(2);
        assertThat(summary.getTemplatesFailed()).isEqualTo(1);
        assertThat(summary.getActionsApplied()).isEqualTo(1);
    }
}
