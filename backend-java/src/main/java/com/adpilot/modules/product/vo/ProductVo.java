package com.adpilot.modules.product.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductVo {

    private String id;
    private String storeId;
    private String sku;
    private String asin;
    private String name;
    private String imageUrl;
    private double price;
    private double cost;
    private double grossMargin;
    private int inventory;
    private double targetAcos;
    private double breakEvenAcos;
    private String category;
    private String brand;
    private String status;
    private String createdAt;
}
