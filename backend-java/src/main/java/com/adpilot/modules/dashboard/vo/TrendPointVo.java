package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A single point on the sales trend chart (Req 18.2): a period bucket label and
 * the two dual-axis series, ad spend and ad sales, for that bucket.
 */
@Data
@Builder
public class TrendPointVo {

    /** Bucket label: {@code yyyy-MM-dd} (day), ISO {@code yyyy-Www} (week), or {@code yyyy-MM} (month). */
    private String period;

    /** 花费 — ad spend in the bucket. */
    private BigDecimal spend;

    /** 销售额 — ad sales in the bucket. */
    private BigDecimal sales;

    /** 总销售额 — total (orders-derived) sales in the bucket. */
    private BigDecimal totalSales;
}
