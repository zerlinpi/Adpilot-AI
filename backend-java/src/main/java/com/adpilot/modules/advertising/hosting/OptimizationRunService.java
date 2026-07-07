package com.adpilot.modules.advertising.hosting;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing the lifecycle of {@code optimization_runs} records (Req 18.1–18.5).
 *
 * <p>Each optimization execution (scheduled or manual) creates a run, records
 * per-campaign results and skip reasons as it progresses, then finalizes with
 * aggregate counts. Failed runs are marked with an error message.
 *
 * <p>A 90-day retention policy (Req 18.5) is enforced via scheduled cleanup.
 *
 * <p>Validates: Requirements 18.1, 18.2, 18.3, 18.4, 18.5.</p>
 */
public interface OptimizationRunService {

    /**
     * Start a new optimization run. Creates a row with status=running and started_at=now.
     *
     * @param storeId     the store this run belongs to
     * @param triggerType "scheduled" or "manual"
     * @return the persisted entity with generated id
     */
    OptimizationRunEntity startRun(UUID storeId, String triggerType);

    /**
     * Record a per-campaign result in the run's JSON results column.
     *
     * @param runId      the run to update
     * @param campaignId the campaign that was processed
     * @param result     structured result detail (serialized to JSON)
     */
    void recordCampaignResult(UUID runId, UUID campaignId, Object result);

    /**
     * Record a skip reason for a campaign in the run's skip_reasons JSON column.
     *
     * @param runId      the run to update
     * @param campaignId the campaign that was skipped
     * @param reason     the skip reason code (e.g., DATA_STALE, DATA_INCOMPLETE, COOLDOWN,
     *                   NO_TARGET_ACOS, LEARNING_PERIOD, NOT_HOSTED, OBSERVE_ONLY, KILL_SWITCH)
     */
    void recordSkip(UUID runId, UUID campaignId, String reason);

    /**
     * Finalize a run as completed. Sets status=completed, completed_at=now, and the aggregate counts.
     *
     * @param runId the run to finalize
     * @param counts the aggregate run counts
     */
    void finalizeRun(UUID runId, RunCounts counts);

    /**
     * Mark a run as failed with an error message.
     *
     * @param runId the run to mark
     * @param error the error description
     */
    void markFailed(UUID runId, String error);

    /**
     * Find a run by its primary key.
     *
     * @param runId the run id
     * @return the entity if found, empty otherwise
     */
    Optional<OptimizationRunEntity> findById(UUID runId);

    /**
     * Delete optimization run records older than 90 days (Req 18.5).
     * Intended to be called by a scheduled job.
     *
     * @return number of records deleted
     */
    int cleanupOlderThan90Days();

    /**
     * Aggregate counts for a finalized optimization run (Req 18.2).
     */
    record RunCounts(
            int campaignsProcessed,
            int campaignsSkipped,
            int operationsCreated,
            int decisionsGenerated,
            int decisionsAutoExecuted,
            int decisionsRequiringApproval
    ) {}
}
