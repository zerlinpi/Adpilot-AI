package com.adpilot.modules.writeback.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.RecommendationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.RecommendationMapper;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationWriteBack;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.writeback.vo.WriteBackResultVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link WriteBackServiceImpl#apply(String)} unsupported-type guarding.
 *
 * <p>Feature: advertising-workspace-rework, Property 27: Unsupported Recommendation_Type is
 * rejected, not applied.
 *
 * <p>Validates: Requirements 11.1, 11.2.
 *
 * <p>Property 27: <em>Applying a Recommendation whose Recommendation_Type does not map to a defined
 * Operation is rejected with an error that names the offending type, no Operation is created, and the
 * Recommendation is left in its {@code pending} state; a Recommendation whose type DOES map to a
 * defined Operation proceeds and creates the corresponding Operation.</em>
 *
 * <p>The supported set under test is {@link WriteBackServiceImpl#SUPPORTED_RECOMMENDATION_TYPES}
 * (matched case-insensitively after trimming, exactly as the production code normalizes). The apply
 * path is exercised against mocked collaborators ({@link OperationService},
 * {@link OperationWriteBack}, {@link RecommendationMapper}, {@link OperationMapper}) so that whether
 * an Operation is created is observed directly from the mock interactions.</p>
 *
 * <p>Two complementary properties are asserted across the whole input space:</p>
 * <ol>
 *   <li><b>Unsupported types are rejected (Req 11.2)</b> — for any type NOT in the supported set,
 *       {@code apply} throws a {@link BusinessException} whose message names the offending type,
 *       {@code createOperation} / {@code applyOperation} are NEVER called (no Operation is created),
 *       and the Recommendation is never written (its status stays {@code pending}).</li>
 *   <li><b>Supported types proceed (Req 11.1)</b> — for any type in the supported set, {@code apply}
 *       does not reject on the unsupported-type guard and creates exactly one corresponding
 *       Operation.</li>
 * </ol>
 */
@Label("Feature: advertising-workspace-rework, Property 27: Unsupported Recommendation_Type is rejected, not applied")
class UnsupportedRecommendationTypeRejectedPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    private static final String STATUS_PENDING = "pending";
    private static final String UNSUPPORTED_ERROR_CODE = "UNSUPPORTED_RECOMMENDATION_TYPE";

    /** The supported set, mirrored from the subject for generator constraint / filtering. */
    private static final Set<String> SUPPORTED = WriteBackServiceImpl.SUPPORTED_RECOMMENDATION_TYPES;

    /** The three writable targets a Recommendation can resolve to. */
    enum Target { KEYWORD, TARGET, CAMPAIGN }

    /** A type (supported or not), the target it acts on, and concrete before/after values. */
    record ApplyScenario(String type, Target target, String currentValue, String recommendedValue) {}

    /**
     * Feature: advertising-workspace-rework, Property 27: Unsupported Recommendation_Type is
     * rejected, not applied.
     *
     * <p>Validates: Requirements 11.2.
     *
     * <p>An unsupported Recommendation_Type is rejected naming the type, creates no Operation, and
     * leaves the Recommendation {@code pending}.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 27: an unsupported Recommendation_Type is rejected, no Operation is created, and the Recommendation stays pending")
    void unsupportedTypeIsRejectedWithoutCreatingOperation(@ForAll("unsupportedScenarios") ApplyScenario s) {
        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationService operationService = mock(OperationService.class);
        OperationWriteBack operationWriteBack = mock(OperationWriteBack.class);

        RecommendationEntity recommendation = buildRecommendation(s);
        UUID recId = recommendation.getId();
        when(recommendationMapper.selectById(any())).thenReturn(recommendation);

        WriteBackServiceImpl service = new WriteBackServiceImpl(
                recommendationMapper, operationMapper, operationService, operationWriteBack);

        // Rejected with an error that names the offending type (Req 11.2).
        assertThatThrownBy(() -> service.apply(recId.toString()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(s.type())
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(UNSUPPORTED_ERROR_CODE);

        // No Operation is created and none is submitted.
        verify(operationService, never()).createOperation(any());
        verify(operationWriteBack, never()).applyOperation(any());

        // The Recommendation is never written: it is left in its pending state (Req 11.2).
        verify(recommendationMapper, never()).updateById(any());
        assertThat(recommendation.getStatus()).isEqualTo(STATUS_PENDING);
    }

    /**
     * Feature: advertising-workspace-rework, Property 27: Unsupported Recommendation_Type is
     * rejected, not applied.
     *
     * <p>Validates: Requirements 11.1.
     *
     * <p>A supported Recommendation_Type is NOT rejected by the type guard and creates exactly one
     * corresponding Operation.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 27: a supported Recommendation_Type proceeds and creates the corresponding Operation")
    void supportedTypeProceedsAndCreatesOperation(@ForAll("supportedScenarios") ApplyScenario s) {
        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationService operationService = mock(OperationService.class);
        OperationWriteBack operationWriteBack = mock(OperationWriteBack.class);

        RecommendationEntity recommendation = buildRecommendation(s);
        UUID recId = recommendation.getId();
        UUID opId = UUID.randomUUID();

        when(recommendationMapper.selectById(any())).thenReturn(recommendation);
        // createOperation resolves to a submittable (pending) Operation, then applyOperation settles
        // it to effective — the concrete outcome is irrelevant to this property; what matters is that
        // a supported type is NOT rejected and DOES create an Operation.
        when(operationService.createOperation(any())).thenReturn(result(opId, SyncState.PENDING));
        when(operationWriteBack.applyOperation(eq(opId))).thenReturn(result(opId, SyncState.EFFECTIVE));
        when(operationMapper.selectById(eq(opId)))
                .thenReturn(operationEntity(opId, recommendation.getStoreId(), SyncState.EFFECTIVE));

        WriteBackServiceImpl service = new WriteBackServiceImpl(
                recommendationMapper, operationMapper, operationService, operationWriteBack);

        WriteBackResultVo result = service.apply(recId.toString());

        // Not rejected by the type guard, and exactly one corresponding Operation is created (Req 11.1).
        assertThat(result).isNotNull();
        verify(operationService, times(1)).createOperation(any());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static OperationResult result(UUID opId, SyncState state) {
        return OperationResult.builder()
                .operationId(opId)
                .logicalOperationId(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .syncState(state)
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .coalesced(false)
                .build();
    }

    private static OperationEntity operationEntity(UUID opId, UUID storeId, SyncState state) {
        return OperationEntity.builder()
                .id(opId)
                .storeId(storeId)
                .operationSource("recommendation")
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("recommendation:" + opId)
                .attemptId(UUID.randomUUID())
                .syncState(OperationMachineValues.toValue(state))
                .platformReference(state == SyncState.EFFECTIVE ? "amzn-ref-" + opId : null)
                .build();
    }

    private static RecommendationEntity buildRecommendation(ApplyScenario s) {
        RecommendationEntity.RecommendationEntityBuilder b = RecommendationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .type(s.type())
                .title("rec")
                .currentValue(s.currentValue())
                .recommendedValue(s.recommendedValue())
                .status(STATUS_PENDING);
        switch (s.target()) {
            case KEYWORD -> b.keywordId(UUID.randomUUID());
            case TARGET -> b.targetId(UUID.randomUUID());
            case CAMPAIGN -> b.campaignId(UUID.randomUUID());
        }
        return b.build();
    }

    /** Normalize exactly as {@link WriteBackServiceImpl} does to compare against the supported set. */
    private static boolean isSupported(String type) {
        if (type == null) {
            return false;
        }
        return SUPPORTED.contains(type.trim().toLowerCase(Locale.ROOT));
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Non-blank type strings that are NOT supported after normalization. Generated freely (alpha +
     * numeric + underscore, including mixed case) and filtered so no value collides with a supported
     * type under the production trim/lowercase normalization.
     */
    @Provide
    Arbitrary<ApplyScenario> unsupportedScenarios() {
        Arbitrary<String> unsupportedTypes = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .numeric()
                .withChars('_')
                .ofMinLength(1)
                .ofMaxLength(24)
                .filter(t -> !t.isBlank())
                .filter(t -> !isSupported(t));
        return baseScenarios(unsupportedTypes);
    }

    /** Types drawn from the supported set itself, in their canonical form. */
    @Provide
    Arbitrary<ApplyScenario> supportedScenarios() {
        Arbitrary<String> supportedTypes = Arbitraries.of(SUPPORTED.toArray(new String[0]));
        return baseScenarios(supportedTypes);
    }

    private Arbitrary<ApplyScenario> baseScenarios(Arbitrary<String> types) {
        Arbitrary<Target> targets = Arbitraries.of(Target.class);
        Arbitrary<String> currentValues = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> recommendedValues = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        return Combinators.combine(types, targets, currentValues, recommendedValues).as(ApplyScenario::new);
    }
}
