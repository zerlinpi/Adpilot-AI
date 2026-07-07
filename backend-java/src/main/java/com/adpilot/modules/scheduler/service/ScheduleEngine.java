package com.adpilot.modules.scheduler.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.adpilot.modules.scheduler.dto.SyncScheduleDto;
import com.adpilot.modules.scheduler.vo.SyncScheduleVo;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

/**
 * Drives recurring sync execution over the {@code sync_schedules} table
 * (Capability Area 4). A single poller scans for due, enabled schedules and
 * dispatches them to the {@link com.adpilot.modules.apisync.service.SyncJobRunner},
 * recording last/next run times as it goes.
 */
public interface ScheduleEngine {

    /**
     * Poll the schedule table and run every due, enabled schedule (Req 4.1.1).
     *
     * <p>For each schedule executed, the last run time is recorded and the next
     * run time is recomputed (Req 4.1.2). Disabled schedules are never executed
     * (Req 4.1.3). If a dispatch fails, the failure is recorded and the next run
     * time is still advanced so subsequent executions are not blocked (Req 4.1.5).</p>
     *
     * <p>Double execution across instances is guarded by a distributed lock.</p>
     */
    @Scheduled(fixedDelayString = "${adpilot.scheduler.poll-ms:30000}")
    void tick();

    /**
     * Create or update a schedule after validating its recurrence expression
     * (Req 4.1.4). Implemented by the schedule-management task.
     */
    SyncScheduleVo upsertSchedule(SyncScheduleDto dto);

    /**
     * Persist the enabled state of a schedule (Req 4.2.2).
     */
    SyncScheduleVo setEnabled(UUID id, boolean enabled);

    /**
     * Run a schedule's job immediately without altering its recurrence (Req 4.2.3).
     */
    ApiSyncJobVo triggerNow(UUID scheduleId, UUID userId);

    /**
     * View a single schedule's enabled state, last run time, and next run time
     * (Req 4.2.1).
     */
    SyncScheduleVo getSchedule(UUID id);

    /**
     * List schedules for the management UI, exposing each schedule's enabled
     * state, last run time, and next run time (Req 4.2.1).
     */
    PageResponse<SyncScheduleVo> listSchedules(int page, int pageSize);
}
