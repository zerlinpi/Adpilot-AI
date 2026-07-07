package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity for the {@code campaign_learning_periods} table (Req 19).
 *
 * <p>Tracks the learning period start date and the personality active at
 * the start for each hosted campaign. The unique constraint on campaign_id
 * means there is at most one active learning period record per campaign;
 * restarting replaces the existing row.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("campaign_learning_periods")
@Table(name = "campaign_learning_periods")
public class CampaignLearningPeriodEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** The date the learning period started. */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** The personality active when the learning period started (used for change detection). */
    @Column(name = "personality_at_start", nullable = false, length = 20)
    private String personalityAtStart;

    /** The configured duration of the learning period (from safety boundary). */
    @Column(name = "learning_period_days", nullable = false)
    @Builder.Default
    private Integer learningPeriodDays = 3;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
