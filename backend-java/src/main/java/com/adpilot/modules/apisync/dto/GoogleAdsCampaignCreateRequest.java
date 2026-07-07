package com.adpilot.modules.apisync.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Manual Google Ads campaign-creation request (platform-workspace-rbac Req 7.1).
 *
 * <p>An authorized independent-site operator submits a new campaign for a
 * connected Google Ads Store. The backend routes the create through the generic
 * Operation pipeline as a {@code platform_mutation} Operation with
 * {@link com.adpilot.modules.advertising.operation.OperationSource#CREATION},
 * which is enqueued to the {@code operation_outbox} and picked up by the
 * {@code OutboxWorker} for submission to the
 * {@link com.adpilot.modules.apisync.connector.GoogleAdsWriteConnector} (the
 * {@code google_ads} platform route). No platform call happens synchronously.</p>
 */
@Data
public class GoogleAdsCampaignCreateRequest {

    /** Independent-site Store whose Google Ads account the campaign is created on; required. */
    @NotBlank(message = "storeId is required")
    private String storeId;

    /**
     * Internal campaign identifier the Operation targets. When omitted, the
     * service mints one so the create Operation has a stable entity id (the
     * platform-assigned id is reconciled when the connector accepts the create).
     */
    private String campaignId;

    /** Campaign name; required (1-255 chars). Carried as the Operation's after value. */
    @NotBlank(message = "name is required")
    private String name;

    /** Optional daily budget in account currency. */
    private BigDecimal budget;

    /** Optional initial status ({@code ENABLED} / {@code PAUSED}); defaults to {@code PAUSED}. */
    private String status;

    /**
     * Optional click-coalescing key so a repeated submission of the same logical
     * create is coalesced into one Operation rather than creating duplicates.
     */
    private String logicalIdempotencyKey;
}
