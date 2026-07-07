package com.adpilot.modules.advertising.controller;

import com.adpilot.modules.advertising.dto.KeywordUpdateRequest;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.service.KeywordService;
import com.adpilot.modules.advertising.service.RecommendationEngineService;
import com.adpilot.modules.advertising.service.RecommendationService;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.advertising.vo.KeywordVo;
import com.adpilot.common.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the three precondition endpoints this rework depends on
 * (Requirement 1). They confirm — at the HTTP layer, through {@link MockMvc} —
 * that the Frontend's request shapes match the Backend's accepted contract:
 *
 * <ul>
 *   <li>campaign update accepts {@code PATCH} with JSON {@code {dailyBudget}} and
 *       applies the supplied {@code dailyBudget} (Req 1.1, 1.2);</li>
 *   <li>keyword update accepts {@code PATCH} and applies the supplied fields
 *       (Req 1.3, 1.4);</li>
 *   <li>generate-recommendations takes {@code storeId} as a query parameter and
 *       returns the generated count in the response (Req 1.5, 1.6).</li>
 * </ul>
 *
 * <p>Validates: Requirements 1.2, 1.4, 1.6.
 *
 * <p>These are conventional example-based contract tests (not property-based).
 * Each controller is wired through a standalone {@code MockMvc} so the actual
 * request-mapping annotations, HTTP-method acceptance, JSON body binding, and
 * query-parameter binding are exercised; the service layer is mocked and its
 * received arguments are captured to confirm the request was bound and applied
 * as the Frontend sends it.
 */
class PreconditionEndpointsContractTest {

    private CampaignService campaignService;
    private KeywordService keywordService;
    private RecommendationService recommendationService;
    private RecommendationEngineService recommendationEngineService;

    private MockMvc campaignMockMvc;
    private MockMvc keywordMockMvc;
    private MockMvc recommendationMockMvc;

    @BeforeEach
    void setUp() {
        campaignService = mock(CampaignService.class);
        keywordService = mock(KeywordService.class);
        recommendationService = mock(RecommendationService.class);
        recommendationEngineService = mock(RecommendationEngineService.class);

        campaignMockMvc = MockMvcBuilders
                .standaloneSetup(new CampaignController(campaignService))
                .build();
        keywordMockMvc = MockMvcBuilders
                .standaloneSetup(new KeywordController(keywordService))
                .build();
        recommendationMockMvc = MockMvcBuilders
                .standaloneSetup(new RecommendationController(recommendationService, recommendationEngineService))
                .build();

        // The KeywordController resolves the acting user via SecurityUtils.getCurrentUserId(),
        // which requires an authenticated principal in the security context.
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("contract-test@adpilot.local")
                .roles(Set.of("operations_manager"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Req 1.1 / 1.2: campaign update accepts a {@code PATCH} carrying JSON
     * {@code {"dailyBudget": ..}} and applies the supplied budget to the campaign.
     */
    @Test
    @DisplayName("PATCH /api/campaigns/{id} with JSON {dailyBudget} is accepted and applies the budget")
    void campaignUpdate_acceptsPatchWithDailyBudget_andAppliesIt() throws Exception {
        String campaignId = UUID.randomUUID().toString();
        when(campaignService.updateCampaign(eq(campaignId), any(), any(), any(), any()))
                .thenReturn(CampaignVo.builder().id(campaignId).build());

        campaignMockMvc.perform(patch("/api/campaigns/{id}", campaignId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"dailyBudget\": 60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(campaignId));

        // The supplied dailyBudget must be bound and applied to the campaign.
        ArgumentCaptor<BigDecimal> budgetCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(campaignService).updateCampaign(eq(campaignId), any(), any(), budgetCaptor.capture(), any());
        assertThat(budgetCaptor.getValue()).isNotNull();
        assertThat(budgetCaptor.getValue()).isEqualByComparingTo(new BigDecimal("60"));
    }

    /**
     * Req 1.3 / 1.4: keyword update accepts a {@code PATCH} and applies the
     * supplied fields ({@code bid}, {@code status}) to the keyword.
     */
    @Test
    @DisplayName("PATCH /api/keywords/{id} is accepted and applies the supplied fields")
    void keywordUpdate_acceptsPatch_andAppliesSuppliedFields() throws Exception {
        String keywordId = UUID.randomUUID().toString();
        when(keywordService.updateKeyword(eq(keywordId), any(KeywordUpdateRequest.class), any()))
                .thenReturn(KeywordVo.builder().id(keywordId).build());

        keywordMockMvc.perform(patch("/api/keywords/{id}", keywordId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"bid\": 1.25, \"status\": \"paused\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(keywordId));

        // The supplied fields must be bound onto the update request reaching the service.
        ArgumentCaptor<KeywordUpdateRequest> requestCaptor =
                ArgumentCaptor.forClass(KeywordUpdateRequest.class);
        verify(keywordService).updateKeyword(eq(keywordId), requestCaptor.capture(), any());
        KeywordUpdateRequest applied = requestCaptor.getValue();
        assertThat(applied.getBid()).isEqualByComparingTo(new BigDecimal("1.25"));
        assertThat(applied.getStatus()).isEqualTo("paused");
    }

    /**
     * Req 1.5 / 1.6: generate-recommendations takes {@code storeId} as a query
     * parameter and returns the generated count in the response body.
     */
    @Test
    @DisplayName("POST /api/recommendations/generate?storeId=.. returns the generated count")
    void generateRecommendations_takesStoreIdQueryParam_andReturnsGeneratedCount() throws Exception {
        String storeId = UUID.randomUUID().toString();
        when(recommendationEngineService.generateRecommendations(storeId)).thenReturn(7);

        recommendationMockMvc.perform(post("/api/recommendations/generate")
                        .param("storeId", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(7));

        // The storeId query parameter must be bound and passed to the engine.
        verify(recommendationEngineService).generateRecommendations(storeId);
    }
}
