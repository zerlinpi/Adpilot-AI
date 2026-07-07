package com.adpilot.modules.settlement.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class SettlementDto {

    private String storeId;
    private String settlementId;
    private LocalDate settlementStartDate;
    private LocalDate settlementEndDate;
    private LocalDate depositDate;
    private BigDecimal totalAmount;
    private String currency;
    private String status;
    private String rawData;
}
