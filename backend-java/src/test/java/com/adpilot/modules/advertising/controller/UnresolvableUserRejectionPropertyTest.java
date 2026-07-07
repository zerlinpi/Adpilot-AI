package com.adpilot.modules.advertising.controller;

import com.adpilot.common.exception.BusinessException;
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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Property-based test asserting that permission-gated advertising operations are
 * rejected with an authorization error and produce no side effects when the
 * acting user cannot be resolved from the security context.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 23: Operations with
 * an unresolvable user are rejected without side effects.
 *
 * <p>Validates: Requirements 12.3.
 *
 * <p>Each permission-gated controller operation resolves the acting user via
 * {@link SecurityUtils#getCurrentUserId()} before calling its service. When the
 * {@code Audit_Context} cannot resolve an authenticated user,
 * {@code getCurrentUserId()} throws an {@code AUTH_001} authorization
 * {@link BusinessException}. These properties generate arbitrary operation
 * arguments, stub {@code SecurityUtils.getCurrentUserId()} (via
 * {@link org.mockito.MockedStatic MockedStatic}) to throw that unresolved-user
 * authorization error, invoke a representative operation, and assert that (a)
 * the call propagates the authorization error and (b) the service dependency was
 * never invoked — i.e. no target data is changed under any placeholder or
 * default identity.
 */
class UnresolvableUserRejectionPropertyTest {

    /** The authorization error raised when the acting user cannot be resolved. */
    private static BusinessException unresolvedUser() {
        return new BusinessException("AUTH_001", "No authenticated user found");
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 23: Operations with
     * an unresolvable user are rejected without side effects.
     *
     * <p>Validates: Requirements 12.3.
     *
     * <p>{@link GoalController#createGoal}, {@link GoalController#updateGoal}, and
     * {@link GoalController#deleteGoal} are rejected and never reach the service
     * when the acting user is unresolvable.
     */
    @Property(tries = 200)
    void goalControllerRejectsUnresolvableUserWithoutSideEffects(@ForAll("resourceIds") UUID id) {
        GoalService goalService = Mockito.mock(GoalService.class);
        GoalController controller = new GoalController(goalService);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenThrow(unresolvedUser());

            assertRejected(() -> controller.createGoal(new GoalCreateRequest()));
            assertRejected(() -> controller.updateGoal(id.toString(), new GoalUpdateRequest()));
            assertRejected(() -> controller.deleteGoal(id.toString()));
        }

        verifyNoInteractions(goalService);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 23: Operations with
     * an unresolvable user are rejected without side effects.
     *
     * <p>Validates: Requirements 12.3.
     *
     * <p>{@link KeywordController#updateKeyword} is rejected and never reaches the
     * service when the acting user is unresolvable.
     */
    @Property(tries = 200)
    void keywordControllerRejectsUnresolvableUserWithoutSideEffects(@ForAll("resourceIds") UUID id) {
        KeywordService keywordService = Mockito.mock(KeywordService.class);
        KeywordController controller = new KeywordController(keywordService);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenThrow(unresolvedUser());

            assertRejected(() -> controller.updateKeyword(id.toString(), new KeywordUpdateRequest()));
        }

        verifyNoInteractions(keywordService);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 23: Operations with
     * an unresolvable user are rejected without side effects.
     *
     * <p>Validates: Requirements 12.3.
     *
     * <p>{@link RecommendationController}'s apply/dismiss/watch operations are each
     * rejected and never reach the service when the acting user is unresolvable.
     */
    @Property(tries = 200)
    void recommendationControllerRejectsUnresolvableUserWithoutSideEffects(@ForAll("resourceIds") UUID id) {
        RecommendationService recommendationService = Mockito.mock(RecommendationService.class);
        RecommendationEngineService engineService = Mockito.mock(RecommendationEngineService.class);
        RecommendationController controller =
                new RecommendationController(recommendationService, engineService);

        String recId = id.toString();
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenThrow(unresolvedUser());

            assertRejected(() -> controller.applyRecommendation(recId));
            assertRejected(() -> controller.dismissRecommendation(recId));
            assertRejected(() -> controller.watchRecommendation(recId));
        }

        verifyNoInteractions(recommendationService);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 23: Operations with
     * an unresolvable user are rejected without side effects.
     *
     * <p>Validates: Requirements 12.3.
     *
     * <p>{@link SearchTermController#harvestSearchTerm} is rejected and never
     * reaches the service when the acting user is unresolvable.
     */
    @Property(tries = 200)
    void searchTermControllerRejectsUnresolvableUserWithoutSideEffects(@ForAll("resourceIds") UUID id) {
        SearchTermService searchTermService = Mockito.mock(SearchTermService.class);
        SearchTermController controller = new SearchTermController(searchTermService);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenThrow(unresolvedUser());

            assertRejected(() -> controller.harvestSearchTerm(id.toString(), new SearchTermHarvestRequest()));
        }

        verifyNoInteractions(searchTermService);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * The operation must be rejected with the {@code AUTH_001} authorization error
     * indicating the acting user could not be determined.
     */
    private static void assertRejected(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo("AUTH_001");
    }

    // --- generators --------------------------------------------------------

    /** Arbitrary target resource ids (random UUIDs). */
    @Provide
    Arbitrary<UUID> resourceIds() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }
}
