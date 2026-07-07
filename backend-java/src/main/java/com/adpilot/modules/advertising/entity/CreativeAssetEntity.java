package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A reusable creative asset (创意素材) — an image or video stored for reuse in
 * ads, scoped to a store (Req 29). {@code assetType} maps to the SparkX creative
 * categories (lifestyle 生活方式图 / scene 场景图 / hd_group 高清图组 /
 * marketing 营销宣传图); {@code mediaKind} is {@code image} or {@code video}.
 *
 * <p>Backed by the additive {@code creative_assets} table created in
 * {@code V4__keyword_rank_creative.sql}. Conventions mirror the other
 * advertising entities: {@code CHAR(36)} ids and JPA + MyBatis-Plus annotations.
 * The {@code storageUrl} is produced by {@code FileStorageUtils} on upload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("creative_assets")
@Table(name = "creative_assets")
public class CreativeAssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** Human-readable asset name (素材名称). */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Creative category: {@code lifestyle|scene|hd_group|marketing}. */
    @Column(name = "asset_type", nullable = false, length = 40)
    private String assetType;

    /** Media kind: {@code image} or {@code video}. */
    @Column(name = "media_kind", nullable = false, length = 20)
    private String mediaKind;

    /** Stored location produced by {@code FileStorageUtils}. */
    @Column(name = "storage_url", nullable = false, length = 1024)
    private String storageUrl;

    /** Optional ASIN the asset is associated with. */
    @Column(name = "asin", length = 20)
    private String asin;

    /** Free-form tags (标签) for searching/grouping. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "json")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** The user who uploaded the asset (创建人). */
    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
