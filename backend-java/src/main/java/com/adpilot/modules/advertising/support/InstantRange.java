package com.adpilot.modules.advertising.support;

import java.time.Instant;
import java.time.Duration;
import java.util.Objects;

/**
 * A half-open instant interval {@code [startInclusive, endExclusive)} produced
 * by {@link MarketplaceTimezone} when it resolves a day boundary or a date range
 * into absolute UTC instants.
 *
 * <p>Half-open semantics ({@code start} included, {@code end} excluded) are used
 * so that consecutive days/ranges tile the timeline without overlap or gaps: a
 * day's {@code endExclusive} is exactly the next day's {@code startInclusive}.
 * Metric queries should filter as {@code timestamp >= startInclusive AND
 * timestamp < endExclusive}.
 */
public final class InstantRange {

    private final Instant startInclusive;
    private final Instant endExclusive;

    public InstantRange(Instant startInclusive, Instant endExclusive) {
        this.startInclusive = Objects.requireNonNull(startInclusive, "startInclusive");
        this.endExclusive = Objects.requireNonNull(endExclusive, "endExclusive");
        if (endExclusive.isBefore(startInclusive)) {
            throw new IllegalArgumentException(
                    "endExclusive (" + endExclusive + ") must not precede startInclusive (" + startInclusive + ")");
        }
    }

    /** The first instant included in the range. */
    public Instant startInclusive() {
        return startInclusive;
    }

    /** The first instant after the range; never included. */
    public Instant endExclusive() {
        return endExclusive;
    }

    /** Whether {@code instant} falls within {@code [startInclusive, endExclusive)}. */
    public boolean contains(Instant instant) {
        return instant != null
                && !instant.isBefore(startInclusive)
                && instant.isBefore(endExclusive);
    }

    /** The wall-clock-independent length of the range; spans DST shifts correctly. */
    public Duration duration() {
        return Duration.between(startInclusive, endExclusive);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InstantRange other)) {
            return false;
        }
        return startInclusive.equals(other.startInclusive)
                && endExclusive.equals(other.endExclusive);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startInclusive, endExclusive);
    }

    @Override
    public String toString() {
        return "InstantRange[" + startInclusive + ", " + endExclusive + ")";
    }
}
