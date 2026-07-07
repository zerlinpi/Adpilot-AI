package com.adpilot.modules.audit.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.audit.vo.AiModelCallLogVo;

public interface AiModelCallLogService {

    /**
     * Create an AI model call log entry.
     */
    void createLog(String userId, String storeId, String feature, String model,
                   String promptSnapshot, String inputSnapshot, String outputSnapshot,
                   String status, String errorMessage);

    /**
     * List AI model call logs with pagination.
     */
    PageResponse<AiModelCallLogVo> listAiModelCallLogs(String storeId, String feature, String model, int page, int pageSize);
}
