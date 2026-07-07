package com.adpilot.modules.scheduler.service.impl;

import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.automation.service.AutomationRunner;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.adpilot.modules.scheduler.entity.SyncScheduleEntity;
import com.adpilot.modules.scheduler.mapper.SyncScheduleMapper;
import com.adpilot.modules.scheduler.service.CronEvaluator;
import com.adpilot.modules.scheduler.vo.SyncScheduleVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link ScheduleEngineImpl}'s schedule-management
 * surface (tasks 13.3/13.4).
 *
 * Feature: core-platform-completion, Property 14: Schedule enable-state
 * round-trips and manual triggers preserve recurrence.
 *
 * <p>For any schedule, persisting an enabled flag and reading it back yields the
 * same value (Req 4.2.2), and a manual trigger executes the job while leaving the
 * configured recurrence expression and computed next run time unchanged by the
 * manual run (Req 4.2.3).</p>
 *
 * <p>The mapper is backed by a stateful in-memory map so {@code selectById}/
 * {@code updateById} behave like a real store (partial, non-null-field updates),
 * letting the round-trip be observed end to end. The cron evaluator, runner, and
 * Redis lock store are mocked because the management paths do not touch them
 * (trigger dispatches via the runner without recomputing recurrence).</p>
 *
 * Validates: Requirements 4.2.2, 4.2.3
 */
class ScheduleEnginePropertyTest {

    // Feature: core-platform-completion, Property 14: Schedule enable-state round-trips and manual triggers preserve recurrence
    // Req 4.2.2: persisting an enabled flag and reading it back yields the same value, for any boolean.
    @Property(tries = 200)
    void setEnabledRoundTripsTheFlag(
            @ForAll("schedules") SyncScheduleEntity seed,
            @ForAll boolean newEnabled) {

        Fixture fixture = new Fixture();
        UUID id = fixture.store(seed);

        SyncScheduleVo returned = fixture.engine.setEnabled(id, newEnabled);

        // The value returned by the write reflects what was persisted.
        assertThat(returned.getEnabled())
                .as("setEnabled(%s) must return the persisted flag", newEnabled)
                .isEqualTo(newEnabled);

        // Reading it back independently yields the same value (true round-trip).
        SyncScheduleVo readBack = fixture.engine.getSchedule(id);
        assertThat(readBack.getEnabled())
                .as("reading the schedule back must yield the persisted flag %s", newEnabled)
                .isEqualTo(newEnabled);
    }

    // Feature: core-platform-completion, Property 14: Schedule enable-state round-trips and manual triggers preserve recurrence
    // Req 4.2.3: a manual trigger executes the job and leaves cron_expression and next_run_at unchanged.
    @Property(tries = 200)
    void triggerNowDispatchesWithoutAlteringRecurrence(
            @ForAll("schedules") SyncScheduleEntity seed,
            @ForAll("optionalUserIds") UUID userId) {

        Fixture fixture = new Fixture();
        UUID id = fixture.store(seed);

        // Capture the configured recurrence and computed next run before the manual run.
        String cronBefore = seed.getCronExpression();
        LocalDateTime nextRunBefore = seed.getNextRunAt();
        UUID connectionId = seed.getConnectionId();
        String jobType = seed.getJobType();

        ApiSyncJobVo job = fixture.engine.triggerNow(id, userId);

        // The manual trigger executes the job immediately.
        assertThat(job).as("triggerNow must return the dispatched job").isNotNull();
        verify(fixture.syncJobRunner, atLeastOnce())
                .startSync(connectionId, jobType, false, userId);

        // The recurrence expression and computed next run time are untouched by the
        // manual run, so the automated cadence is not shifted.
        SyncScheduleEntity after = fixture.backing.get(id);
        assertThat(after.getCronExpression())
                .as("manual trigger must not change cron_expression")
                .isEqualTo(cronBefore);
        assertThat(after.getNextRunAt())
                .as("manual trigger must not change next_run_at")
                .isEqualTo(nextRunBefore);
    }

