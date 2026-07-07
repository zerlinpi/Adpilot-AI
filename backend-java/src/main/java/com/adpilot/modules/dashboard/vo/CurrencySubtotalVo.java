package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A revenue subtotal for a single currency encountered during cross-store
 * aggregation. When no exchange rate to the reporting currency is available, the
 * subtotal is presented in its original currency and flagged {@code unconverted}
 * so that aggregation is never blocked by a missing rate (Req 5.1.5).
 */
@Data
@Builder
public class CurrencySubtotalVo {

    /** The currency this subtotal is denominated in. */
    private String currency;

    /** The summed revenue for this currency, in {@link #currency}. */
    @Builder.Default
    private BigDecimal amount = BigDecimal.ZERO;

    /**
     * {@code true} when this subtotal could not be converted to the reporting
     * currency (no rate available) and is therefore excluded from the converted
     * totals.
     */
    @Builder.Default
    private boolean unconverted = false;
}
