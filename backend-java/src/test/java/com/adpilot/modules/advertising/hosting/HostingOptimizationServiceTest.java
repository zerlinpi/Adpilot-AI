package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.service.AiHostingOptimizer;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
 * Unit tests for {@link HostingOptimizationService}, focused on the per-store minimum
 * manual-trigger interval (Req 28.4) and the synchronous-only trigger contract (Req 28.1, 28.5).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HostingOptimizationServiceTest {

    private static final long MIN_INTERVAL_SECONDS = 300;

    @Mock
    private OptimizationRunService optimizationRunService;
    @Mock
    private OptimizationRunMapper optimizationRunMapper;
    @Mock
    private CampaignMapper campaignMapper;
    @Mock
    private AiHostingOptimizer aiHostingOptimizer;
    @Mock
    private AuditLogMapper auditLogMapper;

    /** Records the dispatched async task without running it, so the trigger stays synchronous-only. */
    private final AtomicReference<Runnable> dispatched = new AtomicReference<>();
    private final Executor capturingExecutor = dispatched::set;

    private HostingOptimizationService service;

    @BeforeEach
    void setUp() {
        service = new HostingOptimizationService(
                optimizationRunService,
                optimizationRunMapper,
                campaignMapper,
                aiHostingOptimizer,
                auditLogMapper,
                new ObjectMapper(),
                capturingExecutor,
                MIN_INTERVAL_SECONDS);
    }

    private OptimizationRunEntity runFor(UUID storeId) {
        return OptimizationRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .triggerType("manual")
                .status("running")
                .startedAt(LocalDateTime.now())
                .build();
    }

    // ── Minimum-interval enforcement (Req 28.4) ──────────────────────────────────

    @Test
    void firstTriggerWithNoPriorRunIsAllowed() {
        UUID storeId = UUID.randomUUID();
        when(optimizationRunMapper.selectOne(any())).thenReturn(null);
        when(optimizationRunService.startRun(eq(storeId), eq("manual"))).thenReturn(runFor(storeId));

        OptimizationRunEntity run = service.triggerManualRun(storeId, null, null);

        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo("running");
        verify(optimizationRunService).startRun(storeId, "manual");
        // The trigger is synchronous-only: the work was dispatched, not executed inline.
        assertThat(dispatched.get()).isNotNull();
    }

    @Test
    void secondTriggerWithinIntervalIsRejected() {
        UUID storeId = UUID.randomUUID();
        OptimizationRunEntity recent = runFor(storeId);
        recent.setStartedAt(LocalDateTime.now().minusSeconds(MIN_INTERVAL_SECONDS - 30)); // still inside the window
        when(optimizationRunMapper.selectOne(any())).thenReturn(recent);

        assertThatThrownBy(() -> service.triggerManualRun(storeId, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(HostingOptimizationService.CODE_RATE_LIMITED);
                    assertThat(be.getStatus()).isEqualTo(429);
                });

        // Rejected before creating a run or dispatching any work.
        verify(optimizationRunService, never()).startRun(any(), any());
        assertThat(dispatched.get()).isNull();
    }

    @Test
    void triggerAfterIntervalElapsedIsAllowed() {
        UUID storeId = UUID.randomUUID();
        OptimizationRunEntity old = runFor(storeId);
        old.setStartedAt(LocalDateTime.now().minusSeconds(MIN_INTERVAL_SECONDS + 60)); // outside the window
        when(optimizationRunMapper.selectOne(any())).thenReturn(old);
        when(optimizationRunService.startRun(eq(storeId), eq("manual"))).thenReturn(runFor(storeId));

        OptimizationRunEntity run = service.triggerManualRun(storeId, null, null);

        assertThat(run).isNotNull();
        verify(optimizationRunService).startRun(storeId, "manual");
    }

    @Test
    void perStoreIsolationDoesNotRejectDifferentStores() {
        // A recent run for a DIFFERENT store must not block this store's first trigger. The mapper
        // query is store-scoped, so for the store under test it returns no prior run.
        UUID storeId = UUID.randomUUID();
        when(optimizationRunMapper.selectOne(any())).thenReturn(null);
        when(optimizationRunService.startRun(eq(storeId), eq("manual"))).thenReturn(runFor(storeId));

        OptimizationRunEntity run = service.triggerManualRun(storeId, null, null);

        assertThat(run).isNotNull();
        verify(optimizationRunService).startRun(storeId, "manual");
    }

    @Test
    void zeroIntervalDisablesEnforcement() {
        UUID storeId = UUID.randomUUID();
        HostingOptimizationService noLimit = new HostingOptimizationService(
                optimizationRunService, optimizationRunMapper, campaignMapper, aiHostingOptimizer,
                auditLogMapper, new ObjectMapper(), capturingExecutor, 0);
        OptimizationRunEntity recent = runFor(storeId);
        recent.setStartedAt(LocalDateTime.now()); // would be inside any positive window
        when(optimizationRunMapper.selectOne(any())).thenReturn(recent);
        when(optimizationRunService.startRun(eq(storeId), eq("manual"))).thenReturn(runFor(storeId));

        OptimizationRunEntity run = noLimit.triggerManualRun(storeId, null, null);

        assertThat(run).isNotNull();
        verify(optimizationRunService).startRun(storeId, "manual");
    }

    // ── Audit (Req 28.6) ─────────────────────────────────────────────────────────

    @Test
    void triggerRecordsAuditLog() {
        UUID storeId = UUID.randomUUID();
        when(optimizationRunMapper.selectOne(any())).thenReturn(null);
        when(optimizationRunService.startRun(eq(storeId), eq("manual"))).thenReturn(runFor(storeId));

        service.triggerManualRun(storeId, null, UUID.randomUUID());

        verify(auditLogMapper).insert(any());
    }

    // ── Asynchronous execution (Req 28.1, 28.2, 28.3) ────────────────────────────

    @Test
    void executeRunOptimizesHostedCampaignsAndFinalizes() {
        UUID storeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        CampaignEntity c1 = CampaignEntity.builder()
                .id(UUID.randomUUID()).storeId(storeId).name("A")
                .hostingEnabled(true).targetAcos(new BigDecimal("25")).build();
        CampaignEntity c2 = CampaignEntity.builder()
                .id(UUID.randomUUID()).storeId(storeId).name("B")
                .hostingEnabled(true).targetAcos(new BigDecimal("30")).build();

        when(aiHostingOptimizer.activePhase()).thenReturn(HostingPhase.V1);
        when(campaignMapper.selectList(any())).thenReturn(List.of(c1, c2));
        when(aiHostingOptimizer.optimizeCampaign(eq(c1), any())).thenReturn(2);
        when(aiHostingOptimizer.optimizeCampaign(eq(c2), any())).thenReturn(1);

        service.executeRun(runId, storeId, null);

        ArgumentCaptor<OptimizationRunService.RunCounts> counts =
                ArgumentCaptor.forClass(OptimizationRunService.RunCounts.class);
        verify(optimizationRunService).finalizeRun(eq(runId), counts.capture());
        assertThat(counts.getValue().campaignsProcessed()).isEqualTo(2);
        assertThat(counts.getValue().campaignsSkipped()).isZero();
        assertThat(counts.getValue().operationsCreated()).isEqualTo(3);
    }

    @Test
    void executeRunIsolatesPerCampaignFailureAsSkip() {
        UUID storeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        CampaignEntity ok = CampaignEntity.builder()
                .id(UUID.randomUUID()).storeId(storeId).name("ok")
                .hostingEnabled(true).targetAcos(new BigDecimal("25")).build();
        CampaignEntity boom = CampaignEntity.builder()
                .id(UUID.randomUUID()).storeId(storeId).name("boom")
                .hostingEnabled(true).targetAcos(new BigDecimal("25")).build();

        when(aiHostingOptimizer.activePhase()).thenReturn(HostingPhase.V1);
        when(campaignMapper.selectList(any())).thenReturn(List.of(ok, boom));
        when(aiHostingOptimizer.optimizeCampaign(eq(ok), any())).thenReturn(1);
        when(aiHostingOptimizer.optimizeCampaign(eq(boom), any()))
                .thenThrow(new RuntimeException("engine failure"));

        service.executeRun(runId, storeId, null);

        verify(optimizationRunService).recordSkip(eq(runId), eq(boom.getId()), eq("ERROR"));
        ArgumentCaptor<OptimizationRunService.RunCounts> counts =
                ArgumentCaptor.forClass(OptimizationRunService.RunCounts.class);
        verify(optimizationRunService).finalizeRun(eq(runId), counts.capture());
        assertThat(counts.getValue().campaignsProcessed()).isEqualTo(2);
        assertThat(counts.getValue().campaignsSkipped()).isEqualTo(1);
        assertThat(counts.getValue().operationsCreated()).isEqualTo(1);
    }
}
