package com.adpilot.modules.advertising.support;

import com.adpilot.modules.advertising.support.SavingsDerivation.SavingsResult;
import com.adpilot.modules.finance.vo.ConvertedAmount;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.NotBlank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link SavingsDerivation}.
 *
 * <p>Feature: advertising-workspace-rework, Property 45: Savings are derived from
 * structured multi-currency-normalized amounts.
 *
 * <p>Validates: Requirements 19.2, 19.3.
 *
 * <p>{@link SavingsDerivation} is the single source of truth for a Recommendation's
 * savings figure. Its only input is a list of typed, currency-normalized
 * {@link ConvertedAmount} components plus the Store's single reporting currency —
 * there is <em>no</em> string/description parameter, so a figure can never be parsed
 * from free text (Requirement 19.2). A single property exercises every facet over an
 * arbitrary mix of normalized and unnormalized components:
 * <ul>
 *   <li><b>Structured multi-currency sum</b> — when every component is normalized to
 *       the reporting currency, the figure is exactly the sum of the structured
 *       {@link ConvertedAmount#converted()} values rounded HALF_UP to money scale, and
 *       is reported derivable (Requirement 19.2, 19.3). A null/empty list yields a
 *       derivable zero.</li>
 *   <li><b>No partial mixed-currency total</b> — if any contributing amount could not
 *       be normalized (unconverted, null converted value, or null component), the
 *       figure is not-derivable and carries no fabricated partial amount
 *       (Requirement 19.3).</li>
 *   <li><b>Derived from reporting-currency values only</b> — the figure depends solely
 *       on the structured converted (reporting-currency) values; scrambling each
 *       component's original source amount, source currency, rate, and rate date never
 *       changes the result, so it is never recovered from source-side fields
 *       (Requirement 19.2).</li>
 * </ul>
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 45: Savings are derived from structured multi-currency-normalized amounts")
class SavingsDerivationStructuredPropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 45: Savings are derived from
     * structured multi-currency-normalized amounts.
     *
     * <p>Validates: Requirements 19.2, 19.3.
     */
    @Property(tries = 200)
    void savingsAreDerivedFromStructuredCurrencyNormalizedAmounts(
            @ForAll("componentLists") List<ConvertedAmount> components,
            @ForAll @NotBlank String reportingCurrency) {

        SavingsResult result = SavingsDerivation.derive(components, reportingCurrency);

        String expectedCurrency = reportingCurrency.trim().toUpperCase(Locale.ROOT);
        // The reporting currency is always normalized, regardless of derivability.
        assertThat(result.reportingCurrency()).isEqualTo(expectedCurrency);

        boolean anyUnnormalized = components.stream()
                .anyMatch(c -> c == null || c.unconverted() || c.converted() == null);

        if (anyUnnormalized) {
            // No partial mixed-currency total is ever fabricated (Req 19.3).
            assertThat(result.derivable()).isFalse();
            assertThat(result.amount()).isNull();
            return;
        }

        // Every component normalized: the figure is the structured sum of the
        // reporting-currency converted values, rounded HALF_UP to money scale.
        BigDecimal expected = BigDecimal.ZERO;
        for (ConvertedAmount c : components) {
            expected = expected.add(c.converted());
        }
        expected = expected.setScale(SavingsDerivation.MONEY_SCALE, RoundingMode.HALF_UP);

        assertThat(result.derivable()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo(expected);
        assertThat(result.amount().scale()).isEqualTo(SavingsDerivation.MONEY_SCALE);

        // The figure is derived ONLY from the structured reporting-currency values:
        // scrambling every source-side field (original amount, source currency, rate,
        // rate date) while keeping the converted value yields an identical result, so
        // the amount can never be recovered from source-side data or free text.
        List<ConvertedAmount> scrambled = new ArrayList<>(components.size());
        for (ConvertedAmount c : components) {
            scrambled.add(ConvertedAmount.converted(
                    c.converted().add(BigDecimal.valueOf(9999.99)), // junk original amount
                    "ZZZ",                                          // junk source currency
                    c.converted(),                                  // unchanged reporting value
                    BigDecimal.valueOf(42),                         // junk rate
                    LocalDate.of(1999, 1, 1)));                     // junk rate date
        }
        SavingsResult fromScrambled = SavingsDerivation.derive(scrambled, reportingCurrency);
        assertThat(fromScrambled.derivable()).isTrue();
        assertThat(fromScrambled.amount()).isEqualByComparingTo(result.amount());
    }

    // --- generators --------------------------------------------------------

    /** Money values in a wide range with up to 4 decimals to exercise rounding. */
    private Arbitrary<BigDecimal> money() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(-1_000_000), BigDecimal.valueOf(1_000_000))
                .ofScale(4);
    }

    private Arbitrary<String> currencyCodes() {
        return Arbitraries.of("USD", "EUR", "GBP", "JPY", "CNY", "AUD");
    }

    /** A fully-converted (normalized) component carrying a non-null reporting value. */
    private Arbitrary<ConvertedAmount> convertedComponent() {
        return Combinators.combine(money(), currencyCodes(), money(), money())
                .as((original, from, converted, rate) ->
                        ConvertedAmount.converted(
                                original, from, converted, rate, LocalDate.of(2024, 6, 1)));
    }

    /**
     * A component that is NOT normalized to the reporting currency: either flagged
     * unconverted (no rate available), carrying a null converted value, or null itself.
     */
    private Arbitrary<ConvertedAmount> unnormalizedComponent() {
        Arbitrary<ConvertedAmount> unconverted =
                Combinators.combine(money(), currencyCodes())
                        .as(ConvertedAmount::unconverted);
        Arbitrary<ConvertedAmount> nullConverted =
                Combinators.combine(money(), currencyCodes())
                        .as((original, from) ->
                                new ConvertedAmount(original, from, null, null, null, false));
        Arbitrary<ConvertedAmount> nullComponent = Arbitraries.just(null);
        return Arbitraries.oneOf(unconverted, nullConverted, nullComponent);
    }

    /**
     * An arbitrary mix of normalized and (possibly) unnormalized components in random
     * order: this single generator drives both the derivable and the not-derivable
     * branches of the property, plus the empty-list case.
     */
    @Provide
    Arbitrary<List<ConvertedAmount>> componentLists() {
        Arbitrary<ConvertedAmount> anyComponent =
                Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(4, convertedComponent()),
                        net.jqwik.api.Tuple.of(1, unnormalizedComponent()));
        return anyComponent.list().ofMinSize(0).ofMaxSize(20);
    }
}
