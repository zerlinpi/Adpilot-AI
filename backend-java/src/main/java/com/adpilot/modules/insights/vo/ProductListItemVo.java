package com.adpilot.modules.insights.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A single product row on the product list Data Insights surface (Req 30.1):
 * ad sales, ad spend, TACoS, total sales, total orders, and inventory status,
 * keyed by parent ASIN and ASIN.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductListItemVo {

    private String productId;

    /** Parent ASIN (父ASIN). Falls back to the ASIN when no parent is stored. */
    private String parentAsin;

    private String asin;

    private String sku;

    private String name;

    /** 广告销额 — ad sales attributed to the product within the date range. */
    private BigDecimal adSales;

    /** 广告花费 — ad spend attributed to the product within the date range. */
    private BigDecimal adSpend;

    /** TACoS — ad spend / total sales as a percentage (computed by AdMetrics). */
    private BigDecimal tacos;

    /** 总销额 — total sales from orders within the date range. */
    private BigDecimal totalSales;

    /** 总订单数 — total order count within the date range. */
    private long totalOrders;

    /** On-hand inventory units. */
    private Integer inventory;

    /** Inventory status: {@code healthy} / {@code low} / {@code out_of_stock}. */
    private String inventoryStatus;
}
