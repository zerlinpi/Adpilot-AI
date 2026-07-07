package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TopProductVo {

    private String id;
    private String name;
    private String sku;
    private double price;
    private int inventory;
    private String status;
}
