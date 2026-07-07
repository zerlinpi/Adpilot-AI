package com.adpilot.modules.advertising.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.advertising.dto.GoalCreateRequest;
import com.adpilot.modules.advertising.dto.GoalUpdateRequest;
import com.adpilot.modules.advertising.vo.GoalVo;

public interface GoalService {

    PageResponse<GoalVo> listGoals(String storeId, int page, int pageSize);

    GoalVo getGoalById(String id);

    GoalVo createGoal(GoalCreateRequest request, String userId);

    GoalVo updateGoal(String id, GoalUpdateRequest request, String userId);

    void deleteGoal(String id, String userId);
}
