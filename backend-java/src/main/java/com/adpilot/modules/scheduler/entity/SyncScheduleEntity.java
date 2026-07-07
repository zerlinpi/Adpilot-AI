package com.adpilot.modules.scheduler.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Maps the dormant {@code sync_schedules} table that drives the scheduling engine
 * (Capability Area 4). A schedule binds a platform connection and a job type
 * (the entity type to sync) to a recurrence expression, tracking when it last ran
 * and when it is next due.
 *
 * <p>Timestamps are stored as UTC {@link LocalDateTime} so next-run computation is
 * deterministic and consistent with {@link com.adpilot.modules.scheduler.service.CronEvaluator},
 * which interprets cron fields in UTC.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("sync_schedules")
@Table(name = "sync_schedules")
public class SyncScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "connection_id", nullable = false, columnDefinition = "char(36)")
    private UUID connectionId;

    /** The entity type to sync (e.g. {@code "order"}, {@code "product"}). */
    @Column(name = "job_type", nullable = false, length = 100)
    private String jobType;

    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
