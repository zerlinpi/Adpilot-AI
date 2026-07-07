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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DB-backed decision drift monitor for AI Hosting production governance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriftMonitorImpl implements DriftMonitor {

    static final String DECISION_VOLUME = "decision_volume";
    static final String AVG_RISK_SCORE = "avg_risk_score";
    static final String REJECTION_RATE = "rejection_rate";
    static final String FAILURE_RATE = "failure_rate";

    private final AiDecisionMapper aiDecisionMapper;
    private final OperationMapper operationMapper;
    private final StoreMapper storeMapper;
    private final HostingNotificationService notificationService;

    @Value("${adpilot.hosting.governance.drift-monitor-enabled:true}")
    private boolean monitorEnabled;

    @Value("${adpilot.hosting.governance.drift-current-window-hours:24}")
    private long currentWindowHours;

    @Value("${adpilot.hosting.governance.drift-baseline-days:7}")
    private long baselineDays;

    @Value("${adpilot.hosting.governance.drift-volume-threshold-percent:100}")
    private double volumeDriftThresholdPercent;

    @Value("${adpilot.hosting.governance.drift-risk-threshold-percent:50}")
    private double riskDriftThresholdPercent;

    @Value("${adpilot.hosting.governance.drift-rate-threshold-percent:50}")
    private double rateDriftThresholdPercent;

    @Override
    public List<DriftResult> evaluateDrift(UUID storeId) {
        if (!monitorEnabled) {
            log.debug("Drift monitor is disabled; no drift evaluation for store={}", storeId);
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentStart = now.minusHours(currentWindowHours);
        LocalDateTime baselineStart = currentStart.minusDays(baselineDays);

        List<AiDecisionEntity> currentDecisions = decisions(storeId, currentStart, now);
        List<AiDecisionEntity> baselineDecisions = decisions(storeId, baselineStart, currentStart);
        List<OperationEntity> currentOps = operations(storeId, currentStart, now);
        List<OperationEntity> baselineOps = operations(storeId, baselineStart, currentStart);

        double normalizedBaselineVolume = baselineDecisions.size() / Math.max(1.0, baselineDays);
        double normalizedBaselineFailures = failureRate(baselineOps);
        double normalizedBaselineRejections = rejectionRate(baselineOps);

        List<DriftResult> results = new ArrayList<>();
        results.add(result(DECISION_VOLUME, currentDecisions.size(), normalizedBaselineVolume,
                volumeDriftThresholdPercent));
        results.add(result(AVG_RISK_SCORE, avgRisk(currentDecisions), avgRisk(baselineDecisions),
                riskDriftThresholdPercent));
        results.add(result(REJECTION_RATE, rejectionRate(currentOps), normalizedBaselineRejections,
                rateDriftThresholdPercent));
        results.add(result(FAILURE_RATE, failureRate(currentOps), normalizedBaselineFailures,
                rateDriftThresholdPercent));
        return results;
    }

    @Override
    @Scheduled(fixedDelayString = "${adpilot.hosting.governance.drift-monitor-ms:300000}")
    public void checkAndAlert() {
        if (!monitorEnabled) {
            log.debug("Drift monitor is disabled; skipping alert evaluation");
            return;
        }

        for (StoreEntity store : storeMapper.selectList(new LambdaQueryWrapper<>())) {
            UUID storeId = store.getId();
            evaluateDrift(storeId).stream()
                    .filter(DriftResult::breached)
                    .forEach(result -> alert(storeId, result));
        }
    }

    private List<AiDecisionEntity> decisions(UUID storeId, LocalDateTime start, LocalDateTime end) {
        LambdaQueryWrapper<AiDecisionEntity> query = new LambdaQueryWrapper<>();
        query.ge(AiDecisionEntity::getCreatedAt, start)
                .lt(AiDecisionEntity::getCreatedAt, end);
        if (storeId != null) {
            query.eq(AiDecisionEntity::getStoreId, storeId);
        }
        return aiDecisionMapper.selectList(query);
    }

    private List<OperationEntity> operations(UUID storeId, LocalDateTime start, LocalDateTime end) {
        LambdaQueryWrapper<OperationEntity> query = new LambdaQueryWrapper<>();
        query.eq(OperationEntity::getOperationSource, "ai_hosting")
                .ge(OperationEntity::getUpdatedAt, start)
                .lt(OperationEntity::getUpdatedAt, end);
        if (storeId != null) {
            query.eq(OperationEntity::getStoreId, storeId);
        }
        return operationMapper.selectList(query);
    }

    private double avgRisk(List<AiDecisionEntity> decisions) {
        return decisions.stream()
                .map(AiDecisionEntity::getRiskScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0);
    }

    private double failureRate(List<OperationEntity> operations) {
        long terminal = operations.stream()
                .filter(op -> SyncState.EFFECTIVE.name().equalsIgnoreCase(op.getSyncState())
                        || SyncState.FAILED.name().equalsIgnoreCase(op.getSyncState()))
                .count();
        if (terminal == 0) {
            return 0;
        }
        long failed = operations.stream()
                .filter(op -> SyncState.FAILED.name().equalsIgnoreCase(op.getSyncState()))
                .count();
        return failed * 100.0 / terminal;
    }

    private double rejectionRate(List<OperationEntity> operations) {
        long approvalTerminal = operations.stream()
                .filter(op -> SyncState.EFFECTIVE.name().equalsIgnoreCase(op.getSyncState())
                        || SyncState.CANCELLED.name().equalsIgnoreCase(op.getSyncState()))
                .count();
        if (approvalTerminal == 0) {
            return 0;
        }
        long rejected = operations.stream()
                .filter(op -> SyncState.CANCELLED.name().equalsIgnoreCase(op.getSyncState()))
                .filter(op -> op.getStatusReason() == null
                        || op.getStatusReason().toLowerCase(java.util.Locale.ROOT).contains("reject"))
                .count();
        return rejected * 100.0 / approvalTerminal;
    }

    private DriftResult result(String metric, double current, double baseline, double threshold) {
        double drift = driftPercent(current, baseline);
        return new DriftResult(metric, current, baseline, drift, threshold, Math.abs(drift) >= threshold);
    }

    private double driftPercent(double current, double baseline) {
        if (baseline == 0) {
            return current == 0 ? 0 : 100;
        }
        return ((current - baseline) / Math.abs(baseline)) * 100.0;
    }

    private void alert(UUID storeId, DriftResult result) {
        try {
            notificationService.notifyEmergency(
                    storeId,
                    "AI Hosting Governance",
                    result.metric(),
                    String.format("current=%.4f, baseline=%.4f, drift=%.2f%%, threshold=%.2f%%",
                            result.currentValue(), result.baselineValue(),
                            result.driftPercent(), result.threshold()),
                    "Decision drift breach detected; review recent optimizer inputs and approvals.");
        } catch (Exception ex) {
            log.warn("Failed to send drift alert store={} metric={}: {}", storeId, result.metric(), ex.getMessage());
        }
    }
}
