package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SloMonitorImplTest {

    private ReportSyncRunMapper reportSyncRunMapper;
    private OperationMapper operationMapper;
    private StoreMapper storeMapper;
    private HostingNotificationService notificationService;
    private SloMonitorImpl monitor;

    @BeforeEach
    void setUp() {
        reportSyncRunMapper = mock(ReportSyncRunMapper.class);
        operationMapper = mock(OperationMapper.class);
        storeMapper = mock(StoreMapper.class);
        notificationService = mock(HostingNotificationService.class);
        monitor = new SloMonitorImpl(reportSyncRunMapper, operationMapper, storeMapper, notificationService);
        ReflectionTestUtils.setField(monitor, "monitorEnabled", true);
        ReflectionTestUtils.setField(monitor, "freshnessThresholdHours", 48.0);
        ReflectionTestUtils.setField(monitor, "successRateThresholdPercent", 95.0);
        ReflectionTestUtils.setField(monitor, "verificationLatencyThresholdMs", 86_400_000.0);
        ReflectionTestUtils.setField(monitor, "windowHours", 24L);
    }

    @Test
    void missingFinalizedReportDataBreachesFreshness() {
        when(reportSyncRunMapper.selectOne(any())).thenReturn(null);
        when(operationMapper.selectList(any())).thenReturn(List.of());

        var result = monitor.evaluate(SloMonitorImpl.REPORT_SYNC_FRESHNESS);

        assertThat(result.status()).isEqualTo(SloMonitor.SloStatus.BREACHED);
        assertThat(result.value()).isGreaterThan(result.threshold());
    }

    @Test
    void successRateUsesRealTerminalAiHostingOperations() {
        UUID storeId = UUID.randomUUID();
        OperationEntity effective = op(storeId, SyncState.EFFECTIVE, 2);
        OperationEntity failed = op(storeId, SyncState.FAILED, 3);
        when(reportSyncRunMapper.selectOne(any())).thenReturn(finalizedRun(storeId));
        when(operationMapper.selectList(any())).thenReturn(List.of(effective, failed));

        var results = monitor.evaluateForStore(storeId);

        assertThat(results.get(SloMonitorImpl.SUBMISSION_SUCCESS_RATE).value()).isEqualTo(50.0);
        assertThat(results.get(SloMonitorImpl.SUBMISSION_SUCCESS_RATE).status())
                .isEqualTo(SloMonitor.SloStatus.BREACHED);
    }

    @Test
    void checkAndAlertSendsEmergencyForBreachedFreshness() {
        UUID storeId = UUID.randomUUID();
        when(storeMapper.selectList(any())).thenReturn(List.of(StoreEntity.builder().id(storeId).build()));
        when(reportSyncRunMapper.selectOne(any())).thenReturn(null);
        when(operationMapper.selectList(any())).thenReturn(List.of());

        monitor.checkAndAlert();

        verify(notificationService).notifyEmergency(eq(storeId), anyString(),
                eq(SloMonitorImpl.REPORT_SYNC_FRESHNESS), anyString(), anyString());
    }

    private static OperationEntity op(UUID storeId, SyncState state, long latencyHours) {
        LocalDateTime created = LocalDateTime.now().minusHours(latencyHours);
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .operationSource("ai_hosting")
                .operationScope("platform_mutation")
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey(UUID.randomUUID().toString())
                .attemptId(UUID.randomUUID())
                .syncState(state.name().toLowerCase())
                .createdAt(created)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static ReportSyncRunEntity finalizedRun(UUID storeId) {
        return ReportSyncRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .reportType("campaigns")
                .requestedDateStart(java.time.LocalDate.now().minusDays(1))
                .requestedDateEnd(java.time.LocalDate.now().minusDays(1))
                .reportStatus("completed")
                .dataStatus("finalized")
                .build();
    }
}
