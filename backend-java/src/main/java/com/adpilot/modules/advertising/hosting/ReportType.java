package com.adpilot.modules.advertising.hosting;

/**
 * Amazon Ads Sponsored Products report types supported by the
 * {@link ReportLifecycleClient}. Each type maps to a specific Amazon Ads
 * Reporting API report configuration.
 */
public enum ReportType {

    /** Sponsored Products campaign-level performance report. */
    SP_CAMPAIGN("spCampaigns"),

    /** Sponsored Products keyword-level performance report. */
    SP_KEYWORD("spKeywords"),

    /** Sponsored Products search-term-level performance report. */
    SP_SEARCH_TERM("spSearchTerms");

    private final String amazonReportType;

    ReportType(String amazonReportType) {
        this.amazonReportType = amazonReportType;
    }

    /** The Amazon Ads API report type identifier used in report creation requests. */
    public String amazonReportType() {
        return amazonReportType;
    }
}
