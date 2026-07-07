package com.adpilot.modules.finance.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.finance.dto.InvoiceDto;
import com.adpilot.modules.finance.entity.InvoiceEntity;
import com.adpilot.modules.finance.mapper.CostAllocationMapper;
import com.adpilot.modules.finance.mapper.FinancialSummaryMapper;
import com.adpilot.modules.finance.mapper.InvoiceItemMapper;
import com.adpilot.modules.finance.mapper.InvoiceMapper;
import com.adpilot.modules.finance.mapper.PaymentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Verifies the additive forensic AUDIT-TRAIL logging on {@link FinanceServiceImpl#createInvoice}:
 * a successful invoice creation writes exactly one audit-log entry keyed to the new invoice, with
 * the {@code CREATE_INVOICE}/{@code invoice} action-entity strings. Collaborators are mocked so no
 * database or security context is required (an unauthenticated call skips data-scope checks).
 */
@DisplayName("FinanceServiceImpl audit-trail")
class FinanceServiceImplAuditTest {

    private InvoiceMapper invoiceMapper;
    private AuditLogService auditLogService;
    private FinanceServiceImpl service;

    @BeforeEach
    void setUp() {
        invoiceMapper = mock(InvoiceMapper.class);
        auditLogService = mock(AuditLogService.class);
        service = new FinanceServiceImpl(
                invoiceMapper,
                mock(InvoiceItemMapper.class),
                mock(PaymentMapper.class),
                mock(CostAllocationMapper.class),
                mock(FinancialSummaryMapper.class),
                mock(DataScopeService.class),
                auditLogService);
    }

    @Test
    @DisplayName("createInvoice: writes a CREATE_INVOICE audit log keyed to the new invoice id")
    void createInvoiceWritesAuditLog() {
        InvoiceDto dto = new InvoiceDto();
        dto.setStoreId(UUID.randomUUID().toString());
        dto.setInvoiceNumber("INV-001");
        dto.setTotalAmount(new BigDecimal("500.00"));
        dto.setCurrency("USD");
        dto.setCustomerName("Acme Corp");

        UUID generatedId = UUID.randomUUID();
        doAnswer(inv -> {
            ((InvoiceEntity) inv.getArgument(0)).setId(generatedId);
            return 1;
        }).when(invoiceMapper).insert(any(InvoiceEntity.class));

        service.createInvoice(dto, null);

        // The audit log is written AFTER the successful insert, keyed to the invoice entity.
        verify(auditLogService).createLog(
                any(), any(), eq("CREATE_INVOICE"), eq("invoice"), eq(generatedId), any());
    }
}
