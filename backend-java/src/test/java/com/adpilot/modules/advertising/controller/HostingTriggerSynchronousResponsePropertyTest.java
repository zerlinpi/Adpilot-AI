package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.modules.advertising.dto.OptimizationTriggerRequest;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.hosting.HostingOptimizationService;
import com.adpilot.modules.advertising.hosting.HostingOrgIsolationGuard;
import com.adpilot.modules.advertising.hosting.OptimizationRunEntity;
import com.adpilot.modules.advertising.hosting.OptimizationRunMapper;
import com.adpilot.modules.advertising.hosting.OptimizationRunService;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.service.AiHostingOptimizer;
import com.adpilot.modules.advertising.vo.OptimizationTriggerVo;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the synchronous-only optimization trigger response served by
 * {@link HostingOptimizationController} on top of the real {@link HostingOptimizationService}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 46: Trigger response is synchronous-only.
 *
 * <p>Validates: Requirements 26.4, 26.5, 28.1.
 *
 * <p>A successful {@code POST /optimize/trigger} validates parameters and the per-store minimum
 * interval synchronously, then returns HTTP 202 with a {@code run_id} + {@code request_id}
 * immediately; the per-campaign optimization work is dispatched to an executor and never blocks
 * the response (Req 26.4, 28.1). Because the response carries a {@code request_id} (Req 26.5) and
 * never waits for the asynchronous work, these properties assert that, for any valid trigger:
 * <ul>
 *   <li>the controller returns 202 with a non-null {@code run_id} and {@code request_id};</li>
 *   <li>the asynchronous work is dispatched (a task is handed to the executor) but has NOT run by
 *       the time the response returns — proving the trigger does not block on it, regardless of how
 *       long that work would take (the executor here never runs the captured task); and</li>
 *   <li>repeated triggers (each allowed by the minimum interval) return distinct {@code run_id}s.</li>
 * </ul>
 *
 * <p>The trigger is exercised against the real service wired with a <em>capturing</em> executor:
 * the dispatched task is recorded but never executed, so any blocking/long-running async work is
 * impossible to wait on. If the trigger were synchronous over the optimization work, the response
 * could not return — and the finalize/markFailed lifecycle calls would have fired.</p>
 */
class HostingTriggerSynchronousResponsePropertyTest {

    /** No minimum-interval gate for these properties: every trigger under test is allowed. */
    private static final long NO_MIN_INTERVAL = 0L;

