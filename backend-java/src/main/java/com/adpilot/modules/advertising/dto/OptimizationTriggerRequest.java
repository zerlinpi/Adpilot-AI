package com.adpilot.modules.advertising.dto;

import lombok.Data;

/**
 * JSON body for {@code POST /api/advertising/hosting/optimize/trigger} (Req 28.1).
 *
 * <p>{@code storeId} is required; {@code campaignId} is optional. When {@code campaignId} is
 * omitted the run optimizes all hosted campaigns in the store (Req 28.2); when present it
 * optimizes only that campaign (Req 28.3). Both are accepted as string identifiers and validated
 * to UUIDs by the controller so a malformed id yields a structured {@code HOSTING_*} error rather
 * than a generic 500.</p>
 */
@Data
public class OptimizationTriggerRequest {

    /** The store to optimize (required). */
    private String storeId;

    /** Optional single campaign to optimize; {@code null}/blank optimizes all hosted campaigns. */
    private String campaignId;
}
