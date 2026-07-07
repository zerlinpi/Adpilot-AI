package com.adpilot.modules.finance.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InvoiceDto {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "Invoice number is required")
    private String invoiceNumber;

    private String invoiceType;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String currency;
    private String customerName;
    private String customerEmail;
    private String billingAddress;
    private String notes;
}
