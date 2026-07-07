package com.adpilot.modules.scheduler.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.adpilot.modules.automation.service.AutomationRunner;
import com.adpilot.modules.scheduler.entity.SyncScheduleEntity;
import com.adpilot.modules.scheduler.mapper.SyncScheduleMapper;
import com.adpilot.modules.scheduler.service.CronEvaluator;
import com.adpilot.modules.scheduler.vo.SyncScheduleVo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduleEngineImpl} covering dispatch of due schedules,
 * exclusion of disabled schedules, and the schedule view projection.
 *
 * <ul>
 *   <li>Req 4.1.1 — a due, enabled schedule is dispatched to the {@link SyncJobRunner}.</li>
 *   <li>Req 4.1.3 — a disabled schedule is never executed (excluded by the poll query).</li>
 *   <li>Req 4.2.1 — the schedule view exposes the enabled state, last run time,
 *       and next run time for both {@code getSchedule} and {@code listSchedules}.</li>
 * </ul>
 *
 * <p>The mapper, cron evaluator, runner, and Redis lock store are mocked so the
 * dispatch and projection logic is exercised without a database or Redis. Where
 * dispatch depends on the clock, the cron evaluator returns a fixed next-run
 * instant so the recorded {@code next_run_at} is deterministic.</p>
 */
class ScheduleEngineImplTest {

    private static final String CRON = "0 0 * * * *";
    private static final String JOB_TYPE = "order";

