package com.adpilot.modules.advertising.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.advertising.dto.KeywordUpdateRequest;
import com.adpilot.modules.advertising.vo.KeywordVo;

public interface KeywordService {

    PageResponse<KeywordVo> listKeywords(String storeId, String campaignId, String status, int page, int pageSize);

    KeywordVo updateKeyword(String id, KeywordUpdateRequest request, String userId);
}
