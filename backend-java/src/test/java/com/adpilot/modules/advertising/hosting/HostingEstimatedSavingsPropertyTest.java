package com.adpilot.modules.advertising.hosting;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the estimated-savings selection rule of
 * {@link HostingAnalyticsCalculator}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 39: Estimated savings selection.
 *
 * <p>Validates: Requirements 27.3, 29.3.
 *
 * <p>Only {@code spend} attribution rows belonging to an ACoS-improving operation (an operation
 * that has an {@code acos} row with negative {@code estimated_incremental_impact}) and whose own
 * spend impact is negative may contribute to the estimate. The qualifying spend impacts are summed
 * (a non-positive number) and the dashboard estimate is its non-negative magnitude. These
 * properties assert that:
 * <ul>
 *   <li>the estimate is always non-negative;</li>
 *   <li>only qualifying rows contribute — rows from non-improving operations, non-spend rows, and
 *       non-negative spend impacts never change the result;</li>
 *   <li>the estimate is exactly the magnitude (negation) of the signed qualifying spend impact;</li>
 *   <li>empty/null input yields zero; and</li>
 *   <li>adding any single non-qualifying row never changes the estimate.</li>
 * </ul>
 */
class HostingEstimatedSavingsPropertyTest {

    /** Pool of operation ids shared across generated rows so operations group naturally. */
    private static final List<UUID> OP_POOL = List.of(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 39: Estimated savings selection.
     *
     * <p>Validates: Requirements 27.3, 29.3.
     *
     * <p>The estimated savings is always non-negative, regardless of input.
     */
    @Property(tries = 300)
    void savingsIsAlwaysNonNegative(@ForAll("attributionRows") List<EffectAttributionEntity> rows) {
        BigDecimal savings = HostingAnalyticsCalculator.estimatedSpendSavings(rows);
        assertThat(savings.signum()).isGreaterThanOrEqualTo(0);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 39: Estimated savings selection.
     *
     * <p>Validates: Requirements 27.3, 29.3.
     *
     * <p>The estimate equals exactly the sum of the spend impacts of qualifying rows (spend rows of
     * ACoS-improving operations whose spend impact is negative), recomputed here from the literal
     * selection rule. Non-improving operations, non-spend rows, and non-negative impacts never
     * contribute.
     */
    @Property(tries = 300)
    void onlyQualifyingRowsContribute(@ForAll("attributionRows") List<EffectAttributionEntity> rows) {
        // Independently derive the set of ACoS-improving operations.
        Set<UUID> improvingOps = new HashSet<>();
        for (EffectAttributionEntity r : rows) {
            if (r.getOperationId() != null
                    && "acos".equalsIgnoreCase(r.getMetricType())
                    && isNegative(r.getEstimatedIncrementalImpact())) {
                improvingOps.add(r.getOperationId());
            }
        }

        // Sum only the qualifying spend impacts, independently of the implementation.
        BigDecimal expectedSigned = BigDecimal.ZERO;
        for (EffectAttributionEntity r : rows) {
            if (r.getOperationId() != null
                    && "spend".equalsIgnoreCase(r.getMetricType())
                    && isNegative(r.getEstimatedIncrementalImpact())
                    && improvingOps.contains(r.getOperationId())) {
                expectedSigned = expectedSigned.add(r.getEstimatedIncrementalImpact());
            }
        }

        assertThat(HostingAnalyticsCalculator.signedQualifyingSpendImpact(rows))
                .isEqualByComparingTo(expectedSigned);
        assertThat(HostingAnalyticsCalculator.estimatedSpendSavings(rows))
                .isEqualByComparingTo(expectedSigned.negate());
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 39: Estimated savings selection.
     *
     * <p>Validates: Requirements 27.3, 29.3.
     *
     * <p>The estimate is always the magnitude (negation) of the signed qualifying spend impact, and
     * the signed impact is non-positive.
     */
    @Property(tries = 300)
    void savingsIsMagnitudeOfSignedImpact(@ForAll("attributionRows") List<EffectAttributionEntity> rows) {
        BigDecimal signed = HostingAnalyticsCalculator.signedQualifyingSpendImpact(rows);
        BigDecimal savings = HostingAnalyticsCalculator.estimatedSpendSavings(rows);

        assertThat(signed.signum()).isLessThanOrEqualTo(0);
        assertThat(savings).isEqualByComparingTo(signed.negate());
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 39: Estimated savings selection.
     *
     * <p>Validates: Requirements 27.3, 29.3.
     *
     * <p>Empty and null input both yield zero.
     */
    @Property(tries = 1)
    void emptyOrNullYieldsZero() {
        assertThat(HostingAnalyticsCalculator.estimatedSpendSavings(null))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(HostingAnalyticsCalculator.estimatedSpendSavings(List.of()))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(HostingAnalyticsCalculator.signedQualifyingSpendImpact(null))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(HostingAnalyticsCalculator.signedQualifyingSpendImpact(List.of()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 39: Estimated savings selection.
     *
     * <p>Validates: Requirements 27.3, 29.3.
     *
     * <p>Adding a single inert row never changes the estimate. An inert row is one that can neither
     * contribute to the sum nor alter another operation's ACoS-improving status: a non-spend,
     * non-acos row (only {@code acos} rows determine improving status), or a {@code spend} row with
     * a non-negative impact. (An acos-negative row is deliberately excluded here because it can flip
     * an operation to ACoS-improving and thereby qualify existing spend rows — that is correct
     * behaviour, not a violation.)
     */
    @Property(tries = 300)
    void addingNonQualifyingRowDoesNotChangeResult(
            @ForAll("attributionRows") List<EffectAttributionEntity> rows,
            @ForAll("nonQualifyingRow") EffectAttributionEntity extra) {

        BigDecimal before = HostingAnalyticsCalculator.estimatedSpendSavings(rows);

        List<EffectAttributionEntity> augmented = new ArrayList<>(rows);
        augmented.add(extra);
        BigDecimal after = HostingAnalyticsCalculator.estimatedSpendSavings(augmented);

        assertThat(after).isEqualByComparingTo(before);
    }

    // ── generators ─────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<EffectAttributionEntity>> attributionRows() {
        return attributionRow().list().ofMinSize(0).ofMaxSize(30);
    }

    private Arbitrary<EffectAttributionEntity> attributionRow() {
        Arbitrary<UUID> ops = Arbitraries.of(OP_POOL);
        Arbitrary<String> metricTypes = Arbitraries.of("acos", "spend", "sales", "impressions", "clicks");
        return Combinators.combine(ops, metricTypes, impacts())
                .as(this::attribution);
    }

    /**
     * A row that can never change the estimate: a non-spend, non-acos row (sales/impressions/clicks
     * — these never contribute and never affect ACoS-improving status), or a {@code spend} row whose
     * impact is non-negative. The operation id is drawn from the shared pool so the row may attach to
     * any operation without effect.
     */
    @Provide
    Arbitrary<EffectAttributionEntity> nonQualifyingRow() {
        Arbitrary<UUID> ops = Arbitraries.of(OP_POOL);
        Arbitrary<EffectAttributionEntity> inertNonSpend = Combinators
                .combine(ops, Arbitraries.of("sales", "impressions", "clicks"), impacts())
                .as(this::attribution);
        Arbitrary<EffectAttributionEntity> spendNonNegative = Combinators
                .combine(ops, nonNegativeImpacts())
                .as((op, impact) -> attribution(op, "spend", impact));
        return Arbitraries.oneOf(inertNonSpend, spendNonNegative);
    }

    /** Impacts spanning negatives, zero, positives, and null (to exercise null tolerance). */
    private Arbitrary<BigDecimal> impacts() {
        Arbitrary<BigDecimal> decimals = Arbitraries.bigDecimals()
                .between(new BigDecimal("-1000"), new BigDecimal("1000"))
                .ofScale(2);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(8, decimals),
                net.jqwik.api.Tuple.of(1, Arbitraries.just(BigDecimal.ZERO)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just((BigDecimal) null)));
    }

    private Arbitrary<BigDecimal> nonNegativeImpacts() {
        Arbitrary<BigDecimal> decimals = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("1000"))
                .ofScale(2);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(8, decimals),
                net.jqwik.api.Tuple.of(1, Arbitraries.just(BigDecimal.ZERO)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just((BigDecimal) null)));
    }

    private EffectAttributionEntity attribution(UUID operationId, String metricType, BigDecimal impact) {
        EffectAttributionEntity e = new EffectAttributionEntity();
        e.setOperationId(operationId);
        e.setStoreId(UUID.randomUUID());
        e.setMetricType(metricType);
        e.setEstimatedIncrementalImpact(impact);
        return e;
    }

    private static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }
}
