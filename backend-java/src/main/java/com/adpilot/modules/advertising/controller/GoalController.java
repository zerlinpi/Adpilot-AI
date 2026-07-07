package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.GoalCreateRequest;
import com.adpilot.modules.advertising.dto.GoalUpdateRequest;
import com.adpilot.modules.advertising.service.GoalService;
import com.adpilot.modules.advertising.vo.GoalVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    /**
     * GET /api/goals - List goals with pagination.
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
     * error envelope with a 4xx/5xx status. Domain errors are raised as
     * {@code BusinessException} by the service layer.
     */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<PageResponse<GoalVo>> listGoals(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PageResponse<GoalVo> result = goalService.listGoals(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/goals/{id} - Get goal by ID.
     */
    @GetMapping("/{id}")
    @RequirePermission("advertising:view")
    public ApiResponse<GoalVo> getGoal(@PathVariable String id) {
        GoalVo goal = goalService.getGoalById(id);
        return ApiResponse.ok(goal);
    }

    /**
     * POST /api/goals - Create a new goal.
     */
    @PostMapping
    @RequirePermission("advertising:manage")
    public ApiResponse<GoalVo> createGoal(@Valid @RequestBody GoalCreateRequest request) {
        String userId = SecurityUtils.getCurrentUserId();
        GoalVo goal = goalService.createGoal(request, userId);
        log.info("Goal created: {}", goal.getId());
        return ApiResponse.ok(goal);
    }

    /**
     * PUT /api/goals/{id} - Update an existing goal.
     */
    @PutMapping("/{id}")
    @RequirePermission("advertising:manage")
    public ApiResponse<GoalVo> updateGoal(@PathVariable String id, @Valid @RequestBody GoalUpdateRequest request) {
        String userId = SecurityUtils.getCurrentUserId();
        GoalVo goal = goalService.updateGoal(id, request, userId);
        log.info("Goal updated: {}", id);
        return ApiResponse.ok(goal);
    }

    /**
     * DELETE /api/goals/{id} - Delete a goal.
     */
    @DeleteMapping("/{id}")
    @RequirePermission("advertising:manage")
    public ApiResponse<Void> deleteGoal(@PathVariable String id) {
        String userId = SecurityUtils.getCurrentUserId();
        goalService.deleteGoal(id, userId);
        log.info("Goal deleted: {}", id);
        return ApiResponse.ok(null);
    }
}
