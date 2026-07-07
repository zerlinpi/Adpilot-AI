package com.adpilot.modules.listing.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@TableName("listing_contents")
@Table(name = "listing_contents")
public class ListingContentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;
    @Column(name = "product_id", columnDefinition = "char(36)") private UUID productId;
    @Column(name = "marketplace_id", columnDefinition = "char(36)") private UUID marketplaceId;
    private String title;
    @Column(name = "bullet_points", columnDefinition = "json") private String bulletPoints;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "backend_search_terms", columnDefinition = "text") private String backendSearchTerms;
    @Column(name = "product_highlights", columnDefinition = "text") private String productHighlights;
    @Column(name = "attributes", columnDefinition = "json") private String attributes;
    @Column(name = "subject_matter") private String subjectMatter;
    @Column(name = "intended_use") private String intendedUse;
    @Column(name = "target_audience") private String targetAudience;
    private String status;
    @Column(name = "listing_score") private Integer listingScore;
    @Column(name = "compliance_score") private Integer complianceScore;
    @Column(name = "seo_score") private Integer seoScore;
    @Column(name = "conversion_score") private Integer conversionScore;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
}
