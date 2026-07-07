package com.adpilot.modules.finance.service;

import com.adpilot.modules.finance.vo.ConvertedAmount;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Converts monetary amounts between currencies for multi-currency
 * normalization (Req 9.2). A pure service over a configured
 * {@link ExchangeRateSource}: it returns both the original and converted
 * amounts plus the rate and effective date used, and flags amounts as
 * unconverted rather than guessing when no rate is available.
 */
public interface CurrencyService {

    /**
     * Converts {@code amount} from {@code fromCurrency} to {@code toCurrency}
     * using the exchange rate effective on {@code date} (Req 9.2.1).
     *
     * <p>The result retains the original amount, the converted amount, the rate
     * value, and the rate's effective date (Req 9.2.2, 9.2.6). When the
     * configured source has no rate for the required currency pair and date, the
     * result is flagged unconverted rather than converted with an undefined rate
     * (Req 9.2.4).</p>
     *
     * @param amount       the amount to convert
     * @param fromCurrency the currency the amount is denominated in
     * @param toCurrency   the target reporting currency
     * @param date         the transaction date the rate must be effective on
     * @return the conversion result
     */
    ConvertedAmount convert(BigDecimal amount, String fromCurrency, String toCurrency, LocalDate date);
}
