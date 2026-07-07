package com.adpilot.modules.apisync.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import com.adpilot.modules.apisync.model.ConnectionStatus;

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
@TableName("platform_connections")
@Table(name = "platform_connections")
public class PlatformConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "platform", nullable = false, length = 50)
    private String platform;

    /**
     * Seller-account identifier for credentials that span multiple marketplaces
     * or stores (e.g. an Amazon seller account). Nullable for single-store
     * platforms. See V5__amazon_sync.sql (Req 8.1.1).
     */
    @Column(name = "seller_account_id", length = 255)
    private String sellerAccountId;

    /**
     * Amazon region key for an Ads OAuth connection ({@code na}/{@code eu}/
     * {@code fe}). Selects the correct LWA token endpoint and Ads API host.
     * See V13 (Amazon Ads OAuth wizard).
     */
    @Column(name = "region", length = 10)
    private String region;

    /** Selected Amazon Advertising profile id bound to this store (V13). */
    @Column(name = "profile_id", length = 64)
    private String profileId;

    /** Marketplace id of the bound advertising profile (V13). */
    @Column(name = "marketplace_id", length = 64)
    private String marketplaceId;

    /**
     * LWA refresh token encrypted at rest via {@code CryptoUtil} (AES-256-GCM).
     * Long-lived credential used to mint short-lived Amazon Ads access tokens;
     * never logged or returned to clients (V13).
     */
    @Column(name = "refresh_token_encrypted", columnDefinition = "TEXT")
    private String refreshTokenEncrypted;

    @Column(name = "connection_name", length = 255)
    private String connectionName;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = ConnectionStatus.DISCONNECTED;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @TableField("config")
    @Column(name = "config", columnDefinition = "TEXT")
    private String configEncrypted;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
