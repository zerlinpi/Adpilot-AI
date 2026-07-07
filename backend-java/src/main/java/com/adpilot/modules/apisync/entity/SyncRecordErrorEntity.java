package com.adpilot.modules.apisync.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-record data-quality error captured during mapping/validation. A record
 * that fails validation is excluded from upsert and produces exactly one entry
 * here, while processing of remaining records continues (Req 1.4).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("sync_record_errors")
@Table(name = "sync_record_errors")
public class SyncRecordErrorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "job_id", nullable = false, columnDefinition = "char(36)")
    private UUID jobId;

    @Column(name = "external_entity_id", length = 255)
    private String externalEntityId;

    @Column(name = "field", length = 100)
    private String field;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
