package com.adpilot.modules.advertising.hosting;

/**
 * Status of an Amazon Ads asynchronous report request.
 */
public enum ReportStatus {

    /** The report is still being generated. */
    IN_PROGRESS,

    /** The report has completed and is ready for download. */
    COMPLETED,

    /** The report generation failed. */
    FAILED,

    /** The report expired before it could be downloaded. */
    EXPIRED
}