    /**
     * A {@link ScheduleEngineImpl} wired to an in-memory, stateful mapper and
     * mocked collaborators, recreated per property example.
     */
    private static final class Fixture {
        final Map<UUID, SyncScheduleEntity> backing = new HashMap<>();
        final SyncScheduleMapper scheduleMapper = mock(SyncScheduleMapper.class);
        final CronEvaluator cronEvaluator = mock(CronEvaluator.class);
        final SyncJobRunner syncJobRunner = mock(SyncJobRunner.class);
        @SuppressWarnings("unchecked")
        final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        final AutomationRunner automationRunner = mock(AutomationRunner.class);
        final ScheduleEngineImpl engine;

        Fixture() {
            // selectById returns the live stored entity.
            when(scheduleMapper.selectById(any(UUID.class)))
                    .thenAnswer(inv -> backing.get(inv.getArgument(0, UUID.class)));

            // updateById applies a MyBatis-Plus style partial update: only non-null
            // fields on the update entity overwrite the stored row.
            when(scheduleMapper.updateById(any(SyncScheduleEntity.class)))
                    .thenAnswer(inv -> {
                        SyncScheduleEntity update = inv.getArgument(0, SyncScheduleEntity.class);
                        SyncScheduleEntity current = backing.get(update.getId());
                        if (current == null) {
                            return 0;
                        }
                        if (update.getConnectionId() != null) current.setConnectionId(update.getConnectionId());
                        if (update.getJobType() != null) current.setJobType(update.getJobType());
                        if (update.getCronExpression() != null) current.setCronExpression(update.getCronExpression());
                        if (update.getEnabled() != null) current.setEnabled(update.getEnabled());
                        if (update.getLastRunAt() != null) current.setLastRunAt(update.getLastRunAt());
                        if (update.getNextRunAt() != null) current.setNextRunAt(update.getNextRunAt());
                        return 1;
                    });

            // The runner always accepts a manual trigger and returns a job view.
            when(syncJobRunner.startSync(any(), anyString(), anyBoolean(), any()))
                    .thenAnswer(inv -> ApiSyncJobVo.builder()
                            .id(UUID.randomUUID().toString())
                            .status("running")
                            .build());

            engine = new ScheduleEngineImpl(scheduleMapper, cronEvaluator, syncJobRunner, redisTemplate, automationRunner);
        }

        UUID store(SyncScheduleEntity entity) {
            UUID id = entity.getId();
            backing.put(id, entity);
            return id;
        }
    }

    // --- generators -----------------------------------------------------------

    /**
     * Arbitrary schedules spanning the management-relevant input space: random
     * ids/connections, varied job types, a mix of cron expressions (including
     * blank and {@code null}), both enabled states, and a {@code next_run_at}
     * that may be null (never scheduled) or a UTC timestamp.
     */
    @Provide
    Arbitrary<SyncScheduleEntity> schedules() {
        Arbitrary<UUID> ids = uuids();
        Arbitrary<UUID> connectionIds = uuids();
        Arbitrary<String> jobTypes = Arbitraries.of("order", "product", "customer", "inventory", "refund");
        Arbitrary<String> crons = Arbitraries.oneOf(
                Arbitraries.of("0 0 * * * *", "*/15 * * * * *", "@daily", "@hourly", "0 30 9 * * *"),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(8),
                Arbitraries.just((String) null));
        Arbitrary<Boolean> enabled = Arbitraries.of(Boolean.TRUE, Boolean.FALSE);
        Arbitrary<LocalDateTime> nextRuns = Arbitraries.longs()
                .between(1_000_000_000L, 3_000_000_000L)
                .map(s -> LocalDateTime.ofInstant(Instant.ofEpochSecond(s), ZoneOffset.UTC))
                .injectNull(0.25);

        return Combinators.combine(ids, connectionIds, jobTypes, crons, enabled, nextRuns)
                .as((id, connId, jobType, cron, en, nextRun) -> SyncScheduleEntity.builder()
                        .id(id)
                        .connectionId(connId)
                        .jobType(jobType)
                        .cronExpression(cron)
                        .enabled(en)
                        .nextRunAt(nextRun)
                        .build());
    }

    /** A manual trigger may carry an originating user or be userless (null). */
    @Provide
    Arbitrary<UUID> optionalUserIds() {
        return uuids().injectNull(0.3);
    }

    private Arbitrary<UUID> uuids() {
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs())
                .as(UUID::new);
    }
}
