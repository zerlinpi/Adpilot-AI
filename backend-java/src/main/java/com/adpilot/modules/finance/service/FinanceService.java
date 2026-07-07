package com.adpilot.modules.finance.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.finance.dto.InvoiceDto;
import com.adpilot.modules.finance.vo.FinancialSummaryVo;
import com.adpilot.modules.finance.vo.InvoiceVo;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface FinanceService {

    /**
     * List invoices with pagination.
     */
    PageResponse<InvoiceVo> listInvoices(int page, int pageSize);

    /**
     * Get invoice by ID.
     */
    InvoiceVo getInvoiceById(String id);

    /**
     * Create a new invoice.
     */
    InvoiceVo createInvoice(InvoiceDto dto, String userId);

    /**
     * List payments with pagination.
     */
    PageResponse<Object> listPayments(int page, int pageSize);

    /**
     * Create a new payment.
     */
    Object createPayment(Object dto, String userId);

    /**
     * List financial summaries with pagination.
     */
    PageResponse<FinancialSummaryVo> listFinancialSummaries(int page, int pageSize);

    /**
     * Get financial summary by ID.
     */
    FinancialSummaryVo getFinancialSummary(String id);
}
