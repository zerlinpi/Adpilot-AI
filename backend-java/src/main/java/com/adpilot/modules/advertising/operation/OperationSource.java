package com.adpilot.modules.advertising.operation;

/**
 * The origin classification of an {@code Operation}.
 *
 * <p>Operation_Source is an IMMUTABLE audit fact recorded at creation and is never rewritten for the
 * life of the Operation. It is exactly one of:</p>
 *
 * <ul>
 *   <li>{@link #MANUAL} — an operator-initiated change (pause, budget/bid change, keyword/negative
 *       add).</li>
 *   <li>{@link #RECOMMENDATION} — applying an AI-generated Recommendation.</li>
 *   <li>{@link #ONE_CLICK_OPTIMIZE} — a one-click optimization action.</li>
 *   <li>{@link #AI_HOSTING} — an AI-hosting adjustment proposed by the optimizer.</li>
 *   <li>{@link #CREATION} — creation of a new advertising object (for example a campaign).</li>
 * </ul>
 *
 * <p>Validates: Requirements 3.8.</p>
 */
public enum OperationSource {
    MANUAL,
    RECOMMENDATION,
    ONE_CLICK_OPTIMIZE,
    AI_HOSTING,
    CREATION
}
