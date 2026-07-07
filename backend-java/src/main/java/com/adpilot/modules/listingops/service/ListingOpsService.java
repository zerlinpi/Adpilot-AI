package com.adpilot.modules.listingops.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.listingops.dto.RepricingRuleDto;
import com.adpilot.modules.listingops.vo.BuyBoxAlertVo;
import com.adpilot.modules.listingops.vo.HijackerAlertVo;
import com.adpilot.modules.listingops.vo.ListingMonitorVo;

import java.util.List;

public interface ListingOpsService {

    /**
     * List listing quality monitors with pagination.
     */
    PageResponse<ListingMonitorVo> listListingMonitors(int page, int pageSize);

    /**
     * List buy box alerts with pagination.
     */
    PageResponse<BuyBoxAlertVo> listBuyBoxAlerts(int page, int pageSize);

    /**
     * List hijacker alerts with pagination.
     */
    PageResponse<HijackerAlertVo> listHijackerAlerts(int page, int pageSize);

    /**
     * List repricing rules with pagination.
     */
    PageResponse<RepricingRuleDto> listRepricingRules(int page, int pageSize);

    /**
     * Create a repricing rule.
     */
    RepricingRuleDto createRepricingRule(RepricingRuleDto dto);

    /**
     * Apply a repricing rule by ID.
     */
    void applyRepricingRule(String id);
}
