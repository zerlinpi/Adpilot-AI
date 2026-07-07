package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

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
@TableName("negative_keywords")
@Table(name = "negative_keywords")
public class NegativeKeywordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "campaign_id", columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "ad_group_id", columnDefinition = "char(36)")
    private UUID adGroupId;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "keyword_text", nullable = false, length = 500)
    private String keywordText;

    @Column(name = "match_type", length = 20)
    @Builder.Default
    private String matchType = "negativeExact";

    @Column(name = "level", length = 20)
    @Builder.Default
    private String level = "campaign";

    @Column(name = "source", length = 50)
    private String source;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "enabled";

    @Column(name = "external_id", length = 100)
    private String externalId;

    /** Optimistic-lock version (Req 5.4). */
    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by", columnDefinition = "char(36)")
    private UUID updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
