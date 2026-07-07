package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.service.AiHostingOptimizer;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.audit.entity.AuditLogEntity;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Coordinates manually-triggered optimization runs (Req 28).
 *
 * <p>This service owns the synchronous side of {@code POST /hosting/optimize/trigger}:
 * <ol>
 *   <li>enforces a minimum interval between manual triggers <em>per store</em> (Req 28.4),
 *       rejecting a too-soon trigger with a structured {@code HOSTING_*} error;</li>
 *   <li>creates a {@code manual} {@link OptimizationRunEntity} via {@link OptimizationRunService}
 *       so the caller immediately receives a {@code run_id} to poll (Req 28.5);</li>
 *   <li>records the trigger in {@code audit_logs} with the acting user and scope (Req 28.6);</li>
 *   <li>hands the actual per-campaign optimization work to a dedicated executor so the HTTP
 *       response is synchronous-only (202 + run_id) while the work proceeds asynchronously
 *       (Req 26.4, 28.1, 28.2, 28.3).</li>
 * </ol>
 *
 * <p>Cross-organization isolation is enforced by {@link HostingOrgIsolationGuard} in the
 * controller <em>before</em> this service is invoked (Req 39).</p>
 *
 * <p>Validates: Requirements 28.1, 28.2, 28.3, 28.4, 28.5, 28.6.</p>
 */
@Slf4j
@Service
public class HostingOptimizationService {

    /** Returned when a manual trigger arrives before the per-store minimum interval elapses. */
    public static final String CODE_RATE_LIMITED = "HOSTING_TRIGGER_RATE_LIMITED";
    private static final String TRIGGER_MANUAL = "manual";
    private static final String AUDIT_ACTION = "HOSTING_OPTIMIZE_TRIGGER";
    private static final String AUDIT_ENTITY_TYPE = "OPTIMIZATION_RUN";

    private final OptimizationRunService optimizationRunService;
    private final OptimizationRunMapper optimizationRunMapper;
    private final CampaignMapper campaignMapper;
    private final AiHostingOptimizer aiHostingOptimizer;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    /** Minimum seconds between two manual triggers for the same store (default 5 minutes, Req 28.4). */
    private final long minIntervalSeconds;

    public HostingOptimizationService(
            OptimizationRunService optimizationRunService,
            OptimizationRunMapper optimizationRunMapper,
            CampaignMapper campaignMapper,
            AiHostingOptimizer aiHostingOptimizer,
            AuditLogMapper auditLogMapper,
            ObjectMapper objectMapper,
            @Qualifier(HostingOptimizationExecutorConfig.EXECUTOR_BEAN) Executor executor,
            @Value("${adpilot.hosting.manual-trigger-min-interval-seconds:300}") long minIntervalSeconds) {
        this.optimizationRunService = optimizationRunService;
        this.optimizationRunMapper = optimizationRunMapper;
        this.campaignMapper = campaignMapper;
        this.aiHostingOptimizer = aiHostingOptimizer;
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.minIntervalSeconds = minIntervalSeconds;
    }

    /**
     * Trigger a manual optimization run for a store (optionally scoped to a single campaign).
     *
     * <p>Enforces the per-store minimum manual-trigger interval (Req 28.4) before creating the run.
     * Returns the persisted {@code running} run synchronously; the per-campaign work is dispatched
     * asynchronously and never affects this call's outcome (Req 26.4, 28.1).</p>
     *
     * @param storeId    the store to optimize (already resolved to the caller's org by the guard)
     * @param campaignId optional single campaign to optimize; {@code null} optimizes all hosted
     *                   campaigns in the store (Req 28.2, 28.3)
     * @param actorId    the acting user id for audit attribution (may be {@code null})
     * @return the started optimization run
     * @throws BusinessException {@code HOSTING_TRIGGER_RATE_LIMITED} (429) when the minimum
     *         interval since the last manual trigger for this store has not yet elapsed
     */
    public OptimizationRunEntity triggerManualRun(UUID storeId, UUID campaignId, UUID actorId) {
        if (storeId == null) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", "storeId must not be null");
        }

        enforceMinimumInterval(storeId);

        OptimizationRunEntity run = optimizationRunService.startRun(storeId, TRIGGER_MANUAL);
        recordTriggerAudit(run.getId(), storeId, campaignId, actorId);

        final UUID runId = run.getId();
        executor.execute(() -> executeRun(runId, storeId, campaignId));

