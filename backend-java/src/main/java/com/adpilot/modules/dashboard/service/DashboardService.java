package com.adpilot.modules.dashboard.service;

import com.adpilot.modules.dashboard.vo.DashboardSummaryVo;

public interface DashboardService {

    /**
     * Get dashboard summary with aggregated statistics.
     */
    DashboardSummaryVo getSummary();
}
