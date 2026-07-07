package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity for the {@code report_sync_runs} table (Req 2.7).
 *
 * <p>Each row records a single report sync execution, including the date range
 * covered, the report type, completion status, and data-status classification.
 * The {@link DataQualityGate} uses these rows to judge finalized coverage
 * completeness (Req 3.2).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("report_sync_runs")
@Table(name = "report_sync_runs")
public class ReportSyncRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "report_type", nullable = false, length = 40)
    private String reportType;

    @Column(name = "requested_date_start", nullable = false)
    private LocalDate requestedDateStart;

    @Column(name = "requested_date_end", nullable = false)
    private LocalDate requestedDateEnd;

    @Column(name = "report_status", nullable = false, length = 20)
    private String reportStatus;

    @Column(name = "data_status", nullable = false, length = 12)
    private String dataStatus;

    @Column(name = "row_count", nullable = false)
    @Builder.Default
    private Integer rowCount = 0;

    @Column(name = "amazon_report_id", length = 120)
    private String amazonReportId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error", length = 1000)
    private String error;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
