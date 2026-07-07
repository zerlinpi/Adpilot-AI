package com.adpilot.modules.finance.vo;

import java.math.BigDecimal;

/**
 * Result of including the VAT component in a Store's financial figure
 * (Req 9.2.3). Retains the original net amount, the VAT component computed for
 * the Store's marketplace, and the gross (VAT-inclusive) amount.
 *
 * <p>When the Store's marketplace is not subject to VAT, {@code vatApplicable}
 * is {@code false}, the {@code vatComponent} is zero, and {@code gross} equals
 * {@code net} — no VAT is applied.</p>
 *
 * @param net           the amount before VAT
 * @param vatComponent  the VAT amount included, or zero when not applicable
 * @param gross         the VAT-inclusive amount ({@code net + vatComponent})
 * @param vatApplicable {@code true} when the marketplace is subject to VAT and
 *                      a VAT component was included
 */
public record VatInclusiveAmount(BigDecimal net,
                                 BigDecimal vatComponent,
                                 BigDecimal gross,
                                 boolean vatApplicable) {
}
