package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Keyword rank-monitoring task (排名监控任务) scoped to a store and optionally
 * tied to a product (Req 28.1). The consumed monitoring quota is the count of
 * {@code active} tasks for the store; quota enforcement happens in the service
 * layer via {@code RankQuota} (Req 28.4).
 *
 * <p>Backed by the additive {@code rank_monitor_tasks} table created in
 * {@code V4__keyword_rank_creative.sql}. Conventions mirror the other
 * advertising entities: {@code CHAR(36)} ids and JPA + MyBatis-Plus annotations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("rank_monitor_tasks")
@Table(name = "rank_monitor_tasks")
public class RankMonitorTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** Optional product the monitored keyword belongs to. */
    @Column(name = "product_id", columnDefinition = "char(36)")
    private UUID productId;

    /** The monitored keyword text (关键词). */
    @Column(name = "keyword_text", nullable = false, length = 255)
    private String keywordText;

    /** Task status: {@code active} (consumes quota) or {@code paused}. */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "active";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
