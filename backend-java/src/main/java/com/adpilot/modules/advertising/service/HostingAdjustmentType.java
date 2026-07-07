package com.adpilot.modules.advertising.service;

/**
 * The four AI-hosting adjustment capabilities that make up the full target capability set of
 * Requirement 22.1: bid adjustment, budget adjustment, keyword addition, and negative-keyword
 * addition.
 *
 * <p>Each capability is gated by the active {@link HostingPhase} (Req 54): the optimizer only
 * emits — and the UI only presents as executable — the capabilities the active phase implements.
 * The optimizer reaches each adjustment type through {@link HostingPhase#supports(HostingAdjustmentType)}
 * before generating any Operation of that type, so a capability that is not yet implemented for the
 * active phase is never emitted (Req 22.2, 54.2/54.3).</p>
 *
 * <p>Validates: Requirements 22.1, 22.2, 54.1, 54.2, 54.3, 54.4.</p>
 */
public enum HostingAdjustmentType {

    /** Keyword/campaign bid adjustment — implemented from V1 (Req 54.1). */
    BID,

    /** Daily-budget adjustment — implemented from V2 (Req 54.1). */
    BUDGET,

    /** Positive-keyword addition (keyword expansion) — implemented from V3 (Req 54.1). */
    KEYWORD,

    /** Negative-keyword addition — implemented from V3 (Req 54.1). */
    NEGATIVE
}
