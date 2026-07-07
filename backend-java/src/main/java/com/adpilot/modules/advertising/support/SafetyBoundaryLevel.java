package com.adpilot.modules.advertising.support;

/**
 * The configuration levels that can supply a Safety_Boundary limit, declared in
 * <strong>strict precedence order</strong> (Req 6.4, 22.11, 49.11):
 *
 * <ol>
 *   <li>{@link #CAMPAIGN_OVERRIDE} — a Campaign-level override always wins;</li>
 *   <li>{@link #GOAL_BOUNDARY} — the boundary stored on the Campaign's Goal;</li>
 *   <li>{@link #STORE_POLICY} — the Active_Store's policy;</li>
 *   <li>{@link #ORGANIZATION_POLICY} — the Organization's policy;</li>
 *   <li>{@link #SYSTEM_DEFAULT} — the configurable backend default (last resort).</li>
 * </ol>
 *
 * <p>The declaration order is the resolution order: {@link SafetyBoundaryResolver}
 * walks the levels top-to-bottom. Under the enhanced resolution (Req 6.4), the
 * final effective value is the <em>most-restrictive-wins intersection</em> across
 * all levels, so inheritance can only tighten. {@link #values()} returns the levels
 * already sorted by decreasing specificity.
 */
public enum SafetyBoundaryLevel {

    /** Campaign-level override; the most specific level. */
    CAMPAIGN_OVERRIDE,

    /** Boundary stored on the Campaign's Goal. */
    GOAL_BOUNDARY,

    /** Active_Store policy. */
    STORE_POLICY,

    /** Organization-level policy (Req 6.4). */
    ORGANIZATION_POLICY,

    /** Configurable backend default; the fallback when no higher level defines a limit. */
    SYSTEM_DEFAULT
}
