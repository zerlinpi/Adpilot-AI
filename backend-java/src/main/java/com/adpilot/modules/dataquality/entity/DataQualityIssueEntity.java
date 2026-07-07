package com.adpilot.modules.dataquality.entity;

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
@TableName("data_quality_issues")
@Table(name = "data_quality_issues")
public class DataQualityIssueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "import_job_id", columnDefinition = "char(36)")
    private UUID importJobId;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "issue_type", nullable = false, length = 100)
    private String issueType;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "related_entity_type", length = 50)
    private String relatedEntityType;

    @Column(name = "related_entity_id", columnDefinition = "char(36)")
    private UUID relatedEntityId;

    @Column(columnDefinition = "json")
    private String rawData;

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
