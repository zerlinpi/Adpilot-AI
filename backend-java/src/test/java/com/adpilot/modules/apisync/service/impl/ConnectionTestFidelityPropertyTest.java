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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

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
 * Property-based test for {@link ApiSyncServiceImpl#testPlatformConnection(String)}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 24: A connection test
 * faithfully reflects the platform connector result.</p>
 *
 * <p>For any platform connector test result (success or failure), the service
 * returns a structured result that faithfully reflects the connector's outcome:
 * success only when the connector succeeds; failure (with message) when the
 * connector fails; and the stored credentials are left unchanged either way
 * (no mapper update/insert/write).</p>
 *
 * <p>The connection lookup and credential decryption are replaced with mocks so
 * the connector's outcome is the only variable; {@code platformConnector.test}
 * is stubbed to return the generated {@link PlatformConnector.TestResult}.</p>
 *
 * <p>Validates: Requirements 13.1, 13.3, 13.6</p>
 */
class ConnectionTestFidelityPropertyTest {

    private static final String PLATFORM = "woocommerce";

    // Feature: platform-ux-logistics-enhancements, Property 24: A connection test faithfully reflects the platform connector result
    @Property(tries = 200)
    void connectionTestFaithfullyReflectsConnectorResult(@ForAll("connectorResults") PlatformConnector.TestResult connectorResult) {
        UUID connectionId = UUID.randomUUID();

        // A stored connection whose credentials are encrypted in the DB column.
        // The exact ciphertext is irrelevant: CryptoUtil is mocked to decrypt it.
        PlatformConnectionEntity connection = PlatformConnectionEntity.builder()
                .id(connectionId)
                .storeId(UUID.randomUUID())
                .platform(PLATFORM)
                .connectionName("My Store")
                .configEncrypted("{\"consumerKey\":\"enc-key\",\"consumerSecret\":\"enc-secret\",\"siteUrl\":\"enc-url\"}")
                .status("configured")
                .build();

        // Connection lookup returns the stored connection (by-id entry point).
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        when(connectionMapper.selectById(eq(connectionId))).thenReturn(connection);

        // Credential decryption: echo back a deterministic plaintext per value.
        CryptoUtil cryptoUtil = mock(CryptoUtil.class);
        lenient().when(cryptoUtil.decrypt(anyString())).thenAnswer(inv -> "plain-" + inv.getArgument(0));

        // The connector returns the generated outcome; the service must reflect it verbatim.
        PlatformConnector platformConnector = mock(PlatformConnector.class);
        when(platformConnector.test(eq(PLATFORM), any())).thenReturn(connectorResult);

        ApiSyncServiceImpl service = new ApiSyncServiceImpl(
                connectionMapper,
                mock(ApiSyncJobMapper.class),
                mock(ApiSyncLogMapper.class),
                cryptoUtil,
                platformConnector,
                new ObjectMapper(),
                mock(SyncJobRunner.class),
                mock(StoreMapper.class),
                mock(MarketplaceMapper.class),
                mock(com.adpilot.modules.store.mapper.UserStoreMapper.class),
                mock(com.adpilot.modules.audit.service.AuditLogService.class));

        PlatformConnector.TestResult result = service.testPlatformConnection(connectionId.toString());

        // Req 13.6 / 13.1: success is reported if and only if the connector succeeded.
        assertThat(result.ok())
                .as("test result success iff connector success")
                .isEqualTo(connectorResult.ok());

        // Req 13.1 / 13.3: the result faithfully carries the connector's message.
        assertThat(result.message())
                .as("test result message faithfully reflects the connector message")
                .isEqualTo(connectorResult.message());

        // Req 13.3: stored credentials are left unchanged — no write of any kind.
        verify(connectionMapper, never()).updateById(any());
        verify(connectionMapper, never()).insert(any());
        verify(cryptoUtil, never()).encrypt(anyString());
    }

    @Provide
    Arbitrary<PlatformConnector.TestResult> connectorResults() {
        Arbitrary<Boolean> ok = Arbitraries.of(true, false);
        // Arbitrary human-readable messages, including empty.
        Arbitrary<String> message = Arbitraries.strings().ofMinLength(0).ofMaxLength(120);
        return Combinators.combine(ok, message)
                .as((o, m) -> o ? PlatformConnector.TestResult.ok(m) : PlatformConnector.TestResult.fail(m));
    }
}
