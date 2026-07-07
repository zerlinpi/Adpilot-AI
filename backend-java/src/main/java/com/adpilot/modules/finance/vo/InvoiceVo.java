package com.adpilot.modules.finance.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class InvoiceVo {

    private String id;
    private String storeId;
    private String invoiceNumber;
    private String invoiceType;
    private String status;
    private String issueDate;
    private String dueDate;
    private String paidDate;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String currency;
    private String customerName;
    private String customerEmail;
    private String billingAddress;
    private String notes;
    private String createdAt;
    private String updatedAt;
}
