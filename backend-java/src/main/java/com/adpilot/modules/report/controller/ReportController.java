package com.adpilot.modules.report.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.report.entity.ReportEntity;
import com.adpilot.modules.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * GET /api/reports - List reports with pagination.
     */
    @GetMapping
    @RequirePermission("report:view")
    public ApiResponse<PageResponse<ReportEntity>> listReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<ReportEntity> result = reportService.listReports(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/reports/generate - Generate a report.
     */
    @PostMapping("/generate")
    @RequirePermission("report:export")
    public ApiResponse<ReportEntity> generateReport(@RequestBody Map<String, String> params) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        ReportEntity report = reportService.generateReport(params, userId);
        return ApiResponse.ok(report);
    }
}
