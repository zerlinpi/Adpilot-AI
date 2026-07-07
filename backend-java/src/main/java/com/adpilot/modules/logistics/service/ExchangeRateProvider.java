package com.adpilot.modules.logistics.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Strategy for resolving the documented Store Reporting_Currency exchange rate
 * for a currency pair effective on a given date (Req 10.4).
 *
 * <p>This is the in-repository "documented rate" source — it does not call any
 * external FX integration. The {@link CostChainService} consults it only when a
 * cost component does not carry its own per-component exchange rate. When no
 * documented rate is available, callers treat the conversion as a pass-through
 * (rate {@code 1}); see {@code CostChainService.compute}.</p>
 */
public interface ExchangeRateProvider {

    /**
     * Resolve the rate that converts an amount in {@code baseCurrency} into
     * {@code quoteCurrency}, using the documented rate effective on or before
     * {@code effectiveDate} (the most recent such rate). When
     * {@code effectiveDate} is {@code null} the latest documented rate for the
     * pair is used.
     *
     * @param baseCurrency  the source (original) currency code
     * @param quoteCurrency the target (reporting) currency code
     * @param effectiveDate the component's recorded cost date, or {@code null}
     * @return the documented rate, or {@link Optional#empty()} when none is
     * documented for the pair on/before the effective date
     */
    Optional<BigDecimal> findRate(String baseCurrency, String quoteCurrency, LocalDate effectiveDate);
}
