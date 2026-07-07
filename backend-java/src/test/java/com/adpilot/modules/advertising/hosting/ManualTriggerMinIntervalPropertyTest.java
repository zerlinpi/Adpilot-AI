package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.service.AiHostingOptimizer;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the per-store minimum manual-trigger interval enforced by
 * {@link HostingOptimizationService#triggerManualRun}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 47: Manual trigger minimum interval.
 *
 * <p>Validates: Requirements 28.4.
 *
 * <p>A manual optimization trigger for a store is rejected with a {@code 429}
 * {@link HostingOptimizationService#CODE_RATE_LIMITED HOSTING_TRIGGER_RATE_LIMITED} error when the
 * configured minimum interval since that store's previous manual trigger has not yet elapsed; once
 * the interval has elapsed the trigger is allowed. The interval is enforced strictly per store, so
 * a recent trigger on one store never rate-limits a different store. A non-positive (disabled)
 * interval never rate-limits. These properties assert that:
 * <ul>
 *   <li>any elapsed gap strictly below the interval is rejected before a run is created;</li>
 *   <li>any elapsed gap at or beyond the interval is allowed and a run is started;</li>
 *   <li>a recent run for store A does not block store B's first trigger; and</li>
 *   <li>a zero/disabled interval allows a trigger no matter how recent the previous one was.</li>
 * </ul>
 */
class ManualTriggerMinIntervalPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns and materialise the
        // bound parameter values of the store-scoped query wrapper without a Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OptimizationRunEntity.class);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 47: Manual trigger minimum interval.
     *
     * <p>Validates: Requirements 28.4.
     *
     * <p>A manual trigger arriving strictly before the minimum interval has elapsed since the
     * store's last manual run is always rejected with {@code 429 HOSTING_TRIGGER_RATE_LIMITED},
     * and no run is created nor work dispatched.
     */
    @Property(tries = 200)
    void triggerWithinIntervalIsAlwaysRejected(
            @ForAll("intervalSeconds") long minIntervalSeconds,
            @ForAll @LongRange(min = 0, max = 1_000_000) long withinOffset) {

        // Elapsed gap strictly inside the window. A >=2s margin keeps the boundary stable against
        // the wall-clock advancing between building the prior run and the service's now-check.
        long elapsed = withinOffset % Math.max(1, minIntervalSeconds - 1);

        Fixture f = new Fixture(minIntervalSeconds);
        UUID storeId = UUID.randomUUID();
        f.seedLastManualRun(storeId, elapsed);

        assertThatThrownBy(() -> f.service.triggerManualRun(storeId, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(HostingOptimizationService.CODE_RATE_LIMITED);
                    assertThat(be.getStatus()).isEqualTo(429);
                });

        // Rejected before any run is created or work dispatched.
        verify(f.optimizationRunService, never()).startRun(any(), any());
        assertThat(f.dispatched.get()).isNull();
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 47: Manual trigger minimum interval.
     *
     * <p>Validates: Requirements 28.4.
     *
     * <p>A manual trigger arriving at or after the minimum interval since the store's last manual
     * run is always allowed: a run is started for that store.
     */
    @Property(tries = 200)
    void triggerAfterIntervalElapsedIsAlwaysAllowed(
            @ForAll("intervalSeconds") long minIntervalSeconds,
            @ForAll @LongRange(min = 1, max = 1_000_000) long beyondOffset) {

        // Elapsed gap at or beyond the window (>=1s past the boundary).
        long elapsed = minIntervalSeconds + beyondOffset;

        Fixture f = new Fixture(minIntervalSeconds);
        UUID storeId = UUID.randomUUID();
        f.seedLastManualRun(storeId, elapsed);

        OptimizationRunEntity run = f.service.triggerManualRun(storeId, null, null);

        assertThat(run).isNotNull();
        verify(f.optimizationRunService).startRun(storeId, "manual");
        // Synchronous-only contract: work is dispatched, not executed inline.
        assertThat(f.dispatched.get()).isNotNull();
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 47: Manual trigger minimum interval.
     *
     * <p>Validates: Requirements 28.4.
     *
     * <p>The interval is enforced per store: a just-now manual run on store A never rate-limits a
     * first trigger on a distinct store B, regardless of how recent A's run was.
     */
    @Property(tries = 200)
    void intervalIsEnforcedPerStore(
            @ForAll("intervalSeconds") long minIntervalSeconds,
            @ForAll @LongRange(min = 0, max = 1_000_000) long storeARecency) {

        Fixture f = new Fixture(minIntervalSeconds);
        UUID storeA = UUID.randomUUID();
        UUID storeB = UUID.randomUUID();

        // Store A has a recent manual run (well inside the window); store B has none.
        long elapsedA = storeARecency % Math.max(1, minIntervalSeconds);
        f.seedLastManualRun(storeA, elapsedA);

        // Triggering store B must succeed: A's recent run is invisible to B's store-scoped query.
        OptimizationRunEntity run = f.service.triggerManualRun(storeB, null, null);

        assertThat(run).isNotNull();
        verify(f.optimizationRunService).startRun(storeB, "manual");
        // Store A is never started as a side effect of triggering B.
        verify(f.optimizationRunService, never()).startRun(eq(storeA), any());
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 47: Manual trigger minimum interval.
     *
     * <p>Validates: Requirements 28.4.
     *
     * <p>A non-positive (disabled) interval never rate-limits: a trigger is allowed even when the
     * store's previous manual run started moments ago.
     */
    @Property(tries = 100)
    void zeroOrDisabledIntervalNeverRateLimits(
            @ForAll @IntRange(min = 0, max = 600) int disabledInterval,
            @ForAll @LongRange(min = 0, max = 1_000_000) long recencySeconds) {

        // minIntervalSeconds <= 0 disables enforcement (Req 28.4 default-off path).
        long minIntervalSeconds = -disabledInterval; // 0 or negative
        Fixture f = new Fixture(minIntervalSeconds);
        UUID storeId = UUID.randomUUID();
        f.seedLastManualRun(storeId, recencySeconds % 1_000);

        OptimizationRunEntity run = f.service.triggerManualRun(storeId, null, null);

        assertThat(run).isNotNull();
        verify(f.optimizationRunService).startRun(storeId, "manual");
    }

    // --- generators --------------------------------------------------------

    /**
     * Positive minimum intervals. Lower bound of 3s keeps the {@code (min - 1)} modulus in the
     * "within" property strictly positive while preserving a >=2s boundary margin.
     */
    @Provide
    Arbitrary<Long> intervalSeconds() {
        return Arbitraries.longs().between(3, 86_400);
    }

    // --- fixture -----------------------------------------------------------

    /**
     * Builds a {@link HostingOptimizationService} backed by mocks. The run mapper is modelled as a
     * per-store store of the latest manual run: a store-scoped {@code selectOne} only sees a run
     * whose {@code store_id} matches the wrapper's filter, mirroring the production query.
     */
    private static final class Fixture {
        final OptimizationRunService optimizationRunService = Mockito.mock(OptimizationRunService.class);
        final OptimizationRunMapper optimizationRunMapper = Mockito.mock(OptimizationRunMapper.class);
        final CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
        final AiHostingOptimizer aiHostingOptimizer = Mockito.mock(AiHostingOptimizer.class);
        final AuditLogMapper auditLogMapper = Mockito.mock(AuditLogMapper.class);

        final AtomicReference<Runnable> dispatched = new AtomicReference<>();
        final Executor capturingExecutor = dispatched::set;

        final List<OptimizationRunEntity> runStore = new ArrayList<>();
        final HostingOptimizationService service;

        Fixture(long minIntervalSeconds) {
            // Latest manual run per store, scoped by the wrapper's store_id filter.
            when(optimizationRunMapper.selectOne(any())).thenAnswer(inv -> {
                UUID scoped = scopedStoreId(inv);
                return runStore.stream()
                        .filter(r -> r.getStoreId().equals(scoped))
                        .filter(r -> "manual".equals(r.getTriggerType()))
                        .max((a, b) -> a.getStartedAt().compareTo(b.getStartedAt()))
                        .orElse(null);
            });
            when(optimizationRunService.startRun(any(), eq("manual")))
                    .thenAnswer(inv -> OptimizationRunEntity.builder()
                            .id(UUID.randomUUID())
                            .storeId(inv.getArgument(0))
                            .triggerType("manual")
                            .status("running")
                            .startedAt(LocalDateTime.now())
                            .build());

            this.service = new HostingOptimizationService(
                    optimizationRunService,
                    optimizationRunMapper,
                    campaignMapper,
                    aiHostingOptimizer,
                    auditLogMapper,
                    new ObjectMapper(),
                    capturingExecutor,
                    minIntervalSeconds);
        }

        void seedLastManualRun(UUID storeId, long secondsAgo) {
            runStore.add(OptimizationRunEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .triggerType("manual")
                    .status("completed")
                    .startedAt(LocalDateTime.now().minusSeconds(secondsAgo))
                    .build());
        }
    }

    /**
     * Extract the store-id the service placed into the {@link LambdaQueryWrapper} so the mocked
     * mapper filters its store exactly as the production store-scoped query would.
     */
    private static UUID scopedStoreId(InvocationOnMock invocation) {
        LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
        wrapper.getTargetSql(); // force lazy materialisation of bound parameter values
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return pairs.values().stream()
                .filter(v -> v instanceof UUID)
                .map(UUID.class::cast)
                .findFirst()
                .orElse(null);
    }
}
