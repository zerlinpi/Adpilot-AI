package com.adpilot.modules.scheduler.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.adpilot.modules.scheduler.dto.SyncScheduleDto;
import com.adpilot.modules.scheduler.service.ScheduleEngine;
import com.adpilot.modules.scheduler.vo.SyncScheduleVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for managing sync schedules and triggering them manually
 * (Capability Area 4, Requirement 4.2). Delegates to the {@link ScheduleEngine}
 * for create/update (with recurrence validation, Req 4.1.4), enable/disable
 * (Req 4.2.2), manual trigger (Req 4.2.3), and viewing enabled state with
 * last/next run times (Req 4.2.1).
 */
@Slf4j
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleEngine scheduleEngine;

    /**
     * GET /api/schedules - List schedules with their enabled state, last run
     * time, and next run time (Req 4.2.1).
     */
    @GetMapping
    public ApiResponse<PageResponse<SyncScheduleVo>> listSchedules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(scheduleEngine.listSchedules(page, pageSize));
    }

    /**
     * GET /api/schedules/{id} - View a single schedule's enabled state, last run
     * time, and next run time (Req 4.2.1).
     */
    @GetMapping("/{id}")
    public ApiResponse<SyncScheduleVo> getSchedule(@PathVariable String id) {
        return ApiResponse.ok(scheduleEngine.getSchedule(parseId(id)));
    }

    /**
     * POST /api/schedules - Create or update a schedule. The recurrence
     * expression is validated before saving (Req 4.1.4).
     */
    @PostMapping
    public ApiResponse<SyncScheduleVo> upsertSchedule(@RequestBody SyncScheduleDto dto) {
        return ApiResponse.ok(scheduleEngine.upsertSchedule(dto));
    }

    /**
     * PUT /api/schedules/{id}/enabled - Enable or disable a schedule and persist
     * the new state (Req 4.2.2). Body: { "enabled": true }.
     */
    @PutMapping("/{id}/enabled")
    public ApiResponse<SyncScheduleVo> setEnabled(
            @PathVariable String id,
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        return ApiResponse.ok(scheduleEngine.setEnabled(parseId(id), enabled));
    }

    /**
     * POST /api/schedules/{id}/trigger - Run the schedule's job immediately
     * without altering its configured recurrence (Req 4.2.3).
     */
    @PostMapping("/{id}/trigger")
    public ApiResponse<ApiSyncJobVo> triggerNow(@PathVariable String id) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        UUID triggeredBy = userId == null ? null : UUID.fromString(userId);
        return ApiResponse.ok(scheduleEngine.triggerNow(parseId(id), triggeredBy));
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new com.adpilot.common.exception.BusinessException(
                    "INVALID_SCHEDULE_ID", "Invalid schedule id: " + id);
        }
    }
}
