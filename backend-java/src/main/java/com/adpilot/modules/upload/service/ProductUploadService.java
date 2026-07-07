package com.adpilot.modules.upload.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.upload.dto.UploadJobCreateRequest;
import com.adpilot.modules.upload.vo.UploadJobVo;

public interface ProductUploadService {
    PageResponse<UploadJobVo> listJobs(String storeId, String status, int page, int pageSize);
    UploadJobVo getJob(String id);
    UploadJobVo createJob(UploadJobCreateRequest request, String userId);
    UploadJobVo validateJob(String id);
    UploadJobVo approveJob(String id, String userId);
    UploadJobVo cancelJob(String id, String userId);
    String exportJob(String id, String format);

    /**
     * Directly publish an approved {@code *_api} upload job to the store's
     * connected independent-site platform (Shopify / WooCommerce) via a real
     * HTTP create call. On success the job becomes {@code published}; on platform
     * rejection / missing connection / missing credentials the job becomes
     * {@code failed} with a readable reason (never exported, never faked).
     */
    UploadJobVo publishJob(String id, String userId);
}
