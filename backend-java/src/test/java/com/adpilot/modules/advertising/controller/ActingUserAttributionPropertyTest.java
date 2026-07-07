package com.adpilot.modules.advertising.controller;

import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.GoalCreateRequest;
import com.adpilot.modules.advertising.dto.GoalUpdateRequest;
import com.adpilot.modules.advertising.dto.KeywordUpdateRequest;
import com.adpilot.modules.advertising.dto.SearchTermHarvestRequest;
import com.adpilot.modules.advertising.service.GoalService;
import com.adpilot.modules.advertising.service.KeywordService;
import com.adpilot.modules.advertising.service.RecommendationEngineService;
import com.adpilot.modules.advertising.service.RecommendationService;
import com.adpilot.modules.advertising.service.SearchTermService;
import com.adpilot.modules.advertising.vo.GoalVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for acting-user attribution across the four advertising
 * controllers ({@link GoalController}, {@link KeywordController},
 * {@link RecommendationController}, {@link SearchTermController}).
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 22: Authenticated
 * advertising operations are attributed to the acting user.
 *
 * <p>Validates: Requirements 12.1, 12.2, 12.4.
 *
 * <p>Each permission-gated controller operation resolves the acting user via
 * {@link SecurityUtils#getCurrentUserId()} before calling its service and passes
 * that resolved id as the {@code userId} acting-user attribution argument. These
 * properties generate an arbitrary authenticated user id (UUID), stub
 * {@code SecurityUtils.getCurrentUserId()} (via {@link MockedStatic
 * MockedStatic}) to return it, mock the service dependencies, invoke a
 * representative operation, and capture the {@code userId} the service received.
 * The captured id must equal the resolved acting user exactly and must never be
 * the literal {@code "system"} placeholder.
 */
class ActingUserAttributionPropertyTest {

    /**
     * Feature: platform-ux-logistics-enhancements, Property 22: Authenticated
     * advertising operations are attributed to the acting user.
     *
     * <p>Validates: Requirements 12.1, 12.2, 12.4.
     *
     * <p>{@link GoalController#createGoal} and {@link GoalController#updateGoal}
     * attribute the operation to the resolved acting user.
     */
    @Property(tries = 200)
    void goalControllerAttributesOperationsToActingUser(@ForAll("userIds") UUID userId) {
        GoalService goalService = Mockito.mock(GoalService.class);
        GoalController controller = new GoalController(goalService);
        when(goalService.createGoal(any(), any())).thenReturn(Mockito.mock(GoalVo.class));

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(userId.toString());

            controller.createGoal(new GoalCreateRequest());
            controller.updateGoal(UUID.randomUUID().toString(), new GoalUpdateRequest());
        }

        ArgumentCaptor<String> createCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalService).createGoal(any(GoalCreateRequest.class), createCaptor.capture());
        assertAttributedTo(createCaptor.getValue(), userId);

        ArgumentCaptor<String> updateCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalService).updateGoal(any(), any(GoalUpdateRequest.class), updateCaptor.capture());
        assertAttributedTo(updateCaptor.getValue(), userId);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 22: Authenticated
     * advertising operations are attributed to the acting user.
     *
     * <p>Validates: Requirements 12.1, 12.2, 12.4.
     *
     * <p>{@link KeywordController#updateKeyword} attributes the update to the
     * resolved acting user.
     */
    @Property(tries = 200)
    void keywordControllerAttributesUpdateToActingUser(@ForAll("userIds") UUID userId) {
        KeywordService keywordService = Mockito.mock(KeywordService.class);
        KeywordController controller = new KeywordController(keywordService);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(userId.toString());
            controller.updateKeyword(UUID.randomUUID().toString(), new KeywordUpdateRequest());
        }

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(keywordService).updateKeyword(any(), any(KeywordUpdateRequest.class), captor.capture());
        assertAttributedTo(captor.getValue(), userId);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 22: Authenticated
     * advertising operations are attributed to the acting user.
     *
     * <p>Validates: Requirements 12.1, 12.2, 12.4.
     *
     * <p>{@link RecommendationController}'s apply/dismiss/watch operations each
     * attribute the action to the resolved acting user.
     */
    @Property(tries = 200)
    void recommendationControllerAttributesOperationsToActingUser(@ForAll("userIds") UUID userId) {
        RecommendationService recommendationService = Mockito.mock(RecommendationService.class);
        RecommendationEngineService engineService = Mockito.mock(RecommendationEngineService.class);
        RecommendationController controller =
                new RecommendationController(recommendationService, engineService);

        String recId = UUID.randomUUID().toString();
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(userId.toString());
            controller.applyRecommendation(recId);
            controller.dismissRecommendation(recId);
            controller.watchRecommendation(recId);
        }

        ArgumentCaptor<String> applyCaptor = ArgumentCaptor.forClass(String.class);
        verify(recommendationService).applyRecommendation(eq(recId), applyCaptor.capture());
        assertAttributedTo(applyCaptor.getValue(), userId);

        ArgumentCaptor<String> dismissCaptor = ArgumentCaptor.forClass(String.class);
        verify(recommendationService).dismissRecommendation(eq(recId), dismissCaptor.capture());
        assertAttributedTo(dismissCaptor.getValue(), userId);

        ArgumentCaptor<String> watchCaptor = ArgumentCaptor.forClass(String.class);
        verify(recommendationService).watchRecommendation(eq(recId), watchCaptor.capture());
        assertAttributedTo(watchCaptor.getValue(), userId);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 22: Authenticated
     * advertising operations are attributed to the acting user.
     *
     * <p>Validates: Requirements 12.1, 12.2, 12.4.
     *
     * <p>{@link SearchTermController#harvestSearchTerm} attributes the harvest to
     * the resolved acting user.
     */
    @Property(tries = 200)
    void searchTermControllerAttributesHarvestToActingUser(@ForAll("userIds") UUID userId) {
        SearchTermService searchTermService = Mockito.mock(SearchTermService.class);
        SearchTermController controller = new SearchTermController(searchTermService);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(userId.toString());
            controller.harvestSearchTerm(UUID.randomUUID().toString(), new SearchTermHarvestRequest());
        }

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(searchTermService)
                .harvestSearchTerm(any(), any(SearchTermHarvestRequest.class), captor.capture());
        assertAttributedTo(captor.getValue(), userId);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * The acting-user attribution the service received must be exactly the
     * resolved authenticated user id, and never the literal "system" placeholder.
     */
    private static void assertAttributedTo(String actual, UUID expectedUserId) {
        assertThat(actual).isEqualTo(expectedUserId.toString());
        assertThat(actual).isNotEqualToIgnoringCase("system");
    }

    // --- generators --------------------------------------------------------

    /** Arbitrary authenticated user ids (random UUIDs). */
    @Provide
    Arbitrary<UUID> userIds() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }
}
