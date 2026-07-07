package com.adpilot.modules.ai.service.impl;

import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.ai.dto.InsightQueryRequest;
import com.adpilot.modules.ai.mapper.InsightAgentInsightMapper;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.ai.vo.InsightResultVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the recommended-action cap enforced by
 * {@link InsightAgentServiceImpl#query}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 27: AI-generated
 * insights return at most ten recommended actions.
 *
 * <p>Validates: Requirements 14.1.
 *
 * <p>When the AI_Provider is enabled and returns conforming output, the
 * Insight_Agent returns an AI-generated result ({@code generatedBy == "ai"})
 * whose recommended-actions list never exceeds ten entries, regardless of how
 * many actions the model emitted. The AI provider is mocked to return
 * variable-length action lists (including lists far larger than ten) so the
 * property exercises the cap across the full input space.
 */
class InsightAgentRecommendedActionCapPropertyTest {

    private static final int MAX_RECOMMENDED_ACTIONS = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Feature: platform-ux-logistics-enhancements, Property 27: AI-generated
     * insights return at most ten recommended actions.
     *
     * <p>Validates: Requirements 14.1.
     *
     * <p>For any AI output containing an arbitrary number of recommended actions
     * (including more than ten), the AI-generated insight result returned by the
     * service contains at most ten recommended actions and is marked
     * {@code ai}-sourced.
     */
    @Property(tries = 200)
    void aiGeneratedInsightsAreCappedAtTenRecommendedActions(
            @ForAll("recommendedActions") List<String> aiActions,
            @ForAll("insightsText") String insightsText,
            @ForAll("queries") String query) {

        AiAssistService aiAssistService = Mockito.mock(AiAssistService.class);
        PerformanceDailyMapper performanceDailyMapper = Mockito.mock(PerformanceDailyMapper.class);
        CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
        InsightAgentInsightMapper insightMapper = Mockito.mock(InsightAgentInsightMapper.class);

        // AI is enabled and returns conforming JSON carrying the (variable-length)
        // action list — this is the AI-generated path (Req 14.1, 14.5).
        when(aiAssistService.isEnabled()).thenReturn(true);
        when(aiAssistService.generate(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of(aiOutputJson(insightsText, aiActions)));

        // No stored metrics: keep the summary deterministic and avoid NPEs in the
        // mocked mappers. The cap applies independently of the underlying data.
        when(performanceDailyMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(campaignMapper.selectCount(any())).thenReturn(0L);

        InsightAgentServiceImpl service = new InsightAgentServiceImpl(
                aiAssistService, performanceDailyMapper, campaignMapper, insightMapper, MAPPER);

        InsightQueryRequest request = new InsightQueryRequest();
        request.setQuery(query);
        request.setSource("all");
        request.setPremium(false);
        request.setStoreId(null);

        InsightResultVo result;
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);
            result = service.query(request);
        }

        // The output was conforming, so this must be a genuine AI-generated result.
        assertThat(result.getGeneratedBy()).isEqualTo("ai");
        // Core property: the recommended-action list is capped at ten (Req 14.1).
        assertThat(result.getRecommendedActions()).isNotNull();
        assertThat(result.getRecommendedActions().size()).isLessThanOrEqualTo(MAX_RECOMMENDED_ACTIONS);
        // The cap is a prefix of the (non-blank) actions the model produced.
        int expected = Math.min(aiActions.size(), MAX_RECOMMENDED_ACTIONS);
        assertThat(result.getRecommendedActions()).hasSize(expected);
    }

    /** Serialize a conforming Insight Agent AI payload: {@code {insights, recommendedActions}}. */
    private static String aiOutputJson(String insights, List<String> actions) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("insights", insights);
        ArrayNode arr = root.putArray("recommendedActions");
        for (String a : actions) {
            arr.add(a);
        }
        return root.toString();
    }

    // --- generators --------------------------------------------------------

    /**
     * Variable-length lists of non-blank action strings, sized 0..25 so the input
     * space includes empty lists, lists at the boundary (10), and lists well over
     * the cap. Strings avoid control characters and are non-blank to mirror what
     * the parser keeps.
     */
    @Provide
    Arbitrary<List<String>> recommendedActions() {
        Arbitrary<String> action = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '-')
                .ofMinLength(1)
                .ofMaxLength(40)
                .filter(s -> !s.isBlank());
        return action.list().ofMinSize(0).ofMaxSize(25);
    }

    /** Non-blank narrative insights text (the parser requires a textual, non-empty value). */
    @Provide
    Arbitrary<String> insightsText() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '.')
                .ofMinLength(1)
                .ofMaxLength(120)
                .filter(s -> !s.isBlank());
    }

    /** Non-blank operator queries (the service requires a query). */
    @Provide
    Arbitrary<String> queries() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '?')
                .ofMinLength(1)
                .ofMaxLength(80)
                .filter(s -> !s.isBlank());
    }
}
