package com.adpilot.modules.procurement.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SupplierVo {

    private String id;
    private String orgId;
    private String supplierName;
    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private String address;
    private String country;
    private String paymentTerms;
    private Integer leadTimeDays;
    private Double rating;
    private String status;
    private String notes;
    private String createdAt;
    private String updatedAt;
}
