package com.adpilot.modules.ai.service.impl;

import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.ai.dto.InsightQueryRequest;
import com.adpilot.modules.ai.mapper.InsightAgentInsightMapper;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.ai.vo.InsightResultVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for secret hygiene in {@link InsightAgentServiceImpl#query}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 28: Insight Agent
 * never leaks secrets.
 *
 * <p>Validates: Requirements 14.8.
 *
 * <p>The Insight_Agent reads the store's stored records (here, the last 30 days
 * of {@code performance_daily}) to build a compact metrics summary, then either
 * feeds that summary plus the operator's question to the AI_Provider or renders
 * a deterministic summary directly. Secret/credential values can be present in
 * the records the agent reads (modelled here by seeding secret strings into a
 * record's string field). Requirement 14.8 demands that no such secret value is
 * ever included in the prompt sent to the AI_Provider or in the result returned
 * to the operator — the agent transmits only aggregated, non-sensitive metrics.
 *
 * <p>Note: secrets are injected into the data the agent reads, not into the
 * operator's literal query, because the query is intentionally echoed back; the
 * property concerns secrets the agent has access to in its scope, never the
 * operator's own input.
 */
class InsightAgentSecretHygienePropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Feature: platform-ux-logistics-enhancements, Property 28: Insight Agent
     * never leaks secrets.
     *
     * <p>Validates: Requirements 14.8.
     *
     * <p>When the AI_Provider is enabled, for any store data carrying secret
     * values, neither the prompt sent to the AI_Provider (system or user prompt)
     * nor the returned result contains any of those secret values.
     */
    @Property(tries = 150)
    void aiPromptAndResultNeverContainStoredSecrets(
            @ForAll("secrets") List<String> secrets,
            @ForAll("queries") String query) {

        AiAssistService aiAssistService = Mockito.mock(AiAssistService.class);
        PerformanceDailyMapper performanceDailyMapper = Mockito.mock(PerformanceDailyMapper.class);
        CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
        InsightAgentInsightMapper insightMapper = Mockito.mock(InsightAgentInsightMapper.class);

        // AI enabled; the provider returns conforming, secret-free JSON. We never
        // make the provider echo secrets — the property is that the agent does not
        // SEND secrets and does not put secrets into the result it builds.
        when(aiAssistService.isEnabled()).thenReturn(true);
        when(aiAssistService.generate(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of("{\"insights\":\"ok\",\"recommendedActions\":[\"do x\"]}"));

        // The store's stored records carry secret values in a string field.
        when(performanceDailyMapper.selectList(any())).thenReturn(secretBearingRows(secrets));
        when(campaignMapper.selectCount(any())).thenReturn(3L);

        InsightAgentServiceImpl service = new InsightAgentServiceImpl(
                aiAssistService, performanceDailyMapper, campaignMapper, insightMapper, MAPPER);

        InsightResultVo result;
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);
            result = service.query(request(query));
        }

        // Capture the exact system + user prompt handed to the AI provider.
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiAssistService).generate(anyString(), any(), any(),
                systemPrompt.capture(), userPrompt.capture());

        String prompt = systemPrompt.getValue() + "\n" + userPrompt.getValue();
        assertExcludesAll(prompt, secrets);
        assertExcludesAll(resultText(result), secrets);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 28: Insight Agent
     * never leaks secrets.
     *
     * <p>Validates: Requirements 14.8.
     *
     * <p>When the AI_Provider is disabled, the agent returns a degraded result
     * built from the same metrics summary; for any store data carrying secret
     * values, that result contains none of those secret values.
     */
    @Property(tries = 150)
    void degradedResultNeverContainsStoredSecrets(
            @ForAll("secrets") List<String> secrets,
            @ForAll("queries") String query) {

        AiAssistService aiAssistService = Mockito.mock(AiAssistService.class);
        PerformanceDailyMapper performanceDailyMapper = Mockito.mock(PerformanceDailyMapper.class);
        CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
        InsightAgentInsightMapper insightMapper = Mockito.mock(InsightAgentInsightMapper.class);

        // AI disabled -> degraded path (Req 14.7); no prompt is sent.
        when(aiAssistService.isEnabled()).thenReturn(false);
        when(performanceDailyMapper.selectList(any())).thenReturn(secretBearingRows(secrets));
        when(campaignMapper.selectCount(any())).thenReturn(3L);

        InsightAgentServiceImpl service = new InsightAgentServiceImpl(
                aiAssistService, performanceDailyMapper, campaignMapper, insightMapper, MAPPER);

        InsightResultVo result;
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);
            result = service.query(request(query));
        }

        assertThat(result.getGeneratedBy()).isEqualTo("degraded");
        assertExcludesAll(resultText(result), secrets);
    }

    // --- helpers -----------------------------------------------------------

    private static InsightQueryRequest request(String query) {
        InsightQueryRequest request = new InsightQueryRequest();
        request.setQuery(query);
        request.setSource("all");
        request.setPremium(false);
        request.setStoreId(null);
        return request;
    }

    /**
     * Build performance rows carrying secret values in a string field
     * ({@code entityType}) alongside real numeric metrics, so the agent has
     * secrets in the data it reads while still producing a non-empty summary.
     */
    private static List<PerformanceDailyEntity> secretBearingRows(List<String> secrets) {
        List<PerformanceDailyEntity> rows = new ArrayList<>();
        for (String secret : secrets) {
            rows.add(PerformanceDailyEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(UUID.randomUUID())
                    .date(LocalDate.now().minusDays(1))
                    .entityType(secret)
                    .impressions(1000L)
                    .clicks(50)
                    .orders(5)
                    .spend(new BigDecimal("12.34"))
                    .sales(new BigDecimal("56.78"))
                    .build());
        }
        return rows;
    }

    /** Flatten every operator-visible string field of the result for inspection. */
    private static String resultText(InsightResultVo result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.getQuery()).append('\n')
                .append(result.getSource()).append('\n')
                .append(result.getInsights()).append('\n')
                .append(result.getGeneratedBy()).append('\n')
                .append(result.getDegradedReason()).append('\n');
        if (result.getRecommendedActions() != null) {
            for (String a : result.getRecommendedActions()) {
                sb.append(a).append('\n');
            }
        }
        return sb.toString();
    }

    private static void assertExcludesAll(String haystack, List<String> secrets) {
        for (String secret : secrets) {
            assertThat(haystack)
                    .as("leaked secret value")
                    .doesNotContain(secret);
        }
    }

    // --- generators --------------------------------------------------------

    /**
     * Lists of distinctive secret/credential values (API keys, client secrets,
     * tokens). Each carries a recognizable credential prefix plus a random hex
     * body so it cannot collide with the aggregated numeric summary text or the
     * lowercase operator query.
     */
    @Provide
    Arbitrary<List<String>> secrets() {
        Arbitrary<String> prefix = Arbitraries.of(
                "sk_live_", "AKIA", "client_secret_", "xoxb-", "ghp_", "Bearer_");
        Arbitrary<String> body = Arbitraries.strings()
                .withCharRange('0', '9')
                .withCharRange('a', 'f')
                .ofMinLength(16)
                .ofMaxLength(40);
        Arbitrary<String> secret = Combinators.combine(prefix, body).as(String::concat);
        return secret.list().ofMinSize(1).ofMaxSize(5).uniqueElements();
    }

    /** Non-blank lowercase operator queries (the service requires a query). */
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
