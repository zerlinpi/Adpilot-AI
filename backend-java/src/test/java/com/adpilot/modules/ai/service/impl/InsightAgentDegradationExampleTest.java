package com.adpilot.modules.ai.service.impl;

import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.ai.dto.InsightQueryRequest;
import com.adpilot.modules.ai.entity.InsightAgentInsightEntity;
import com.adpilot.modules.ai.mapper.InsightAgentInsightMapper;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.ai.vo.InsightResultVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example/unit tests for the Insight Agent no-data and failure paths served by
 * {@link InsightAgentServiceImpl}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, task 27.6.
 *
 * <p>Complements the property test {@link InsightAgentResultPropertyTest} with
 * concrete, named examples for three behaviours hardened in task 27.1:
 * <ul>
 *   <li>Req 14.2 — AI enabled, conforming output with zero recommended actions
 *       plus a human-readable explanation.</li>
 *   <li>Req 14.6 — AI enabled but the call fails/throws: a degraded result that
 *       records a failure reason and retains the operator query.</li>
 *   <li>Req 14.10 — history persistence fails: the produced result is still
 *       returned to the operator (the failure is recorded, not propagated).</li>
 * </ul>
 */
class InsightAgentDegradationExampleTest {

    private static final String SOURCE_AI = "ai";
    private static final String SOURCE_DEGRADED = "degraded";

    private ObjectMapper objectMapper;
    private AiAssistService aiAssistService;
    private PerformanceDailyMapper performanceDailyMapper;
    private CampaignMapper campaignMapper;
    private InsightAgentInsightMapper insightMapper;
    private InsightAgentServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiAssistService = mock(AiAssistService.class);
        performanceDailyMapper = mock(PerformanceDailyMapper.class);
        campaignMapper = mock(CampaignMapper.class);
        insightMapper = mock(InsightAgentInsightMapper.class);

        // Deterministic, empty store summary — these tests are about output
        // structure and degradation behaviour, not metric content. Both reads
        // must be stubbed so summarizeStore() never dereferences a null.
        when(performanceDailyMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(campaignMapper.selectCount(any())).thenReturn(0L);

        service = new InsightAgentServiceImpl(
                aiAssistService, performanceDailyMapper, campaignMapper, insightMapper, objectMapper);
    }

    private static InsightQueryRequest request(String query) {
        InsightQueryRequest request = new InsightQueryRequest();
        request.setQuery(query);
        request.setSource("all");
        request.setPremium(false);
        return request;
    }

    // ------------------------------------------------------------------
    // Req 14.2 — AI no-data / no-action explanation
    // ------------------------------------------------------------------

    /**
     * Req 14.2: WHERE the AI provider is enabled and the store has no data or no
     * action is appropriate, the AI-generated result returns zero recommended
     * actions together with a human-readable explanation.
     */
    @Test
    @DisplayName("Req 14.2: AI enabled with conforming zero-action output returns no actions plus an explanation")
    void aiNoActionResultReturnsZeroActionsWithExplanation() throws Exception {
        when(insightMapper.insert(any())).thenReturn(1);
        when(aiAssistService.isEnabled()).thenReturn(true);

        String explanation = "This store has no advertising data in the last 30 days, "
                + "so there are no actions to recommend yet.";
        // Conforming JSON object with an empty recommendedActions array (Req 14.2/14.3).
        String conforming = "{\"insights\": \"" + explanation + "\", \"recommendedActions\": []}";
        when(aiAssistService.generate(eq("insight-agent"), any(), any(), anyString(), anyString()))
                .thenReturn(java.util.Optional.of(conforming));

        InsightResultVo result = service.query(request("Why are there no actions for my store?"));

        // Genuine AI-generated result (Req 14.5) — not degraded.
        assertThat(result.getGeneratedBy()).isEqualTo(SOURCE_AI);
        assertThat(result.getDegradedReason()).isNull();

        // Zero recommended actions, but a present, non-blank explanation (Req 14.2).
        assertThat(result.getRecommendedActions()).isNotNull().isEmpty();
        assertThat(result.getInsights()).isNotBlank().isEqualTo(explanation);
    }

    // ------------------------------------------------------------------
    // Req 14.6 — degraded-on-failure path
    // ------------------------------------------------------------------

