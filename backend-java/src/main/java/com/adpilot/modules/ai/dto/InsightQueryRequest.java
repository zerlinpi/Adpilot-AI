package com.adpilot.modules.ai.dto;

import lombok.Data;

/**
 * Request payload for an Insight Agent conversational query (Req 24.1, 24.3).
 *
 * <p>{@code source} selects the analysis source the operator picked in the UI
 * (for example {@code ads}, {@code listing}, {@code all}); {@code premium}
 * toggles Premium mode for a deeper analysis.</p>
 */
@Data
public class InsightQueryRequest {

    /** Natural-language data query / analysis request. Required. */
    private String query;

    /** Active store the request is scoped to (Req 24.1). */
    private String storeId;

    /** Selected analysis source (Req 24.3). */
    private String source;

    /** Whether Premium mode is enabled (Req 24.3). */
    private Boolean premium;
}