    private OptimizationRunEntity runningRun(UUID storeId) {
        return OptimizationRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .triggerType("manual")
                .status("running")
                .startedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Wire a controller on top of a real {@link HostingOptimizationService} that dispatches async
     * work to {@code executor} (a capturing executor that never runs the task). Each
     * {@code startRun} returns a fresh {@code running} run with a unique id.
     */
    private HostingOptimizationController newController(
            UUID storeId,
            Executor executor,
            OptimizationRunService optimizationRunService,
            HostingOrgIsolationGuard guard) {

        OptimizationRunMapper optimizationRunMapper = mock(OptimizationRunMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        AiHostingOptimizer aiHostingOptimizer = mock(AiHostingOptimizer.class);
        AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);

        // No prior manual run for this store => the minimum-interval gate (disabled anyway) never trips.
        when(optimizationRunMapper.selectOne(any())).thenReturn(null);
        // Each trigger creates a brand-new run with its own unique id.
        when(optimizationRunService.startRun(any(), eq("manual")))
                .thenAnswer(inv -> runningRun(inv.getArgument(0)));

        HostingOptimizationService service = new HostingOptimizationService(
                optimizationRunService,
                optimizationRunMapper,
                campaignMapper,
                aiHostingOptimizer,
                auditLogMapper,
                new ObjectMapper(),
                executor,
                NO_MIN_INTERVAL);

        return new HostingOptimizationController(service, guard, new ObjectMapper());
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 46: Trigger response is synchronous-only.
     *
     * <p>Validates: Requirements 26.4, 26.5, 28.1.
     *
     * <p>For any valid trigger request (store, optional campaign), the controller returns HTTP 202
     * with a non-null {@code run_id} and {@code request_id} immediately, the async work is
     * dispatched but never executed inline, and the trigger never waits for that work to complete
     * (the run lifecycle calls — finalize/markFailed — have not fired when the response returns).
     */
    @Property(tries = 200)
    void triggerReturns202WithRunIdAndRequestIdWithoutBlockingOnAsyncWork(
            @ForAll("storeIds") UUID storeId,
            @ForAll boolean withCampaign) {

        // Capturing executor: records the dispatched async task WITHOUT running it. This models an
        // arbitrarily long-running optimization — if the trigger blocked on it, control would never
        // return here.
        AtomicReference<Runnable> dispatched = new AtomicReference<>();
        Executor capturingExecutor = dispatched::set;

        OptimizationRunService optimizationRunService = mock(OptimizationRunService.class);
        HostingOrgIsolationGuard guard = mock(HostingOrgIsolationGuard.class);

        OptimizationTriggerRequest request = new OptimizationTriggerRequest();
        request.setStoreId(storeId.toString());
        if (withCampaign) {
            UUID campaignId = UUID.randomUUID();
            request.setCampaignId(campaignId.toString());
            CampaignEntity campaign = CampaignEntity.builder().id(campaignId).storeId(storeId).build();
            when(guard.resolveCampaignInCallerOrg(campaignId)).thenReturn(campaign);
        }

        HostingOptimizationController controller =
                newController(storeId, capturingExecutor, optimizationRunService, guard);

        var response = controller.triggerOptimization(request);

        // Synchronous 202 contract (Req 28.1).
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        ApiResponse<Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isTrue();
        assertThat(body.getData()).isInstanceOf(OptimizationTriggerVo.class);

        // Always carries a non-null run_id (Req 28.1) and request_id (Req 26.5).
        OptimizationTriggerVo vo = (OptimizationTriggerVo) body.getData();
        assertThat(vo.getRunId()).isNotNull();
        assertThat(UUID.fromString(vo.getRunId())).isNotNull(); // run_id is a well-formed id
        assertThat(vo.getRequestId()).isNotBlank();

        // The optimization work was dispatched asynchronously, not executed inline (Req 26.4).
        assertThat(dispatched.get()).isNotNull();
        // ...and the trigger did NOT wait for that async work to complete: its lifecycle calls have
        // not fired, so the response cannot have blocked on the per-campaign work.
        verify(optimizationRunService, never()).finalizeRun(any(), any());
        verify(optimizationRunService, never()).markFailed(any(), any());
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 46: Trigger response is synchronous-only.
     *
     * <p>Validates: Requirements 26.4, 26.5, 28.1.
     *
     * <p>Repeated triggers (each permitted by the minimum interval) each return their own distinct
     * {@code run_id}, so a caller can always correlate a 202 response with a unique run to poll.
     */
    @Property(tries = 100)
    void repeatedTriggersEachReturnDistinctRunIds(
            @ForAll("storeIds") UUID storeId,
            @ForAll("triggerCounts") int triggerCount) {

        // Capturing executor that never runs the dispatched task: repeated triggers stay synchronous.
        Executor capturingExecutor = r -> { /* capture only */ };

        OptimizationRunService optimizationRunService = mock(OptimizationRunService.class);
        HostingOrgIsolationGuard guard = mock(HostingOrgIsolationGuard.class);

        HostingOptimizationController controller =
                newController(storeId, capturingExecutor, optimizationRunService, guard);

        Set<String> runIds = new HashSet<>();
        for (int i = 0; i < triggerCount; i++) {
            OptimizationTriggerRequest request = new OptimizationTriggerRequest();
            request.setStoreId(storeId.toString());

            var response = controller.triggerOptimization(request);

            assertThat(response.getStatusCode().value()).isEqualTo(202);
            OptimizationTriggerVo vo = (OptimizationTriggerVo) response.getBody().getData();
            assertThat(vo.getRunId()).isNotNull();
            runIds.add(vo.getRunId());
        }

        // Every trigger produced its own distinct run_id.
        assertThat(runIds).hasSize(triggerCount);
    }

    // --- generators --------------------------------------------------------

    @Provide
    Arbitrary<UUID> storeIds() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }

    /** A handful of consecutive triggers per store. */
    @Provide
    Arbitrary<Integer> triggerCounts() {
        return Arbitraries.integers().between(2, 6);
    }
}
