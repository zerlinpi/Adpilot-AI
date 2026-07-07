package com.adpilot.modules.finance.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.finance.dto.InvoiceDto;
import com.adpilot.modules.finance.dto.PaymentDto;
import com.adpilot.modules.finance.service.FinanceService;
import com.adpilot.modules.finance.vo.FinancialSummaryVo;
import com.adpilot.modules.finance.vo.InvoiceVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;

    // ==================== Invoice Endpoints ====================

    /**
     * GET /api/finance/invoices - List invoices with pagination.
     */
    @GetMapping("/invoices")
    @RequirePermission("finance:view")
    public ApiResponse<PageResponse<InvoiceVo>> listInvoices(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<InvoiceVo> result = financeService.listInvoices(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/finance/invoices/{id} - Get invoice by ID.
     */
    @GetMapping("/invoices/{id}")
    @RequirePermission("finance:view")
    public ApiResponse<InvoiceVo> getInvoice(@PathVariable String id) {
        InvoiceVo invoice = financeService.getInvoiceById(id);
        return ApiResponse.ok(invoice);
    }

    /**
     * POST /api/finance/invoices - Create a new invoice.
     */
    @PostMapping("/invoices")
    @RequirePermission("finance:manage")
    public ApiResponse<InvoiceVo> createInvoice(@Valid @RequestBody InvoiceDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        InvoiceVo invoice = financeService.createInvoice(dto, userId);
        return ApiResponse.ok(invoice);
    }

    // ==================== Payment Endpoints ====================

    /**
     * GET /api/finance/payments - List payments with pagination.
     */
    @GetMapping("/payments")
    @RequirePermission("finance:view")
    public ApiResponse<PageResponse<Object>> listPayments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<Object> result = financeService.listPayments(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/finance/payments - Create a new payment.
     *
     * <p>The request is now bound to a typed, validated {@link PaymentDto} (previously an untyped
     * {@code Object}). To keep downstream behavior identical, the DTO is mapped back to the same
     * field-keyed input the service already consumes; the service's existing conditional resolution
     * (deriving {@code storeId}/{@code amount}/{@code currency} from the linked invoice, defaulting
     * dates and status) is unchanged. Null-valued keys are carried through unchanged because the
     * service treats an absent key and a null value identically.</p>
     */
    @PostMapping("/payments")
    @RequirePermission("finance:manage")
    public ApiResponse<Object> createPayment(@Valid @RequestBody PaymentDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        Object payment = financeService.createPayment(toServiceInput(dto), userId);
        return ApiResponse.ok(payment);
    }

    /**
     * Map the validated {@link PaymentDto} to the field-keyed input the payment service consumes,
     * preserving the exact semantics of the previous untyped body: every field is passed through
     * (including nulls, which the service resolves via its own defaults) and no field is added or
     * dropped.
     */
    private static java.util.Map<String, Object> toServiceInput(PaymentDto dto) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("storeId", dto.getStoreId());
        data.put("invoiceId", dto.getInvoiceId());
        data.put("paymentNumber", dto.getPaymentNumber());
        data.put("paymentMethod", dto.getPaymentMethod());
        data.put("paymentDate", dto.getPaymentDate());
        data.put("amount", dto.getAmount());
        data.put("currency", dto.getCurrency());
        data.put("status", dto.getStatus());
        data.put("referenceNumber", dto.getReferenceNumber());
        data.put("notes", dto.getNotes());
        return data;
    }

    // ==================== Financial Summary Endpoints ====================

    /**
     * GET /api/finance/summaries - List financial summaries with pagination.
     */
    @GetMapping("/summaries")
    @RequirePermission("finance:view")
    public ApiResponse<PageResponse<FinancialSummaryVo>> listFinancialSummaries(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<FinancialSummaryVo> result = financeService.listFinancialSummaries(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/finance/summaries/{id} - Get financial summary by ID.
     */
    @GetMapping("/summaries/{id}")
    @RequirePermission("finance:view")
    public ApiResponse<FinancialSummaryVo> getFinancialSummary(@PathVariable String id) {
        FinancialSummaryVo summary = financeService.getFinancialSummary(id);
        return ApiResponse.ok(summary);
    }
}
