package com.adpilot.modules.advertising.hosting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of {@link OptimizationRunService}.
 *
 * <p>Manages the full lifecycle of {@code optimization_runs} records:
 * <ol>
 *   <li>{@link #startRun} — creates a new row with status=running</li>
 *   <li>{@link #recordCampaignResult} — appends per-campaign detail to the JSON</li>
 *   <li>{@link #recordSkip} — records skip reasons in the skip_reasons JSON</li>
 *   <li>{@link #finalizeRun} — sets status=completed with aggregate counts</li>
 *   <li>{@link #markFailed} — sets status=failed with error</li>
 * </ol>
 *
 * <p>A scheduled cleanup deletes records older than 90 days (Req 18.5).
 *
 * <p>Validates: Requirements 18.1, 18.2, 18.3, 18.4, 18.5.</p>
 */
@Service
public class OptimizationRunServiceImpl implements OptimizationRunService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationRunServiceImpl.class);
    private static final int RETENTION_DAYS = 90;

    private final OptimizationRunMapper optimizationRunMapper;
    private final ObjectMapper objectMapper;

    public OptimizationRunServiceImpl(OptimizationRunMapper optimizationRunMapper,
                                      ObjectMapper objectMapper) {
        this.optimizationRunMapper = optimizationRunMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public OptimizationRunEntity startRun(UUID storeId, String triggerType) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId must not be null");
        }
        if (triggerType == null || triggerType.isBlank()) {
            throw new IllegalArgumentException("triggerType must not be null or blank");
        }
        if (!triggerType.equals("scheduled") && !triggerType.equals("manual")) {
            throw new IllegalArgumentException(
                    "triggerType must be 'scheduled' or 'manual', got: " + triggerType);
        }

        OptimizationRunEntity entity = OptimizationRunEntity.builder()
                .storeId(storeId)
                .triggerType(triggerType)
                .status("running")
                .startedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .campaignsProcessed(0)
                .campaignsSkipped(0)
                .operationsCreated(0)
                .decisionsGenerated(0)
                .decisionsAutoExecuted(0)
                .decisionsRequiringApproval(0)
                .perCampaignResults("{}")
                .skipReasons("{}")
                .build();

        optimizationRunMapper.insert(entity);
        log.info("Started optimization run {} for store {} (trigger={})",
                entity.getId(), storeId, triggerType);
        return entity;
    }

    @Override
    public void recordCampaignResult(UUID runId, UUID campaignId, Object result) {
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
        if (campaignId == null) {
            throw new IllegalArgumentException("campaignId must not be null");
        }

        OptimizationRunEntity entity = optimizationRunMapper.selectById(runId);
        if (entity == null) {
            throw new IllegalArgumentException("Optimization run not found: " + runId);
        }

        Map<String, Object> results = deserializeJsonMap(entity.getPerCampaignResults());
        results.put(campaignId.toString(), result);
        entity.setPerCampaignResults(serializeToJson(results));
        optimizationRunMapper.updateById(entity);
    }

    @Override
    public void recordSkip(UUID runId, UUID campaignId, String reason) {
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
        if (campaignId == null) {
            throw new IllegalArgumentException("campaignId must not be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be null or blank");
        }

        OptimizationRunEntity entity = optimizationRunMapper.selectById(runId);
        if (entity == null) {
            throw new IllegalArgumentException("Optimization run not found: " + runId);
        }

        Map<String, Object> skipReasons = deserializeJsonMap(entity.getSkipReasons());
        skipReasons.put(campaignId.toString(), reason);
        entity.setSkipReasons(serializeToJson(skipReasons));
        optimizationRunMapper.updateById(entity);
    }

    @Override
    public void finalizeRun(UUID runId, RunCounts counts) {
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
        if (counts == null) {
            throw new IllegalArgumentException("counts must not be null");
        }

        OptimizationRunEntity entity = optimizationRunMapper.selectById(runId);
        if (entity == null) {
            throw new IllegalArgumentException("Optimization run not found: " + runId);
        }

        entity.setStatus("completed");
        entity.setCompletedAt(LocalDateTime.now());
        entity.setCampaignsProcessed(counts.campaignsProcessed());
        entity.setCampaignsSkipped(counts.campaignsSkipped());
        entity.setOperationsCreated(counts.operationsCreated());
        entity.setDecisionsGenerated(counts.decisionsGenerated());
        entity.setDecisionsAutoExecuted(counts.decisionsAutoExecuted());
        entity.setDecisionsRequiringApproval(counts.decisionsRequiringApproval());

        optimizationRunMapper.updateById(entity);
        log.info("Finalized optimization run {} — processed={}, skipped={}, decisions={}, auto={}",
                runId, counts.campaignsProcessed(), counts.campaignsSkipped(),
                counts.decisionsGenerated(), counts.decisionsAutoExecuted());
    }

    @Override
    public void markFailed(UUID runId, String error) {
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }

        OptimizationRunEntity entity = optimizationRunMapper.selectById(runId);
        if (entity == null) {
            throw new IllegalArgumentException("Optimization run not found: " + runId);
        }

        entity.setStatus("failed");
        entity.setCompletedAt(LocalDateTime.now());

        // Store the error in per_campaign_results as a top-level _error key
        Map<String, Object> results = deserializeJsonMap(entity.getPerCampaignResults());
        results.put("_error", error != null ? error : "Unknown error");
        entity.setPerCampaignResults(serializeToJson(results));

        optimizationRunMapper.updateById(entity);
        log.warn("Optimization run {} marked as failed: {}", runId, error);
    }

    @Override
    public Optional<OptimizationRunEntity> findById(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(optimizationRunMapper.selectById(runId));
    }

    @Override
    public int cleanupOlderThan90Days() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = optimizationRunMapper.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Retention cleanup: deleted {} optimization_runs older than {} days",
                    deleted, RETENTION_DAYS);
        }
        return deleted;
    }

    /**
     * Scheduled retention cleanup — runs daily at 03:00 to remove runs older than 90 days (Req 18.5).
     */
    @Scheduled(cron = "${adpilot.hosting.optimization-run.retention-cron:0 0 3 * * ?}")
    public void scheduledRetentionCleanup() {
        try {
            cleanupOlderThan90Days();
        } catch (Exception e) {
            log.error("Retention cleanup failed for optimization_runs", e);
        }
    }

    // ─── JSON helpers ────────────────────────────────────────────────────────────

    private Map<String, Object> deserializeJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize JSON map, starting fresh: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String serializeToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize value to JSON", e);
        }
    }
}
