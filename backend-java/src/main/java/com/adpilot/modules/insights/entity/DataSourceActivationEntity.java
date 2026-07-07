package com.adpilot.modules.insights.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-store activation flag for an external data source (item 8). When a source
 * is activated the Data Insights brand / SQP / AMC surfaces compute from
 * available stored data (or show an empty state) instead of the
 * "requires activation" gate. Activation only unlocks the surfaces to use
 * stored data; it never fabricates Amazon-side data.
 *
 * <p>Backed by the additive {@code data_source_activation} table. One row per
 * (store, source).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("data_source_activation")
@Table(name = "data_source_activation")
public class DataSourceActivationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** Source key: brand_analytics | amc. */
    @Column(name = "source", nullable = false, length = 40)
    private String source;

    @Column(name = "activated")
    @Builder.Default
    private Boolean activated = false;

    @Column(name = "activated_by", columnDefinition = "char(36)")
    private UUID activatedBy;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
