package com.adpilot.modules.dataquality.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.dataquality.service.DataQualityService;
import com.adpilot.modules.dataquality.vo.DataQualityIssueVo;
import com.adpilot.modules.dataquality.vo.QualityCheckResultVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/data-quality")
@RequiredArgsConstructor
public class DataQualityController {

    private final DataQualityService dataQualityService;

    @GetMapping("/issues")
    public ApiResponse<List<DataQualityIssueVo>> listIssues(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity) {
        return ApiResponse.ok(dataQualityService.listIssues(storeId, status, severity));
    }

    @PostMapping("/check")
    public ApiResponse<QualityCheckResultVo> runCheck(@RequestParam String storeId) {
        return ApiResponse.ok(dataQualityService.runQualityCheck(storeId));
    }

    @PostMapping("/issues/{id}/resolve")
    public ApiResponse<DataQualityIssueVo> resolve(@PathVariable String id) {
        return ApiResponse.ok(dataQualityService.resolveIssue(id, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PostMapping("/issues/{id}/ignore")
    public ApiResponse<DataQualityIssueVo> ignore(@PathVariable String id) {
        return ApiResponse.ok(dataQualityService.ignoreIssue(id, SecurityUtils.getCurrentUserIdOrNull()));
    }
}
