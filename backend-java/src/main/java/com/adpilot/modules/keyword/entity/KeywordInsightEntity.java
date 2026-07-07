package com.adpilot.modules.keyword.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("keyword_insights")
@Table(name = "keyword_insights")
public class KeywordInsightEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "product_id", columnDefinition = "char(36)")
    private UUID productId;

    @Column(name = "campaign_id", columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "keyword_id", columnDefinition = "char(36)")
    private UUID keywordId;

    @Column(name = "search_term_id", columnDefinition = "char(36)")
    private UUID searchTermId;

    @Column(name = "text", length = 500)
    private String text;

    @Column(name = "source", length = 50)
    private String source;

    @Column(name = "segment", length = 50)
    private String segment;

    @Column(name = "health_score")
    private Integer healthScore;

    @Column(name = "opportunity_score")
    private Integer opportunityScore;

    @Column(name = "waste_score")
    private Integer wasteScore;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    @Column(name = "recommended_action", length = 100)
    private String recommendedAction;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "current_data", columnDefinition = "json")
    private String currentData;

    @Column(name = "expected_impact", length = 500)
    private String expectedImpact;

    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "open";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
