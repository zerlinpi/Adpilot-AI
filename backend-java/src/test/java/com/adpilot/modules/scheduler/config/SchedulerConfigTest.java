package com.adpilot.modules.scheduler.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SchedulerConfig} (reliability fix H2): the app registers
 * a bounded {@link ThreadPoolTaskScheduler} so {@code @Scheduled} jobs run in
 * parallel instead of sharing Spring's single scheduler thread (head-of-line
 * blocking). The pool size is config-driven.
 */
@DisplayName("SchedulerConfig — bounded scheduled-task thread pool")
class SchedulerConfigTest {

    @Test
    @DisplayName("registers a ThreadPoolTaskScheduler with the configured pool size and name prefix")
    void taskSchedulerHonorsConfiguredPoolSize() {
        SchedulerConfig config = new SchedulerConfig(8);
        TaskScheduler scheduler = config.taskScheduler();

        assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler pool = (ThreadPoolTaskScheduler) scheduler;
        // getPoolSize() reflects live threads (lazily created); the configured size
        // is the executor's core pool size.
        assertThat(pool.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(8);
        assertThat(pool.getThreadNamePrefix()).isEqualTo(SchedulerConfig.THREAD_NAME_PREFIX);

        pool.shutdown();
    }

    @Test
    @DisplayName("a custom pool size is honored")
    void customPoolSize() {
        SchedulerConfig config = new SchedulerConfig(3);
        ThreadPoolTaskScheduler pool = (ThreadPoolTaskScheduler) config.taskScheduler();
        assertThat(pool.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
        pool.shutdown();
    }

    @Test
    @DisplayName("a non-positive pool size is clamped to at least one thread")
    void poolSizeClampedToMinimumOne() {
        SchedulerConfig config = new SchedulerConfig(0);
        ThreadPoolTaskScheduler pool = (ThreadPoolTaskScheduler) config.taskScheduler();
        assertThat(pool.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(1);
        pool.shutdown();
    }
}
