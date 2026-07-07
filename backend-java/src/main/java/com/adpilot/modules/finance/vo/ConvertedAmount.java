package com.adpilot.modules.finance.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of converting a monetary amount from one currency to another
 * (Req 9.2). Retains both the original currency amount and the converted
 * reporting-currency amount (Req 9.2.2), along with the exchange rate value and
 * the effective date of the rate used for the conversion (Req 9.2.6).
 *
 * <p>When no rate is available for the required currency pair and date, the
 * amount is flagged {@code unconverted} rather than converted with an undefined
 * rate (Req 9.2.4); in that case {@code converted}, {@code rate}, and
 * {@code rateDate} are {@code null} and {@code original} carries the
 * untouched source amount.</p>
 *
 * @param original     the original amount in {@code fromCurrency}
 * @param fromCurrency the currency the original amount is denominated in
 * @param converted    the amount expressed in the target currency, or
 *                     {@code null} when {@code unconverted} is {@code true}
 * @param rate         the exchange rate applied, or {@code null} when
 *                     {@code unconverted} is {@code true}
 * @param rateDate     the effective date of the rate used, or {@code null}
 *                     when {@code unconverted} is {@code true}
 * @param unconverted  {@code true} when no rate was available and the amount
 *                     was left unconverted
 */
public record ConvertedAmount(BigDecimal original,
                              String fromCurrency,
                              BigDecimal converted,
                              BigDecimal rate,
                              LocalDate rateDate,
                              boolean unconverted) {

    /**
     * Builds a successful conversion result retaining the original amount, the
     * converted amount, the applied rate, and the rate's effective date
     * (Req 9.2.2, 9.2.6).
     */
    public static ConvertedAmount converted(BigDecimal original,
                                            String fromCurrency,
                                            BigDecimal converted,
                                            BigDecimal rate,
                                            LocalDate rateDate) {
        return new ConvertedAmount(original, fromCurrency, converted, rate, rateDate, false);
    }

    /**
     * Builds an unconverted result for when no rate is available, retaining the
     * original amount and flagging it unconverted rather than applying an
     * undefined rate (Req 9.2.4).
     */
    public static ConvertedAmount unconverted(BigDecimal original, String fromCurrency) {
        return new ConvertedAmount(original, fromCurrency, null, null, null, true);
    }
}
