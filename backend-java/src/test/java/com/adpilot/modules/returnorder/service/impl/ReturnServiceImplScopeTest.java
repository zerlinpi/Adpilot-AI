package com.adpilot.modules.returnorder.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.returnorder.dto.ReturnDto;
import com.adpilot.modules.returnorder.entity.ReturnEntity;
import com.adpilot.modules.returnorder.mapper.ReturnMapper;
import com.adpilot.modules.returnorder.vo.ReturnVo;
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
 * Verifies the return service (H5) enforces the store data-scope: a by-id read of a
 * record outside the caller's scope is rejected (cross-tenant BOLA blocked), an
 * in-scope read succeeds, imports assert write-scope on the target store, and lists
 * are store-scoped. Enforcement routes through the shared {@link DataScopeService}.
 */
class ReturnServiceImplScopeTest {

    private ReturnMapper returnMapper;
    private DataScopeService dataScopeService;
    private ReturnServiceImpl service;

    @BeforeEach
    void setUp() {
        returnMapper = mock(ReturnMapper.class);
        dataScopeService = mock(DataScopeService.class);
        service = new ReturnServiceImpl(returnMapper, dataScopeService);
        authenticate();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getReturnByIdBlocksCrossTenantRead() {
        UUID id = UUID.randomUUID();
        ReturnEntity foreign = ReturnEntity.builder()
                .id(id).storeId(UUID.randomUUID()).returnId("RT-1").build();
        when(returnMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.getReturnById(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));
    }

    @Test
    void getReturnByIdReturnsInScopeRecord() {
        UUID id = UUID.randomUUID();
        ReturnEntity owned = ReturnEntity.builder()
                .id(id).storeId(UUID.randomUUID()).returnId("RT-2").build();
        when(returnMapper.selectById(id)).thenReturn(owned);

        ReturnVo vo = service.getReturnById(id.toString());

        assertThat(vo).isNotNull();
        assertThat(vo.getReturnId()).isEqualTo("RT-2");
        verify(dataScopeService).assertCanRead(eq(owned), any(CurrentUser.class));
    }

    @Test
    void importReturnsBlocksOutOfScopeTargetStore() {
        ReturnDto dto = new ReturnDto();
        dto.setStoreId(UUID.randomUUID().toString());
        dto.setReturnId("RT-IMP");
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanWrite(any(ReturnEntity.class), any(CurrentUser.class));

        assertThatThrownBy(() -> service.importReturns(List.of(dto), "user"))
                .isInstanceOf(BusinessException.class);

        verify(returnMapper, never()).insert(any());
    }

    @Test
    void listReturnsAppliesStoreScope() {
        when(returnMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.listReturns(null, 1, 20);

        verify(dataScopeService).applyScope(any(), any(), any(CurrentUser.class));
    }

    private void authenticate() {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("op@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(java.util.Set.of("operations_specialist"))
                .permissions(List.of("order:view", "order:import"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
