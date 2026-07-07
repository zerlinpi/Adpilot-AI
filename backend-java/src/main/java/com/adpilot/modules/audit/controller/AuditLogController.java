package com.adpilot.modules.audit.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.audit.service.AiModelCallLogService;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.audit.vo.AiModelCallLogVo;
import com.adpilot.modules.audit.vo.AuditLogVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final AiModelCallLogService aiModelCallLogService;

    /**
     * GET /api/audit-logs - List audit logs with pagination.
     */
    @GetMapping("/audit-logs")
    @RequirePermission("audit:view")
    public ApiResponse<PageResponse<AuditLogVo>> listAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<AuditLogVo> result = auditLogService.listAuditLogs(action, entityType, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/ai-model-call-logs - List AI model call logs with optional filters.
     */
    @GetMapping("/ai-model-call-logs")
    @RequirePermission("audit:view")
    public ApiResponse<PageResponse<AiModelCallLogVo>> listAiModelCallLogs(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String feature,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<AiModelCallLogVo> result = aiModelCallLogService.listAiModelCallLogs(storeId, feature, model, page, pageSize);
        return ApiResponse.ok(result);
    }
}
