package com.adpilot.modules.listingops.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BuyBoxAlertVo {

    private String id;
    private String storeId;
    private String asin;
    private String sku;
    private String productName;
    private String buyBoxSeller;
    private BigDecimal buyBoxPrice;
    private Boolean isOwnBuyBox;
    private String alertType;
    private String status;
    private String createdAt;
    private String updatedAt;
}
