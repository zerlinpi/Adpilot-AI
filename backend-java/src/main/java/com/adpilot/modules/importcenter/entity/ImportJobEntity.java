package com.adpilot.modules.importcenter.entity;

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
@TableName("import_jobs")
@Table(name = "import_jobs")
public class ImportJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "marketplace_id", columnDefinition = "char(36)")
    private UUID marketplaceId;

    @Column(name = "report_type", nullable = false, length = 50)
    private String reportType;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "uploaded";

    @Column(name = "total_rows")
    @Builder.Default
    private Integer totalRows = 0;

    @Column(name = "valid_rows")
    @Builder.Default
    private Integer validRows = 0;

    @Column(name = "invalid_rows")
    @Builder.Default
    private Integer invalidRows = 0;

    @Column(name = "duplicate_rows")
    @Builder.Default
    private Integer duplicateRows = 0;

    @Column(name = "mapping_config", columnDefinition = "json")
    private String mappingConfig;

    @Column(columnDefinition = "json")
    private String summary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
