package com.adpilot.modules.ai.service.impl;

import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.ai.dto.InsightQueryRequest;
import com.adpilot.modules.ai.mapper.InsightAgentInsightMapper;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.ai.vo.InsightResultVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
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
import static org.mockito.Mockito.when;

/**
 * Property-based test for the Insight Agent result contract served by
 * {@link InsightAgentServiceImpl}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 26: Insight Agent
 * results conform to the output structure and source marker.
 *
 * <p>Validates: Requirements 14.3, 14.4, 14.5, 14.7.
 *
 * <p>For any query, the returned {@link InsightResultVo} always conforms to the
 * defined output structure (a non-blank {@code insights} narrative, a present
 * {@code recommendedActions} list, and a source marker) and the source marker
 * ({@code generatedBy}) is exactly one of {@code ai} or {@code degraded}. The
 * marker is {@code ai} only when the AI provider is enabled and returns
 * conforming output (Req 14.3, 14.5); it is {@code degraded} whenever AI is not
 * enabled (Req 14.7) or returns non-conforming output / fails (Req 14.4). When
 * AI is disabled the source is never {@code ai}.
 */
class InsightAgentResultPropertyTest {

    private static final String SOURCE_AI = "ai";
    private static final String SOURCE_DEGRADED = "degraded";

    /**
     * How the mocked {@link AiAssistService} behaves for a given query. Only
     * {@link #ENABLED_CONFORMING} can legitimately yield an {@code ai} marker;
     * every other scenario must degrade.
     */
    enum Scenario {
        /** Req 14.7 — AI not enabled. */
        DISABLED,
        /** Req 14.3, 14.5 — enabled and returns a conforming JSON object. */
        ENABLED_CONFORMING,
        /** Req 14.4 — enabled but the model output does not conform. */
        ENABLED_NONCONFORMING,
        /** Req 14.6 — enabled but the call returns no result (failure/unavailable). */
        ENABLED_FAILURE,
        /** Req 14.6 — enabled but the call throws. */
        ENABLED_EXCEPTION
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 26: Insight Agent
     * results conform to the output structure and source marker.
     *
     * <p>Validates: Requirements 14.3, 14.4, 14.5, 14.7.
     */
    @Property(tries = 200)
    void insightResultAlwaysConformsToStructureAndSourceMarker(
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

        // Deterministic, empty store summary: the property is about output
        // structure and the source marker, not metric content. Both reads must be
        // stubbed so summarizeStore() never dereferences a null.
        when(performanceDailyMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(campaignMapper.selectCount(any())).thenReturn(0L);
        // Persistence is best-effort (failures are swallowed); allow the insert.
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

        InsightResultVo result = service.query(request);

        // --- Structure (Req 14.3): a result is always produced and conforms. ---
        assertThat(result).isNotNull();
        assertThat(result.getInsights())
                .as("insights narrative is always present and non-blank")
                .isNotNull()
                .isNotBlank();
        assertThat(result.getRecommendedActions())
                .as("recommended-actions list is always present (may be empty)")
                .isNotNull();

        // --- Source marker (Req 14.5): exactly one of "ai" or "degraded". ---
        assertThat(result.getGeneratedBy())
                .as("source marker is exactly one of ai/degraded")
                .isIn(SOURCE_AI, SOURCE_DEGRADED);

        // --- Req 14.7: when AI is disabled the source is never "ai". ---
        if (!aiEnabled) {
            assertThat(result.getGeneratedBy())
                    .as("AI disabled => degraded, never ai")
                    .isEqualTo(SOURCE_DEGRADED);
        }

        // --- Req 14.3/14.4/14.5: marker matches provider outcome exactly. ---
        if (scenario == Scenario.ENABLED_CONFORMING) {
            assertThat(result.getGeneratedBy())
                    .as("enabled + conforming output => ai")
                    .isEqualTo(SOURCE_AI);
            assertThat(result.getDegradedReason())
                    .as("genuine ai result records no degraded reason")
                    .isNull();
        } else {
            assertThat(result.getGeneratedBy())
                    .as("disabled / non-conforming / failure => degraded")
                    .isEqualTo(SOURCE_DEGRADED);
            assertThat(result.getDegradedReason())
                    .as("degraded result records why it degraded")
                    .isNotBlank();
        }

        // The echoed query is always retained regardless of outcome (Req 14.6).
        assertThat(result.getQuery()).isEqualTo(query.trim());
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
     * {@code degraded} (Req 14.4). Covers: not JSON, a JSON array, an object
     * missing insights, a non-textual insights field, a blank insights field,
     * and a wrong-typed recommendedActions field.
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
