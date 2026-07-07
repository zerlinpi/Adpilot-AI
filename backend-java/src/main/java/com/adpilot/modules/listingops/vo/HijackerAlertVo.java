package com.adpilot.modules.listingops.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class HijackerAlertVo {

    private String id;
    private String storeId;
    private String asin;
    private String sku;
    private String productName;
    private String hijackerSeller;
    private BigDecimal hijackerPrice;
    private String alertType;
    private String status;
    private String createdAt;
    private String updatedAt;
}
