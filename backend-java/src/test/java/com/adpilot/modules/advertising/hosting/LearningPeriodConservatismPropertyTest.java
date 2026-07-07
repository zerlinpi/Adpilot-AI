package com.adpilot.modules.advertising.hosting;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for learning-period conservatism (Requirement 19).
 *
 * <p><b>Property 33: Learning-period conservatism</b></p>
 *
 * <p><b>Validates: Requirements 19.2, 19.4</b></p>
 *
 * <p>Verifies four properties:
 * <ol>
 *   <li>During the learning period, the bid change magnitude never exceeds 10% of currentBid (Req 19.2)</li>
 *   <li>During the learning period, budget changes are always blocked (Req 19.2)</li>
 *   <li>When personality changes, the learning period restarts (Req 19.4)</li>
 *   <li>After the learning period ends, full-magnitude bid changes are allowed (Req 19.3)</li>
 * </ol>
 */
class LearningPeriodConservatismPropertyTest {

    /**
     * Tolerance for rounding: clampBidForLearningPeriod uses setScale(4, HALF_UP)
     * which can introduce up to half a unit in the 4th decimal place (0.00005).
     */
    private static final BigDecimal EPSILON = new BigDecimal("0.00005");

    /** The 10% cap applied during the learning period. */
    private static final BigDecimal MAX_CHANGE_RATIO = new BigDecimal("0.10");

    // =========================================================================
    // Property 1: During learning period, bid change ≤ 10% of currentBid (Req 19.2)
    // =========================================================================

    /**
     * For any positive currentBid and any proposedBid, the learning-period clamp
     * ensures the resulting bid differs from currentBid by at most 10%.
     *
     * <p><b>Validates: Requirements 19.2</b></p>
     */
    @Property(tries = 500)
    void bidChangeMagnitudeNeverExceedsTenPercentDuringLearningPeriod(
            @ForAll("positiveBid") BigDecimal currentBid,
            @ForAll("anyBid") BigDecimal proposedBid) {

        LearningPeriodServiceImpl service = createServiceWithActivelearningPeriod();

        BigDecimal clamped = service.clampBidForLearningPeriod(currentBid, proposedBid);

        BigDecimal maxAllowedChange = currentBid.multiply(MAX_CHANGE_RATIO);
        BigDecimal actualChange = clamped.subtract(currentBid).abs();

        assertThat(actualChange)
                .as("Bid change magnitude (%s) should not exceed 10%% of currentBid (%s = %s)",
                        actualChange, currentBid, maxAllowedChange)
                .isLessThanOrEqualTo(maxAllowedChange.add(EPSILON));
    }

    /**
     * When the proposed bid is within 10% of currentBid, the clamp returns the
     * proposed bid unchanged (no unnecessary modification).
     *
     * <p><b>Validates: Requirements 19.2</b></p>
     */
    @Property(tries = 500)
    void bidWithinTenPercentIsUnchanged(
            @ForAll("positiveBid") BigDecimal currentBid,
            @ForAll("withinTenPercentBid") WithinTenPercentBid scenario) {

        LearningPeriodServiceImpl service = createServiceWithActivelearningPeriod();

        BigDecimal proposedBid = scenario.compute(currentBid);
        // Skip if proposedBid would be negative (edge case for very small currentBid)
        Assume.that(proposedBid.signum() > 0);

        BigDecimal clamped = service.clampBidForLearningPeriod(currentBid, proposedBid);

        assertThat(clamped)
                .as("Proposed bid within 10%% should pass through unchanged")
                .isEqualByComparingTo(proposedBid);
    }

    // =========================================================================
    // Property 2: During learning period, budget changes are always blocked (Req 19.2)
    // =========================================================================

