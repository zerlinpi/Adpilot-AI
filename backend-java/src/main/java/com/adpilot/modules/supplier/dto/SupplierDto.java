package com.adpilot.modules.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SupplierDto {

    private String orgId;

    @NotBlank(message = "Supplier name is required")
    private String supplierName;

    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private String address;
    private String country;
    private String paymentTerms;
    private Integer leadTimeDays;
    private BigDecimal rating;
    private String notes;
}
