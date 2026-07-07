package com.adpilot.modules.review.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("listing_templates")
@Table(name = "listing_templates")
public class ListingTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "template_name", nullable = false, length = 255)
    private String templateName;

    @Column(name = "marketplace_id", length = 50)
    private String marketplaceId;

    @Column(name = "category", length = 255)
    private String category;

    @Column(name = "title_template", columnDefinition = "TEXT")
    private String titleTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bullet_templates", columnDefinition = "json")
    @Builder.Default
    private String bulletTemplates = "[]";

    @Column(name = "description_template", columnDefinition = "TEXT")
    private String descriptionTemplate;

    @Column(name = "search_terms", columnDefinition = "TEXT")
    private String searchTerms;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keywords", columnDefinition = "json")
    @Builder.Default
    private String keywords = "[]";

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "active";

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
