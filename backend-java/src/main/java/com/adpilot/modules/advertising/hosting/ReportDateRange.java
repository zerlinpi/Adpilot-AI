package com.adpilot.modules.advertising.hosting;

import java.time.LocalDate;

/**
 * A date range for an Amazon Ads report request.
 *
 * @param startDate the inclusive start date
 * @param endDate   the inclusive end date
 */
public record ReportDateRange(LocalDate startDate, LocalDate endDate) {

    public ReportDateRange {
        if (startDate == null) {
            throw new IllegalArgumentException("startDate must not be null");
        }
        if (endDate == null) {
            throw new IllegalArgumentException("endDate must not be null");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
    }
}
