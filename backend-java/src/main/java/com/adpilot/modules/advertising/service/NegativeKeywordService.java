package com.adpilot.modules.advertising.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.advertising.vo.NegativeKeywordVo;

public interface NegativeKeywordService {

    /**
     * List negative keywords/targets for the active store, optionally narrowed
     * to one campaign, enriched with the parent-campaign name.
     */
    PageResponse<NegativeKeywordVo> listNegativeKeywords(String storeId, String campaignId, String level, int page, int pageSize);
}