    /**
     * Req 14.6: IF the AI provider is enabled but the analysis request fails,
     * the result is marked degraded, records the failure reason, and retains the
     * operator-submitted query.
     */
    @Test
    @DisplayName("Req 14.6: AI call throwing yields a degraded result that records the reason and retains the query")
    void aiFailureYieldsDegradedResultWithReasonAndRetainedQuery() throws Exception {
        when(insightMapper.insert(any())).thenReturn(1);
        when(aiAssistService.isEnabled()).thenReturn(true);
        when(aiAssistService.generate(eq("insight-agent"), any(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("connection reset"));

        String query = "Compare my ad performance over the last 30 days";
        InsightResultVo result = service.query(request(query));

        // Marked degraded (Req 14.6), never AI-generated.
        assertThat(result.getGeneratedBy()).isEqualTo(SOURCE_DEGRADED);
        // A failure reason is recorded.
        assertThat(result.getDegradedReason()).isNotBlank();
        // The operator-submitted query is retained.
        assertThat(result.getQuery()).isEqualTo(query);
        // A renderable result is still produced.
        assertThat(result.getInsights()).isNotBlank();
        assertThat(result.getRecommendedActions()).isNotNull();
    }

    /**
     * Req 14.6: a call that returns no result (provider unavailable/failed) is
     * equally treated as a degraded result with a recorded reason.
     */
    @Test
    @DisplayName("Req 14.6: AI call returning no result yields a degraded result with a recorded reason")
    void aiEmptyResultYieldsDegradedResultWithReason() throws Exception {
        when(insightMapper.insert(any())).thenReturn(1);
        when(aiAssistService.isEnabled()).thenReturn(true);
        when(aiAssistService.generate(eq("insight-agent"), any(), any(), anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        String query = "Diagnose a high-ACoS campaign";
        InsightResultVo result = service.query(request(query));

        assertThat(result.getGeneratedBy()).isEqualTo(SOURCE_DEGRADED);
        assertThat(result.getDegradedReason()).isNotBlank();
        assertThat(result.getQuery()).isEqualTo(query);
        assertThat(result.getInsights()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // Req 14.10 — persistence-failure path
    // ------------------------------------------------------------------

    /**
     * Req 14.10: IF persistence of the result to the saved-insights history
     * fails, the produced result is still returned to the operator (the failure
     * is recorded/swallowed, not propagated).
     */
    @Test
    @DisplayName("Req 14.10: history persistence failure still returns the produced result to the operator")
    void persistenceFailureStillReturnsProducedResult() throws Exception {
        when(aiAssistService.isEnabled()).thenReturn(true);
        String explanation = "Recent spend is concentrated in two campaigns with rising ACoS.";
        String conforming = "{\"insights\": \"" + explanation + "\", "
                + "\"recommendedActions\": [\"Lower bids on high-ACoS keywords\"]}";
        when(aiAssistService.generate(eq("insight-agent"), any(), any(), anyString(), anyString()))
                .thenReturn(java.util.Optional.of(conforming));

        // History persistence throws — must not fail the request (Req 14.10).
        when(insightMapper.insert(any())).thenThrow(new RuntimeException("db write failed"));

        String query = "Generate this week's store summary";
        InsightResultVo result = service.query(request(query));

        // The produced result is returned despite the persistence failure.
        assertThat(result).isNotNull();
        assertThat(result.getQuery()).isEqualTo(query);
        assertThat(result.getInsights()).isEqualTo(explanation);
        assertThat(result.getRecommendedActions()).containsExactly("Lower bids on high-ACoS keywords");
        // The genuine AI outcome is preserved — persistence failure does not degrade it.
        assertThat(result.getGeneratedBy()).isEqualTo(SOURCE_AI);

        // Persistence was attempted exactly once (the failure was recorded, not retried into the response).
        verify(insightMapper, times(1)).insert(any(InsightAgentInsightEntity.class));
    }

    /**
     * Req 14.10 (degraded variant): a degraded result whose persistence fails is
     * likewise returned to the operator rather than failing the request.
     */
    @Test
    @DisplayName("Req 14.10: persistence failure on a degraded result still returns it to the operator")
    void persistenceFailureOnDegradedResultStillReturnsIt() throws Exception {
        when(aiAssistService.isEnabled()).thenReturn(false); // degraded path (Req 14.7)
        when(insightMapper.insert(any())).thenThrow(new RuntimeException("db write failed"));

        String query = "Analyze my product line performance";
        InsightResultVo result = service.query(request(query));

        assertThat(result).isNotNull();
        assertThat(result.getGeneratedBy()).isEqualTo(SOURCE_DEGRADED);
        assertThat(result.getDegradedReason()).isNotBlank();
        assertThat(result.getQuery()).isEqualTo(query);
        assertThat(result.getInsights()).isNotBlank();
        verify(insightMapper).insert(any(InsightAgentInsightEntity.class));
        // The AI provider is never consulted when it is disabled.
        verify(aiAssistService, never()).generate(anyString(), any(), any(), anyString(), anyString());
    }
}
