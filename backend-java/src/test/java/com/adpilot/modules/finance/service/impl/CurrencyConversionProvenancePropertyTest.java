package com.adpilot.modules.finance.service.impl;

import com.adpilot.modules.finance.service.ExchangeRateSource;
import com.adpilot.modules.finance.service.ExchangeRateSource.RateLookup;
import com.adpilot.modules.finance.vo.ConvertedAmount;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.time.api.Dates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link CurrencyServiceImpl#convert}.
 *
 * Feature: core-platform-completion, Property 20: Currency conversion uses the
 * date-effective rate and retains full provenance.
 *
 * <p>For any conversion, the result uses the rate effective on the date and
 * retains the original amount, converted amount, rate value, and effective
 * date; when no rate exists the amount is flagged unconverted.</p>
 *
 * <p>The {@link ExchangeRateSource} is replaced with a Mockito stub so rate
 * availability and the rate/effective-date returned can be controlled
 * precisely, exercising the conversion and provenance logic without a
 * database.</p>
 *
 * Validates: Requirements 9.2.1, 9.2.2, 9.2.4, 9.2.6
 */
class CurrencyConversionProvenancePropertyTest {

    /** Scale used for converted amounts; mirrors {@link CurrencyServiceImpl}. */
    private static final int MONEY_SCALE = 4;

    /** A conversion request together with a rate that is available for it. */
    record ConvertibleScenario(BigDecimal amount,
                               String fromCurrency,
                               String toCurrency,
                               LocalDate date,
                               BigDecimal rate,
                               LocalDate effectiveDate) {
    }

    /** A conversion request for which no rate is available. */
    record UnconvertibleScenario(BigDecimal amount,
                                 String fromCurrency,
                                 String toCurrency,
                                 LocalDate date) {
    }

    // Feature: core-platform-completion, Property 20: Currency conversion uses the date-effective rate and retains full provenance
    @Property(tries = 200)
    void convertsWithDateEffectiveRateAndRetainsProvenance(@ForAll("convertibleScenarios") ConvertibleScenario s) {
        ExchangeRateSource source = mock(ExchangeRateSource.class);
        String from = s.fromCurrency().trim().toUpperCase();
        String to = s.toCurrency().trim().toUpperCase();
        // Req 9.2.1 / 9.2.5: the rate effective on the date is obtained from the configured source.
        when(source.findRate(eq(from), eq(to), eq(s.date())))
                .thenReturn(Optional.of(new RateLookup(s.rate(), s.effectiveDate())));

        CurrencyServiceImpl service = new CurrencyServiceImpl(source);

        ConvertedAmount result = service.convert(s.amount(), s.fromCurrency(), s.toCurrency(), s.date());

        BigDecimal expectedConverted = s.amount().multiply(s.rate()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // A rate exists -> a converted result, not flagged unconverted (Req 9.2.4).
        assertThat(result.unconverted()).isFalse();
        // converted = original * rate, scaled to the monetary scale (Req 9.2.1).
        assertThat(result.converted()).isEqualByComparingTo(expectedConverted);
        // Full provenance retained: original amount and source currency (Req 9.2.2).
        assertThat(result.original()).isEqualByComparingTo(s.amount());
        assertThat(result.fromCurrency()).isEqualTo(from);
        // Rate value and its effective date retained (Req 9.2.6).
        assertThat(result.rate()).isEqualByComparingTo(s.rate());
        assertThat(result.rateDate()).isEqualTo(s.effectiveDate());
    }

    // Feature: core-platform-completion, Property 20: Currency conversion uses the date-effective rate and retains full provenance
    @Property(tries = 200)
    void flagsUnconvertedWhenNoRateAndRetainsOriginal(@ForAll("unconvertibleScenarios") UnconvertibleScenario s) {
        ExchangeRateSource source = mock(ExchangeRateSource.class);
        // No rate for the pair/date.
        when(source.findRate(eq(s.fromCurrency().trim().toUpperCase()),
                eq(s.toCurrency().trim().toUpperCase()), eq(s.date())))
                .thenReturn(Optional.empty());

        CurrencyServiceImpl service = new CurrencyServiceImpl(source);

        ConvertedAmount result = service.convert(s.amount(), s.fromCurrency(), s.toCurrency(), s.date());

        // Req 9.2.4: no rate -> flag unconverted, do not apply an undefined rate.
        assertThat(result.unconverted()).isTrue();
        assertThat(result.converted()).isNull();
        assertThat(result.rate()).isNull();
        assertThat(result.rateDate()).isNull();
        // Original amount and currency are still retained (Req 9.2.2).
        assertThat(result.original()).isEqualByComparingTo(s.amount());
        assertThat(result.fromCurrency()).isEqualTo(s.fromCurrency().trim().toUpperCase());
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<ConvertibleScenario> convertibleScenarios() {
        return Combinators.combine(
                        amounts(),
                        currencyPairs(),
                        dates(),
                        rates(),
                        dates())
                .as((amount, pair, date, rate, effectiveDate) ->
                        new ConvertibleScenario(amount, pair[0], pair[1], date, rate, effectiveDate));
    }

    @Provide
    Arbitrary<UnconvertibleScenario> unconvertibleScenarios() {
        return Combinators.combine(amounts(), currencyPairs(), dates())
                .as((amount, pair, date) ->
                        new UnconvertibleScenario(amount, pair[0], pair[1], date));
    }

    /** Non-negative monetary amounts up to ~10,000,000.0000 with up to 4 dp. */
    private Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs().between(0L, 100_000_000_000L)
                .map(l -> new BigDecimal(l).movePointLeft(MONEY_SCALE));
    }

    /** Strictly positive exchange rates with up to 8 dp (matching rate columns). */
    private Arbitrary<BigDecimal> rates() {
        return Arbitraries.longs().between(1L, 1_000_000_000L)
                .map(l -> new BigDecimal(l).movePointLeft(6));
    }

    /**
     * A distinct currency-code pair {@code [from, to]}, occasionally with mixed
     * casing/whitespace so the service's normalization is exercised. Distinctness
     * forces the rate-lookup path rather than the same-currency identity branch.
     */
    private Arbitrary<String[]> currencyPairs() {
        Arbitrary<String> codes = Arbitraries.of("USD", "EUR", "GBP", "CNY", "JPY", "AUD", "CAD", "usd", " eur ");
        return Combinators.combine(codes, codes)
                .as((a, b) -> new String[]{a, b})
                .filter(p -> !p[0].trim().equalsIgnoreCase(p[1].trim()));
    }

    private Arbitrary<LocalDate> dates() {
        return Dates.dates().between(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31));
    }
}
