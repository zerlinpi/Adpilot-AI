package com.adpilot.modules.maintenance;

import com.adpilot.modules.advertising.hosting.NotificationDeliveryLogMapper;
import com.adpilot.modules.advertising.hosting.ReportSyncErrorMapper;
import com.adpilot.modules.audit.mapper.AiModelCallLogMapper;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.adpilot.modules.feishu.mapper.FeishuMessageLogMapper;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetentionCleanupJob} (reliability fix M4).
 *
 * <p>Verifies that: aged rows are deleted using the correct age predicate (cutoff
 * = now − retentionDays), deletes loop in bounded batches until the backlog is
 * drained, a retention of {@code <= 0} skips the table entirely, and one table's
 * failure never aborts the others.</p>
 */
@DisplayName("RetentionCleanupJob")
class RetentionCleanupJobTest {

    private static final int BATCH_SIZE = 1000;
    private static final int OPERATIONAL_DAYS = 90;
    private static final int AUDIT_DAYS = 365;

    private ReportSyncErrorMapper reportSyncErrorMapper;
    private NotificationDeliveryLogMapper notificationDeliveryLogMapper;
    private AiModelCallLogMapper aiModelCallLogMapper;
    private FeishuMessageLogMapper feishuMessageLogMapper;
    private AuditLogMapper auditLogMapper;
    private LoginLogMapper loginLogMapper;
    private RetentionCleanupJob job;

    @BeforeEach
    void setUp() {
        reportSyncErrorMapper = mock(ReportSyncErrorMapper.class);
        notificationDeliveryLogMapper = mock(NotificationDeliveryLogMapper.class);
        aiModelCallLogMapper = mock(AiModelCallLogMapper.class);
        feishuMessageLogMapper = mock(FeishuMessageLogMapper.class);
        auditLogMapper = mock(AuditLogMapper.class);
        loginLogMapper = mock(LoginLogMapper.class);
        job = new RetentionCleanupJob(reportSyncErrorMapper, notificationDeliveryLogMapper,
                aiModelCallLogMapper, feishuMessageLogMapper, auditLogMapper, loginLogMapper);
        ReflectionTestUtils.setField(job, "operationalLogDays", OPERATIONAL_DAYS);
        ReflectionTestUtils.setField(job, "auditLogDays", AUDIT_DAYS);
        ReflectionTestUtils.setField(job, "batchSize", BATCH_SIZE);
    }

    @Nested
    @DisplayName("Age predicate")
    class AgePredicate {

        @Test
        @DisplayName("operational tables use cutoff = now - operationalLogDays")
        void operationalCutoff() {
            when(reportSyncErrorMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);

            LocalDateTime before = LocalDateTime.now().minusDays(OPERATIONAL_DAYS);
            job.scheduledCleanup();
            LocalDateTime after = LocalDateTime.now().minusDays(OPERATIONAL_DAYS);

            ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(reportSyncErrorMapper).deleteOlderThan(cutoff.capture(), eq(BATCH_SIZE));

            // Rows older than the cutoff are deleted; newer rows (>= cutoff) are retained.
            assertThat(cutoff.getValue()).isBetween(before, after);
        }

        @Test
        @DisplayName("audit tables use the longer cutoff = now - auditLogDays")
        void auditCutoff() {
            when(auditLogMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);
            when(loginLogMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);

            LocalDateTime before = LocalDateTime.now().minusDays(AUDIT_DAYS);
            job.scheduledCleanup();
            LocalDateTime after = LocalDateTime.now().minusDays(AUDIT_DAYS);

            ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(auditLogMapper).deleteOlderThan(cutoff.capture(), eq(BATCH_SIZE));
            assertThat(cutoff.getValue()).isBetween(before, after);

            verify(loginLogMapper).deleteOlderThan(any(), eq(BATCH_SIZE));
        }
    }

    @Nested
    @DisplayName("Batched deletes")
    class BatchedDeletes {

