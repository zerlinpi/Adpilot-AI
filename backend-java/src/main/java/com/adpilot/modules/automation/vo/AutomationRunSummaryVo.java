package com.adpilot.modules.automation.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregate outcome of an {@code AutomationRunner} pass over enabled rules
 * (Req 13.2). Reports how many rules were evaluated and how the resulting
 * changes fared when submitted to the live platform.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRunSummaryVo {

    /** Number of enabled rules evaluated on this run (Req 13.2.1). */
    @Builder.Default
    private int rulesEvaluated = 0;

    /** Number of changes whose condition matched and were submitted to the platform. */
    @Builder.Default
    private int changesSubmitted = 0;

    /** Number of changes accepted by the live platform. */
    @Builder.Default
    private int changesAccepted = 0;

    /** Number of changes rejected by the platform (internal record left unchanged, Req 13.2.7). */
    @Builder.Default
    private int changesRejected = 0;

    /** Number of bid changes whose computed value was clamped to a bound (Req 13.2.5). */
    @Builder.Default
    private int changesClamped = 0;

    /** Number of rules whose execution was gated into a pending approval (Req 13.2.6). */
    @Builder.Default
    private int gatedForApproval = 0;
}
