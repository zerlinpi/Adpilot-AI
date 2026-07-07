package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Per-item outcome of a bulk campaign operation (Req 19.7). One result is
 * returned for every id in the request, in request order, so partial failures
 * are visible to the operator.
 */
@Data
@Builder
public class CampaignBulkResultVo {

    /** The campaign id this result refers to. */
    private String id;

    /** Whether the operation succeeded for this campaign. */
    private boolean success;

    /** Human-readable outcome / error detail. */
    private String message;
}
