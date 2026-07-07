package com.adpilot.modules.advertising.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.advertising.vo.BidChangeVo;

public interface BidChangeService {

    /**
     * List recent bid changes (manual and automated bid adjustments) for the
     * active store, optionally narrowed to one campaign, enriched with the
     * parent-campaign name and ordered newest-first.
     */
    PageResponse<BidChangeVo> listBidChanges(String storeId, String campaignId, String entityType, int page, int pageSize);
}
