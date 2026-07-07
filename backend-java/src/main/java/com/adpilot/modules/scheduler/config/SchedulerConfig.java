package com.adpilot.modules.scheduler.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Enables Spring's annotation-driven scheduling so the {@link
 * com.adpilot.modules.scheduler.service.ScheduleEngine#tick()} poller fires on its
 * configured fixed delay (Req 4.1.1).
 *
 * <p><b>Why the explicit {@link TaskScheduler} pool:</b> with only
 * {@code @EnableScheduling}, Spring uses a single-threaded scheduler, so ALL
 * {@code @Scheduled} jobs across the app run on ONE thread — a slow job blocks
 * every other job (head-of-line blocking). This configuration registers a
 * bounded {@link ThreadPoolTaskScheduler} so independent scheduled jobs run in
 * parallel. The pool size is config-driven via
 * {@code adpilot.scheduling.pool-size} (default 8). No job's cron/fixedDelay is
 * changed; only the executor backing them is parallelized.</p>
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    /** Thread name prefix for scheduled-task threads (aids diagnostics). */
    static final String THREAD_NAME_PREFIX = "adpilot-sched-";

    /** Bounded wait (seconds) for in-flight scheduled tasks to finish on shutdown. */
    static final int AWAIT_TERMINATION_SECONDS = 30;

    private final int poolSize;

    public SchedulerConfig(@Value("${adpilot.scheduling.pool-size:8}") int poolSize) {
        this.poolSize = poolSize;
    }

    /**
     * The application-wide {@link TaskScheduler} used for all {@code @Scheduled}
     * methods. Registered as the primary scheduler so Spring routes scheduled
     * jobs onto this bounded pool instead of a single thread.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
