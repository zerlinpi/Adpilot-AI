package com.adpilot.modules.apisync.dto;

import lombok.Data;

/**
 * Manual Google Ads bid / budget / status adjustment request
 * (platform-workspace-rbac Req 7.2).
 *
 * <p>An authorized independent-site operator changes a bid, budget, or status on
 * an existing Google Ads entity for a connected Store. The backend routes the
 * change through the generic Operation pipeline as a {@code platform_mutation}
 * Operation with
 * {@link com.adpilot.modules.advertising.operation.OperationSource#MANUAL}, which
 * is enqueued to the {@code operation_outbox} and picked up by the
 * {@code OutboxWorker} for submission to the
 * {@link com.adpilot.modules.apisync.connector.GoogleAdsWriteConnector}.</p>
 *
 * <p>The {@code changeType} maps to the connector's change routing: {@code bid}
 * &rarr; ad-group criterion bid, {@code budget} &rarr; campaign budget,
 * {@code status} &rarr; campaign status.</p>
 */
@Data
public class GoogleAdsAdjustRequest {

    /** Independent-site Store whose Google Ads account the change targets; required. */
    private String storeId;

    /**
     * The internal entity type the change applies to (for example {@code campaign}
     * for budget/status, {@code keyword} for a bid). Defaults to {@code campaign}
     * when omitted.
     */
    private String entityType;

    /** Internal id of the entity being changed; required. */
    private String entityId;

    /** One of {@code bid}, {@code budget}, or {@code status}; required. */
    private String changeType;

    /** The platform-confirmed value before the change, for provenance; optional. */
    private String beforeValue;

    /**
     * The requested new value; required. A bid/budget number (account currency) or
     * a status token ({@code ENABLED} / {@code PAUSED}).
     */
    private String afterValue;

    /**
     * Optional click-coalescing key so a repeated submission of the same logical
     * change is coalesced into one Operation rather than creating duplicates.
     */
    private String logicalIdempotencyKey;

    /**
     * Optional optimistic-lock version the operator's view was loaded with; when
     * supplied the pipeline rejects a stale change.
     */
    private Long expectedVersion;
}
