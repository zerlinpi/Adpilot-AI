package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity mapping to the {@code optimization_runs} table (Req 18.1).
 *
 * <p>Records the lifecycle of each optimization execution — from start to
 * completion or failure — including per-campaign results, skip reasons,
 * and aggregate counts. Retained for 90 days for audit purposes (Req 18.5).
 *
 * <p>Validates: Requirements 18.1, 18.2, 18.3, 18.4, 18.5.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("optimization_runs")
@Table(name = "optimization_runs")
public class OptimizationRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** Trigger type: 'scheduled' or 'manual'. */
    @Column(name = "trigger_type", nullable = false, length = 12)
    private String triggerType;

    /** Current run status: 'running', 'completed', or 'failed'. */
    @Column(name = "status", nullable = false, length = 12)
    private String status;

    /** The hosting phase active at run time (V1/V2/V3). */
    @Column(name = "phase", length = 4)
    private String phase;

    /** Reference to the immutable data snapshot used for this run. */
    @Column(name = "snapshot_ref", columnDefinition = "char(36)")
    private UUID snapshotRef;

    /** Number of campaigns evaluated during this run. */
    @Column(name = "campaigns_processed")
    @Builder.Default
    private Integer campaignsProcessed = 0;

    /** Number of campaigns skipped during this run. */
    @Column(name = "campaigns_skipped")
    @Builder.Default
    private Integer campaignsSkipped = 0;

    /** Number of operations created during this run. */
    @Column(name = "operations_created")
    @Builder.Default
    private Integer operationsCreated = 0;

    /** Number of AI decisions generated during this run. */
    @Column(name = "decisions_generated")
    @Builder.Default
    private Integer decisionsGenerated = 0;

    /** Number of decisions auto-executed during this run. */
    @Column(name = "decisions_auto_executed")
    @Builder.Default
    private Integer decisionsAutoExecuted = 0;

    /** Number of decisions requiring human approval. */
    @Column(name = "decisions_requiring_approval")
    @Builder.Default
    private Integer decisionsRequiringApproval = 0;

    /** Per-campaign results as JSON (campaign_id → result detail). */
    @Column(name = "per_campaign_results", columnDefinition = "json")
    private String perCampaignResults;

    /** Skip reasons as JSON (campaign_id → skip reason code). */
    @Column(name = "skip_reasons", columnDefinition = "json")
    private String skipReasons;

    /** When the run was started. */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** When the run was completed or failed. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Row creation timestamp. */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
