package com.adpilot.modules.advertising.hosting;

/**
 * Exception thrown when a step in the Amazon Ads asynchronous report lifecycle
 * fails (creation, polling, download, decompression, or validation).
 */
public class ReportLifecycleException extends RuntimeException {

    private final String reportId;
    private final ReportStatus status;

    public ReportLifecycleException(String message) {
        super(message);
        this.reportId = null;
        this.status = null;
    }

    public ReportLifecycleException(String message, Throwable cause) {
        super(message, cause);
        this.reportId = null;
        this.status = null;
    }

    public ReportLifecycleException(String reportId, ReportStatus status, String message) {
        super(message);
        this.reportId = reportId;
        this.status = status;
    }

    public ReportLifecycleException(String reportId, ReportStatus status, String message, Throwable cause) {
        super(message, cause);
        this.reportId = reportId;
        this.status = status;
    }

    /** The report ID associated with the failure, or {@code null} if not yet assigned. */
    public String getReportId() {
        return reportId;
    }

    /** The terminal report status at failure time, or {@code null} if not applicable. */
    public ReportStatus getStatus() {
        return status;
    }
}
