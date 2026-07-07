package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Property-based test for overlapping-window confidence reduction in
 * {@link AttributionWorker#computeConfidence}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 38: Overlapping attribution lowers confidence
 *
 * <p><b>Validates: Requirements 8.5</b>
 *
 * <p>Verifies four properties of {@code computeConfidence(operation, overlapCount)}:
 * <ol>
 *   <li>As overlapCount increases, confidence monotonically decreases (or stays at the floor)</li>
 *   <li>With zero overlaps, confidence equals BASE_CONFIDENCE</li>
 *   <li>Confidence is never below MIN_CONFIDENCE regardless of overlap count</li>
 *   <li>Confidence is always in [0.0, 1.0]</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 38: Overlapping attribution lowers confidence")
class OverlappingWindowConfidencePropertyTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    /**
     * Builds an AttributionWorker with mock mappers. {@code computeConfidence} is a pure
     * function of overlapCount and does not touch the mappers, so mocks suffice.
     */
    private AttributionWorker newWorker() {
        return new AttributionWorker(
                mock(OperationMapper.class),
                mock(PerformanceDailyMapper.class),
                mock(EffectAttributionMapper.class),
                mock(CampaignMapper.class),
                mock(KeywordMapper.class),
                7);
    }

    private OperationEntity newOperation() {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(STORE_ID)
                .operationSource("ai_hosting")
                .operationScope("platform_mutation")
                .entityType("campaign")
                .entityId(CAMPAIGN_ID)
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("test-key-" + UUID.randomUUID())
                .attemptId(UUID.randomUUID())
                .syncState("effective")
                .updatedAt(LocalDateTime.now().minusDays(8))
                .build();
    }

    // =========================================================================
    // Property 1: confidence monotonically decreases (or stays at the floor)
    //             as overlapCount increases.
    //
    // Feature: amazon-ads-ai-hosting-system, Property 38: Overlapping attribution lowers confidence
    // Validates: Requirements 8.5
    // =========================================================================

    @Property(tries = 500)
    void confidenceIsMonotonicallyNonIncreasingInOverlapCount(
            @ForAll @IntRange(min = 0, max = 50) int overlapCount,
            @ForAll @IntRange(min = 1, max = 50) int extraOverlaps) {

        AttributionWorker worker = newWorker();
        OperationEntity op = newOperation();

        int higherCount = overlapCount + extraOverlaps;

        BigDecimal lower = worker.computeConfidence(op, overlapCount);
        BigDecimal higher = worker.computeConfidence(op, higherCount);

        assertThat(higher)
                .as("confidence at %d overlaps (%s) must be <= confidence at %d overlaps (%s)",
                        higherCount, higher, overlapCount, lower)
                .isLessThanOrEqualTo(lower);
    }

    // =========================================================================
    // Property 2: zero overlaps -> confidence equals BASE_CONFIDENCE.
    //
    // Validates: Requirements 8.5
    // =========================================================================

    @Property(tries = 50)
    void zeroOverlapsYieldsBaseConfidence(@ForAll("alwaysZero") int overlapCount) {
        AttributionWorker worker = newWorker();
        OperationEntity op = newOperation();

        BigDecimal confidence = worker.computeConfidence(op, overlapCount);

        assertThat(confidence)
                .as("with zero overlaps, confidence must equal BASE_CONFIDENCE")
                .isEqualByComparingTo(AttributionWorker.BASE_CONFIDENCE);
    }

    // =========================================================================
    // Property 3: confidence never below MIN_CONFIDENCE regardless of overlaps.
    //
    // Validates: Requirements 8.5
    // =========================================================================

    @Property(tries = 500)
    void confidenceNeverBelowMinimumFloor(
            @ForAll @IntRange(min = 0, max = 10_000) int overlapCount) {

        AttributionWorker worker = newWorker();
        OperationEntity op = newOperation();

        BigDecimal confidence = worker.computeConfidence(op, overlapCount);

        assertThat(confidence)
                .as("confidence (%s) must never be below MIN_CONFIDENCE (%s) at %d overlaps",
                        confidence, AttributionWorker.MIN_CONFIDENCE, overlapCount)
                .isGreaterThanOrEqualTo(AttributionWorker.MIN_CONFIDENCE);
    }

    // =========================================================================
    // Property 4: confidence always within [0.0, 1.0].
    //
    // Validates: Requirements 8.5
    // =========================================================================

    @Property(tries = 500)
    void confidenceAlwaysWithinUnitInterval(
            @ForAll @IntRange(min = 0, max = 10_000) int overlapCount) {

        AttributionWorker worker = newWorker();
        OperationEntity op = newOperation();

        BigDecimal confidence = worker.computeConfidence(op, overlapCount);

        assertThat(confidence)
                .as("confidence (%s) must be >= 0.0 at %d overlaps", confidence, overlapCount)
                .isGreaterThanOrEqualTo(ZERO);
        assertThat(confidence)
                .as("confidence (%s) must be <= 1.0 at %d overlaps", confidence, overlapCount)
                .isLessThanOrEqualTo(ONE);
    }

    // =========================================================================
    // Generators
    // =========================================================================

    /** Always-zero overlap count to exercise the base-confidence branch. */
    @Provide
    Arbitrary<Integer> alwaysZero() {
        return Arbitraries.just(0);
    }
}
