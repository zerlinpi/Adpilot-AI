package com.adpilot.modules.product.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductDto {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "SKU is required")
    private String sku;

    private String asin;

    @NotBlank(message = "Product name is required")
    private String name;

    private String imageUrl;
    private BigDecimal price;
    private BigDecimal cost;
    private BigDecimal grossMargin;
    private Integer inventory;
    private BigDecimal targetAcos;
    private BigDecimal breakEvenAcos;
    private String category;
    private String brand;
}
