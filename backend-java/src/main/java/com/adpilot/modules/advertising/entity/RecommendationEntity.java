package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("recommendations")
@Table(name = "recommendations")
public class RecommendationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "campaign_id", columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "keyword_id", columnDefinition = "char(36)")
    private UUID keywordId;

    @Column(name = "target_id", columnDefinition = "char(36)")
    private UUID targetId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "priority", length = 10)
    @Builder.Default
    private String priority = "medium";

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "expected_impact", columnDefinition = "text")
    private String expectedImpact;

    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    @Column(name = "target_entity_type", length = 50)
    private String targetEntityType;

    @Column(name = "target_entity_name", length = 500)
    private String targetEntityName;

    @Column(name = "current_data", columnDefinition = "json")
    private String currentData;

    @Column(name = "current_value", length = 255)
    private String currentValue;

    @Column(name = "recommended_value", length = 255)
    private String recommendedValue;

    @Column(name = "estimated_impact", precision = 10, scale = 2)
    private BigDecimal estimatedImpact;

    @Column(name = "confidence", precision = 5, scale = 2)
    private BigDecimal confidence;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "pending";

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @Column(name = "applied_by", columnDefinition = "char(36)")
    private UUID appliedBy;

    @Column(name = "metadata", columnDefinition = "json")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
