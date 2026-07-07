package com.adpilot.modules.advertising.hosting;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated worker pool that runs manually-triggered optimization runs off the request thread
 * (Req 28.1).
 *
 * <p>The trigger endpoint returns HTTP 202 + {@code run_id} immediately; the actual per-campaign
 * optimization work is handed to this executor so it never blocks the synchronous HTTP response
 * (Req 26.4, 28.1). A small bounded pool keeps manual triggers from overwhelming the instance.</p>
 */
@Configuration
public class HostingOptimizationExecutorConfig {

    /** Spring bean name for the manual-trigger optimization executor. */
    public static final String EXECUTOR_BEAN = "hostingOptimizationExecutor";

    @Bean(name = EXECUTOR_BEAN)
    public Executor hostingOptimizationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("hosting-optimize-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
