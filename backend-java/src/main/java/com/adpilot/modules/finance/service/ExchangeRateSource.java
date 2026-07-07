package com.adpilot.modules.finance.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * A configured source of exchange rates (Req 9.2.5). The system obtains the
 * rate for a currency pair effective on a given date from the configured
 * implementation of this interface.
 */
public interface ExchangeRateSource {

    /**
     * Looks up the rate to convert one unit of {@code fromCurrency} into
     * {@code toCurrency}, effective on {@code date}.
     *
     * @param fromCurrency the source currency code
     * @param toCurrency   the target currency code
     * @param date         the date the rate must be effective on
     * @return the matching rate, or {@link Optional#empty()} when the configured
     *         source has no rate for the pair and date
     */
    Optional<RateLookup> findRate(String fromCurrency, String toCurrency, LocalDate date);

    /**
     * A rate value together with the effective date of the row that supplied it,
     * so a conversion can retain the rate and its effective date (Req 9.2.6).
     *
     * @param rate          the conversion factor (one {@code fromCurrency} unit
     *                      expressed in {@code toCurrency})
     * @param effectiveDate the effective date of the rate that was used
     */
    record RateLookup(BigDecimal rate, LocalDate effectiveDate) {
    }
}
