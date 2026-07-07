package com.adpilot.modules.importcenter.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.importcenter.entity.ImportJobEntity;
import com.adpilot.modules.importcenter.mapper.ImportJobMapper;
import com.adpilot.modules.importcenter.mapper.ImportRowErrorMapper;
import com.adpilot.modules.importcenter.mapper.RawSearchTermReportMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
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
 * Verifies the import center (H9) enforces the store data-scope: by-id reads
 * (get/preview/errors) and commit of an import job whose store is outside the
 * caller's scope are rejected (cross-tenant BOLA/BFLA blocked), and the listing is
 * store-scoped through the shared {@link DataScopeService}.
 */
class ImportServiceImplScopeTest {

    private ImportJobMapper importJobMapper;
    private RawSearchTermReportMapper rawSearchTermReportMapper;
    private ImportRowErrorMapper importRowErrorMapper;
    private DataScopeService dataScopeService;
    private ImportServiceImpl service;

    @BeforeEach
    void setUp() {
        importJobMapper = mock(ImportJobMapper.class);
        importRowErrorMapper = mock(ImportRowErrorMapper.class);
        rawSearchTermReportMapper = mock(RawSearchTermReportMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        AdGroupMapper adGroupMapper = mock(AdGroupMapper.class);
        SearchTermMapper searchTermMapper = mock(SearchTermMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceReferenceService marketplaceReferenceService = mock(MarketplaceReferenceService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        dataScopeService = mock(DataScopeService.class);
        service = new ImportServiceImpl(
                importJobMapper, importRowErrorMapper, rawSearchTermReportMapper, campaignMapper,
                adGroupMapper, searchTermMapper, performanceDailyMapper, storeMapper,
                marketplaceReferenceService, auditLogService, new ObjectMapper(), dataScopeService);
        authenticate();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getImportBlocksCrossTenantRead() {
        UUID id = UUID.randomUUID();
        ImportJobEntity foreign = ImportJobEntity.builder()
                .id(id).storeId(UUID.randomUUID()).reportType("search_term").build();
        when(importJobMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.getImport(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));
    }

    @Test
    void previewImportBlocksCrossTenantRead() {
        UUID id = UUID.randomUUID();
        ImportJobEntity foreign = ImportJobEntity.builder()
                .id(id).storeId(UUID.randomUUID()).reportType("search_term").build();
        when(importJobMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.previewImport(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(rawSearchTermReportMapper, never()).selectList(any());
    }

    @Test
    void getImportErrorsBlocksCrossTenantRead() {
        UUID id = UUID.randomUUID();
        ImportJobEntity foreign = ImportJobEntity.builder()
                .id(id).storeId(UUID.randomUUID()).reportType("search_term").build();
        when(importJobMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.getImportErrors(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(importRowErrorMapper, never()).selectList(any());
    }

    @Test
    void commitImportBlocksCrossTenantWrite() {
        UUID id = UUID.randomUUID();
        ImportJobEntity foreign = ImportJobEntity.builder()
                .id(id).storeId(UUID.randomUUID()).reportType("search_term").status("validated").build();
        when(importJobMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanWrite(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.commitImport(id.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);

        // The atomic status-claim update must never run for an out-of-scope job.
        verify(importJobMapper, never()).update(any(), any());
    }

    @Test
    void listImportsAppliesStoreScope() {
        when(importJobMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.listImports(null, 1, 50);

        verify(dataScopeService).applyScope(any(), any(), any(CurrentUser.class));
    }

    private void authenticate() {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("importer@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(java.util.Set.of("operations_specialist"))
                .permissions(List.of("import:view", "import:manage"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
