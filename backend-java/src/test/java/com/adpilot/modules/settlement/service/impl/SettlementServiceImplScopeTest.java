package com.adpilot.modules.settlement.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.settlement.entity.SettlementEntity;
import com.adpilot.modules.settlement.mapper.SettlementMapper;
import com.adpilot.modules.settlement.vo.SettlementVo;
import com.adpilot.modules.audit.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the settlement service (H3) enforces the store data-scope: a by-id read
 * of a record outside the caller's scope is rejected (cross-tenant BOLA blocked),
 * while an in-scope read succeeds. Scope is enforced through the shared
 * {@link DataScopeService}, mirroring the finance/order modules.
 */
class SettlementServiceImplScopeTest {

    private SettlementMapper settlementMapper;
    private DataScopeService dataScopeService;
    private SettlementServiceImpl service;

    @BeforeEach
    void setUp() {
        settlementMapper = mock(SettlementMapper.class);
        dataScopeService = mock(DataScopeService.class);
        service = new SettlementServiceImpl(settlementMapper, dataScopeService, mock(AuditLogService.class));
        authenticate();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getSettlementByIdBlocksCrossTenantRead() {
        UUID id = UUID.randomUUID();
        SettlementEntity foreign = SettlementEntity.builder()
                .id(id).storeId(UUID.randomUUID()).settlementId("S-1").build();
        when(settlementMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.getSettlementById(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));
    }

    @Test
    void getSettlementByIdReturnsInScopeRecord() {
        UUID id = UUID.randomUUID();
        SettlementEntity owned = SettlementEntity.builder()
                .id(id).storeId(UUID.randomUUID()).settlementId("S-2").build();
        when(settlementMapper.selectById(id)).thenReturn(owned);
        // dataScopeService.assertCanRead is a no-op for in-scope records.

        SettlementVo vo = service.getSettlementById(id.toString());

        assertThat(vo).isNotNull();
        assertThat(vo.getSettlementId()).isEqualTo("S-2");
        verify(dataScopeService).assertCanRead(eq(owned), any(CurrentUser.class));
    }

    @Test
    void listSettlementsAppliesStoreScope() {
        when(settlementMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.listSettlements(null, 1, 20);

        verify(dataScopeService).applyScope(any(), any(), any(CurrentUser.class));
    }

    private void authenticate() {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("op@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(java.util.Set.of("operations_specialist"))
                .permissions(List.of("finance:view", "finance:manage"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
