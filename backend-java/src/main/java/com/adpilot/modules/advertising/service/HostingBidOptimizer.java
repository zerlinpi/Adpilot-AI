package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Pure, side-effect-free bid math shared by AI_Hosting (Req 21.2) and
 * Ad_Placement_Lock (Req 26.3).
 *
 * <p>The {@link #adjustBid adjustBid} function moves a campaign/keyword bid
 * toward its {@code targetAcos} while guaranteeing the result is always
 * <em>both</em>:
 * <ul>
 *   <li>within the permitted absolute range {@code [minBid, maxBid]}, and</li>
 *   <li>within &plusmn;{@code maxChangePct} of the {@code currentBid}.</li>
 * </ul>
 *
 * <p>Direction follows ACoS efficiency:
 * <ul>
 *   <li>recent ACoS <strong>above</strong> the target (too expensive) &rarr; the
 *       bid <strong>never increases</strong> (it decreases, toward the target);</li>
 *   <li>recent ACoS <strong>below</strong> the target (room to spend) &rarr; the
 *       bid <strong>never decreases</strong> (it increases, toward the target);</li>
 *   <li>recent ACoS <strong>equal</strong> to the target &rarr; the bid is a
 *       fixpoint (no-op): the adjusted bid equals the current bid.</li>
 * </ul>
 *
 * <p>{@link #clampToRange clampToRange} exposes the same range clamp used by
 * placement-lock strategies to keep a chosen keyword bid within its configured
 * {@code [bidMin, bidMax]} window.
 *
 * <p>This class is stateless and holds no Spring dependencies so it can be unit-
 * and property-tested in isolation (see Property 4).
 */
public final class HostingBidOptimizer {

    /** Precision used for intermediate division (e.g. proportional intensity). */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private HostingBidOptimizer() {
    }

    /**
     * Compute the adjusted bid that moves {@code currentBid} toward
     * {@code targetAcos}, clamped within {@code [minBid, maxBid]} and within
     * &plusmn;{@code maxChangePct} of {@code currentBid}.
     *
     * <p>The magnitude of the move is proportional to how far {@code recentAcos}
     * is from {@code targetAcos} (relative to the target), scaled by the maximum
     * permitted step; it never exceeds {@code maxChangePct}. The result is then
     * clamped into the intersection of the absolute range and the percentage
     * band, so both bounds always hold.
     *
     * <p>Contract / guarantees (assuming {@code currentBid} lies within
     * {@code [minBid, maxBid]} and {@code minBid <= maxBid}):
     * <ul>
     *   <li>{@code minBid <= result <= maxBid};</li>
     *   <li>{@code |result - currentBid| <= currentBid * maxChangePct/100};</li>
     *   <li>if {@code recentAcos > targetAcos} then {@code result <= currentBid};</li>
     *   <li>if {@code recentAcos < targetAcos} then {@code result >= currentBid};</li>
     *   <li>if {@code recentAcos == targetAcos} then {@code result == currentBid}.</li>
     * </ul>
     *
     * @param currentBid    the current bid; must not be {@code null}
     * @param recentAcos    the recently observed ACoS (any non-negative scale);
     *                      must not be {@code null}
     * @param targetAcos    the Target_ACoS to optimize toward; must not be
     *                      {@code null}
     * @param minBid        the minimum permitted bid; must not be {@code null}
     * @param maxBid        the maximum permitted bid; must not be {@code null}
     * @param maxChangePct  the maximum permitted change as a percentage of the
     *                      current bid (e.g. {@code 20} means &plusmn;20%); must
     *                      not be {@code null} and must be {@code >= 0}
     * @return the adjusted bid
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code maxChangePct} is negative
     */
    public static BigDecimal adjustBid(BigDecimal currentBid,
                                       BigDecimal recentAcos,
                                       BigDecimal targetAcos,
                                       BigDecimal minBid,
                                       BigDecimal maxBid,
                                       BigDecimal maxChangePct) {
        requireNonNull(currentBid, "currentBid");
        requireNonNull(recentAcos, "recentAcos");
        requireNonNull(targetAcos, "targetAcos");
        requireNonNull(minBid, "minBid");
        requireNonNull(maxBid, "maxBid");
        requireNonNull(maxChangePct, "maxChangePct");
        if (maxChangePct.signum() < 0) {
            throw new IllegalArgumentException("maxChangePct must not be negative");
        }

        int direction = recentAcos.compareTo(targetAcos);

        // Fixpoint: at the target there is nothing to do.
        if (direction == 0) {
            return currentBid;
        }

        // The percentage band around the current bid: [currentBid*(1-p), currentBid*(1+p)].
        BigDecimal fraction = maxChangePct.divide(HUNDRED, MC);
        BigDecimal maxStep = currentBid.multiply(fraction);
        BigDecimal bandLower = currentBid.subtract(maxStep);
        BigDecimal bandUpper = currentBid.add(maxStep);

        // Intersect the percentage band with the absolute [minBid, maxBid] range.
        BigDecimal windowLower = bandLower.max(minBid);
        BigDecimal windowUpper = bandUpper.min(maxBid);

        // Proportional move intensity in [0, 1] based on distance from target.
        BigDecimal intensity = proportionalIntensity(recentAcos, targetAcos);
        BigDecimal step = maxStep.multiply(intensity);

        BigDecimal desired;
        if (direction > 0) {
            // recent ACoS above target -> too expensive -> reduce the bid.
            desired = currentBid.subtract(step);
        } else {
            // recent ACoS below target -> room to spend -> raise the bid.
            desired = currentBid.add(step);
        }

        return clampToRange(desired, windowLower, windowUpper);
    }

    /**
     * Clamp {@code bid} into the inclusive range {@code [min, max]}.
     * Pure function: {@code result = min(max(bid, min), max)}.
     *
     * <p>Used directly by placement-lock strategies to keep a keyword bid within
     * its configured {@code [bidMin, bidMax]} window (Req 26.3).
     *
     * @param bid the proposed bid; must not be {@code null}
     * @param min the lower bound; must not be {@code null}
     * @param max the upper bound; must not be {@code null}
     * @return {@code bid} clamped to {@code [min, max]}; when {@code min > max}
     *         the lower bound wins (returns {@code min})
     */
    public static BigDecimal clampToRange(BigDecimal bid, BigDecimal min, BigDecimal max) {
        requireNonNull(bid, "bid");
        requireNonNull(min, "min");
        requireNonNull(max, "max");
        BigDecimal result = bid;
        if (result.compareTo(max) > 0) {
            result = max;
        }
        if (result.compareTo(min) < 0) {
            result = min;
        }
        return result;
    }

    /**
     * Safety_Boundary-aware bid clamp (Req 22.3, 22.4, 22.5, 22.6, 49.4, 49.10,
     * 49.11). It moves {@code currentBid} toward {@code targetAcos} applying
     * <strong>at most</strong> the minimum of the personality-allowed magnitude
     * ({@code maxChangePct}) and the resolved Safety_Boundary, while guaranteeing
     * the result stays within the effective {@code [minBid, maxBid]} window.
     *
     * <p>The effective absolute window is resolved from {@code safetyBoundary}:
     * its {@link SafetyBoundaryLimit#MIN_BID}/{@link SafetyBoundaryLimit#MAX_BID}
     * win when defined, otherwise the supplied {@code defaultMinBid}/
     * {@code defaultMaxBid} (the System-default hard floor/ceiling) apply — exactly
     * the precedence resolved by {@code SafetyBoundaryResolver} (Req 22.11 /
     * 49.11). The boundary is a <strong>hard limit</strong> and is
     * <strong>never widened</strong> to admit an out-of-range current value
     * (Req 22.4).
     *
     * <p>Contract / guarantees:
     * <ul>
     *   <li>the applied move magnitude never exceeds
     *       {@code currentBid * maxChangePct/100} (personality magnitude cap,
     *       Req 49.4/49.10);</li>
     *   <li><strong>in-range</strong> ({@code minBid <= currentBid <= maxBid}):
     *       the result is kept within {@code [minBid, maxBid]} and within the
     *       percentage band, moving toward the target (or a no-op at the target),
     *       and {@link SafeBidAdjustment#isFlagged()} is {@code false};</li>
     *   <li><strong>out-of-range</strong> ({@code currentBid > maxBid} or
     *       {@code currentBid < minBid}): the result moves ONLY toward the safe
     *       range (never further out), bounded by the magnitude cap, never
     *       overshooting past the nearest boundary, and
     *       {@link SafeBidAdjustment#isFlagged()} is {@code true} so the caller
     *       can flag the Campaign and raise an alert (Req 22.5).</li>
     * </ul>
     *
     * @param currentBid    the current bid; must not be {@code null}
     * @param recentAcos    the recently observed ACoS; must not be {@code null}
     * @param targetAcos    the Target_ACoS to optimize toward; must not be {@code null}
     * @param maxChangePct  the personality-allowed maximum change as a percentage
     *                      of the current bid (e.g. {@code 20} means &plusmn;20%);
     *                      must not be {@code null} and must be {@code >= 0}
     * @param safetyBoundary the resolved effective Safety_Boundary; must not be
     *                      {@code null} (an undefined MIN_BID/MAX_BID falls back to
     *                      the supplied defaults)
     * @param defaultMinBid the System-default absolute bid floor used when the
     *                      boundary leaves {@code MIN_BID} undefined; must not be {@code null}
     * @param defaultMaxBid the System-default absolute bid ceiling used when the
     *                      boundary leaves {@code MAX_BID} undefined; must not be {@code null}
     * @return the adjusted bid paired with whether the Campaign must be flagged
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code maxChangePct} is negative
     */
    public static SafeBidAdjustment adjustBidWithinBoundary(BigDecimal currentBid,
                                                            BigDecimal recentAcos,
                                                            BigDecimal targetAcos,
                                                            BigDecimal maxChangePct,
                                                            SafetyBoundary safetyBoundary,
                                                            BigDecimal defaultMinBid,
                                                            BigDecimal defaultMaxBid) {
        requireNonNull(currentBid, "currentBid");
        requireNonNull(recentAcos, "recentAcos");
        requireNonNull(targetAcos, "targetAcos");
        requireNonNull(maxChangePct, "maxChangePct");
        requireNonNull(safetyBoundary, "safetyBoundary");
        requireNonNull(defaultMinBid, "defaultMinBid");
        requireNonNull(defaultMaxBid, "defaultMaxBid");
        if (maxChangePct.signum() < 0) {
            throw new IllegalArgumentException("maxChangePct must not be negative");
        }

        // Resolve the effective hard window from the Safety_Boundary, falling back
        // to the System-default floor/ceiling for any limit the boundary leaves
        // undefined (Req 22.11 / 49.11). The boundary is never widened (Req 22.4).
        BigDecimal effectiveMin = safetyBoundary.get(SafetyBoundaryLimit.MIN_BID).orElse(defaultMinBid);
        BigDecimal effectiveMax = safetyBoundary.get(SafetyBoundaryLimit.MAX_BID).orElse(defaultMaxBid);

        // The personality-allowed magnitude: at most +/- maxChangePct of currentBid.
        BigDecimal maxStep = currentBid.multiply(maxChangePct.divide(HUNDRED, MC)).abs();

        // Out-of-range above the ceiling: only ever move down toward the safe range
        // (Req 22.5). Bounded by the magnitude cap; never below the ceiling so we
        // never overshoot into the range further than necessary, and never up.
        if (currentBid.compareTo(effectiveMax) > 0) {
            BigDecimal moved = currentBid.subtract(maxStep).max(effectiveMax);
            return new SafeBidAdjustment(moved, true);
        }

        // Out-of-range below the floor: only ever move up toward the safe range.
        if (currentBid.compareTo(effectiveMin) < 0) {
            BigDecimal moved = currentBid.add(maxStep).min(effectiveMin);
            return new SafeBidAdjustment(moved, true);
        }

        // In-range: reuse the proportional clamp, bounded by the personality band
        // intersected with the hard [effectiveMin, effectiveMax] window.
        BigDecimal adjusted = adjustBid(
                currentBid, recentAcos, targetAcos, effectiveMin, effectiveMax, maxChangePct);
        return new SafeBidAdjustment(adjusted, false);
    }

    /**
     * The outcome of {@link #adjustBidWithinBoundary}: the clamped adjusted bid
     * and whether the Campaign must be flagged because its current value was
     * outside the hard Safety_Boundary (Req 22.5). Immutable value object.
     */
    public static final class SafeBidAdjustment {

        private final BigDecimal adjustedBid;
        private final boolean flagged;

        SafeBidAdjustment(BigDecimal adjustedBid, boolean flagged) {
            this.adjustedBid = adjustedBid;
            this.flagged = flagged;
        }

        /** The bid after applying the personality magnitude cap and the hard Safety_Boundary. */
        public BigDecimal getAdjustedBid() {
            return adjustedBid;
        }

        /**
         * {@code true} when the current bid was already outside the resolved
         * Safety_Boundary, so the caller MUST flag the Campaign and raise an alert
         * (and MAY pause hosting) per Req 22.5.
         */
        public boolean isFlagged() {
            return flagged;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SafeBidAdjustment)) {
                return false;
            }
            SafeBidAdjustment that = (SafeBidAdjustment) o;
            return flagged == that.flagged
                    && Objects.compare(adjustedBid, that.adjustedBid, BigDecimal::compareTo) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(adjustedBid == null ? null : adjustedBid.stripTrailingZeros(), flagged);
        }

        @Override
        public String toString() {
            return "SafeBidAdjustment{adjustedBid=" + adjustedBid + ", flagged=" + flagged + '}';
        }
    }

    /**
     * Distance of {@code recentAcos} from {@code targetAcos}, normalized by the
     * target and capped to {@code [0, 1]}. When the target is non-positive the
     * intensity saturates to {@code 1} so the full permitted step is taken in the
     * correct direction.
     */
    private static BigDecimal proportionalIntensity(BigDecimal recentAcos, BigDecimal targetAcos) {
        if (targetAcos.signum() <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal distance = recentAcos.subtract(targetAcos).abs();
        BigDecimal ratio = distance.divide(targetAcos, MC);
        return ratio.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : ratio;
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
    }
}
