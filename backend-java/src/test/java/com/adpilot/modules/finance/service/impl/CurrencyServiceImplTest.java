package com.adpilot.modules.finance.service.impl;

import com.adpilot.modules.finance.service.ExchangeRateSource;
import com.adpilot.modules.finance.service.ExchangeRateSource.RateLookup;
import com.adpilot.modules.finance.vo.ConvertedAmount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CurrencyServiceImpl} covering multi-currency
 * normalization behavior (Req 9.2.1, 9.2.2, 9.2.4, 9.2.6).
 */
@ExtendWith(MockitoExtension.class)
class CurrencyServiceImplTest {

    @Mock
    private ExchangeRateSource exchangeRateSource;

    @InjectMocks
    private CurrencyServiceImpl currencyService;

    private static final LocalDate DATE = LocalDate.of(2024, 6, 15);

    @Test
    void convertsUsingRateEffectiveOnTheDate() {
        // Req 9.2.1: convert using the rate effective on the date.
        LocalDate effective = LocalDate.of(2024, 6, 10);
        when(exchangeRateSource.findRate(eq("USD"), eq("EUR"), eq(DATE)))
                .thenReturn(Optional.of(new RateLookup(new BigDecimal("0.90000000"), effective)));

        ConvertedAmount result =
                currencyService.convert(new BigDecimal("100.00"), "USD", "EUR", DATE);

        assertThat(result.unconverted()).isFalse();
        // Req 9.2.2: retain original and converted amounts.
        assertThat(result.original()).isEqualByComparingTo("100.00");
        assertThat(result.fromCurrency()).isEqualTo("USD");
        assertThat(result.converted()).isEqualByComparingTo("90.0000");
        // Req 9.2.6: retain rate value and effective date used.
        assertThat(result.rate()).isEqualByComparingTo("0.90000000");
        assertThat(result.rateDate()).isEqualTo(effective);
    }

    @Test
    void flagsUnconvertedWhenNoRateAvailable() {
        // Req 9.2.4: no rate -> flag unconverted, do not apply an undefined rate.
        when(exchangeRateSource.findRate(any(), any(), any())).thenReturn(Optional.empty());

        ConvertedAmount result =
                currencyService.convert(new BigDecimal("100.00"), "USD", "JPY", DATE);

        assertThat(result.unconverted()).isTrue();
        assertThat(result.original()).isEqualByComparingTo("100.00");
        assertThat(result.fromCurrency()).isEqualTo("USD");
        assertThat(result.converted()).isNull();
        assertThat(result.rate()).isNull();
        assertThat(result.rateDate()).isNull();
    }

    @Test
    void identityConversionForSameCurrency() {
        ConvertedAmount result =
                currencyService.convert(new BigDecimal("42.50"), "USD", "usd", DATE);

        assertThat(result.unconverted()).isFalse();
        assertThat(result.converted()).isEqualByComparingTo("42.50");
        assertThat(result.rate()).isEqualByComparingTo("1");
        assertThat(result.rateDate()).isEqualTo(DATE);
    }

    @Test
    void normalizesCurrencyCasingBeforeLookup() {
        LocalDate effective = LocalDate.of(2024, 6, 1);
        when(exchangeRateSource.findRate(eq("USD"), eq("EUR"), eq(DATE)))
                .thenReturn(Optional.of(new RateLookup(new BigDecimal("0.5"), effective)));

        ConvertedAmount result =
                currencyService.convert(new BigDecimal("10"), "usd", "eur", DATE);

        assertThat(result.fromCurrency()).isEqualTo("USD");
        assertThat(result.converted()).isEqualByComparingTo("5.0000");
    }

    @Test
    void rejectsMissingArguments() {
        assertThatThrownBy(() -> currencyService.convert(null, "USD", "EUR", DATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> currencyService.convert(BigDecimal.ONE, " ", "EUR", DATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> currencyService.convert(BigDecimal.ONE, "USD", null, DATE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
