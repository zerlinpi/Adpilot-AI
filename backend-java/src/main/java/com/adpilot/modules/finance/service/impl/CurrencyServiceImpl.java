package com.adpilot.modules.finance.service.impl;

import com.adpilot.modules.finance.service.CurrencyService;
import com.adpilot.modules.finance.service.ExchangeRateSource;
import com.adpilot.modules.finance.vo.ConvertedAmount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Default {@link CurrencyService}. A pure conversion service over a configured
 * {@link ExchangeRateSource} (Req 9.2.5).
 *
 * <ul>
 *   <li>Uses the rate effective on the supplied date (Req 9.2.1).</li>
 *   <li>Retains the original and converted amounts (Req 9.2.2) and the rate
 *       value plus its effective date (Req 9.2.6).</li>
 *   <li>Flags the amount as unconverted when no rate is available rather than
 *       applying an undefined rate (Req 9.2.4).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CurrencyServiceImpl implements CurrencyService {

    /** Scale used for converted amounts; matches the 4-dp monetary columns. */
    private static final int MONEY_SCALE = 4;

    private final ExchangeRateSource exchangeRateSource;

    @Override
    public ConvertedAmount convert(BigDecimal amount, String fromCurrency, String toCurrency, LocalDate date) {
        if (amount == null || !StringUtils.hasText(fromCurrency) || !StringUtils.hasText(toCurrency)) {
            throw new IllegalArgumentException("amount, fromCurrency, and toCurrency are required");
        }

        String from = fromCurrency.trim().toUpperCase();
        String to = toCurrency.trim().toUpperCase();

        // Same currency: identity conversion at rate 1 effective on the date.
        if (from.equals(to)) {
            return ConvertedAmount.converted(amount, from, amount, BigDecimal.ONE, date);
        }

        Optional<ExchangeRateSource.RateLookup> lookup =
                exchangeRateSource.findRate(from, to, date);

        // Req 9.2.4: no rate available -> flag unconverted, do not guess.
        if (lookup.isEmpty()) {
            return ConvertedAmount.unconverted(amount, from);
        }

        ExchangeRateSource.RateLookup rate = lookup.get();
        BigDecimal converted = amount.multiply(rate.rate())
                .setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);

        // Req 9.2.2 / 9.2.6: retain original, converted, rate value, and effective date.
        return ConvertedAmount.converted(amount, from, converted, rate.rate(), rate.effectiveDate());
    }
}
