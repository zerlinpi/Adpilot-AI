package com.adpilot.modules.keyword.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.keyword.dto.KeywordInsightQueryRequest;
import com.adpilot.modules.keyword.entity.KeywordInsightEntity;
import com.adpilot.modules.keyword.mapper.KeywordCoverageMapper;
import com.adpilot.modules.keyword.mapper.KeywordInsightMapper;
import com.adpilot.modules.keyword.mapper.KeywordLibraryItemMapper;
import com.adpilot.modules.keyword.mapper.KeywordLibraryMapper;
import com.adpilot.modules.keyword.mapper.KeywordNgramMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies keyword intelligence (H7) enforces the store data-scope: insight
 * mutations (apply/watch/dismiss) on a record whose store is outside the caller's
 * scope are rejected (cross-tenant BFLA blocked), and the insights listing is
 * store-scoped so an unset/foreign storeId cannot surface other tenants' insights.
 * Enforcement routes through the shared {@link DataScopeService}.
 */
class KeywordIntelligenceServiceImplScopeTest {

    private KeywordInsightMapper insightMapper;
    private DataScopeService dataScopeService;
    private KeywordIntelligenceServiceImpl service;

    @BeforeEach
    void setUp() {
        insightMapper = mock(KeywordInsightMapper.class);
        KeywordCoverageMapper coverageMapper = mock(KeywordCoverageMapper.class);
        KeywordNgramMapper ngramMapper = mock(KeywordNgramMapper.class);
        KeywordLibraryMapper libraryMapper = mock(KeywordLibraryMapper.class);
        KeywordLibraryItemMapper libraryItemMapper = mock(KeywordLibraryItemMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        dataScopeService = mock(DataScopeService.class);
        service = new KeywordIntelligenceServiceImpl(
                insightMapper, coverageMapper, ngramMapper, libraryMapper, libraryItemMapper,
                auditLogService, new ObjectMapper(), dataScopeService);
        authenticate();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void applyInsightBlocksCrossTenantWrite() {
        UUID id = UUID.randomUUID();
        KeywordInsightEntity foreign = KeywordInsightEntity.builder()
                .id(id).storeId(UUID.randomUUID()).text("foreign kw").status("open").build();
        when(insightMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanWrite(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.applyInsight(id.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);

        verify(insightMapper, never()).updateById(any());
    }

    @Test
    void watchInsightBlocksCrossTenantWrite() {
        UUID id = UUID.randomUUID();
        KeywordInsightEntity foreign = KeywordInsightEntity.builder()
                .id(id).storeId(UUID.randomUUID()).text("foreign kw").status("open").build();
        when(insightMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanWrite(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.watchInsight(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(insightMapper, never()).updateById(any());
    }

    @Test
    void dismissInsightBlocksCrossTenantWrite() {
        UUID id = UUID.randomUUID();
        KeywordInsightEntity foreign = KeywordInsightEntity.builder()
                .id(id).storeId(UUID.randomUUID()).text("foreign kw").status("open").build();
        when(insightMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanWrite(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.dismissInsight(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(insightMapper, never()).updateById(any());
    }

    @Test
    void getInsightsAppliesStoreScope() {
        when(insightMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        KeywordInsightQueryRequest request = new KeywordInsightQueryRequest();
        service.getInsights(request);

        verify(dataScopeService).applyScope(any(), any(), any(CurrentUser.class));
    }

    private void authenticate() {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("kw@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(java.util.Set.of("operations_specialist"))
                .permissions(List.of("keyword:view", "keyword:manage", "keyword:apply"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
