package com.adpilot.modules.insights.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Activation status for a per-store data source (item 8). Returned by the
 * activate action and the status query so the Data Insights / AMC surfaces can
 * show whether the source is activated and how to activate it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSourceActivationVo {

    private String storeId;

    /** Source key: brand_analytics | amc. */
    private String source;

    /** Whether the source is activated for the store. */
    private boolean activated;

    /** When the source was activated (yyyy-MM-dd HH:mm:ss); null when not activated. */
    private String activatedAt;

    /** Human-readable status message. */
    private String message;
}
