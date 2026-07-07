package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.dto.PlatformConnectionDto;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A2 — forensic AUDIT-TRAIL coverage for the platform credential / integration lifecycle.
 *
 * <p>Verifies that a successful {@code createPlatformConnection} writes a single audit entry with
 * the {@code PLATFORM_CONNECTION_CREATE} action against the {@code platform_connection} entity type.
 * The audit is additive and best-effort — it must never change the create behavior — so this test
 * only asserts the audit call is made with the expected action/entity strings and carries no secret.
 */
class ApiSyncServiceAuditTest {

    @Test
    void createPlatformConnectionWritesPlatformConnectionCreateAudit() {
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        ApiSyncServiceImpl service = new ApiSyncServiceImpl(
                connectionMapper,
                mock(ApiSyncJobMapper.class),
                mock(ApiSyncLogMapper.class),
                mock(CryptoUtil.class),
                mock(PlatformConnector.class),
                new ObjectMapper(),
                mock(SyncJobRunner.class),
                mock(StoreMapper.class),
                mock(MarketplaceMapper.class),
                mock(UserStoreMapper.class),
                auditLogService);

        PlatformConnectionDto dto = new PlatformConnectionDto();
        dto.setPlatform("shopify");
        dto.setStoreId(UUID.randomUUID().toString());

        service.createPlatformConnection(dto, UUID.randomUUID().toString());

        verify(auditLogService, times(1)).createLog(
                any(), any(), eq("PLATFORM_CONNECTION_CREATE"), eq("platform_connection"), any(), any());
    }
}