        @Test
        @DisplayName("loops until fewer than batchSize rows are deleted")
        void loopsUntilDrained() {
            // Two full batches then a partial batch (< batchSize) ends the loop.
            when(reportSyncErrorMapper.deleteOlderThan(any(), eq(BATCH_SIZE)))
                    .thenReturn(BATCH_SIZE, BATCH_SIZE, 42);

            int deleted = job.cleanupTable("report_sync_errors", OPERATIONAL_DAYS, BATCH_SIZE,
                    reportSyncErrorMapper::deleteOlderThan);

            assertThat(deleted).isEqualTo(BATCH_SIZE + BATCH_SIZE + 42);
            verify(reportSyncErrorMapper, times(3)).deleteOlderThan(any(), eq(BATCH_SIZE));
        }

        @Test
        @DisplayName("a single partial batch stops the loop immediately")
        void singlePartialBatch() {
            when(reportSyncErrorMapper.deleteOlderThan(any(), eq(BATCH_SIZE))).thenReturn(5);

            int deleted = job.cleanupTable("report_sync_errors", OPERATIONAL_DAYS, BATCH_SIZE,
                    reportSyncErrorMapper::deleteOlderThan);

            assertThat(deleted).isEqualTo(5);
            verify(reportSyncErrorMapper, times(1)).deleteOlderThan(any(), eq(BATCH_SIZE));
        }
    }

    @Nested
    @DisplayName("Disable behavior (retention <= 0)")
    class DisableBehavior {

        @Test
        @DisplayName("retention of 0 skips the table (never deletes)")
        void zeroRetentionSkips() {
            int deleted = job.cleanupTable("audit_logs", 0, BATCH_SIZE,
                    auditLogMapper::deleteOlderThan);

            assertThat(deleted).isZero();
            verify(auditLogMapper, never()).deleteOlderThan(any(), anyInt());
        }

        @Test
        @DisplayName("negative retention skips the table (never deletes)")
        void negativeRetentionSkips() {
            int deleted = job.cleanupTable("login_logs", -1, BATCH_SIZE,
                    loginLogMapper::deleteOlderThan);

            assertThat(deleted).isZero();
            verify(loginLogMapper, never()).deleteOlderThan(any(), anyInt());
        }

        @Test
        @DisplayName("scheduled sweep skips all tables when both retentions are disabled")
        void scheduledSweepSkipsAllWhenDisabled() {
            ReflectionTestUtils.setField(job, "operationalLogDays", 0);
            ReflectionTestUtils.setField(job, "auditLogDays", -5);

            job.scheduledCleanup();

            verify(reportSyncErrorMapper, never()).deleteOlderThan(any(), anyInt());
            verify(notificationDeliveryLogMapper, never()).deleteOlderThan(any(), anyInt());
            verify(aiModelCallLogMapper, never()).deleteOlderThan(any(), anyInt());
            verify(feishuMessageLogMapper, never()).deleteOlderThan(any(), anyInt());
            verify(auditLogMapper, never()).deleteOlderThan(any(), anyInt());
            verify(loginLogMapper, never()).deleteOlderThan(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("Fault isolation")
    class FaultIsolation {

        @Test
        @DisplayName("one table failing does not abort the others")
        void perTableFaultIsolation() {
            when(reportSyncErrorMapper.deleteOlderThan(any(), anyInt()))
                    .thenThrow(new RuntimeException("db error"));
            when(notificationDeliveryLogMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);
            when(aiModelCallLogMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);
            when(feishuMessageLogMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);
            when(auditLogMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);
            when(loginLogMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);

            job.scheduledCleanup();

            // Even though report_sync_errors threw, every other table was still swept.
            verify(notificationDeliveryLogMapper).deleteOlderThan(any(), anyInt());
            verify(aiModelCallLogMapper).deleteOlderThan(any(), anyInt());
            verify(feishuMessageLogMapper).deleteOlderThan(any(), anyInt());
            verify(auditLogMapper).deleteOlderThan(any(), anyInt());
            verify(loginLogMapper).deleteOlderThan(any(), anyInt());
        }
    }

    private static LocalDateTime any() {
        return org.mockito.ArgumentMatchers.any(LocalDateTime.class);
    }
}
