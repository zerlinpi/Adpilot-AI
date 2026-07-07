package com.adpilot.modules.feishu.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.feishu.service.FeishuService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link FeishuController#sendTestMessage} — the "test connection"
 * endpoint {@code POST /api/integrations/feishu/{id}/test-message} (task 9.4).
 *
 * <p>Validates: Requirements 7.5.
 *
 * <p>Req 7.5 requires that when the user clicks "test connection", the System dispatches
 * a connectivity test through this endpoint using the bound integration's credentials and
 * returns the result. These tests assert the controller (a) delegates to
 * {@link FeishuService#sendTestMessage(String, String)} with the path id and the optional
 * target chat, so the service resolves and uses that integration's bound credentials, and
 * (b) surfaces the connectivity outcome: a successful response on success and the
 * propagated failure when the bound credentials cannot reach Feishu.
 */
@ExtendWith(MockitoExtension.class)
class FeishuControllerTest {

    @Mock
    private FeishuService feishuService;

    @InjectMocks
    private FeishuController controller;

    @Test
    @DisplayName("test-message delegates to the bound integration by id and returns a successful connectivity result")
    void sendTestMessageDelegatesAndReturnsSuccess() {
        String integrationId = UUID.randomUUID().toString();
        doNothing().when(feishuService).sendTestMessage(integrationId, null);

        ApiResponse<Void> response = controller.sendTestMessage(integrationId, null);

        // Delegates to the service keyed by the integration id, so the bound integration's
        // credentials are the ones exercised for the connectivity test.
        verify(feishuService).sendTestMessage(integrationId, null);
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("test-message forwards the explicit target chat id to the bound integration")
    void sendTestMessageForwardsChatId() {
        String integrationId = UUID.randomUUID().toString();
        String chatId = "oc_test_chat";
        doNothing().when(feishuService).sendTestMessage(integrationId, chatId);

        ApiResponse<Void> response = controller.sendTestMessage(integrationId, chatId);

        verify(feishuService).sendTestMessage(integrationId, chatId);
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("test-message surfaces the connectivity failure raised by the bound integration")
    void sendTestMessagePropagatesConnectivityFailure() {
        String integrationId = UUID.randomUUID().toString();
        doThrow(new BusinessException("FEISHU_TEST_FAILED", "Failed to send test message to Feishu"))
                .when(feishuService).sendTestMessage(integrationId, null);

        assertThatThrownBy(() -> controller.sendTestMessage(integrationId, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Failed to send test message");
    }
}