    /**
     * For any campaign currently in its learning period, shouldBlockBudgetChanges
     * always returns true — the V2 budget engine must skip this campaign entirely.
     *
     * <p><b>Validates: Requirements 19.2</b></p>
     */
    @Property(tries = 200)
    void budgetChangesAlwaysBlockedDuringLearningPeriod(
            @ForAll("learningPeriodDays") int learningPeriodDays,
            @ForAll("daysElapsed") int daysElapsed) {

        // Ensure we're within the learning period
        Assume.that(daysElapsed < learningPeriodDays);

        UUID campaignId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        CampaignLearningPeriodMapper mapper = mock(CampaignLearningPeriodMapper.class);
        CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                .campaignId(campaignId)
                .storeId(storeId)
                .startDate(LocalDate.now().minusDays(daysElapsed))
                .personalityAtStart("balanced")
                .learningPeriodDays(learningPeriodDays)
                .build();
        when(mapper.selectOne(any())).thenReturn(entity);

        LearningPeriodServiceImpl service = new LearningPeriodServiceImpl(mapper);

        assertThat(service.shouldBlockBudgetChanges(campaignId))
                .as("Budget changes must be blocked during learning period (day %d of %d)",
                        daysElapsed, learningPeriodDays)
                .isTrue();
    }

    // =========================================================================
    // Property 3: Personality change restarts the learning period (Req 19.4)
    // =========================================================================

    /**
     * When the current personality differs from the personality recorded at the
     * learning period start, checkAndRestartOnPersonalityChange returns true,
     * indicating the period was restarted.
     *
     * <p><b>Validates: Requirements 19.4</b></p>
     */
    @Property(tries = 200)
    void personalityChangeRestartsLearningPeriod(
            @ForAll("personality") String originalPersonality,
            @ForAll("personality") String newPersonality) {

        // Ensure the personalities are actually different
        Assume.that(!originalPersonality.equals(newPersonality));

        UUID campaignId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        CampaignLearningPeriodMapper mapper = mock(CampaignLearningPeriodMapper.class);
        CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                .id(UUID.randomUUID())
                .campaignId(campaignId)
                .storeId(storeId)
                .startDate(LocalDate.now().minusDays(1))
                .personalityAtStart(originalPersonality)
                .learningPeriodDays(3)
                .build();
        when(mapper.selectOne(any())).thenReturn(entity);
        when(mapper.updateById(any())).thenReturn(1);

        LearningPeriodServiceImpl service = new LearningPeriodServiceImpl(mapper);

        boolean restarted = service.checkAndRestartOnPersonalityChange(
                campaignId, storeId, newPersonality);

        assertThat(restarted)
                .as("Period should restart when personality changes from '%s' to '%s'",
                        originalPersonality, newPersonality)
                .isTrue();
    }

    /**
     * When the current personality is the same as the personality recorded at the
     * learning period start, checkAndRestartOnPersonalityChange returns false.
     *
     * <p><b>Validates: Requirements 19.4</b></p>
     */
    @Property(tries = 200)
    void samePersonalityDoesNotRestartLearningPeriod(
            @ForAll("personality") String personality) {

        UUID campaignId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        CampaignLearningPeriodMapper mapper = mock(CampaignLearningPeriodMapper.class);
        CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                .id(UUID.randomUUID())
                .campaignId(campaignId)
                .storeId(storeId)
                .startDate(LocalDate.now().minusDays(1))
                .personalityAtStart(personality)
                .learningPeriodDays(3)
                .build();
        when(mapper.selectOne(any())).thenReturn(entity);

        LearningPeriodServiceImpl service = new LearningPeriodServiceImpl(mapper);

        boolean restarted = service.checkAndRestartOnPersonalityChange(
                campaignId, storeId, personality);

        assertThat(restarted)
                .as("Period should NOT restart when personality is unchanged ('%s')", personality)
                .isFalse();
    }

    // =========================================================================
    // Property 4: After learning period ends, full-magnitude changes allowed (Req 19.3)
    // =========================================================================

    /**
     * After the learning period expires, shouldBlockBudgetChanges returns false,
     * allowing the V2 engine to propose budget changes.
     *
     * <p><b>Validates: Requirements 19.2</b></p>
     */
    @Property(tries = 200)
    void budgetChangesAllowedAfterLearningPeriodEnds(
            @ForAll("learningPeriodDays") int learningPeriodDays,
            @ForAll("extraDays") int extraDays) {

        // Ensure we're past the learning period
        int daysElapsed = learningPeriodDays + extraDays;

        UUID campaignId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        CampaignLearningPeriodMapper mapper = mock(CampaignLearningPeriodMapper.class);
        CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                .campaignId(campaignId)
                .storeId(storeId)
                .startDate(LocalDate.now().minusDays(daysElapsed))
                .personalityAtStart("balanced")
                .learningPeriodDays(learningPeriodDays)
                .build();
        when(mapper.selectOne(any())).thenReturn(entity);

        LearningPeriodServiceImpl service = new LearningPeriodServiceImpl(mapper);

        assertThat(service.shouldBlockBudgetChanges(campaignId))
                .as("Budget changes should be allowed after learning period expires " +
                        "(days elapsed: %d, period: %d)", daysElapsed, learningPeriodDays)
                .isFalse();
    }

