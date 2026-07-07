package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.feishu.service.FeishuService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NotificationDigestWorker}.
 *
 * Validates: Requirements 9.5, 9.7, 30.4
 */
@DisplayName("NotificationDigestWorker")
class NotificationDigestWorkerTest {

    private NotificationDeliveryLogMapper deliveryLogMapper;
    private FeishuService feishuService;
    private ObjectMapper objectMapper;
    private NotificationDigestWorker worker;

    @BeforeEach
    void setUp() {
        deliveryLogMapper = mock(NotificationDeliveryLogMapper.class);
        feishuService = mock(FeishuService.class);
        objectMapper = new ObjectMapper();
        worker = new NotificationDigestWorker(deliveryLogMapper, feishuService, objectMapper);
    }

    @Nested
    @DisplayName("Digest processing")
    class DigestProcessing {

        @Test
        @DisplayName("No queued notifications results in no Feishu calls")
        void emptyQueueNoOp() {
            when(deliveryLogMapper.findAllQueued()).thenReturn(Collections.emptyList());

            worker.processDigest();

            verify(feishuService, never()).pushAiNotification(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("Queued notifications for a store are batched into one Feishu message")
        void batchesPerStore() {
            UUID storeId = UUID.randomUUID();
            List<NotificationDeliveryLogEntity> queued = List.of(
                    buildQueuedLog(storeId, "approval_needed",
                            "{\"campaignName\":\"Campaign A\",\"proposedChange\":\"Bid decrease\"}"),
                    buildQueuedLog(storeId, "effective_confirmed",
                            "{\"campaignName\":\"Campaign B\",\"changeDescription\":\"Budget increased\"}")
            );
            when(deliveryLogMapper.findAllQueued()).thenReturn(queued);
            when(feishuService.pushAiNotification(eq(storeId), anyString(), anyString()))
                    .thenReturn(true);

            worker.processDigest();

            // Only one Feishu call for the store (batched)
            verify(feishuService, times(1)).pushAiNotification(eq(storeId), anyString(), anyString());
            // Both entries marked delivered
            verify(deliveryLogMapper, times(2)).updateDeliveryStatus(anyString(), eq("delivered"),
                    anyInt(), isNull());
        }

        @Test
        @DisplayName("Multiple stores get separate digest messages")
        void separateDigestsPerStore() {
            UUID storeA = UUID.randomUUID();
            UUID storeB = UUID.randomUUID();
            List<NotificationDeliveryLogEntity> queued = List.of(
                    buildQueuedLog(storeA, "approval_needed",
                            "{\"campaignName\":\"A1\",\"proposedChange\":\"Change A\"}"),
                    buildQueuedLog(storeB, "failed",
                            "{\"campaignName\":\"B1\",\"failureReason\":\"Timeout\"}")
            );
            when(deliveryLogMapper.findAllQueued()).thenReturn(queued);
            when(feishuService.pushAiNotification(any(), anyString(), anyString()))
                    .thenReturn(true);

            worker.processDigest();

            verify(feishuService).pushAiNotification(eq(storeA), anyString(), anyString());
            verify(feishuService).pushAiNotification(eq(storeB), anyString(), anyString());
        }

        @Test
        @DisplayName("Digest title contains notification count")
        void digestTitleHasCount() {
            UUID storeId = UUID.randomUUID();
            List<NotificationDeliveryLogEntity> queued = List.of(
                    buildQueuedLog(storeId, "approval_needed",
                            "{\"campaignName\":\"C1\",\"proposedChange\":\"X\"}"),
                    buildQueuedLog(storeId, "effective_confirmed",
                            "{\"campaignName\":\"C2\",\"changeDescription\":\"Y\"}"),
                    buildQueuedLog(storeId, "failed",
                            "{\"campaignName\":\"C3\",\"failureReason\":\"Z\"}")
            );
            when(deliveryLogMapper.findAllQueued()).thenReturn(queued);
            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            when(feishuService.pushAiNotification(eq(storeId), titleCaptor.capture(), anyString()))
                    .thenReturn(true);

            worker.processDigest();

            assertThat(titleCaptor.getValue()).contains("3");
        }
    }

    @Nested
    @DisplayName("Digest content formatting")
    class ContentFormatting {

        @Test
        @DisplayName("Digest content groups notifications by type")
        void contentGroupsByType() {
            UUID storeId = UUID.randomUUID();
            List<NotificationDeliveryLogEntity> notifications = List.of(
                    buildQueuedLog(storeId, "approval_needed",
                            "{\"campaignName\":\"CampA\",\"proposedChange\":\"Bid change\"}"),
                    buildQueuedLog(storeId, "approval_needed",
                            "{\"campaignName\":\"CampB\",\"proposedChange\":\"Budget change\"}"),
                    buildQueuedLog(storeId, "effective_confirmed",
                            "{\"campaignName\":\"CampC\",\"changeDescription\":\"Bid updated\"}")
            );

            String content = worker.buildDigestContent(notifications);

            assertThat(content).contains("需要审批");
            assertThat(content).contains("(2)");
            assertThat(content).contains("已生效");
            assertThat(content).contains("(1)");
            assertThat(content).contains("CampA");
            assertThat(content).contains("CampB");
            assertThat(content).contains("CampC");
        }
    }

    @Nested
    @DisplayName("Retry on delivery failure (Req 9.7)")
    class RetryBehavior {

        @Test
        @DisplayName("On delivery failure, notifications remain queued for next cycle")
        void failureKeepsQueued() {
            UUID storeId = UUID.randomUUID();
            NotificationDigestWorker spyWorker = spy(worker);
            doNothing().when(spyWorker).sleep(anyLong());

            List<NotificationDeliveryLogEntity> queued = List.of(
                    buildQueuedLog(storeId, "approval_needed",
                            "{\"campaignName\":\"F1\",\"proposedChange\":\"X\"}")
            );
            when(deliveryLogMapper.findAllQueued()).thenReturn(queued);
            when(feishuService.pushAiNotification(eq(storeId), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Connection refused"));

            spyWorker.processDigest();

            // Should stay queued (not marked as failed)
            verify(deliveryLogMapper).updateDeliveryStatus(anyString(), eq("queued"),
                    anyInt(), contains("Digest delivery failed"));
        }

        @Test
        @DisplayName("Delivery retries up to 3 times per store digest")
        void retriesThreeTimes() {
            UUID storeId = UUID.randomUUID();
            NotificationDigestWorker spyWorker = spy(worker);
            doNothing().when(spyWorker).sleep(anyLong());

            List<NotificationDeliveryLogEntity> queued = List.of(
                    buildQueuedLog(storeId, "effective_confirmed",
                            "{\"campaignName\":\"R1\",\"changeDescription\":\"Done\"}")
            );
            when(deliveryLogMapper.findAllQueued()).thenReturn(queued);
            when(feishuService.pushAiNotification(eq(storeId), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Timeout"))
                    .thenThrow(new RuntimeException("Timeout"))
                    .thenThrow(new RuntimeException("Timeout"));

            spyWorker.processDigest();

            verify(feishuService, times(3)).pushAiNotification(eq(storeId), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Non-blocking (Req 30.4)")
    class NonBlocking {

        @Test
        @DisplayName("When no Feishu destination, notifications stay queued without error")
        void noDestinationGraceful() {
            UUID storeId = UUID.randomUUID();
            List<NotificationDeliveryLogEntity> queued = List.of(
                    buildQueuedLog(storeId, "effective_confirmed",
                            "{\"campaignName\":\"NB1\",\"changeDescription\":\"Test\"}")
            );
            when(deliveryLogMapper.findAllQueued()).thenReturn(queued);
            when(feishuService.pushAiNotification(eq(storeId), anyString(), anyString()))
                    .thenReturn(false); // No destination

            worker.processDigest();

            // Notification stays queued, no error
            verify(deliveryLogMapper).updateDeliveryStatus(anyString(), eq("queued"),
                    anyInt(), contains("Digest delivery failed"));
        }
    }

    @Nested
    @DisplayName("Per-store fault isolation (M2/M3 hardening)")
    class FaultIsolation {

        @Test
        @DisplayName("One store's digest failure does not abort the remaining stores")
        void oneStoreFailureDoesNotAbortOthers() {
            UUID storeA = UUID.randomUUID();
            UUID storeB = UUID.randomUUID();
            NotificationDeliveryLogEntity a = buildQueuedLog(storeA, "approval_needed",
                    "{\"campaignName\":\"A1\",\"proposedChange\":\"X\"}");
            NotificationDeliveryLogEntity b = buildQueuedLog(storeB, "effective_confirmed",
                    "{\"campaignName\":\"B1\",\"changeDescription\":\"Y\"}");
            // Store A is encountered first (grouping preserves list order).
            when(deliveryLogMapper.findAllQueued()).thenReturn(List.of(a, b));
            when(feishuService.pushAiNotification(any(), anyString(), anyString())).thenReturn(true);

            // Store A's status write blows up mid-digest — this must not stop store B.
            doThrow(new RuntimeException("delivery-log write failed"))
                    .when(deliveryLogMapper)
                    .updateDeliveryStatus(eq(a.getId().toString()), eq("delivered"), anyInt(), isNull());

            worker.processDigest();

            // Store B was still delivered despite store A failing (isolation holds).
            verify(feishuService).pushAiNotification(eq(storeB), anyString(), anyString());
            verify(deliveryLogMapper).updateDeliveryStatus(
                    eq(b.getId().toString()), eq("delivered"), anyInt(), isNull());
            // Store A's failure was recorded as failed (best-effort).
            verify(deliveryLogMapper).updateDeliveryStatus(
                    eq(a.getId().toString()), eq("failed"), anyInt(), anyString());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private NotificationDeliveryLogEntity buildQueuedLog(UUID storeId, String type, String payload) {
        return NotificationDeliveryLogEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .notificationType(type)
                .status("queued")
                .attemptCount(0)
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
