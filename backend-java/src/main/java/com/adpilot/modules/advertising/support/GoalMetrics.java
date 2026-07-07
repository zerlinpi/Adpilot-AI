package com.adpilot.modules.advertising.support;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Aggregated performance metrics for a single Goal, computed strictly from the Campaigns
 * <strong>associated with that Goal</strong> (Requirement 20.1) rather than from every Campaign in
 * the Store.
 *
 * <p>This is an immutable value object. The {@code acos} figure is carried as a
 * <strong>decimal ratio</strong> following the {@link AcosScale} convention (so {@code 0.25} denotes
 * a 25% ACoS); callers multiply by 100 only for display.</p>
 *
 * <p>Validates: Requirement 20.1.</p>
 */
@Value
@Builder
public class GoalMetrics {

    /** The Goal these metrics were scoped to. */
    UUID goalId;

    /** Number of Campaigns associated with the Goal (the scope of the aggregation). */
    int campaignCount;

    BigDecimal spend;
    BigDecimal sales;
    int orders;
    long impressions;
    int clicks;

    /** ACoS as a decimal ratio (Req 17 / {@link AcosScale}); {@code spend / sales}. */
    BigDecimal acos;

    /** Return on ad spend; {@code sales / spend}. */
    BigDecimal roas;

    /** Conversion rate as a decimal ratio; {@code orders / clicks}. */
    BigDecimal conversionRate;

    /** Average cost per click; {@code spend / clicks}. */
    BigDecimal avgCpc;
}
