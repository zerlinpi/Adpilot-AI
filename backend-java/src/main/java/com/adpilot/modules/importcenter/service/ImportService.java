package com.adpilot.modules.importcenter.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.importcenter.dto.ImportMappingRequest;
import com.adpilot.modules.importcenter.dto.ImportUploadRequest;
import com.adpilot.modules.importcenter.vo.ImportJobVo;
import com.adpilot.modules.importcenter.vo.ImportPreviewVo;
import com.adpilot.modules.importcenter.vo.ImportRowErrorVo;
import com.adpilot.modules.importcenter.vo.ImportValidationVo;

import java.util.List;

public interface ImportService {

    /**
     * List import jobs with optional store filter and pagination.
     */
    PageResponse<ImportJobVo> listImports(String storeId, int page, int pageSize);

    /**
     * Upload and parse a CSV file, creating an import job and storing raw rows.
     */
    ImportJobVo uploadImport(ImportUploadRequest request, String userId);

    /**
     * Get a single import job by ID.
     */
    ImportJobVo getImport(String id);

    /**
     * Preview the first 20 rows of raw imported data.
     */
    ImportPreviewVo previewImport(String id);

    /**
     * Save column mapping configuration for an import job.
     */
    ImportJobVo mapImport(String id, ImportMappingRequest request);

    /**
     * Validate imported data: check required fields, numeric values, detect duplicates.
     */
    ImportValidationVo validateImport(String id);

    /**
     * Commit validated data: map raw rows to search_terms and performance_daily tables.
     */
    ImportJobVo commitImport(String id, String userId);

    /**
     * Get row-level errors for an import job.
     */
    List<ImportRowErrorVo> getImportErrors(String id);

    /**
     * Re-analyze an existing import job (re-parse and re-validate).
     */
    ImportJobVo reanalyzeImport(String id, String userId);
}
