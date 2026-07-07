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
 * Point-in-time capture of a monitored keyword's organic rank (自然排名) and ad
 * rank (广告排名) for a {@link RankMonitorTaskEntity} (Req 28.2). The most recent
 * snapshot per task supplies the ranks rendered on the Rank Monitoring page.
 *
 * <p>Backed by the additive {@code rank_monitor_snapshots} table created in
 * {@code V4__keyword_rank_creative.sql}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("rank_monitor_snapshots")
@Table(name = "rank_monitor_snapshots")
public class RankMonitorSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "task_id", nullable = false, columnDefinition = "char(36)")
    private UUID taskId;

    /** Organic search rank (自然排名); {@code null} until first captured. */
    @Column(name = "organic_rank")
    private Integer organicRank;

    /** Sponsored/ad rank (广告排名); {@code null} until first captured. */
    @Column(name = "ad_rank")
    private Integer adRank;

    @CreationTimestamp
    @Column(name = "captured_at", updatable = false)
    private LocalDateTime capturedAt;
}