    /**
     * After the learning period expires, the service reports not in learning period,
     * meaning the bid engine is free to apply full personality-driven adjustments.
     *
     * <p><b>Validates: Requirements 19.2</b></p>
     */
    @Property(tries = 200)
    void notInLearningPeriodAfterExpiry(
            @ForAll("learningPeriodDays") int learningPeriodDays,
            @ForAll("extraDays") int extraDays) {

        int daysElapsed = learningPeriodDays + extraDays;

        UUID campaignId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        CampaignLearningPeriodMapper mapper = mock(CampaignLearningPeriodMapper.class);
        CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                .campaignId(campaignId)
                .storeId(storeId)
                .startDate(LocalDate.now().minusDays(daysElapsed))
                .personalityAtStart("aggressive")
                .learningPeriodDays(learningPeriodDays)
                .build();
        when(mapper.selectOne(any())).thenReturn(entity);

        LearningPeriodServiceImpl service = new LearningPeriodServiceImpl(mapper);

        assertThat(service.isInLearningPeriod(campaignId))
                .as("Campaign should NOT be in learning period after expiry")
                .isFalse();
    }

    // =========================================================================
    // Generators
    // =========================================================================

    /** Positive bid values: 0.01 to 500.00 */
    @Provide
    Arbitrary<BigDecimal> positiveBid() {
        return Arbitraries.longs().between(1L, 50000L)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    /** Any bid values including those far from currentBid: 0.01 to 2000.00 */
    @Provide
    Arbitrary<BigDecimal> anyBid() {
        return Arbitraries.longs().between(1L, 200000L)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    /** Generate a scenario where proposedBid is within 10% of currentBid. */
    @Provide
    Arbitrary<WithinTenPercentBid> withinTenPercentBid() {
        // Percentage change between -10% and +10% (exclusive of the boundary for safety)
        return Arbitraries.integers().between(-99, 99)
                .map(permille -> new WithinTenPercentBid(permille));
    }

    /** Learning period duration: 1 to 14 days */
    @Provide
    Arbitrary<Integer> learningPeriodDays() {
        return Arbitraries.integers().between(1, 14);
    }

    /** Days elapsed within a learning period: 0 to 13 */
    @Provide
    Arbitrary<Integer> daysElapsed() {
        return Arbitraries.integers().between(0, 13);
    }

    /** Extra days past the learning period end: 0 to 30 */
    @Provide
    Arbitrary<Integer> extraDays() {
        return Arbitraries.integers().between(0, 30);
    }

    /** Personality names matching the known personality types */
    @Provide
    Arbitrary<String> personality() {
        return Arbitraries.of("balanced", "aggressive", "conservative", "growth", "defensive");
    }

    // =========================================================================
    // Helper types and methods
    // =========================================================================

    /**
     * Represents a proposed bid that is within 10% of a reference bid.
     * The permille field is -99 to 99 representing -9.9% to +9.9%.
     */
    record WithinTenPercentBid(int permille) {
        BigDecimal compute(BigDecimal currentBid) {
            // permille/1000 gives a ratio between -0.099 and +0.099 (within 10%)
            BigDecimal ratio = BigDecimal.valueOf(permille, 3);
            return currentBid.add(currentBid.multiply(ratio))
                    .setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * Create a LearningPeriodServiceImpl with a mock mapper that returns
     * an active learning period entity (for testing the pure clamp method).
     */
    private LearningPeriodServiceImpl createServiceWithActivelearningPeriod() {
        CampaignLearningPeriodMapper mapper = mock(CampaignLearningPeriodMapper.class);
        return new LearningPeriodServiceImpl(mapper);
    }
}
