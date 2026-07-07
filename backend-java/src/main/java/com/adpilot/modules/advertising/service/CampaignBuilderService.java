package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.GoalEntity;

import java.util.List;

public interface CampaignBuilderService {

    List<String> buildCampaignsFromGoal(GoalEntity goal, String storeId);
}
