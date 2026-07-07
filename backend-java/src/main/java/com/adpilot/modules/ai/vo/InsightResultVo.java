package com.adpilot.modules.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of an Insight Agent query (Req 24.1): the produced insights plus the
 * recommended next actions, echoing back the selected source and Premium mode.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsightResultVo {

    /** Echo of the operator's query. */
    private String query;

    /** Selected analysis source. */
    private String source;

    /** Whether Premium mode was applied. */
    private boolean premium;

    /** Narrative insights produced by the analysis. */
    private String insights;

    /** Recommended next actions (Req 24.1). */
    private List<String> recommendedActions;

    /**
     * Source marker for how the response was produced (Req 14.5): {@code ai}
     * (validated live-model output) or {@code degraded} (AI disabled, failed,
     * timed out, or produced non-conforming output). Distinguishes a genuine
     * AI-generated result from a degraded fallback.
     */
    private String generatedBy;

    /**
     * Human-readable reason a result was marked {@code degraded} (Req 14.4,
     * 14.6, 14.7); {@code null} for genuine {@code ai} results.
     */
    private String degradedReason;
}
