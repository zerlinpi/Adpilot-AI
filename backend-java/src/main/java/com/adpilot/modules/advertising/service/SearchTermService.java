package com.adpilot.modules.advertising.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.advertising.dto.SearchTermHarvestRequest;
import com.adpilot.modules.advertising.vo.SearchTermVo;

public interface SearchTermService {

    PageResponse<SearchTermVo> listSearchTerms(String storeId, String campaignId, String harvested, int page, int pageSize);

    SearchTermVo harvestSearchTerm(String id, SearchTermHarvestRequest request, String userId);
}
