package com.adpilot.modules.apisync.service;

import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.apisync.dto.GoogleAdsAdjustRequest;
import com.adpilot.modules.apisync.dto.GoogleAdsCampaignCreateRequest;

/**
 * Write-side of the GoogleAds_Module (platform-workspace-rbac Req 7).
 *
 * <p>Turns a manual Google Ads campaign-create or bid/budget/status change into a
 * {@code platform_mutation} Operation routed through the generic
 * {@link com.adpilot.modules.advertising.operation.OperationService} &rarr;
 * {@code operation_outbox} &rarr;
 * {@link com.adpilot.modules.apisync.connector.GoogleAdsWriteConnector} pipeline.
 * The service never calls Google Ads synchronously — it persists the Operation
 * and enqueues an Outbox entry on the {@code google_ads} platform route, which the
 * {@code OutboxWorker} submits asynchronously (Req 7.1, 7.2). Acceptance advances
 * the Operation through its Sync_States and records the platform response;
 * rejection records the failure reason and leaves the internal record unchanged
 * (Req 7.3, 7.4), all handled by the generic pipeline.</p>
 *
 * <p>Authorization (the independent-site advertising Functional_Permission) is
 * enforced by the controller's {@code @RequirePermission} gate before any
 * Operation is created, so a caller lacking the permission receives HTTP 403 and
 * no Operation is created (Req 7.5).</p>
 */
public interface GoogleAdsManualOperationService {

    /**
     * Create a Google Ads campaign as a {@code platform_mutation} Operation with
     * {@link com.adpilot.modules.advertising.operation.OperationSource#CREATION}
     * routed to the GoogleAdsWriteConnector (Req 7.1).
     *
     * @param request the campaign-create request; {@code storeId} and {@code name} are required
     * @return the creation result (Operation persisted and enqueued for submission)
     */
    OperationResult createCampaign(GoogleAdsCampaignCreateRequest request);

    /**
     * Submit a bid, budget, or status change as a {@code platform_mutation}
     * Operation with
     * {@link com.adpilot.modules.advertising.operation.OperationSource#MANUAL}
     * routed to the GoogleAdsWriteConnector (Req 7.2).
     *
     * @param request the adjustment request; {@code storeId}, {@code entityId},
     *                {@code changeType}, and {@code afterValue} are required
     * @return the creation result (Operation persisted and enqueued for submission)
     */
    OperationResult adjust(GoogleAdsAdjustRequest request);
}