        log.info("Manual optimization run {} triggered for store={} campaign={} by actor={}",
                runId, storeId, campaignId, actorId);
        return run;
    }

    // ------------------------------------------------------------------
    // Minimum-interval enforcement (Req 28.4)
    // ------------------------------------------------------------------

    private void enforceMinimumInterval(UUID storeId) {
        if (minIntervalSeconds <= 0) {
            return;
        }
        OptimizationRunEntity lastManual = optimizationRunMapper.selectOne(
                new LambdaQueryWrapper<OptimizationRunEntity>()
                        .eq(OptimizationRunEntity::getStoreId, storeId)
                        .eq(OptimizationRunEntity::getTriggerType, TRIGGER_MANUAL)
                        .orderByDesc(OptimizationRunEntity::getStartedAt)
                        .last("LIMIT 1"));

        if (lastManual == null || lastManual.getStartedAt() == null) {
            return;
        }

        LocalDateTime earliestNext = lastManual.getStartedAt().plusSeconds(minIntervalSeconds);
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(earliestNext)) {
            long waitSeconds = java.time.Duration.between(now, earliestNext).getSeconds() + 1;
            throw new BusinessException(429, CODE_RATE_LIMITED,
                    "A manual optimization was triggered for this store too recently; "
                            + "retry in " + waitSeconds + "s (minimum interval "
                            + minIntervalSeconds + "s).");
        }
    }

    // ------------------------------------------------------------------
    // Asynchronous execution (Req 28.1, 28.2, 28.3)
    // ------------------------------------------------------------------

    /**
     * Run the optimization for the run's target campaigns and finalize the run. Per-campaign
     * failures are isolated as skips so one campaign never aborts the run; a fatal error marks the
     * whole run failed. Amazon Ads platform failures occurring inside Operation execution are
     * recorded on the Operation, never surfaced on the trigger response (Req 26.4).
     */
    void executeRun(UUID runId, UUID storeId, UUID campaignId) {
        try {
            HostingPhase phase = aiHostingOptimizer.activePhase();
            List<CampaignEntity> targets = resolveTargets(storeId, campaignId);

            int processed = 0;
            int skipped = 0;
            int operations = 0;
            for (CampaignEntity campaign : targets) {
                processed++;
                try {
                    int created = aiHostingOptimizer.optimizeCampaign(campaign, phase);
                    operations += created;
                    optimizationRunService.recordCampaignResult(runId, campaign.getId(),
                            Map.of("operationsCreated", created));
                } catch (Exception ex) {
                    skipped++;
                    optimizationRunService.recordSkip(runId, campaign.getId(), "ERROR");
                    log.warn("Optimization run {} skipped campaign {}: {}",
                            runId, campaign.getId(), rootMessage(ex));
                }
            }

            optimizationRunService.finalizeRun(runId, new OptimizationRunService.RunCounts(
                    processed, skipped, operations, 0, 0, 0));
        } catch (Exception ex) {
            log.error("Optimization run {} failed", runId, ex);
            try {
                optimizationRunService.markFailed(runId, rootMessage(ex));
            } catch (Exception markEx) {
                log.error("Failed to mark optimization run {} as failed", runId, markEx);
            }
        }
    }

    private List<CampaignEntity> resolveTargets(UUID storeId, UUID campaignId) {
        if (campaignId != null) {
            CampaignEntity campaign = campaignMapper.selectById(campaignId);
            return campaign == null ? List.of() : List.of(campaign);
        }
        return campaignMapper.selectList(new LambdaQueryWrapper<CampaignEntity>()
                .eq(CampaignEntity::getStoreId, storeId)
                .eq(CampaignEntity::getHostingEnabled, true)
                .isNotNull(CampaignEntity::getTargetAcos));
    }

    // ------------------------------------------------------------------
    // Audit (Req 28.6)
    // ------------------------------------------------------------------

    private void recordTriggerAudit(UUID runId, UUID storeId, UUID campaignId, UUID actorId) {
        try {
            UUID orgId = SecurityUtils.isAuthenticated()
                    ? parseUuidOrNull(SecurityUtils.getCurrentOrgId())
                    : null;
            AuditLogEntity entry = AuditLogEntity.builder()
                    .userId(actorId)
                    .orgId(orgId)
                    .action(AUDIT_ACTION)
                    .entityType(AUDIT_ENTITY_TYPE)
                    .entityId(runId)
                    .newData(buildAuditDetail(runId, storeId, campaignId))
                    .source("app")
                    .build();
            auditLogMapper.insert(entry);
        } catch (Exception ex) {
            // Auditing must never block a legitimate trigger.
            log.warn("Failed to audit manual optimization trigger for store {}: {}", storeId, rootMessage(ex));
        }
    }

    private String buildAuditDetail(UUID runId, UUID storeId, UUID campaignId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", runId != null ? runId.toString() : null);
        detail.put("storeId", storeId != null ? storeId.toString() : null);
        detail.put("campaignId", campaignId != null ? campaignId.toString() : null);
        detail.put("triggerType", TRIGGER_MANUAL);
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            return "{\"runId\":\"" + runId + "\",\"storeId\":\"" + storeId + "\"}";
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }
}
