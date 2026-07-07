package com.adpilot.modules.report.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.order.mapper.OrderMapper;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.report.mapper.ReportMapper;
import com.adpilot.modules.store.service.StoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the report service (H8) enforces the store data-scope: the listing is
 * store-scoped through the shared {@link DataScopeService}, and report generation
 * refuses to aggregate a store that is outside the caller's scope, or an org-wide
 * (no store) report for a non-admin (cross-tenant aggregation blocked).
 */
class ReportServiceImplScopeTest {

    private ReportMapper reportMapper;
    private DataScopeService dataScopeService;
    private StoreService storeService;
    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        reportMapper = mock(ReportMapper.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        dataScopeService = mock(DataScopeService.class);
        storeService = mock(StoreService.class);
        service = new ReportServiceImpl(
                reportMapper, new ObjectMapper(), orderMapper, performanceDailyMapper, productMapper,
                dataScopeService, storeService);
        authenticate();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void generateReportBlocksCrossTenantStore() {
        String foreignStore = UUID.randomUUID().toString();
        when(storeService.getStoreById(foreignStore))
                .thenThrow(new BusinessException(403, "STORE_FORBIDDEN", "Store is outside your data scope"));

        Map<String, String> params = baseParams();
        params.put("storeId", foreignStore);

        assertThatThrownBy(() -> service.generateReport(params, UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);

        verify(reportMapper, never()).insert(any());
    }

    @Test
    void generateReportRequiresStoreForNonAdmin() {
        // No storeId supplied and the caller is not a store admin -> refuse an
        // org-wide aggregation that would cross tenant boundaries.
        Map<String, String> params = baseParams();

        assertThatThrownBy(() -> service.generateReport(params, UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);

        verify(reportMapper, never()).insert(any());
    }

    @Test
    void listReportsAppliesStoreScope() {
        when(reportMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.listReports(1, 20);

        verify(dataScopeService).applyScope(any(), any(), any(CurrentUser.class));
    }

    private Map<String, String> baseParams() {
        Map<String, String> params = new HashMap<>();
        params.put("title", "Weekly Report");
        params.put("periodStart", "2024-01-01");
        params.put("periodEnd", "2024-01-07");
        return params;
    }

    private void authenticate() {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("analyst@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(java.util.Set.of("operations_specialist"))
                .permissions(List.of("report:view", "report:export"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
