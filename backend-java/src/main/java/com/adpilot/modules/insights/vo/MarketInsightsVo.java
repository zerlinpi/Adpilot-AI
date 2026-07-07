package com.adpilot.modules.insights.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Market insights Data Insights surface (Req 30.4): the available
 * market-monitoring reports. This project stores no market-monitoring data, so
 * the surface returns an empty report list (empty-state) rather than fabricating
 * market data (Req 30.8).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketInsightsVo {

    /** True when a market-data source must be activated to populate the surface. */
    private boolean requiresActivation;

    /** Human-readable explanation shown by the activation / empty state. */
    private String message;

    /** Available market-monitoring reports; empty when none are available. */
    private List<MarketReportVo> reports;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MarketReportVo {
        private String id;
        private String name;
        private String category;
        private String updatedAt;
    }
}
