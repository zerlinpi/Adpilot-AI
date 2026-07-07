package com.adpilot.modules.importcenter.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.importcenter.dto.ImportMappingRequest;
import com.adpilot.modules.importcenter.dto.ImportUploadRequest;
import com.adpilot.modules.importcenter.service.ImportService;
import com.adpilot.modules.importcenter.vo.ImportJobVo;
import com.adpilot.modules.importcenter.vo.ImportPreviewVo;
import com.adpilot.modules.importcenter.vo.ImportRowErrorVo;
import com.adpilot.modules.importcenter.vo.ImportValidationVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @GetMapping
    @RequirePermission("import:view")
    public ApiResponse<PageResponse<ImportJobVo>> listImports(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.ok(importService.listImports(storeId, page, pageSize));
    }

    @PostMapping("/upload")
    @RequirePermission("import:manage")
    public ApiResponse<ImportJobVo> uploadImport(@RequestBody ImportUploadRequest request) {
        return ApiResponse.ok(importService.uploadImport(request, currentUserOrSystem()));
    }

    @GetMapping("/{id}")
    @RequirePermission("import:view")
    public ApiResponse<ImportJobVo> getImport(@PathVariable String id) {
        return ApiResponse.ok(importService.getImport(id));
    }

    @PostMapping("/{id}/preview")
    @RequirePermission("import:view")
    public ApiResponse<ImportPreviewVo> previewImport(@PathVariable String id) {
        return ApiResponse.ok(importService.previewImport(id));
    }

    @PostMapping("/{id}/map")
    @RequirePermission("import:manage")
    public ApiResponse<ImportJobVo> mapImport(@PathVariable String id, @RequestBody ImportMappingRequest request) {
        return ApiResponse.ok(importService.mapImport(id, request));
    }

    @PostMapping("/{id}/validate")
    @RequirePermission("import:view")
    public ApiResponse<ImportValidationVo> validateImport(@PathVariable String id) {
        return ApiResponse.ok(importService.validateImport(id));
    }

    @PostMapping("/{id}/commit")
    @RequirePermission("import:manage")
    public ApiResponse<ImportJobVo> commitImport(@PathVariable String id) {
        return ApiResponse.ok(importService.commitImport(id, currentUserOrSystem()));
    }

    @GetMapping("/{id}/errors")
    @RequirePermission("import:view")
    public ApiResponse<List<ImportRowErrorVo>> getImportErrors(@PathVariable String id) {
        return ApiResponse.ok(importService.getImportErrors(id));
    }

    @PostMapping("/{id}/reanalyze")
    @RequirePermission("import:manage")
    public ApiResponse<ImportJobVo> reanalyzeImport(@PathVariable String id) {
        return ApiResponse.ok(importService.reanalyzeImport(id, currentUserOrSystem()));
    }

    private String currentUserOrSystem() {
        return com.adpilot.common.utils.SecurityUtils.isAuthenticated()
                ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : "system";
    }
}
