package com.adpilot.modules.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A persisted Insight Agent result rendered in the saved-insights history
 * (item 16). Carries the stored id and creation time in addition to the
 * insight content so the page can list and delete saved insights.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavedInsightVo {

    /** Stored insight id (used by the manual-delete action). */
    private String id;

    private String storeId;

    /** The operator's query that produced the insight. */
    private String query;

    /** Selected analysis source. */
    private String source;

    /** Whether Premium mode was applied. */
    private boolean premium;

    /** Narrative insights produced by the analysis. */
    private String insights;

    /** Recommended next actions. */
    private List<String> recommendedActions;

    /** Source marker for how the response was produced: {@code ai} or {@code degraded}. */
    private String generatedBy;

    /** Creation time (newest-first ordering on the page). */
    private String createdAt;
}
