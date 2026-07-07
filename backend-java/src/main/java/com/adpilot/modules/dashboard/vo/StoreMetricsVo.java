package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Per-store metric breakdown contributed to a cross-store aggregation
 * (Req 5.1.2). Carries the revenue in the store's own currency along with the
 * value converted to the reporting currency where a rate exists; when no rate is
 * available {@code convertedRevenue} is {@code null} and {@code unconverted} is
 * {@code true} (Req 5.1.4, 5.1.5).
 */
@Data
@Builder
public class StoreMetricsVo {

    /** The store this breakdown row describes. */
    private String storeId;

    /** Human-readable store name. */
    private String storeName;

    /** The currency the store's amounts are denominated in. */
    private String currency;

    /** Revenue in the store's own {@link #currency}. */
    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;

    /**
     * Revenue converted to the reporting currency, or {@code null} when no
     * exchange rate was available for the store's currency.
     */
    private BigDecimal convertedRevenue;

    /** {@code true} when the store's revenue could not be converted. */
    @Builder.Default
    private boolean unconverted = false;

    /** Number of distinct orders for this store in range. */
    @Builder.Default
    private long orderCount = 0L;

    /** Units sold for this store in range. */
    @Builder.Default
    private long unitsSold = 0L;
}
