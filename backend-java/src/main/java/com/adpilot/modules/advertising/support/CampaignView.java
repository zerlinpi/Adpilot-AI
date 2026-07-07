package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;

/**
 * Immutable, database-free projection of the filterable attributes of a
 * Campaign (Requirement 19.4). This is the value type the pure
 * {@link CampaignFilter} predicate operates on, so the filter logic can be
 * exercised without a database or an Amazon connection (and is the type the
 * filter soundness/completeness property test — Property 6, task 10.2 —
 * generates directly).
 *
 * <p>Field meanings:
 * <ul>
 *   <li>{@code storeId} — owning store (the active-store scope key).</li>
 *   <li>{@code adType} — SP / SB / SD (mapped from the campaign type).</li>
 *   <li>{@code portfolioId} — the ad portfolio the campaign belongs to,
 *       {@code null} when unassigned.</li>
 *   <li>{@code parentAsin} — parent ASIN the campaign promotes, {@code null}
 *       when not resolvable.</li>
 *   <li>{@code targetingGoal} — the campaign's targeting goal (投放目标),
 *       {@code null} when unset.</li>
 *   <li>{@code status} — campaign status (e.g. enabled / paused).</li>
 *   <li>{@code targetAcos} — assigned Target ACoS, {@code null} when not
 *       hosted / unset.</li>
 *   <li>{@code recentAcos} — recent realized ACoS, used by smart-filter
 *       presets, {@code null} when unknown.</li>
 *   <li>{@code aiManaged} — AI入格 indicator.</li>
 *   <li>{@code hostingEnabled} — whether the campaign is under AI hosting.</li>
 * </ul>
 */
public record CampaignView(
        String storeId,
        String adType,
        String portfolioId,
        String parentAsin,
        String targetingGoal,
        String status,
        BigDecimal targetAcos,
        BigDecimal recentAcos,
        boolean aiManaged,
        boolean hostingEnabled) {
}
