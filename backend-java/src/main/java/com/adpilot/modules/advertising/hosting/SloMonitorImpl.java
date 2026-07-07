package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DB-backed SLO monitor for AI Hosting production governance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SloMonitorImpl implements SloMonitor {

    static final String REPORT_SYNC_FRESHNESS = "report_sync_freshness";
    static final String SUBMISSION_SUCCESS_RATE = "submission_success_rate";
    static final String VERIFICATION_LATENCY = "verification_latency";

    private static final List<String> TERMINAL_OPERATION_STATES = List.of(
            SyncState.EFFECTIVE.name().toLowerCase(),
            SyncState.FAILED.name().toLowerCase());

    private final ReportSyncRunMapper reportSyncRunMapper;
    private final OperationMapper operationMapper;
    private final StoreMapper storeMapper;
    private final HostingNotificationService notificationService;

    @Value("${adpilot.hosting.governance.slo-monitor-enabled:true}")
    private boolean monitorEnabled;

    @Value("${adpilot.hosting.governance.slo-freshness-threshold-hours:48}")
    private double freshnessThresholdHours;

    @Value("${adpilot.hosting.governance.slo-success-rate-threshold-percent:95}")
    private double successRateThresholdPercent;

    @Value("${adpilot.hosting.governance.slo-verification-latency-threshold-ms:86400000}")
    private double verificationLatencyThresholdMs;

    @Value("${adpilot.hosting.governance.slo-window-hours:24}")
    private long windowHours;

    @Override
    public Map<String, SloResult> evaluateAll() {
        if (!monitorEnabled) {
            Map<String, SloResult> disabled = new LinkedHashMap<>();
            disabled.put(REPORT_SYNC_FRESHNESS, disabled(REPORT_SYNC_FRESHNESS, "hours"));
            disabled.put(SUBMISSION_SUCCESS_RATE, disabled(SUBMISSION_SUCCESS_RATE, "percent"));
            disabled.put(VERIFICATION_LATENCY, disabled(VERIFICATION_LATENCY, "ms"));
            return disabled;
        }

        return evaluateForStore(null);
    }

    @Override
    public SloResult evaluate(String sloName) {
        if (sloName == null || sloName.isBlank()) {
            return null;
        }
        return evaluateAll().get(sloName);
    }

    @Override
    @Scheduled(fixedDelayString = "${adpilot.hosting.governance.slo-monitor-ms:300000}")
    public void checkAndAlert() {
        if (!monitorEnabled) {
            log.debug("SLO monitor is disabled; skipping alert evaluation");
            return;
        }

        for (StoreEntity store : storeMapper.selectList(new LambdaQueryWrapper<>())) {
            UUID storeId = store.getId();
            Map<String, SloResult> results = evaluateForStore(storeId);
            results.values().stream()
                    .filter(r -> r.status() == SloStatus.BREACHED)
                    .forEach(r -> alert(storeId, r));
        }
    }

    Map<String, SloResult> evaluateForStore(UUID storeId) {
        Map<String, SloResult> results = new LinkedHashMap<>();
        results.put(REPORT_SYNC_FRESHNESS, evaluateFreshness(storeId));
        results.put(SUBMISSION_SUCCESS_RATE, evaluateSuccessRate(storeId));
        results.put(VERIFICATION_LATENCY, evaluateVerificationLatency(storeId));
        return results;
    }

    private SloResult evaluateFreshness(UUID storeId) {
        LambdaQueryWrapper<ReportSyncRunEntity> query = new LambdaQueryWrapper<>();
        query.eq(ReportSyncRunEntity::getReportStatus, "completed")
                .eq(ReportSyncRunEntity::getDataStatus, "finalized")
                .orderByDesc(ReportSyncRunEntity::getRequestedDateEnd)
                .last("LIMIT 1");
        if (storeId != null) {
            query.eq(ReportSyncRunEntity::getStoreId, storeId);
        }

        ReportSyncRunEntity latest = reportSyncRunMapper.selectOne(query);
        if (latest == null || latest.getRequestedDateEnd() == null) {
            return new SloResult(REPORT_SYNC_FRESHNESS, SloStatus.BREACHED,
                    freshnessThresholdHours + 1, freshnessThresholdHours, "hours");
        }

        LocalDate latestDate = latest.getRequestedDateEnd();
        double hours = Duration.between(latestDate.plusDays(1).atStartOfDay(), LocalDateTime.now()).toHours();
        double stalenessHours = Math.max(0, hours);
        return new SloResult(REPORT_SYNC_FRESHNESS,
                lowerIsBetterStatus(stalenessHours, freshnessThresholdHours),
                stalenessHours, freshnessThresholdHours, "hours");
    }

    private SloResult evaluateSuccessRate(UUID storeId) {
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
        List<OperationEntity> terminal = terminalOperations(storeId, since);
        if (terminal.isEmpty()) {
            return new SloResult(SUBMISSION_SUCCESS_RATE, SloStatus.HEALTHY,
                    100, successRateThresholdPercent, "percent");
        }

        long effective = terminal.stream()
                .filter(op -> SyncState.EFFECTIVE.name().equalsIgnoreCase(op.getSyncState()))
                .count();
        double successRate = effective * 100.0 / terminal.size();
        return new SloResult(SUBMISSION_SUCCESS_RATE,
                higherIsBetterStatus(successRate, successRateThresholdPercent),
                successRate, successRateThresholdPercent, "percent");
    }

    private SloResult evaluateVerificationLatency(UUID storeId) {
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
        List<OperationEntity> terminal = terminalOperations(storeId, since);
        double avgMs = terminal.stream()
                .filter(op -> op.getCreatedAt() != null && op.getUpdatedAt() != null)
                .mapToLong(op -> Math.max(0, Duration.between(op.getCreatedAt(), op.getUpdatedAt()).toMillis()))
                .average()
                .orElse(0);
        return new SloResult(VERIFICATION_LATENCY,
                lowerIsBetterStatus(avgMs, verificationLatencyThresholdMs),
                avgMs, verificationLatencyThresholdMs, "ms");
    }

    private List<OperationEntity> terminalOperations(UUID storeId, LocalDateTime since) {
        LambdaQueryWrapper<OperationEntity> query = new LambdaQueryWrapper<>();
        query.eq(OperationEntity::getOperationSource, "ai_hosting")
                .in(OperationEntity::getSyncState, TERMINAL_OPERATION_STATES)
                .ge(OperationEntity::getUpdatedAt, since);
        if (storeId != null) {
            query.eq(OperationEntity::getStoreId, storeId);
        }
        return operationMapper.selectList(query);
    }

    private void alert(UUID storeId, SloResult result) {
        try {
            notificationService.notifyEmergency(
                    storeId,
                    "AI Hosting Governance",
                    result.name(),
                    String.format("value=%.2f %s, threshold=%.2f %s",
                            result.value(), result.unit(), result.threshold(), result.unit()),
                    "SLO breach detected; review data sync, write-back, and verification workers.");
        } catch (Exception ex) {
            log.warn("Failed to send SLO alert store={} slo={}: {}", storeId, result.name(), ex.getMessage());
        }
    }

    private SloStatus lowerIsBetterStatus(double value, double threshold) {
        if (value > threshold) {
            return SloStatus.BREACHED;
        }
        if (value > threshold * 0.8) {
            return SloStatus.WARNING;
        }
        return SloStatus.HEALTHY;
    }

    private SloStatus higherIsBetterStatus(double value, double threshold) {
        if (value < threshold) {
            return SloStatus.BREACHED;
        }
        if (value < threshold + 2.0) {
            return SloStatus.WARNING;
        }
        return SloStatus.HEALTHY;
    }

    private SloResult disabled(String name, String unit) {
        return new SloResult(name, SloStatus.DISABLED, 0, 0, unit);
    }
}
