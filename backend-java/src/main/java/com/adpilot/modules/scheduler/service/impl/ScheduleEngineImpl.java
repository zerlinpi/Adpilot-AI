package com.adpilot.modules.scheduler.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.adpilot.modules.automation.service.AutomationRunner;
import com.adpilot.modules.automation.vo.AutomationRunSummaryVo;
import com.adpilot.modules.scheduler.dto.SyncScheduleDto;
import com.adpilot.modules.scheduler.entity.SyncScheduleEntity;
import com.adpilot.modules.scheduler.mapper.SyncScheduleMapper;
import com.adpilot.modules.scheduler.service.CronEvaluator;
import com.adpilot.modules.scheduler.service.ScheduleEngine;
import com.adpilot.modules.scheduler.vo.SyncScheduleVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Default {@link ScheduleEngine}. A Spring {@code @Scheduled} poller scans
 * {@code sync_schedules} for due, enabled rows and dispatches each to the
 * {@link SyncJobRunner}, recording {@code last_run_at} and recomputing
 * {@code next_run_at} via the {@link CronEvaluator}.
 *
 * <h2>Time handling</h2>
 * <p>All schedule timestamps are treated as UTC. {@code next_run_at} is computed
 * with {@link CronEvaluator#nextRunAfter} (which interprets cron fields in UTC),
 * keeping due-detection deterministic and host-timezone independent.</p>
 *
 * <h2>Double-execution guard (design: DB/Redis lock)</h2>
 * <p>Before processing a due schedule, the engine acquires a short-lived Redis
 * lock keyed on the schedule id. If the lock cannot be acquired another instance
 * (or an overlapping tick) is already handling that schedule, so it is skipped.
 * The {@code next_run_at} bump committed under the lock makes the schedule
 * no-longer-due for subsequent ticks. The {@link SyncJobRunner} additionally
 * enforces single-flight per {@code (store, entityType)}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleEngineImpl implements ScheduleEngine {

    /** Prefix for the per-schedule distributed lock key. */
    private static final String LOCK_PREFIX = "scheduler:lock:";

    /** How long a per-schedule lock is held before it self-expires. */
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final SyncScheduleMapper scheduleMapper;
    private final CronEvaluator cronEvaluator;
    private final SyncJobRunner syncJobRunner;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AutomationRunner automationRunner;

    @Override
    public void tick() {
        Instant now = Instant.now();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);

        List<SyncScheduleEntity> due = findDueEnabled(nowUtc);
        if (!due.isEmpty()) {
            log.debug("Scheduler tick: {} due schedule(s) at {}", due.size(), nowUtc);
            for (SyncScheduleEntity schedule : due) {
                runOne(schedule, now);
            }
        }

        // Req 13.2.1: evaluate enabled automation rules against current performance
        // data on each scheduled run. Dispatched as part of the poller cycle so
        // rule evaluation rides the same cadence as due-schedule dispatch.
        dispatchAutomation();
    }

    /**
     * Dispatches automation-rule evaluation for the current tick (Req 13.2.1).
     * The runner gates approvals, clamps bids, submits to the live platform, and
     * audits each change internally. Any failure here is logged and contained so
     * it never blocks the scheduler poller or subsequent ticks.
     */
    private void dispatchAutomation() {
        try {
            AutomationRunSummaryVo summary = automationRunner.runEnabledRules();
            if (summary != null && summary.getRulesEvaluated() > 0) {
                log.info("Scheduler dispatched automation: {} rule(s) evaluated, "
                                + "{} change(s) submitted ({} accepted, {} rejected, {} clamped), {} gated for approval",
                        summary.getRulesEvaluated(), summary.getChangesSubmitted(),
                        summary.getChangesAccepted(), summary.getChangesRejected(),
                        summary.getChangesClamped(), summary.getGatedForApproval());
            }
        } catch (Exception ex) {
            log.error("Scheduler automation dispatch failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Selects enabled schedules whose next run time has arrived. A null
     * {@code next_run_at} (never scheduled) is treated as immediately due so a
     * freshly enabled schedule bootstraps on the next tick. Disabled schedules
     * are excluded by the query (Req 4.1.3).
     */
    private List<SyncScheduleEntity> findDueEnabled(LocalDateTime nowUtc) {
        LambdaQueryWrapper<SyncScheduleEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SyncScheduleEntity::getEnabled, Boolean.TRUE)
                .and(w -> w.isNull(SyncScheduleEntity::getNextRunAt)
                        .or()
                        .le(SyncScheduleEntity::getNextRunAt, nowUtc));
        return scheduleMapper.selectList(wrapper);
    }

    /**
     * Processes a single due schedule under the distributed lock: records the run,
     * advances the next run time, and dispatches the job. A dispatch failure is
     * recorded but never blocks the next run (Req 4.1.5).
     */
    private void runOne(SyncScheduleEntity schedule, Instant now) {
        UUID scheduleId = schedule.getId();

        // Skip schedules without a usable recurrence: we cannot advance next_run_at.
        if (!cronEvaluator.isValid(schedule.getCronExpression())) {
            log.warn("Skipping schedule {}: invalid or missing recurrence expression '{}'",
                    scheduleId, schedule.getCronExpression());
            return;
        }

        String lockKey = LOCK_PREFIX + scheduleId;
        if (!acquireLock(lockKey)) {
            log.debug("Skipping schedule {}: lock held by another worker", scheduleId);
            return;
        }

        try {
            // Req 4.1.2: record the last run time and compute the next run time.
            // Done before dispatch so a dispatch failure still advances the schedule
            // (Req 4.1.5) and the row is no longer due for concurrent ticks.
            LocalDateTime ranAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
            Instant next = cronEvaluator.nextRunAfter(schedule.getCronExpression(), now);

            SyncScheduleEntity update = new SyncScheduleEntity();
            update.setId(scheduleId);
            update.setLastRunAt(ranAt);
            update.setNextRunAt(next == null ? null : LocalDateTime.ofInstant(next, ZoneOffset.UTC));
            scheduleMapper.updateById(update);

            dispatch(schedule);
        } catch (Exception ex) {
            // Req 4.1.5: record the failure; next_run_at is already advanced above
            // (when reached) so subsequent executions are not blocked.
            log.error("Schedule {} failed during execution: {}", scheduleId, ex.getMessage(), ex);
        } finally {
            releaseLock(lockKey);
        }
    }

    /**
     * Dispatches the schedule's job to the runner. The runner records and executes
     * the job asynchronously; a system trigger has no originating user.
     */
    private void dispatch(SyncScheduleEntity schedule) {
        try {
            ApiSyncJobVo job = syncJobRunner.startSync(
                    schedule.getConnectionId(), schedule.getJobType(), false, null);
            log.info("Scheduler dispatched job {} for schedule {} (connection={}, type={})",
                    job.getId(), schedule.getId(), schedule.getConnectionId(), schedule.getJobType());
        } catch (Exception ex) {
            // A job already running for this (store, entityType) is an expected,
            // non-fatal outcome; treat any dispatch problem as a recorded failure
            // without blocking the recomputed next run (Req 4.1.5).
            log.warn("Scheduler could not dispatch schedule {} (connection={}, type={}): {}",
                    schedule.getId(), schedule.getConnectionId(), schedule.getJobType(), ex.getMessage());
        }
    }

    private boolean acquireLock(String key) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, Instant.now().toString(), LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            // If the lock store is unavailable, fall back to proceeding: the runner's
            // own single-flight guard still prevents duplicate concurrent jobs.
            log.warn("Schedule lock acquisition failed for {}: {}", key, ex.getMessage());
            return true;
        }
    }

    private void releaseLock(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception ex) {
            log.warn("Schedule lock release failed for {} (will self-expire): {}", key, ex.getMessage());
        }
    }

    // --- Schedule management (Task 13.4) ------------------------------------

    @Override
    public SyncScheduleVo upsertSchedule(SyncScheduleDto dto) {
        if (dto == null) {
            throw new BusinessException("SCHEDULE_001", "Schedule payload is required");
        }

        String cron = dto.getCronExpression();
        // Req 4.1.4: validate the recurrence expression before saving.
        if (!cronEvaluator.isValid(cron)) {
            throw new BusinessException("SCHEDULE_002",
                    "Invalid recurrence expression: " + cron);
        }

        // Compute next_run_at from the validated recurrence, anchored to now (UTC).
        Instant now = Instant.now();
        Instant next = cronEvaluator.nextRunAfter(cron, now);
        LocalDateTime nextRunAt = next == null ? null : LocalDateTime.ofInstant(next, ZoneOffset.UTC);

        SyncScheduleEntity entity;
        if (dto.getId() != null && !dto.getId().isBlank()) {
            // Update: load the existing schedule and apply changes.
            UUID id = UUID.fromString(dto.getId());
            entity = scheduleMapper.selectById(id);
            if (entity == null) {
                throw new BusinessException("SCHEDULE_003", "Schedule not found: " + dto.getId());
            }
            if (dto.getConnectionId() != null && !dto.getConnectionId().isBlank()) {
                entity.setConnectionId(UUID.fromString(dto.getConnectionId()));
            }
            if (dto.getJobType() != null && !dto.getJobType().isBlank()) {
                entity.setJobType(dto.getJobType());
            }
            entity.setCronExpression(cron.trim());
            if (dto.getEnabled() != null) {
                entity.setEnabled(dto.getEnabled());
            }
            entity.setNextRunAt(nextRunAt);
            scheduleMapper.updateById(entity);
        } else {
            // Insert: a connection and job type are required to create a schedule.
            if (dto.getConnectionId() == null || dto.getConnectionId().isBlank()) {
                throw new BusinessException("SCHEDULE_004", "Connection id is required");
            }
            if (dto.getJobType() == null || dto.getJobType().isBlank()) {
                throw new BusinessException("SCHEDULE_005", "Job type is required");
            }
            entity = SyncScheduleEntity.builder()
                    .connectionId(UUID.fromString(dto.getConnectionId()))
                    .jobType(dto.getJobType())
                    .cronExpression(cron.trim())
                    .enabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled())
                    .nextRunAt(nextRunAt)
                    .build();
            scheduleMapper.insert(entity);
        }

        // Re-read so the view reflects DB-managed fields (timestamps, generated id).
        return toVo(scheduleMapper.selectById(entity.getId()));
    }

    @Override
    public SyncScheduleVo setEnabled(UUID id, boolean enabled) {
        SyncScheduleEntity entity = scheduleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("SCHEDULE_003", "Schedule not found: " + id);
        }
        // Req 4.2.2: persist the new enabled state without touching recurrence.
        SyncScheduleEntity update = new SyncScheduleEntity();
        update.setId(id);
        update.setEnabled(enabled);
        scheduleMapper.updateById(update);

        return toVo(scheduleMapper.selectById(id));
    }

    @Override
    public ApiSyncJobVo triggerNow(UUID scheduleId, UUID userId) {
        SyncScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw new BusinessException("SCHEDULE_003", "Schedule not found: " + scheduleId);
        }
        // Req 4.2.3: run the job immediately via the runner. The configured
        // recurrence (cron_expression) and computed next_run_at are intentionally
        // left untouched so the manual run does not shift the automated cadence.
        ApiSyncJobVo job = syncJobRunner.startSync(
                schedule.getConnectionId(), schedule.getJobType(), false, userId);
        log.info("Manual trigger dispatched job {} for schedule {} (connection={}, type={})",
                job.getId(), scheduleId, schedule.getConnectionId(), schedule.getJobType());
        return job;
    }

    @Override
    public SyncScheduleVo getSchedule(UUID id) {
        SyncScheduleEntity entity = scheduleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("SCHEDULE_003", "Schedule not found: " + id);
        }
        // Req 4.2.1: expose enabled state, last run time, and next run time.
        return toVo(entity);
    }

    @Override
    public PageResponse<SyncScheduleVo> listSchedules(int page, int pageSize) {
        Page<SyncScheduleEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<SyncScheduleEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SyncScheduleEntity::getCreatedAt);

        Page<SyncScheduleEntity> result = scheduleMapper.selectPage(pageParam, wrapper);
        List<SyncScheduleVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .toList();
        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    /**
     * Projects a schedule entity onto its view (Req 4.2.1), serializing the
     * UTC timestamps to ISO-8601 strings.
     */
    private SyncScheduleVo toVo(SyncScheduleEntity entity) {
        return SyncScheduleVo.builder()
                .id(entity.getId() == null ? null : entity.getId().toString())
                .connectionId(entity.getConnectionId() == null ? null : entity.getConnectionId().toString())
                .jobType(entity.getJobType())
                .cronExpression(entity.getCronExpression())
                .enabled(entity.getEnabled())
                .lastRunAt(entity.getLastRunAt() == null ? null : entity.getLastRunAt().toString())
                .nextRunAt(entity.getNextRunAt() == null ? null : entity.getNextRunAt().toString())
                .build();
    }
}
