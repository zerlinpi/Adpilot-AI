package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.vo.ProductAdCampaignResultVo;

/**
 * Per-product AI ad creation orchestration (Req 1). Drives the single-transaction
 * flow behind {@code POST /api/product-ads/campaign}: validate the submitted
 * inputs, create a keyword Campaign via {@code CampaignService}, link it to the
 * product (ASIN), and — when {@code hostingEnabled} is true — persist the
 * Hosting_Config and Safety_Boundary, then enqueue the external write via the
 * Operation-Outbox so the Amazon Ads API is never called on the request thread.
 *
 * <p>This first slice (task 2.2) establishes the contract together with the
 * authoritative input validation (Req 1.4) and Execution_Mode resolution
 * (Req 1.3). The full atomic orchestration body (campaign creation, product
 * link, hosting config, safety boundary, Operation+Outbox) is wired in a later
 * task (3.1).
 */
public interface ProductAdCampaignService {

    /**
     * Validate the request and create the product's keyword ad campaign.
     *
     * <p>Before any record is written, the budget, target ACoS and bid/budget
     * bounds are validated: each supplied amount must be positive and each upper
     * bound must be no smaller than its lower bound. A violation rejects the
     * submission with a {@code BusinessException(400)} naming the offending field
     * and persists nothing (Req 1.4). The Execution_Mode is resolved from
     * {@link ProductAdCampaignRequest#getExecutionMode()}, falling back to
     * {@code ExecutionMode.DEFAULT} ({@code observe_only}) when omitted, blank, or
     * unrecognized (Req 1.3).
     *
     * @param request the validated per-product ad modal payload
     * @param userId  the authenticated user submitting the request (may be {@code null}
     *                for system contexts)
     * @return the created campaign id, linked product/ASIN, and resolved Execution_Mode (Req 1.10)
     */
    ProductAdCampaignResultVo createProductAd(ProductAdCampaignRequest request, String userId);
}
