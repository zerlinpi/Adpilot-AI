package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DriftMonitorImplTest {

    private AiDecisionMapper aiDecisionMapper;
    private OperationMapper operationMapper;
    private StoreMapper storeMapper;
    private HostingNotificationService notificationService;
    private DriftMonitorImpl monitor;

    @BeforeEach
    void setUp() {
        aiDecisionMapper = mock(AiDecisionMapper.class);
        operationMapper = mock(OperationMapper.class);
        storeMapper = mock(StoreMapper.class);
        notificationService = mock(HostingNotificationService.class);
        monitor = new DriftMonitorImpl(aiDecisionMapper, operationMapper, storeMapper, notificationService);
        ReflectionTestUtils.setField(monitor, "monitorEnabled", true);
        ReflectionTestUtils.setField(monitor, "currentWindowHours", 24L);
        ReflectionTestUtils.setField(monitor, "baselineDays", 7L);
        ReflectionTestUtils.setField(monitor, "volumeDriftThresholdPercent", 100.0);
        ReflectionTestUtils.setField(monitor, "riskDriftThresholdPercent", 50.0);
        ReflectionTestUtils.setField(monitor, "rateDriftThresholdPercent", 50.0);
    }

    @Test
    void evaluatesDecisionVolumeAgainstPriorBaseline() {
        UUID storeId = UUID.randomUUID();
        when(aiDecisionMapper.selectList(any()))
                .thenReturn(decisions(storeId, 10, "0.00"))
                .thenReturn(decisions(storeId, 7, "0.00"));
        when(operationMapper.selectList(any())).thenReturn(List.of()).thenReturn(List.of());

        List<DriftMonitor.DriftResult> results = monitor.evaluateDrift(storeId);

        DriftMonitor.DriftResult volume = results.stream()
                .filter(r -> DriftMonitorImpl.DECISION_VOLUME.equals(r.metric()))
                .findFirst()
                .orElseThrow();
        assertThat(volume.currentValue()).isEqualTo(10.0);
        assertThat(volume.baselineValue()).isEqualTo(1.0);
        assertThat(volume.breached()).isTrue();
    }

    @Test
    void checkAndAlertSendsEmergencyForBreachedDrift() {
        UUID storeId = UUID.randomUUID();
        when(storeMapper.selectList(any())).thenReturn(List.of(StoreEntity.builder().id(storeId).build()));
        when(aiDecisionMapper.selectList(any()))
                .thenReturn(decisions(storeId, 10, "0.00"))
                .thenReturn(List.of());
        when(operationMapper.selectList(any())).thenReturn(List.of()).thenReturn(List.of());

        monitor.checkAndAlert();

        verify(notificationService).notifyEmergency(eq(storeId), anyString(),
                eq(DriftMonitorImpl.DECISION_VOLUME), anyString(), anyString());
    }

    @Test
    void failureRateUsesTerminalAiHostingOperations() {
        UUID storeId = UUID.randomUUID();
        when(aiDecisionMapper.selectList(any())).thenReturn(List.of()).thenReturn(List.of());
        when(operationMapper.selectList(any()))
                .thenReturn(List.of(op(storeId, SyncState.EFFECTIVE), op(storeId, SyncState.FAILED)))
                .thenReturn(List.of(op(storeId, SyncState.EFFECTIVE), op(storeId, SyncState.EFFECTIVE)));

        DriftMonitor.DriftResult failureRate = monitor.evaluateDrift(storeId).stream()
                .filter(r -> DriftMonitorImpl.FAILURE_RATE.equals(r.metric()))
                .findFirst()
                .orElseThrow();

        assertThat(failureRate.currentValue()).isEqualTo(50.0);
        assertThat(failureRate.baselineValue()).isEqualTo(0.0);
        assertThat(failureRate.breached()).isTrue();
    }

    private static List<AiDecisionEntity> decisions(UUID storeId, int count, String riskScore) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> AiDecisionEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(storeId)
                        .engine("v1_bid")
                        .decisionType("bid_adjustment")
                        .executionMode("auto_execute")
                        .riskScore(new BigDecimal(riskScore))
                        .createdAt(LocalDateTime.now())
                        .decisionSnapshot("{}")
                        .build())
                .toList();
    }

    private static OperationEntity op(UUID storeId, SyncState state) {
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
                .createdAt(LocalDateTime.now().minusHours(1))
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
