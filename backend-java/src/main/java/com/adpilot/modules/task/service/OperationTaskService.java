package com.adpilot.modules.task.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.task.dto.OperationTaskDto;
import com.adpilot.modules.task.vo.OperationTaskVo;

public interface OperationTaskService {

    /**
     * List tasks with pagination, optionally filtered by store, status,
     * priority, and task type. Null/blank filters are ignored.
     */
    PageResponse<OperationTaskVo> listTasks(int page, int pageSize,
                                            String storeId, String status,
                                            String priority, String taskType);

    /**
     * Get task by ID.
     */
    OperationTaskVo getTaskById(String id);

    /**
     * Create a new task.
     */
    OperationTaskVo createTask(OperationTaskDto dto, String userId);

    /**
     * Update an existing task.
     */
    OperationTaskVo updateTask(String id, OperationTaskDto dto, String userId);

    /**
     * Mark a task as completed.
     */
    OperationTaskVo completeTask(String id, String userId);

    /**
     * Dismiss a task.
     */
    OperationTaskVo dismissTask(String id, String userId);

    /**
     * Assign a task to a user.
     */
    OperationTaskVo assignTask(String id, String assignedToUserId, String userId);
}
