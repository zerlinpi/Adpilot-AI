package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example/unit tests for {@link ApiSyncServiceImpl#testPlatformConnection(String)} message handling.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Requirement 13 (Real Platform Connection Test).
 * These examples complement {@link ConnectionTestFidelityPropertyTest} by pinning down the two
 * concrete, human-readable outcomes an administrator sees:</p>
 *
 * <ul>
 *   <li>A successful connector result yields a success result (ok=true) carrying the
 *       human-readable success message (Requirement 13.2).</li>
 *   <li>An unreachable connector (the test cannot obtain a result) yields a failure result
 *       (ok=false) with a human-readable "unreachable" message, and the stored credentials
 *       are left unchanged (Requirement 13.5).</li>
 * </ul>
 */
class ConnectionTestMessageTest {

    private static final String PLATFORM = "woocommerce";

    /** Build the service with all collaborators mocked; returns the wired service plus the
     *  mocks the assertions need to inspect. */
    private record Fixture(ApiSyncServiceImpl service,
                           PlatformConnectionMapper connectionMapper,
                           CryptoUtil cryptoUtil,
                           PlatformConnector connector,
                           UUID connectionId) {}

    private Fixture newFixture(PlatformConnector connector) {
        UUID connectionId = UUID.randomUUID();

        PlatformConnectionEntity connection = PlatformConnectionEntity.builder()
                .id(connectionId)
                .storeId(UUID.randomUUID())
                .platform(PLATFORM)
                .connectionName("My Store")
                .configEncrypted("{\"consumerKey\":\"enc-key\",\"consumerSecret\":\"enc-secret\",\"siteUrl\":\"enc-url\"}")
                .status("configured")
                .build();

        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        when(connectionMapper.selectById(eq(connectionId))).thenReturn(connection);

        CryptoUtil cryptoUtil = mock(CryptoUtil.class);
        lenient().when(cryptoUtil.decrypt(anyString())).thenAnswer(inv -> "plain-" + inv.getArgument(0));

        ApiSyncServiceImpl service = new ApiSyncServiceImpl(
                connectionMapper,
                mock(ApiSyncJobMapper.class),
                mock(ApiSyncLogMapper.class),
                cryptoUtil,
                connector,
                new ObjectMapper(),
                mock(SyncJobRunner.class),
                mock(StoreMapper.class),
                mock(MarketplaceMapper.class),
                mock(com.adpilot.modules.store.mapper.UserStoreMapper.class),
                mock(com.adpilot.modules.audit.service.AuditLogService.class));

        return new Fixture(service, connectionMapper, cryptoUtil, connector, connectionId);
    }

    // Requirement 13.2: a successful connector result returns ok=true with a human-readable success message.
    @Test
    void successfulTestReturnsHumanReadableSuccessMessage() {
        String successMessage = "WooCommerce 连接成功";
        PlatformConnector connector = mock(PlatformConnector.class);
        when(connector.test(eq(PLATFORM), any())).thenReturn(PlatformConnector.TestResult.ok(successMessage));

        Fixture f = newFixture(connector);

        PlatformConnector.TestResult result = f.service().testPlatformConnection(f.connectionId().toString());

        assertThat(result.ok())
                .as("a successful connector result is reported as success")
                .isTrue();
        assertThat(result.message())
                .as("the success result carries a human-readable, non-blank message")
                .isNotBlank()
                .isEqualTo(successMessage);
    }

    // Requirement 13.5: an unreachable connector returns ok=false with a human-readable failure
    // message and leaves the stored credentials unchanged.
    @Test
    void unreachableConnectorReturnsFailureMessageAndLeavesCredentialsUnchanged() {
        PlatformConnector connector = mock(PlatformConnector.class);
        // The connector cannot be reached: it raises an error instead of returning a result.
        when(connector.test(eq(PLATFORM), any()))
                .thenThrow(new RuntimeException("connection refused"));

        Fixture f = newFixture(connector);

        PlatformConnector.TestResult result = f.service().testPlatformConnection(f.connectionId().toString());

        assertThat(result.ok())
                .as("an unreachable platform is reported as a failure")
                .isFalse();
        assertThat(result.message())
                .as("the failure result carries a human-readable 'unreachable' message")
                .isNotBlank()
                .contains("不可达");

        // Stored credentials are left unchanged: no update/insert and no re-encryption.
        verify(f.connectionMapper(), never()).updateById(any());
        verify(f.connectionMapper(), never()).insert(any());
        verify(f.cryptoUtil(), never()).encrypt(anyString());
    }
}