    private SyncScheduleMapper scheduleMapper;
    private CronEvaluator cronEvaluator;
    private SyncJobRunner syncJobRunner;
    private AutomationRunner automationRunner;
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);

    private ScheduleEngineImpl engine;

    @BeforeAll
    static void initTableInfo() {
        // findDueEnabled/listSchedules build MyBatis-Plus LambdaQueryWrappers whose
        // lambda column references resolve table metadata. Outside a Spring context
        // that metadata must be registered explicitly.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SyncScheduleEntity.class);
    }

    @BeforeEach
    void setUp() {
        scheduleMapper = mock(SyncScheduleMapper.class);
        cronEvaluator = mock(CronEvaluator.class);
        syncJobRunner = mock(SyncJobRunner.class);
        automationRunner = mock(AutomationRunner.class);

        engine = new ScheduleEngineImpl(scheduleMapper, cronEvaluator, syncJobRunner, redisTemplate, automationRunner);
    }

    private SyncScheduleEntity dueEnabledSchedule(UUID id, UUID connectionId) {
        return SyncScheduleEntity.builder()
                .id(id)
                .connectionId(connectionId)
                .jobType(JOB_TYPE)
                .cronExpression(CRON)
                .enabled(Boolean.TRUE)
                // already past, so the poll query would treat it as due
                .nextRunAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                .build();
    }

    /** Arrange the Redis lock so it can always be acquired. */
    private void givenLockAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(Boolean.TRUE);
    }

    // ── Req 4.1.1: a due, enabled schedule is dispatched to the runner ──

    @Test
    void tickDispatchesDueEnabledScheduleToRunner() {
        UUID scheduleId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        SyncScheduleEntity schedule = dueEnabledSchedule(scheduleId, connectionId);

        when(scheduleMapper.selectList(any())).thenReturn(List.of(schedule));
        when(cronEvaluator.isValid(CRON)).thenReturn(true);
        // Controlled clock: the next run is a fixed instant, independent of wall time.
        Instant nextRun = Instant.parse("2024-06-01T12:00:00Z");
        when(cronEvaluator.nextRunAfter(eq(CRON), any(Instant.class))).thenReturn(nextRun);
        givenLockAcquired();
        when(syncJobRunner.startSync(eq(connectionId), eq(JOB_TYPE), eq(false), isNull()))
                .thenReturn(ApiSyncJobVo.builder().id(UUID.randomUUID().toString()).build());

        engine.tick();

        // The job for the schedule's connection and type is dispatched (Req 4.1.1).
        // A system trigger has no originating user (triggeredBy == null).
        verify(syncJobRunner).startSync(connectionId, JOB_TYPE, false, null);

        // The run is recorded: last_run_at set, next_run_at advanced to the computed instant.
        ArgumentCaptor<SyncScheduleEntity> captor = ArgumentCaptor.forClass(SyncScheduleEntity.class);
        verify(scheduleMapper).updateById(captor.capture());
        SyncScheduleEntity update = captor.getValue();
        assertThat(update.getId()).isEqualTo(scheduleId);
        assertThat(update.getLastRunAt()).isNotNull();
        assertThat(update.getNextRunAt())
                .isEqualTo(LocalDateTime.ofInstant(nextRun, ZoneOffset.UTC));
    }

    @Test
    void tickDispatchesNothingWhenNoSchedulesAreDue() {
        when(scheduleMapper.selectList(any())).thenReturn(List.of());

        engine.tick();

        verify(syncJobRunner, never()).startSync(any(), anyString(), anyBoolean(), any());
        verify(scheduleMapper, never()).updateById(any());
    }

    // ── Req 4.1.3: disabled schedules are never executed ──

    @Test
    void tickQueryExcludesDisabledSchedulesSoTheyAreNeverDispatched() {
        // The poll query (which the production code mocks here) restricts to enabled
        // rows, so a disabled schedule is never returned and therefore never run.
        ArgumentCaptor<LambdaQueryWrapper<SyncScheduleEntity>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(scheduleMapper.selectList(wrapperCaptor.capture())).thenReturn(List.of());

        engine.tick();

        // The query filters on the enabled column, excluding disabled schedules (Req 4.1.3).
        String sql = wrapperCaptor.getValue().getTargetSql();
        assertThat(sql.toLowerCase()).contains("enabled");

        // Nothing dispatched because no enabled, due schedule was returned.
        verify(syncJobRunner, never()).startSync(any(), anyString(), anyBoolean(), any());
    }

    @Test
    void tickSkipsDispatchWhenLockHeldByAnotherWorker() {
        UUID scheduleId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        SyncScheduleEntity schedule = dueEnabledSchedule(scheduleId, connectionId);

        when(scheduleMapper.selectList(any())).thenReturn(List.of(schedule));
        when(cronEvaluator.isValid(CRON)).thenReturn(true);
        // Lock cannot be acquired: another worker is already handling this schedule.
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(Boolean.FALSE);

        engine.tick();

        verify(syncJobRunner, never()).startSync(any(), anyString(), anyBoolean(), any());
        verify(scheduleMapper, never()).updateById(any());
    }

    // ── Req 4.2.1: schedule view projects enabled state, last run, and next run ──

    @Test
    void getScheduleProjectsEnabledStateAndRunTimes() {
        UUID scheduleId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        LocalDateTime lastRun = LocalDateTime.of(2024, 5, 1, 6, 30);
        LocalDateTime nextRun = LocalDateTime.of(2024, 5, 1, 7, 0);
        SyncScheduleEntity entity = SyncScheduleEntity.builder()
                .id(scheduleId)
                .connectionId(connectionId)
                .jobType(JOB_TYPE)
                .cronExpression(CRON)
                .enabled(Boolean.FALSE)
                .lastRunAt(lastRun)
                .nextRunAt(nextRun)
                .build();
        when(scheduleMapper.selectById(scheduleId)).thenReturn(entity);

        SyncScheduleVo vo = engine.getSchedule(scheduleId);

        assertThat(vo.getId()).isEqualTo(scheduleId.toString());
        assertThat(vo.getConnectionId()).isEqualTo(connectionId.toString());
        assertThat(vo.getJobType()).isEqualTo(JOB_TYPE);
        assertThat(vo.getEnabled()).isFalse();
        assertThat(vo.getLastRunAt()).isEqualTo(lastRun.toString());
        assertThat(vo.getNextRunAt()).isEqualTo(nextRun.toString());
    }

    @Test
    void getScheduleProjectsNullRunTimesForNeverRunSchedule() {
        UUID scheduleId = UUID.randomUUID();
        SyncScheduleEntity entity = SyncScheduleEntity.builder()
                .id(scheduleId)
                .connectionId(UUID.randomUUID())
                .jobType(JOB_TYPE)
                .cronExpression(CRON)
                .enabled(Boolean.TRUE)
                .lastRunAt(null)
                .nextRunAt(null)
                .build();
        when(scheduleMapper.selectById(scheduleId)).thenReturn(entity);

        SyncScheduleVo vo = engine.getSchedule(scheduleId);

        assertThat(vo.getEnabled()).isTrue();
        assertThat(vo.getLastRunAt()).isNull();
        assertThat(vo.getNextRunAt()).isNull();
    }

    @Test
    void listSchedulesProjectsEnabledStateAndRunTimes() {
        UUID enabledId = UUID.randomUUID();
        UUID disabledId = UUID.randomUUID();
        LocalDateTime lastRun = LocalDateTime.of(2024, 5, 1, 6, 30);
        LocalDateTime nextRun = LocalDateTime.of(2024, 5, 1, 7, 0);

        SyncScheduleEntity enabled = SyncScheduleEntity.builder()
                .id(enabledId).connectionId(UUID.randomUUID()).jobType(JOB_TYPE)
                .cronExpression(CRON).enabled(Boolean.TRUE)
                .lastRunAt(lastRun).nextRunAt(nextRun).build();
        SyncScheduleEntity disabled = SyncScheduleEntity.builder()
                .id(disabledId).connectionId(UUID.randomUUID()).jobType(JOB_TYPE)
                .cronExpression(CRON).enabled(Boolean.FALSE)
                .lastRunAt(null).nextRunAt(null).build();

        Page<SyncScheduleEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(enabled, disabled));
        page.setTotal(2);
        when(scheduleMapper.selectPage(any(), any())).thenReturn(page);

        PageResponse<SyncScheduleVo> response = engine.listSchedules(1, 20);

        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.getItems()).hasSize(2);

        SyncScheduleVo enabledVo = response.getItems().get(0);
        assertThat(enabledVo.getId()).isEqualTo(enabledId.toString());
        assertThat(enabledVo.getEnabled()).isTrue();
        assertThat(enabledVo.getLastRunAt()).isEqualTo(lastRun.toString());
        assertThat(enabledVo.getNextRunAt()).isEqualTo(nextRun.toString());

        SyncScheduleVo disabledVo = response.getItems().get(1);
        assertThat(disabledVo.getId()).isEqualTo(disabledId.toString());
        assertThat(disabledVo.getEnabled()).isFalse();
        assertThat(disabledVo.getLastRunAt()).isNull();
        assertThat(disabledVo.getNextRunAt()).isNull();
    }
}
