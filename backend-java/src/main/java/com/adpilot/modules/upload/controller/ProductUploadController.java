package com.adpilot.modules.upload.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.upload.dto.UploadJobCreateRequest;
import com.adpilot.modules.upload.service.ProductUploadService;
import com.adpilot.modules.upload.vo.UploadJobVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/product-upload")
@RequiredArgsConstructor
public class ProductUploadController {
    private final ProductUploadService service;

    @GetMapping("/jobs")
    @RequirePermission("product:view")
    public ApiResponse<PageResponse<UploadJobVo>> listJobs(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.ok(service.listJobs(storeId, status, page, pageSize));
    }

    @PostMapping("/jobs")
    @RequirePermission("product:create")
    public ApiResponse<UploadJobVo> createJob(@RequestBody UploadJobCreateRequest request) {
        return ApiResponse.ok(service.createJob(request, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @GetMapping("/jobs/{id}")
    @RequirePermission("product:view")
    public ApiResponse<UploadJobVo> getJob(@PathVariable String id) {
        return ApiResponse.ok(service.getJob(id));
    }

    @PostMapping("/jobs/{id}/validate")
    @RequirePermission("product:update")
    public ApiResponse<UploadJobVo> validateJob(@PathVariable String id) {
        return ApiResponse.ok(service.validateJob(id));
    }

    @PostMapping("/jobs/{id}/approve")
    @RequirePermission("product:update")
    public ApiResponse<UploadJobVo> approveJob(@PathVariable String id) {
        return ApiResponse.ok(service.approveJob(id, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PostMapping("/jobs/{id}/cancel")
    @RequirePermission("product:update")
    public ApiResponse<UploadJobVo> cancelJob(@PathVariable String id) {
        return ApiResponse.ok(service.cancelJob(id, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PostMapping("/jobs/{id}/export")
    @RequirePermission("product:view")
    public ApiResponse<String> exportJob(@PathVariable String id, @RequestParam(defaultValue = "json") String format) {
        return ApiResponse.ok(service.exportJob(id, format));
    }

    @PostMapping("/jobs/{id}/publish")
    @RequirePermission("product:update")
    public ApiResponse<UploadJobVo> publishJob(@PathVariable String id) {
        return ApiResponse.ok(service.publishJob(id, SecurityUtils.getCurrentUserIdOrNull()));
    }
}
