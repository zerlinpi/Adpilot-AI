package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity for the {@code report_sync_errors} table (Req 2.7).
 *
 * <p>Each row records a single failed attempt of a report sync run, including
 * the attempt number and the error message. Used for retry tracking with
 * exponential backoff (up to 3 attempts per run).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("report_sync_errors")
@Table(name = "report_sync_errors")
public class ReportSyncErrorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "run_id", nullable = false, columnDefinition = "char(36)")
    private UUID runId;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "attempt", nullable = false)
    @Builder.Default
    private Integer attempt = 1;

    @Column(name = "error", length = 1000)
    private String error;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
