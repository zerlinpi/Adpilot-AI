package com.adpilot.modules.finance.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.finance.dto.InvoiceDto;
import com.adpilot.modules.finance.entity.CostAllocationEntity;
import com.adpilot.modules.finance.entity.FinancialSummaryEntity;
import com.adpilot.modules.finance.entity.InvoiceEntity;
import com.adpilot.modules.finance.entity.InvoiceItemEntity;
import com.adpilot.modules.finance.entity.PaymentEntity;
import com.adpilot.modules.finance.mapper.CostAllocationMapper;
import com.adpilot.modules.finance.mapper.FinancialSummaryMapper;
import com.adpilot.modules.finance.mapper.InvoiceItemMapper;
import com.adpilot.modules.finance.mapper.InvoiceMapper;
import com.adpilot.modules.finance.mapper.PaymentMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.finance.service.FinanceService;
import com.adpilot.modules.finance.vo.FinancialSummaryVo;
import com.adpilot.modules.finance.vo.InvoiceVo;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinanceServiceImpl implements FinanceService {

    private final InvoiceMapper invoiceMapper;
    private final InvoiceItemMapper invoiceItemMapper;
    private final PaymentMapper paymentMapper;
    private final CostAllocationMapper costAllocationMapper;
    private final FinancialSummaryMapper financialSummaryMapper;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Store-only scope target shared by finance entities (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    // ==================== Invoice Methods ====================

    @Override
    public PageResponse<InvoiceVo> listInvoices(int page, int pageSize) {
        Page<InvoiceEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<InvoiceEntity> wrapper = new QueryWrapper<>();
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<InvoiceEntity> result = invoiceMapper.selectPage(pageParam, wrapper);
        List<InvoiceVo> voList = result.getRecords().stream()
                .map(this::toInvoiceVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public InvoiceVo getInvoiceById(String id) {
        InvoiceEntity entity = invoiceMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("INVOICE_NOT_FOUND", "Invoice not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return toInvoiceVo(entity);
    }

    @Override
    @Transactional
    public InvoiceVo createInvoice(InvoiceDto dto, String userId) {
        InvoiceEntity entity = InvoiceEntity.builder()
                .storeId(UUID.fromString(dto.getStoreId()))
                .invoiceNumber(dto.getInvoiceNumber())
                .invoiceType(dto.getInvoiceType())
                .status("draft")
                .issueDate(dto.getIssueDate())
                .dueDate(dto.getDueDate())
                .subtotal(dto.getSubtotal() != null ? dto.getSubtotal() : BigDecimal.ZERO)
                .taxAmount(dto.getTaxAmount() != null ? dto.getTaxAmount() : BigDecimal.ZERO)
                .totalAmount(dto.getTotalAmount() != null ? dto.getTotalAmount() : BigDecimal.ZERO)
                .currency(dto.getCurrency())
                .customerName(dto.getCustomerName())
                .customerEmail(dto.getCustomerEmail())
                .billingAddress(dto.getBillingAddress())
                .notes(dto.getNotes())
                .build();

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        invoiceMapper.insert(entity);
        log.info("Invoice created: id={}, invoiceNumber={}", entity.getId(), entity.getInvoiceNumber());

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("amount", entity.getTotalAmount());
        auditDetails.put("currency", entity.getCurrency());
        auditDetails.put("customer", entity.getCustomerName());
        writeAudit("CREATE_INVOICE", "invoice", entity.getId(), auditDetails);

        return toInvoiceVo(entity);
    }

    // ==================== Payment Methods ====================

    @Override
    public PageResponse<Object> listPayments(int page, int pageSize) {
        Page<PaymentEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<PaymentEntity> wrapper = new QueryWrapper<>();
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<PaymentEntity> result = paymentMapper.selectPage(pageParam, wrapper);
        List<Object> voList = result.getRecords().stream()
                .map(this::toPaymentVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public Object createPayment(Object dto, String userId) {
        Map<String, Object> data = asMap(dto);
        UUID invoiceId = optionalUuid(data, "invoiceId");
        InvoiceEntity invoice = invoiceId != null ? invoiceMapper.selectById(invoiceId) : null;
        if (invoiceId != null && invoice == null) {
            throw new BusinessException("INVOICE_NOT_FOUND", "Invoice not found: " + invoiceId);
        }

        UUID storeId = optionalUuid(data, "storeId");
        if (storeId == null && invoice != null) {
            storeId = invoice.getStoreId();
        }
        if (storeId == null) {
            throw new BusinessException("VALIDATION_ERROR", "storeId is required when invoiceId is not provided");
        }

        BigDecimal amount = decimal(data.get("amount"), invoice != null ? invoice.getTotalAmount() : BigDecimal.ZERO);
        if (amount.signum() <= 0) {
            throw new BusinessException("VALIDATION_ERROR", "amount must be positive");
        }
        LocalDate paymentDate = date(data.get("paymentDate"), LocalDate.now());
        String status = normalizePaymentStatus(text(data, "status", "completed"));

        PaymentEntity entity = PaymentEntity.builder()
                .storeId(storeId)
                .invoiceId(invoiceId)
                .paymentNumber(text(data, "paymentNumber", defaultPaymentNumber()))
                .paymentMethod(text(data, "paymentMethod", "manual"))
                .paymentDate(paymentDate)
                .amount(amount)
                .currency(text(data, "currency", invoice != null ? invoice.getCurrency() : "USD"))
                .status(status)
                .referenceNumber(text(data, "referenceNumber", null))
                .notes(text(data, "notes", null))
                .build();

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        paymentMapper.insert(entity);
        if (invoice != null && isSettledPayment(status)) {
            invoice.setStatus("paid");
            invoice.setPaidDate(paymentDate);
            invoiceMapper.updateById(invoice);
        }
        log.info("Payment created: id={}, paymentNumber={}, invoiceId={}", entity.getId(), entity.getPaymentNumber(), invoiceId);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("amount", entity.getAmount());
        auditDetails.put("currency", entity.getCurrency());
        auditDetails.put("invoiceId", invoiceId != null ? invoiceId.toString() : null);
        writeAudit("CREATE_PAYMENT", "payment", entity.getId(), auditDetails);

        return toPaymentVo(entity);
    }

    // ==================== Financial Summary Methods ====================

    @Override
    public PageResponse<FinancialSummaryVo> listFinancialSummaries(int page, int pageSize) {
        Page<FinancialSummaryEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<FinancialSummaryEntity> wrapper = new QueryWrapper<>();
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<FinancialSummaryEntity> result = financialSummaryMapper.selectPage(pageParam, wrapper);
        List<FinancialSummaryVo> voList = result.getRecords().stream()
                .map(this::toFinancialSummaryVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public FinancialSummaryVo getFinancialSummary(String id) {
        FinancialSummaryEntity entity = financialSummaryMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("FINANCIAL_SUMMARY_NOT_FOUND", "Financial summary not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return toFinancialSummaryVo(entity);
    }

    // ==================== VO Conversion Methods ====================

    private InvoiceVo toInvoiceVo(InvoiceEntity entity) {
        return InvoiceVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .invoiceNumber(entity.getInvoiceNumber())
                .invoiceType(entity.getInvoiceType())
                .status(entity.getStatus())
                .issueDate(entity.getIssueDate() != null ? entity.getIssueDate().format(DATE_FORMATTER) : null)
                .dueDate(entity.getDueDate() != null ? entity.getDueDate().format(DATE_FORMATTER) : null)
                .paidDate(entity.getPaidDate() != null ? entity.getPaidDate().format(DATE_FORMATTER) : null)
                .subtotal(entity.getSubtotal() != null ? entity.getSubtotal() : BigDecimal.ZERO)
                .taxAmount(entity.getTaxAmount() != null ? entity.getTaxAmount() : BigDecimal.ZERO)
                .totalAmount(entity.getTotalAmount() != null ? entity.getTotalAmount() : BigDecimal.ZERO)
                .currency(entity.getCurrency())
                .customerName(entity.getCustomerName())
                .customerEmail(entity.getCustomerEmail())
                .billingAddress(entity.getBillingAddress())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }

    private Object toPaymentVo(PaymentEntity entity) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", entity.getId() != null ? entity.getId().toString() : null);
        vo.put("storeId", entity.getStoreId() != null ? entity.getStoreId().toString() : null);
        vo.put("invoiceId", entity.getInvoiceId() != null ? entity.getInvoiceId().toString() : null);
        vo.put("paymentNumber", entity.getPaymentNumber());
        vo.put("paymentMethod", entity.getPaymentMethod());
        vo.put("paymentDate", entity.getPaymentDate() != null ? entity.getPaymentDate().format(DATE_FORMATTER) : null);
        vo.put("amount", entity.getAmount() != null ? entity.getAmount() : BigDecimal.ZERO);
        vo.put("currency", entity.getCurrency());
        vo.put("status", entity.getStatus());
        vo.put("referenceNumber", entity.getReferenceNumber());
        vo.put("notes", entity.getNotes());
        vo.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null);
        vo.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null);
        return vo;
    }

    private FinancialSummaryVo toFinancialSummaryVo(FinancialSummaryEntity entity) {
        return FinancialSummaryVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .summaryType(entity.getSummaryType())
                .periodStart(entity.getPeriodStart() != null ? entity.getPeriodStart().format(DATE_FORMATTER) : null)
                .periodEnd(entity.getPeriodEnd() != null ? entity.getPeriodEnd().format(DATE_FORMATTER) : null)
                .totalRevenue(entity.getTotalRevenue() != null ? entity.getTotalRevenue() : BigDecimal.ZERO)
                .totalCost(entity.getTotalCost() != null ? entity.getTotalCost() : BigDecimal.ZERO)
                .totalFees(entity.getTotalFees() != null ? entity.getTotalFees() : BigDecimal.ZERO)
                .grossProfit(entity.getGrossProfit() != null ? entity.getGrossProfit() : BigDecimal.ZERO)
                .netProfit(entity.getNetProfit() != null ? entity.getNetProfit() : BigDecimal.ZERO)
                .currency(entity.getCurrency())
                .details(entity.getDetails())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object dto) {
        if (dto instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new BusinessException("VALIDATION_ERROR", "Request body must be an object");
    }

    private UUID optionalUuid(Map<String, Object> data, String key) {
        String value = text(data, key, null);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("VALIDATION_ERROR", key + " is invalid");
        }
    }

    private String text(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private BigDecimal decimal(Object raw, BigDecimal fallback) {
        if (raw == null) {
            return fallback != null ? fallback : BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("VALIDATION_ERROR", "amount is invalid");
        }
    }

    private LocalDate date(Object raw, LocalDate fallback) {
        if (raw == null || String.valueOf(raw).trim().isEmpty()) {
            return fallback;
        }
        try {
            return LocalDate.parse(String.valueOf(raw).trim());
        } catch (Exception e) {
            throw new BusinessException("VALIDATION_ERROR", "paymentDate must use yyyy-MM-dd");
        }
    }

    private String normalizePaymentStatus(String status) {
        String s = status != null ? status.trim().toLowerCase() : "completed";
        return switch (s) {
            case "pending", "processing", "completed", "paid", "failed", "cancelled" -> s;
            default -> "completed";
        };
    }

    private boolean isSettledPayment(String status) {
        return "completed".equals(status) || "paid".equals(status);
    }

    private String defaultPaymentNumber() {
        return "PAY-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    // ==================== Audit Trail ====================

    /**
     * Write a forensic AUDIT-TRAIL entry for a state-changing financial action. Auditing is
     * additive and best-effort: a failure here is logged and swallowed so it can never break
     * the business operation. The details map must never carry secret values.
     */
    private void writeAudit(String action, String entityType, UUID entityId, Map<String, Object> details) {
        try {
            auditLogService.createLog(resolveActorId(), resolveOrgId(), action, entityType, entityId, details);
        } catch (Exception ex) {
            log.warn("Failed to write audit log action={} entityId={}: {}", action, entityId, ex.getMessage());
        }
    }

    /** Acting user id from the security context, or {@code null} for a system/background actor. */
    private static UUID resolveActorId() {
        return parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull());
    }

    /** Caller org id from the security context, or {@code null} when unauthenticated. */
    private static UUID resolveOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            return null;
        }
        return parseUuidOrNull(SecurityUtils.getCurrentOrgId());
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
