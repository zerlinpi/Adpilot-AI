package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("bid_changes")
@Table(name = "bid_changes")
public class BidChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "keyword_id", columnDefinition = "char(36)")
    private UUID keywordId;

    @Column(name = "target_id", columnDefinition = "char(36)")
    private UUID targetId;

    @Column(name = "campaign_id", columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "entity_type", length = 30)
    private String entityType;

    @Column(name = "old_bid", precision = 10, scale = 4)
    private BigDecimal oldBid;

    @Column(name = "new_bid", precision = 10, scale = 4)
    private BigDecimal newBid;

    @Column(name = "change_reason", length = 100)
    private String changeReason;

    @Column(name = "changed_by", columnDefinition = "char(36)")
    private UUID changedBy;

    @Column(name = "is_automated")
    @Builder.Default
    private Boolean isAutomated = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
