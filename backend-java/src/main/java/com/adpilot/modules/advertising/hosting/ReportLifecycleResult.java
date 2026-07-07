package com.adpilot.modules.advertising.hosting;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The result of a completed report lifecycle execution, containing the
 * decompressed report rows along with validation metadata.
 *
 * @param reportId   the Amazon-assigned report ID
 * @param reportType the type of report that was retrieved
 * @param startDate  the requested date range start
 * @param endDate    the requested date range end
 * @param rows       the decompressed and parsed report rows
 * @param rowCount   total number of data rows (excluding headers)
 */
public record ReportLifecycleResult(
        String reportId,
        ReportType reportType,
        LocalDate startDate,
        LocalDate endDate,
        List<Map<String, Object>> rows,
        int rowCount) {
}
