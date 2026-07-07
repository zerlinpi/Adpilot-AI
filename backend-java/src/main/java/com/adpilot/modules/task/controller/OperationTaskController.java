package com.adpilot.modules.task.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.task.dto.OperationTaskDto;
import com.adpilot.modules.task.service.OperationTaskService;
import com.adpilot.modules.task.vo.OperationTaskVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class OperationTaskController {

    private final OperationTaskService taskService;

    /**
     * GET /api/tasks - List tasks with pagination.
     */
    @GetMapping
    public ApiResponse<PageResponse<OperationTaskVo>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String taskType) {
        PageResponse<OperationTaskVo> result =
                taskService.listTasks(page, pageSize, storeId, status, priority, taskType);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/tasks/{id} - Get task by ID.
     */
    @GetMapping("/{id}")
    public ApiResponse<OperationTaskVo> getTask(@PathVariable String id) {
        OperationTaskVo task = taskService.getTaskById(id);
        return ApiResponse.ok(task);
    }

    /**
     * POST /api/tasks - Create a new task.
     */
    @PostMapping
    public ApiResponse<OperationTaskVo> createTask(@Valid @RequestBody OperationTaskDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        OperationTaskVo task = taskService.createTask(dto, userId);
        return ApiResponse.ok(task);
    }

    /**
     * PUT /api/tasks/{id} - Update an existing task.
     */
    @PutMapping("/{id}")
    public ApiResponse<OperationTaskVo> updateTask(@PathVariable String id, @Valid @RequestBody OperationTaskDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        OperationTaskVo task = taskService.updateTask(id, dto, userId);
        return ApiResponse.ok(task);
    }

    /**
     * POST /api/tasks/{id}/complete - Mark task as completed.
     */
    @PostMapping("/{id}/complete")
    public ApiResponse<OperationTaskVo> completeTask(@PathVariable String id) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        OperationTaskVo task = taskService.completeTask(id, userId);
        return ApiResponse.ok(task);
    }

    /**
     * POST /api/tasks/{id}/dismiss - Dismiss a task.
     */
    @PostMapping("/{id}/dismiss")
    public ApiResponse<OperationTaskVo> dismissTask(@PathVariable String id) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        OperationTaskVo task = taskService.dismissTask(id, userId);
        return ApiResponse.ok(task);
    }

    /**
     * POST /api/tasks/{id}/assign - Assign a task to a user.
     */
    @PostMapping("/{id}/assign")
    public ApiResponse<OperationTaskVo> assignTask(@PathVariable String id, @RequestBody Map<String, String> body) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        OperationTaskVo task = taskService.assignTask(id, body.get("userId"), userId);
        return ApiResponse.ok(task);
    }
}
