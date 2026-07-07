package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity mapping to the {@code metric_quarantine} table (Req 14.6).
 * Holds report rows whose external entity IDs cannot be resolved to
 * internal UUIDs via {@code external_entity_mappings}. These rows are
 * quarantined rather than fabricating incomplete entities.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("metric_quarantine")
@Table(name = "metric_quarantine")
public class MetricQuarantineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "report_type", nullable = false, length = 40)
    private String reportType;

    @Column(name = "external_entity_type", length = 50)
    private String externalEntityType;

    @Column(name = "external_entity_id", length = 255)
    private String externalEntityId;

    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "raw_row", columnDefinition = "json")
    private String rawRow;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "resolved", nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
