package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.SmartDiagnosisCreateRequest;
import com.adpilot.modules.advertising.service.SmartDiagnosisService;
import com.adpilot.modules.advertising.vo.SmartDiagnosisTaskVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Smart Diagnosis endpoints (Req 22). Diagnosis tasks run per product (parent
 * ASIN) and analyze the product's ad structure, reusing the recommendation
 * engine to produce the diagnosis result.
 *
 * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
 * error envelope with a 4xx/5xx status.
 */
@Slf4j
@RestController
@RequestMapping("/api/smart-diagnosis")
@RequiredArgsConstructor
public class SmartDiagnosisController {

    private final SmartDiagnosisService smartDiagnosisService;

    /**
     * GET /api/smart-diagnosis/tasks - List diagnosis tasks for the active store
     * with their product, frequency, creator, last-diagnosis time, and result
     * (Req 22.3, 22.4).
     */
    @GetMapping("/tasks")
    @RequirePermission("advertising:view")
    public ApiResponse<List<SmartDiagnosisTaskVo>> listTasks(@RequestParam(required = false) String storeId) {
        List<SmartDiagnosisTaskVo> result = smartDiagnosisService.listTasks(storeId);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/smart-diagnosis/tasks/{id} - Get a single diagnosis task with its
     * result (Req 22.2).
     */
    @GetMapping("/tasks/{id}")
    @RequirePermission("advertising:view")
    public ApiResponse<SmartDiagnosisTaskVo> getTask(@PathVariable String id) {
        SmartDiagnosisTaskVo task = smartDiagnosisService.getTaskById(id);
        return ApiResponse.ok(task);
    }

    /**
     * POST /api/smart-diagnosis/tasks - Create a diagnosis task for a parent
     * ASIN and run the diagnosis (Req 22.1, 22.2).
     */
    @PostMapping("/tasks")
    @RequirePermission("advertising:manage")
    public ApiResponse<SmartDiagnosisTaskVo> createTask(@Valid @RequestBody SmartDiagnosisCreateRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        SmartDiagnosisTaskVo task = smartDiagnosisService.createTask(request, userId);
        log.info("Smart diagnosis task created: {}", task.getId());
        return ApiResponse.ok(task);
    }
}
