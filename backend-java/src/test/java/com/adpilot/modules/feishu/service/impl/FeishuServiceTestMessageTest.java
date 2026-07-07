package com.adpilot.modules.feishu.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.resilience.CircuitBreaker;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.feishu.client.FeishuApiClient;
import com.adpilot.modules.feishu.entity.FeishuIntegrationEntity;
import com.adpilot.modules.feishu.mapper.FeishuIntegrationMapper;
import com.adpilot.modules.feishu.mapper.FeishuMessageLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FeishuServiceImpl#sendTestMessage} — the connectivity check behind
 * {@code POST /api/integrations/feishu/{id}/test-message} (task 9.4).
 *
 * <p>Validates: Requirements 7.5.
 *
 * <p>Req 7.5 requires the test-connection endpoint to verify connectivity using the bound
 * integration's own credentials and return the result. These tests assert that, given the
 * integration id, the service (a) loads that integration, (b) decrypts and uses its bound
 * app credentials (appId + decrypted app secret) to obtain a tenant token, (c) sends to the
 * bound default chat when no chat id is supplied, and (d) reports the connectivity outcome —
 * succeeding silently when Feishu accepts the message and raising a failure when it does not.
 */
@ExtendWith(MockitoExtension.class)
class FeishuServiceTestMessageTest {

    @Mock
    private FeishuIntegrationMapper feishuIntegrationMapper;
    @Mock
    private FeishuMessageLogMapper feishuMessageLogMapper;
    @Mock
    private FeishuApiClient feishuApiClient;
    @Mock
    private CryptoUtil cryptoUtil;
    @Mock
    private CircuitBreaker circuitBreaker;

    @InjectMocks
    private FeishuServiceImpl service;

    private UUID orgId;

    @BeforeEach
    void authenticate() {
        // The dispatch path consults the circuit breaker before sending; allow it through
        // so these connectivity tests exercise the real send/decrypt behavior.
        lenient().when(circuitBreaker.allow(any())).thenReturn(true);
        orgId = UUID.randomUUID();
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("manager@example.com")
                .orgId(orgId.toString())
                .name("Manager")
                .roles(Set.of("operations_manager"))
                .permissions(List.of("feishu:manage"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private FeishuIntegrationEntity appIntegration(UUID id) {
        // Org-wide integration (store_id == null) so it sidesteps store-scope checks and
        // isolates the credential-usage assertions for this test.
        return FeishuIntegrationEntity.builder()
                .id(id)
                .orgId(orgId)
                .storeId(null)
                .connectionType("app")
                .appId("cli_bound_app")
                .appSecretEncrypted("enc-bound-secret")
                .defaultChatId("oc_bound_chat")
                .status("active")
                .build();
    }

    @Test
    @DisplayName("uses the bound integration's decrypted credentials and default chat, returning success")
    void usesBoundCredentialsAndReturnsSuccess() {
        UUID id = UUID.randomUUID();
        when(feishuIntegrationMapper.selectById(id)).thenReturn(appIntegration(id));
        when(cryptoUtil.decrypt("enc-bound-secret")).thenReturn("plain-bound-secret");
        when(feishuApiClient.tenantAccessToken("cli_bound_app", "plain-bound-secret"))
                .thenReturn("tenant-token-xyz");
        when(feishuApiClient.buildTextContent(any())).thenReturn("{\"text\":\"test\"}");

        // No exception thrown => connectivity confirmed as successful.
        service.sendTestMessage(id.toString(), null);

        // The bound integration's own appId + decrypted secret are what authenticate the test.
        verify(cryptoUtil).decrypt("enc-bound-secret");
        verify(feishuApiClient).tenantAccessToken("cli_bound_app", "plain-bound-secret");
        // Sends to the integration's bound default chat using the obtained token.
        verify(feishuApiClient).sendAppMessage(
                eq("tenant-token-xyz"), eq("cli_bound_app"), eq("oc_bound_chat"), eq("text"), any());
    }

    @Test
    @DisplayName("reports a connectivity failure when sending with the bound credentials fails")
    void reportsConnectivityFailure() {
        UUID id = UUID.randomUUID();
        when(feishuIntegrationMapper.selectById(id)).thenReturn(appIntegration(id));
        when(cryptoUtil.decrypt("enc-bound-secret")).thenReturn("plain-bound-secret");
        when(feishuApiClient.tenantAccessToken("cli_bound_app", "plain-bound-secret"))
                .thenReturn("tenant-token-xyz");
        when(feishuApiClient.buildTextContent(any())).thenReturn("{\"text\":\"test\"}");
        // Bound credentials reach Feishu but the send is rejected -> connectivity failed.
        lenient().doThrow(new RuntimeException("HTTP 403"))
                .when(feishuApiClient).sendAppMessage(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.sendTestMessage(id.toString(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Failed to send test message");
    }

    @Test
    @DisplayName("rejects a test for an unknown integration without contacting Feishu")
    void rejectsUnknownIntegration() {
        UUID id = UUID.randomUUID();
        when(feishuIntegrationMapper.selectById(id)).thenReturn(null);

        assertThatThrownBy(() -> service.sendTestMessage(id.toString(), null))
                .isInstanceOf(BusinessException.class);
        verify(feishuApiClient, never()).tenantAccessToken(any(), any());
        verify(feishuApiClient, never()).sendAppMessage(any(), any(), any(), any(), any());
    }
}
