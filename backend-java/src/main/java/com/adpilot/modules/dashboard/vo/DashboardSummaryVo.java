package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DashboardSummaryVo {

    private int totalProducts;
    private int activeProducts;
    private long totalInventory;
    private double inventoryValue;
    private int storeCount;
    private int pendingTasks;
    private List<TopProductVo> topProducts;
    private List<RecentAuditLogVo> recentAuditLogs;
}
