package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Smart Diagnosis task row for the Smart Diagnosis page (Req 22). Carries the
 * task's product (parent ASIN), update frequency, creator, last-diagnosis time,
 * status, and the structured diagnosis result produced over the product's ad
 * structure (Req 22.2, 22.3).
 */
@Data
@Builder
public class SmartDiagnosisTaskVo {

    private String id;
    private String storeId;

    /** Product the task diagnoses (父ASIN). */
    private String parentAsin;

    /** Update frequency (更新频率). */
    private String updateFrequency;

    /** Task status: pending / running / completed / failed. */
    private String status;

    /** Creator user id (创建人). */
    private String createdBy;

    /** Last diagnosis time (最近诊断时间). */
    private String lastDiagnosedAt;

    private String createdAt;

    /** Structured diagnosis result; {@code null} until the task has completed. */
    private DiagnosisResult result;

    /**
     * Diagnosis result analyzing the product's ad structure (Req 22.2). The
     * summary metrics are aggregated over the store's campaigns and the issues
     * are derived from the recommendation engine's analysis.
     */
    @Data
    @Builder
    public static class DiagnosisResult {

        /**
         * Whether enough ad-structure data existed to run an effective diagnosis.
         * When {@code false}, the result is an explicit "insufficient data" outcome
         * and {@code summary} explains what is missing. Legacy results stored before
         * this field existed deserialize to {@code null} and are treated as available.
         */
        @Builder.Default
        private Boolean dataAvailable = true;

        /** Human-readable summary of the diagnosis outcome (诊断结论). */
        private String summary;

        /** Number of campaigns analyzed (广告活动数量). */
        private int campaignCount;

        /** Number of enabled campaigns (投放中广告活动). */
        private int enabledCampaignCount;

        /** Total ad spend across analyzed campaigns (广告花费). */
        private double totalSpend;

        /** Total ad sales across analyzed campaigns (广告销售额). */
        private double totalSales;

        /** Aggregate ACoS across analyzed campaigns, as a percentage (整体ACOS). */
        private double acos;

        /** Total number of diagnosed issues (诊断问题数). */
        private int issueCount;

        /** Issue count by severity: high / medium / low. */
        private int highCount;
        private int mediumCount;
        private int lowCount;

        /** Overall health score in [0,100] (健康评分). */
        private int healthScore;

        /** The diagnosed issues with actionable detail (诊断结果). */
        private List<DiagnosisIssue> issues;
    }

    /** One diagnosed issue derived from the ad-structure analysis. */
    @Data
    @Builder
    public static class DiagnosisIssue {
        private String type;
        private String priority;
        private String title;
        private String description;
    }
}
