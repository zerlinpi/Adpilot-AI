package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-keyword enforcement task generated for an {@link AdPlacementLockStrategyEntity}
 * (Req 26.2, 26.3). The scheduled evaluator records the last bid it applied to the
 * keyword and when it last ran.
 *
 * <p>Backed by the additive {@code ad_placement_lock_tasks} table created in
 * {@code V3__ai_workitems.sql}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("ad_placement_lock_tasks")
@Table(name = "ad_placement_lock_tasks")
public class AdPlacementLockTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "strategy_id", nullable = false, columnDefinition = "char(36)")
    private UUID strategyId;

    @Column(name = "keyword_id", columnDefinition = "char(36)")
    private UUID keywordId;

    /** The most recent bid the evaluator applied to the keyword (Req 26.3). */
    @Column(name = "last_bid", precision = 18, scale = 4)
    private BigDecimal lastBid;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
