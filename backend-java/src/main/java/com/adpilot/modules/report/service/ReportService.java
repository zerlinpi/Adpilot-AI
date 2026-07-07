package com.adpilot.modules.report.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.report.entity.ReportEntity;

import java.util.Map;

public interface ReportService {

    /**
     * List reports with pagination.
     */
    PageResponse<ReportEntity> listReports(int page, int pageSize);

    /**
     * Generate a report and save to database.
     *
     * @param params report generation parameters (type, storeId, title, periodStart, periodEnd)
     * @param userId the user generating the report
     * @return the generated report
     */
    ReportEntity generateReport(Map<String, String> params, String userId);
}
