package com.adpilot.modules.advertising.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.advertising.vo.AdGroupVo;

public interface AdGroupService {

    /**
     * List ad groups for the active store, optionally narrowed to one campaign,
     * enriched with parent-campaign name, keyword count, and aggregated
     * spend/sales/ACoS derived from the ad group's keywords.
     */
    PageResponse<AdGroupVo> listAdGroups(String storeId, String campaignId, String status, int page, int pageSize);
}
