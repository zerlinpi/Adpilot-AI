package com.adpilot.modules.insights.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A scheduled custom report shown on the custom-report Data Insights surface
 * (Req 30.2). Backed by the existing {@code reports} table.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomReportVo {

    private String id;

    private String title;

    private String type;

    private String periodStart;

    private String periodEnd;

    private String createdAt;
}
