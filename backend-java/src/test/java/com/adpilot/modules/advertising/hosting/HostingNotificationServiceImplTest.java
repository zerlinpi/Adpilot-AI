package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.feishu.service.FeishuService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HostingNotificationServiceImpl}.
 *
 * Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 30.4
 */
@DisplayName("HostingNotificationServiceImpl")
class HostingNotificationServiceImplTest {

    private FeishuService feishuService;
    private NotificationDeliveryLogMapper deliveryLogMapper;
    private ObjectMapper objectMapper;
    private HostingNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        feishuService = mock(FeishuService.class);
        deliveryLogMapper = mock(NotificationDeliveryLogMapper.class);
        objectMapper = new ObjectMapper();
        service = new HostingNotificationServiceImpl(feishuService, deliveryLogMapper, objectMapper);
    }

    @Nested
    @DisplayName("Emergency notifications (Req 9.4)")
    class EmergencyNotifications {

        @Test
        @DisplayName("Emergency notifications are sent immediately via FeishuService")
        void emergencySentImmediately() {
            UUID storeId = UUID.randomUUID();
            when(feishuService.pushAiNotification(eq(storeId), anyString(), anyString()))
                    .thenReturn(true);

            service.notifyEmergency(storeId, "Campaign A", "daily_spend",
                    "Spend > 2x budget", "All AI operations halted");

            // Verify immediate FeishuService call
            verify(feishuService).pushAiNotification(eq(storeId), contains("紧急停止"), anyString());
            // Verify log entry is created and updated to delivered
            verify(deliveryLogMapper).insert(any(NotificationDeliveryLogEntity.class));
            verify(deliveryLogMapper).updateDeliveryStatus(anyString(), eq("delivered"), eq(1), isNull());
        }

        @Test
        @DisplayName("Emergency notification content includes all required fields")
        void emergencyContentComplete() {
            UUID storeId = UUID.randomUUID();
            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            when(feishuService.pushAiNotification(eq(storeId), anyString(), contentCaptor.capture()))
                    .thenReturn(true);

            service.notifyEmergency(storeId, "Campaign X", "ACoS",
                    "ACoS 85% > 3x target 25%", "Kill switch activated");

            String content = contentCaptor.getValue();
            assertThat(content).contains("Campaign X");
            assertThat(content).contains("ACoS");
            assertThat(content).contains("ACoS 85% > 3x target 25%");
            assertThat(content).contains("Kill switch activated");
        }
    }

    @Nested
    @DisplayName("Non-urgent notifications (Req 9.5)")
    class NonUrgentNotifications {

        @Test
        @DisplayName("Approval-needed notification is queued for digest")
        void approvalNeededQueued() {
            UUID storeId = UUID.randomUUID();

            service.notifyApprovalNeeded(storeId, "Campaign B", "bid_adjustment",
                    "Decrease bid from $1.50 to $1.35", new BigDecimal("0.65"),
                    "https://app.adpilot.com/approve/123");

            // Should NOT call FeishuService directly
            verify(feishuService, never()).pushAiNotification(any(), anyString(), anyString());
            // Should insert a queued log entry
            ArgumentCaptor<NotificationDeliveryLogEntity> captor =
                    ArgumentCaptor.forClass(NotificationDeliveryLogEntity.class);
            verify(deliveryLogMapper).insert(captor.capture());

            NotificationDeliveryLogEntity logged = captor.getValue();
            assertThat(logged.getStatus()).isEqualTo("queued");
            assertThat(logged.getNotificationType()).isEqualTo("approval_needed");
            assertThat(logged.getStoreId()).isEqualTo(storeId);
            assertThat(logged.getAttemptCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Effective-confirmed notification is queued for digest")
        void effectiveConfirmedQueued() {
            UUID storeId = UUID.randomUUID();

            service.notifyEffective(storeId, "Campaign C", "Bid changed from $1.50 to $1.35",
                    "Request ID: amzn1.req.123");

            verify(feishuService, never()).pushAiNotification(any(), anyString(), anyString());
            ArgumentCaptor<NotificationDeliveryLogEntity> captor =
                    ArgumentCaptor.forClass(NotificationDeliveryLogEntity.class);
            verify(deliveryLogMapper).insert(captor.capture());

            assertThat(captor.getValue().getNotificationType()).isEqualTo("effective_confirmed");
            assertThat(captor.getValue().getStatus()).isEqualTo("queued");
        }

        @Test
        @DisplayName("Failed notification is queued for digest")
        void failedQueued() {
            UUID storeId = UUID.randomUUID();

            service.notifyFailed(storeId, "Campaign D", "Budget increase to $50",
                    "INVALID_BID_VALUE", false);

            verify(feishuService, never()).pushAiNotification(any(), anyString(), anyString());
            ArgumentCaptor<NotificationDeliveryLogEntity> captor =
                    ArgumentCaptor.forClass(NotificationDeliveryLogEntity.class);
            verify(deliveryLogMapper).insert(captor.capture());

            assertThat(captor.getValue().getNotificationType()).isEqualTo("failed");
        }

        @Test
        @DisplayName("Data-gap notification is queued for digest")
        void dataGapQueued() {
            UUID storeId = UUID.randomUUID();

            service.notifyDataGap(storeId, 3, "SP_KEYWORD", "Missing data for 3 days");

            verify(feishuService, never()).pushAiNotification(any(), anyString(), anyString());
            ArgumentCaptor<NotificationDeliveryLogEntity> captor =
                    ArgumentCaptor.forClass(NotificationDeliveryLogEntity.class);
            verify(deliveryLogMapper).insert(captor.capture());

            assertThat(captor.getValue().getNotificationType()).isEqualTo("data_gap");
        }
    }

    @Nested
    @DisplayName("Retry with backoff (Req 9.7)")
    class RetryBehavior {

        @Test
        @DisplayName("Retries up to 3 times on delivery failure")
        void retriesOnFailure() {
            UUID storeId = UUID.randomUUID();
            // Use a spy to skip actual sleep
            HostingNotificationServiceImpl spyService = spy(service);
            doNothing().when(spyService).sleep(anyLong());

            when(feishuService.pushAiNotification(eq(storeId), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Connection timeout"))
                    .thenThrow(new RuntimeException("Connection timeout"))
                    .thenThrow(new RuntimeException("Connection timeout"));

            spyService.notifyEmergency(storeId, "Campaign E", "spend",
                    "Threshold breached", "Halted");

            // Should have attempted 3 times
            verify(feishuService, times(3)).pushAiNotification(eq(storeId), anyString(), anyString());
            // Should record final failure
            verify(deliveryLogMapper).updateDeliveryStatus(anyString(), eq("failed"), eq(3),
                    eq("Connection timeout"));
        }

        @Test
        @DisplayName("Succeeds on second attempt after first failure")
        void succeedsOnRetry() {
            UUID storeId = UUID.randomUUID();
            HostingNotificationServiceImpl spyService = spy(service);
            doNothing().when(spyService).sleep(anyLong());

            when(feishuService.pushAiNotification(eq(storeId), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Timeout"))
                    .thenReturn(true);

            spyService.notifyEmergency(storeId, "Campaign F", "acos",
                    "ACoS breached", "Emergency stop");

            verify(feishuService, times(2)).pushAiNotification(eq(storeId), anyString(), anyString());
            verify(deliveryLogMapper).updateDeliveryStatus(anyString(), eq("delivered"), eq(2), isNull());
        }

        @Test
        @DisplayName("Exponential backoff applied between retries")
        void exponentialBackoff() {
            UUID storeId = UUID.randomUUID();
            HostingNotificationServiceImpl spyService = spy(service);
            doNothing().when(spyService).sleep(anyLong());

            when(feishuService.pushAiNotification(eq(storeId), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Error"))
                    .thenThrow(new RuntimeException("Error"))
                    .thenReturn(true);

            spyService.notifyEmergency(storeId, "Campaign G", "spend",
                    "Breached", "Stopped");

            // Backoff is now exponential + bounded jitter (fix L2): base stays 1000ms
            // then 2000ms, and jitter (default 50%) may add up to 50% on top. Assert the
            // two sleeps fall within [base, base * 1.5] rather than exact values.
            ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
            verify(spyService, times(2)).sleep(captor.capture());

            assertThat(captor.getAllValues().get(0)).isBetween(1000L, 1500L);
            assertThat(captor.getAllValues().get(1)).isBetween(2000L, 3000L);
        }
    }

    @Nested
    @DisplayName("Non-blocking when Feishu unreachable (Req 30.4)")
    class NonBlockingBehavior {

        @Test
        @DisplayName("Non-urgent notifications queue without attempting delivery")
        void nonUrgentNeverBlocks() {
            UUID storeId = UUID.randomUUID();

            // Even if Feishu would throw, non-urgent should not call it at all
            service.notifyApprovalNeeded(storeId, "Campaign H", "budget_change",
                    "Increase budget", new BigDecimal("0.3"), null);

            verify(feishuService, never()).pushAiNotification(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("When no Feishu destination, emergency is gracefully queued")
        void noDestinationGraceful() {
            UUID storeId = UUID.randomUUID();
            when(feishuService.pushAiNotification(eq(storeId), anyString(), anyString()))
                    .thenReturn(false); // No Feishu destination configured

            service.notifyEmergency(storeId, "Campaign I", "spend",
                    "Threshold breached", "Halted");

            // Should be marked as queued with a readable skip reason (Req 7.4)
            verify(deliveryLogMapper).updateDeliveryStatus(anyString(), eq("queued"), eq(1),
                    eq("No Feishu destination bound for this store/account; notification skipped"));
        }
    }

    @Nested
    @DisplayName("Payload serialization")
    class PayloadSerialization {

        @Test
        @DisplayName("Notification payload is serialized as JSON in log entry")
        void payloadSerializedAsJson() {
            UUID storeId = UUID.randomUUID();

            service.notifyDataGap(storeId, 5, "SP_CAMPAIGN", "5-day gap detected");

            ArgumentCaptor<NotificationDeliveryLogEntity> captor =
                    ArgumentCaptor.forClass(NotificationDeliveryLogEntity.class);
            verify(deliveryLogMapper).insert(captor.capture());

            String payload = captor.getValue().getPayload();
            assertThat(payload).contains("\"gapDays\":5");
            assertThat(payload).contains("\"reportType\":\"SP_CAMPAIGN\"");
            assertThat(payload).contains("\"details\":\"5-day gap detected\"");
        }
    }
}
