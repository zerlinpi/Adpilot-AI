package com.adpilot.modules.ai.service.impl;

import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.ai.dto.InsightQueryRequest;
import com.adpilot.modules.ai.entity.InsightAgentInsightEntity;
import com.adpilot.modules.ai.mapper.InsightAgentInsightMapper;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.ai.vo.InsightResultVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for saved-insights history persistence in
 * {@link InsightAgentServiceImpl#query}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 29: Every Insight
 * Agent result is persisted to history.
 *
 * <p>Validates: Requirements 14.9.
 *
 * <p>For any query — across AI-enabled and AI-disabled providers and across the
 * conforming, non-conforming, failure, and exception outcome paths — the service
 * produces exactly one {@link InsightResultVo} and writes a corresponding entry
 * to the saved-insights history via {@link InsightAgentInsightMapper#insert}
 * exactly once. The persisted record always corresponds to the returned result:
 * its {@code query}, {@code source}, {@code premium}, {@code insights},
 * {@code generatedBy} marker, and recommended actions match what the operator
 * receives, regardless of which outcome path produced the result.
 */
class InsightAgentHistoryPersistencePropertyTest {

    private static final String SOURCE_AI = "ai";
    private static final String SOURCE_DEGRADED = "degraded";

    /**
     * How the mocked {@link AiAssistService} behaves for a given query. Each
     * exercises a distinct outcome path through {@code query()}; all paths must
     * still persist exactly one history entry (Req 14.9).
     */
    enum Scenario {
        /** Req 14.7 — AI not enabled (degraded path). */
        DISABLED,
        /** Req 14.3, 14.5 — enabled and returns a conforming JSON object (ai path). */
        ENABLED_CONFORMING,
        /** Req 14.4 — enabled but the model output does not conform (degraded path). */
        ENABLED_NONCONFORMING,
        /** Req 14.6 — enabled but the call returns no result (degraded path). */
        ENABLED_FAILURE,
        /** Req 14.6 — enabled but the call throws (degraded path). */
        ENABLED_EXCEPTION
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 29: Every Insight
     * Agent result is persisted to history.
     *
     * <p>Validates: Requirements 14.9.
     */
    @Property(tries = 200)
    void everyResultIsPersistedToHistoryExactlyOnce(
            @ForAll("queries") String query,
            @ForAll("sources") String source,
            @ForAll boolean premium,
            @ForAll("scenarios") Scenario scenario,
            @ForAll("conformingInsights") String conformingInsightsText,
            @ForAll("nonConformingOutputs") String nonConformingOutput) throws Exception {

        ObjectMapper objectMapper = new ObjectMapper();

        AiAssistService aiAssistService = Mockito.mock(AiAssistService.class);
        PerformanceDailyMapper performanceDailyMapper = Mockito.mock(PerformanceDailyMapper.class);
        CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
        InsightAgentInsightMapper insightMapper = Mockito.mock(InsightAgentInsightMapper.class);

        // Deterministic, empty store summary: the property concerns persistence of
        // the produced result, not metric content. Both reads must be stubbed so
        // summarizeStore() never dereferences a null.
        when(performanceDailyMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(campaignMapper.selectCount(any())).thenReturn(0L);
        when(insightMapper.insert(any())).thenReturn(1);

        // Wire the AI provider per scenario.
        boolean aiEnabled = scenario != Scenario.DISABLED;
        when(aiAssistService.isEnabled()).thenReturn(aiEnabled);
        if (aiEnabled) {
            switch (scenario) {
                case ENABLED_CONFORMING -> when(aiAssistService.generate(
                        eq("insight-agent"), any(), any(), anyString(), anyString()))
                        .thenReturn(Optional.of(conformingJson(objectMapper, conformingInsightsText)));
                case ENABLED_NONCONFORMING -> when(aiAssistService.generate(
                        eq("insight-agent"), any(), any(), anyString(), anyString()))
                        .thenReturn(Optional.of(nonConformingOutput));
                case ENABLED_FAILURE -> when(aiAssistService.generate(
                        eq("insight-agent"), any(), any(), anyString(), anyString()))
                        .thenReturn(Optional.empty());
                case ENABLED_EXCEPTION -> when(aiAssistService.generate(
                        eq("insight-agent"), any(), any(), anyString(), anyString()))
                        .thenThrow(new RuntimeException("boom"));
                default -> { /* unreachable */ }
            }
        }

        InsightAgentServiceImpl service = new InsightAgentServiceImpl(
                aiAssistService, performanceDailyMapper, campaignMapper, insightMapper, objectMapper);

        InsightQueryRequest request = new InsightQueryRequest();
        request.setQuery(query);
        request.setSource(source);
        request.setPremium(premium);

        InsightResultVo result;
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);
            result = service.query(request);
        }

        assertThat(result).isNotNull();

        // --- Req 14.9: exactly one history entry is written, on every path. ---
        ArgumentCaptor<InsightAgentInsightEntity> persisted =
                ArgumentCaptor.forClass(InsightAgentInsightEntity.class);
        verify(insightMapper, times(1)).insert(persisted.capture());

        // --- The persisted record corresponds to the returned result. ---
        InsightAgentInsightEntity entity = persisted.getValue();
        assertThat(entity)
                .as("a non-null history entity is persisted")
                .isNotNull();
        assertThat(entity.getQuery())
                .as("persisted query matches the returned result's query")
                .isEqualTo(result.getQuery());
        assertThat(entity.getSource())
                .as("persisted source matches the returned result's source")
                .isEqualTo(result.getSource());
        assertThat(entity.getPremium())
                .as("persisted premium flag matches the returned result")
                .isEqualTo(result.isPremium());
        assertThat(entity.getInsights())
                .as("persisted insights narrative matches the returned result")
                .isEqualTo(result.getInsights());
        assertThat(entity.getGeneratedBy())
                .as("persisted source marker matches the returned result")
                .isEqualTo(result.getGeneratedBy());
        // generatedBy is always one of the two recognised markers.
        assertThat(entity.getGeneratedBy()).isIn(SOURCE_AI, SOURCE_DEGRADED);

        // Recommended actions are persisted as a JSON string; it must round-trip
        // back to the same list the operator received (null/empty list -> null).
        List<String> returnedActions = result.getRecommendedActions();
        if (returnedActions == null || returnedActions.isEmpty()) {
            assertThat(entity.getRecommendedActions())
                    .as("no actions => no recommended-actions JSON persisted")
                    .isNull();
        } else {
            assertThat(entity.getRecommendedActions())
                    .as("recommended-actions JSON is persisted")
                    .isNotNull();
            List<String> roundTripped = objectMapper.readValue(
                    entity.getRecommendedActions(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            assertThat(roundTripped)
                    .as("persisted actions round-trip to the returned actions")
                    .isEqualTo(returnedActions);
        }
    }

    /** Build a conforming model payload: a JSON object with a non-blank insights field. */
    private static String conformingJson(ObjectMapper mapper, String insights) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("insights", insights);
        payload.put("recommendedActions", List.of("Lower bids on high-ACoS keywords", "Add negative keywords"));
        return mapper.writeValueAsString(payload);
    }

    // --- generators --------------------------------------------------------

    /** Non-blank natural-language queries (a blank query is rejected up front, not part of this property). */
    @Provide
    Arbitrary<String> queries() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '?', '分', '析')
                .ofMinLength(1)
                .ofMaxLength(120)
                .filter(s -> s.trim().length() > 0);
    }

    /** Selected analysis source, including blank (which the service defaults to "all"). */
    @Provide
    Arbitrary<String> sources() {
        return Arbitraries.of("ads", "listing", "all", "", "  ");
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Arbitraries.of(Scenario.values());
    }

    /** Non-blank insights text for the conforming-output case. */
    @Provide
    Arbitrary<String> conformingInsights() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '.')
                .ofMinLength(1)
                .ofMaxLength(200)
                .filter(s -> s.trim().length() > 0);
    }

    /**
     * Output that does not conform to the expected {@code {"insights": string,
     * "recommendedActions": string[]}} shape, so the service must mark it
     * {@code degraded} (Req 14.4) — yet still persist the degraded result.
     */
    @Provide
    Arbitrary<String> nonConformingOutputs() {
        return Arbitraries.of(
                "this is not json at all",
                "",
                "[]",
                "[\"insights\"]",
                "{\"recommendedActions\": []}",
                "{\"insights\": 123}",
                "{\"insights\": \"\"}",
                "{\"insights\": \"   \"}",
                "{\"insights\": \"ok\", \"recommendedActions\": \"oops-not-an-array\"}",
                "{\"foo\": \"bar\"}");
    }
}
