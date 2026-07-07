package com.adpilot.modules.listing.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@TableName("listing_drafts")
@Table(name = "listing_drafts")
public class ListingDraftEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(name = "product_id", columnDefinition = "char(36)") private UUID productId;
    @Column(name = "marketplace_id", columnDefinition = "char(36)") private UUID marketplaceId;
    @Column(name = "source_type") private String sourceType;
    @Column(columnDefinition = "json") private String content;
    @Column(name = "validation_result", columnDefinition = "json") private String validationResult;
    private String status;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
}
