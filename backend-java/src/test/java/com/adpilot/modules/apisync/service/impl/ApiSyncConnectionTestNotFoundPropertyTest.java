package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the not-found path of the real platform connection
 * test served by {@link ApiSyncServiceImpl#testPlatformConnection(String)}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 25: A connection test
 * for an unknown target returns not-found.
 *
 * <p>Validates: Requirements 13.4.
 *
 * <p>Both entry points (by platform key, by connection id) converge on the same
 * lookup before the real connector runs: a UUID-shaped target resolves a
 * connection by id, anything else is treated as a platform key. When the target
 * corresponds to no stored {@link PlatformConnectionEntity}, the service must
 * reject the request as not-found and must never route through the platform
 * connector (so it returns no record data / connector result).
 */
class ApiSyncConnectionTestNotFoundPropertyTest {

    /**
     * Feature: platform-ux-logistics-enhancements, Property 25: A connection test
     * for an unknown target returns not-found.
     *
     * <p>Validates: Requirements 13.4.
     *
     * <p>For any target (UUID-shaped id or arbitrary platform key) that does not
     * correspond to an existing connection, {@code testPlatformConnection} throws
     * a {@link BusinessException} indicating the connection was not found, and the
     * {@link PlatformConnector} is never invoked.
     */
    @Property(tries = 200)
    void unknownTargetReturnsNotFoundAndNeverRunsConnector(
            @ForAll("unknownTargets") String unknownTarget) {

        PlatformConnectionMapper connectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        ApiSyncJobMapper jobMapper = Mockito.mock(ApiSyncJobMapper.class);
        ApiSyncLogMapper logMapper = Mockito.mock(ApiSyncLogMapper.class);
        CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        PlatformConnector platformConnector = Mockito.mock(PlatformConnector.class);
        ObjectMapper objectMapper = new ObjectMapper();
        SyncJobRunner syncJobRunner = Mockito.mock(SyncJobRunner.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        MarketplaceMapper marketplaceMapper = Mockito.mock(MarketplaceMapper.class);

        // Empty data store: no connection exists for any id (by-id path) or for
        // any platform key (by-key path). Every lookup therefore misses.
        when(connectionMapper.selectById(any())).thenReturn(null);
        when(connectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ApiSyncServiceImpl service = new ApiSyncServiceImpl(
                connectionMapper, jobMapper, logMapper, cryptoUtil, platformConnector,
                objectMapper, syncJobRunner, storeMapper, marketplaceMapper,
                Mockito.mock(com.adpilot.modules.store.mapper.UserStoreMapper.class),
                Mockito.mock(com.adpilot.modules.audit.service.AuditLogService.class));

        // The connection test rejects the unknown target as not-found.
        assertThatThrownBy(() -> service.testPlatformConnection(unknownTarget))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo("CONNECTION_NOT_FOUND");

        // No record data is produced: the real connector is never run for an
        // unknown target, so credentials are never decrypted nor transmitted.
        verify(platformConnector, never()).test(anyString(), any());
        verify(platformConnector, never()).test(any(com.adpilot.modules.apisync.model.ConnectionContext.class));
        verify(cryptoUtil, never()).decrypt(anyString());
    }

    // --- generators --------------------------------------------------------

    /**
     * Targets that do not correspond to any stored connection. The space mixes
     * the two real entry-point shapes so both converge on the not-found path:
     * <ul>
     *   <li>UUID-shaped strings (resolved via {@code selectById}); and</li>
     *   <li>arbitrary non-blank platform keys (resolved via {@code selectOne}).</li>
     * </ul>
     * All are unknown because the mocked mapper holds no rows.
     */
    @Provide
    Arbitrary<String> unknownTargets() {
        Arbitrary<String> uuidShaped =
                Arbitraries.randomValue(r -> UUID.randomUUID().toString());
        Arbitrary<String> platformKeys = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('_', '-')
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(60);
        return Arbitraries.oneOf(uuidShaped, platformKeys);
    }
}
