package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, side-effect-free resolver for a Campaign's effective Safety_Boundary
 * (安全边界).
 *
 * <p>It resolves <strong>each {@link SafetyBoundaryLimit} independently</strong>
 * through the 5-level precedence hierarchy of Req 6.4:
 *
 * <pre>Campaign override &gt; Goal boundary &gt; Store policy &gt; Organization policy &gt; System default</pre>
 *
 * <p>The single, exhaustively-stated rule is: for a given limit, the resolver
 * folds all level values through that limit's
 * {@link SafetyBoundaryLimit#moreRestrictive(BigDecimal, BigDecimal)} operator,
 * so the result is the most-restrictive-wins intersection — inheritance can only
 * tighten (Req 6.2, 6.4).
 *
 * <p>The class holds no Spring dependencies so it can be unit- and property-
 * tested in isolation; it is the single source of truth targeted by the
 * Safety_Boundary resolution property tests.
 */
public final class SafetyBoundaryResolver {

    private SafetyBoundaryResolver() {
        // Utility class — not instantiable.
    }

    /**
     * Resolve the effective Safety_Boundary from the four hierarchy levels.
     * (Legacy 4-level overload for backward compatibility; organization level
     * is treated as empty.)
     *
     * @param campaignOverride Campaign-level overrides (highest specificity)
     * @param goalBoundary     boundary stored on the Campaign's Goal
     * @param storePolicy      Active_Store policy
     * @param systemDefault    configurable backend default (lowest specificity)
     * @return the resolved {@link SafetyBoundary}
     */
    public static SafetyBoundary resolve(SafetyBoundaryLimits campaignOverride,
                                         SafetyBoundaryLimits goalBoundary,
                                         SafetyBoundaryLimits storePolicy,
                                         SafetyBoundaryLimits systemDefault) {
        return resolve(campaignOverride, goalBoundary, storePolicy, null, systemDefault);
    }

    /**
     * Resolve the effective Safety_Boundary from the full 5-level hierarchy
     * (Req 6.4): campaign → goal → store → organization → system.
     *
     * @param campaignOverride     Campaign-level overrides (highest specificity)
     * @param goalBoundary         boundary stored on the Campaign's Goal
     * @param storePolicy          Active_Store policy
     * @param organizationPolicy   Organization-level policy (may be null)
     * @param systemDefault        configurable backend default (lowest specificity)
     * @return the resolved {@link SafetyBoundary}
     */
    public static SafetyBoundary resolve(SafetyBoundaryLimits campaignOverride,
                                         SafetyBoundaryLimits goalBoundary,
                                         SafetyBoundaryLimits storePolicy,
                                         SafetyBoundaryLimits organizationPolicy,
                                         SafetyBoundaryLimits systemDefault) {
        Map<SafetyBoundaryLevel, SafetyBoundaryLimits> levels =
                new EnumMap<>(SafetyBoundaryLevel.class);
        levels.put(SafetyBoundaryLevel.CAMPAIGN_OVERRIDE, orEmpty(campaignOverride));
        levels.put(SafetyBoundaryLevel.GOAL_BOUNDARY, orEmpty(goalBoundary));
        levels.put(SafetyBoundaryLevel.STORE_POLICY, orEmpty(storePolicy));
        levels.put(SafetyBoundaryLevel.ORGANIZATION_POLICY, orEmpty(organizationPolicy));
        levels.put(SafetyBoundaryLevel.SYSTEM_DEFAULT, orEmpty(systemDefault));
        return resolve(levels);
    }

    /**
     * Resolve the effective Safety_Boundary from a map of contributions keyed by
     * level. Missing or {@code null} entries are treated as defining nothing.
     * Levels are always scanned in {@link SafetyBoundaryLevel} declaration
     * (precedence) order regardless of the map's own iteration order, so the
     * result is deterministic.
     *
     * <p>The resolution uses <strong>most-restrictive-wins</strong> semantics
     * (Req 6.4): for a given limit, ALL levels that define it are folded through
     * that limit's {@link SafetyBoundaryLimit#moreRestrictive(BigDecimal, BigDecimal)}
     * operator. The result is the most-restrictive value across all defining
     * levels — inheritance can only tighten.
     *
     * <p>Source attribution tracks which level contributed the final winning value.
     * When a new level's value ties with the current winner (i.e. they are equally
     * restrictive), the higher-precedence (earlier) level keeps attribution.
     *
     * @param contributions per-level partial limit sets; may be {@code null}
     * @return the resolved {@link SafetyBoundary}
     */
    public static SafetyBoundary resolve(Map<SafetyBoundaryLevel, SafetyBoundaryLimits> contributions) {
        Map<SafetyBoundaryLevel, SafetyBoundaryLimits> safe =
                contributions == null ? new LinkedHashMap<>() : contributions;

        EnumMap<SafetyBoundaryLimit, BigDecimal> resolvedValues =
                new EnumMap<>(SafetyBoundaryLimit.class);
        EnumMap<SafetyBoundaryLimit, SafetyBoundaryLevel> resolvedSources =
                new EnumMap<>(SafetyBoundaryLimit.class);

        for (SafetyBoundaryLimit limit : SafetyBoundaryLimit.values()) {
            // Fold all levels through moreRestrictive; track which level supplied the winner.
            BigDecimal winningValue = null;
            SafetyBoundaryLevel winningLevel = null;

            for (SafetyBoundaryLevel level : SafetyBoundaryLevel.values()) {
                SafetyBoundaryLimits contribution = orEmpty(safe.get(level));
                if (contribution.isDefined(limit)) {
                    BigDecimal candidate = contribution.get(limit).orElseThrow();
                    if (winningValue == null) {
                        // First level to define this limit — it becomes the initial winner.
                        winningValue = candidate;
                        winningLevel = level;
                    } else {
                        // Fold: compute the more-restrictive of the current winner and this candidate.
                        BigDecimal folded = limit.moreRestrictive(winningValue, candidate);
                        // Update source attribution only if this candidate actually tightened.
                        if (folded.compareTo(winningValue) != 0) {
                            winningValue = folded;
                            winningLevel = level;
                        }
                    }
                }
            }

            if (winningValue != null) {
                resolvedValues.put(limit, winningValue);
                resolvedSources.put(limit, winningLevel);
            }
        }

        return new SafetyBoundary(resolvedValues, resolvedSources);
    }

    private static SafetyBoundaryLimits orEmpty(SafetyBoundaryLimits limits) {
        return limits == null ? SafetyBoundaryLimits.empty() : limits;
    }
}
