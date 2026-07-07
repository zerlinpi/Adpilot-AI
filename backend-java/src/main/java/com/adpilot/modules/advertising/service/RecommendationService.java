package com.adpilot.modules.advertising.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.advertising.vo.RecommendationVo;

public interface RecommendationService {

    PageResponse<RecommendationVo> listRecommendations(String storeId, String status, String type, int page, int pageSize);

    RecommendationVo applyRecommendation(String id, String userId);

    RecommendationVo dismissRecommendation(String id, String userId);

    RecommendationVo watchRecommendation(String id, String userId);
}
