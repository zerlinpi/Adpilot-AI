package com.adpilot.modules.advertising.support;

import com.adpilot.modules.finance.vo.ConvertedAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * Pure, side-effect-free derivation of a Recommendation savings figure from
 * <strong>structured</strong> monetary amounts (Requirement 19.2, 19.3).
 *
 * <p>The savings figure is summed from already currency-normalized
 * {@link ConvertedAmount} components — the structured output of the multi-currency
 * normalization service (Req 9.2) — and is <strong>never</strong> parsed from a
 * Recommendation's free-text description. Because the input is a list of typed
 * monetary values rather than a string, there is no code path through which a
 * savings figure could be recovered from text.</p>
 *
 * <p>Where the contributing amounts originate in more than one currency they are
 * expected to have already been converted to the Store's single reporting
 * currency before reaching this class (Req 19.3). If <em>any</em> component
 * could not be converted (no rate available — {@link ConvertedAmount#unconverted()}),
 * the figure is reported as <em>not derivable</em> rather than silently summing a
 * partial, mixed-currency total.</p>
 *
 * <p>All methods are <strong>total</strong>: {@code null} and empty component
 * lists produce defined results and no method throws on data shape (only a
 * missing reporting currency, a programming error, is rejected). This class is
 * the single source of truth targeted by the structured-savings property test
 * (Property 45, task 14.23) and consumed by the recommendation savings
 * computation (task 14.10).</p>
 */
public final class SavingsDerivation {

    /** Scale (decimal places) of the derived savings figure. */
    public static final int MONEY_SCALE = 2;

    private SavingsDerivation() {
        // Utility class — not instantiable.
    }

    /**
     * Outcome of deriving a savings figure. When {@link #derivable()} is
     * {@code false} the {@link #amount()} is {@code null}: at least one
     * contributing amount could not be normalized to the reporting currency, so
     * no partial figure is fabricated (Requirement 19.3).
     *
     * @param amount            the derived savings amount in
     *                          {@link #reportingCurrency()}, or {@code null}
     *                          when {@link #derivable()} is {@code false}
     * @param reportingCurrency the single reporting currency the figure is
     *                          expressed in (upper-cased ISO code)
     * @param derivable         whether every contributing amount was normalized
     *                          and the figure could be produced
     */
    public record SavingsResult(BigDecimal amount, String reportingCurrency, boolean derivable) {

        /** Builds a not-derivable result (a component could not be converted). */
        public static SavingsResult notDerivable(String reportingCurrency) {
            return new SavingsResult(null, reportingCurrency, false);
        }
    }

    /**
     * Derives a savings figure by summing the {@link ConvertedAmount#converted()}
     * values of {@code components}, all of which must already be normalized to
     * {@code reportingCurrency} (Requirement 19.2, 19.3).
     *
     * <ul>
     *   <li>A {@code null} or empty component list yields a derivable zero.</li>
     *   <li>If any component is {@code null}, flagged
     *       {@link ConvertedAmount#unconverted()}, or carries a {@code null}
     *       converted value, the result is {@link SavingsResult#notDerivable}.</li>
     *   <li>Otherwise the converted amounts are summed and rounded to
     *       {@link #MONEY_SCALE} decimals.</li>
     * </ul>
     *
     * @param components        the structured, currency-normalized contributing
     *                          amounts (never description text)
     * @param reportingCurrency the Store's single reporting currency
     * @return the derived savings figure, or a not-derivable result
     */
    public static SavingsResult derive(List<ConvertedAmount> components, String reportingCurrency) {
        if (reportingCurrency == null || reportingCurrency.isBlank()) {
            throw new IllegalArgumentException("reportingCurrency is required");
        }
        String currency = reportingCurrency.trim().toUpperCase(Locale.ROOT);

        if (components == null || components.isEmpty()) {
            return new SavingsResult(BigDecimal.ZERO.setScale(MONEY_SCALE), currency, true);
        }

        BigDecimal total = BigDecimal.ZERO;
        for (ConvertedAmount component : components) {
            if (component == null || component.unconverted() || component.converted() == null) {
                // Cannot normalize every contributing amount -> do not fabricate a partial figure.
                return SavingsResult.notDerivable(currency);
            }
            total = total.add(component.converted());
        }
        return new SavingsResult(total.setScale(MONEY_SCALE, RoundingMode.HALF_UP), currency, true);
    }
}
