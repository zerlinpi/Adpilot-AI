package com.adpilot.modules.settlement.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SettlementVo {

    private String id;
    private String storeId;
    private String settlementId;
    private String settlementStartDate;
    private String settlementEndDate;
    private String depositDate;
    private double totalAmount;
    private String currency;
    private String status;
    private String rawData;
    private String createdAt;
    private String updatedAt;
}
