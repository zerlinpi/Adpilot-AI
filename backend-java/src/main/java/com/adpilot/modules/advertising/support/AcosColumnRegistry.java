package com.adpilot.modules.advertising.support;

import java.util.List;

/**
 * The fixed, internal catalogue of advertising-module ACoS columns and each column's
 * {@link AcosColumnScale known source semantics}, used to drive the per-column historical migration
 * to the canonical decimal-ratio scale (Requirement 17.5).
 *
 * <p>The semantics below are derived from the verified current behaviour of the codebase:</p>
 * <ul>
 *   <li><strong>Decimal-ratio columns</strong> — the configured target/break-even ACoS fields on
 *       {@code goals} and {@code products} already store decimal ratios (their seed values are
 *       {@code 0.22}, {@code 0.15}, {@code 0.7166}, etc.).</li>
 *   <li><strong>Percentage columns</strong> — {@code campaigns.target_acos} is annotated in the
 *       schema as a percentage, and the per-record metric {@code acos} columns are produced/compared
 *       on a percentage scale by {@code AdMetrics.acos} (which multiplies by 100) and the AI hosting
 *       optimizer (which treats a {@code target_acos} of {@code 25} as 25%). These are divided by 100
 *       during migration.</li>
 * </ul>
 *
 * <p>A column whose source scale cannot be established should be registered as
 * {@link AcosColumnScale#UNKNOWN} so its values are flagged for manual review rather than guessed
 * (Requirement 17.6). All entries here are compile-time constants and contain no user input, so the
 * {@code (table, column)} pairs are safe to interpolate into migration SQL.</p>
 */
public final class AcosColumnRegistry {

    private static final List<AcosColumn> COLUMNS = List.of(
            // --- Configured target / break-even ACoS: already decimal ratios ---
            new AcosColumn("goals", "target_acos", AcosColumnScale.DECIMAL_RATIO),
            new AcosColumn("products", "target_acos", AcosColumnScale.DECIMAL_RATIO),
            new AcosColumn("products", "break_even_acos", AcosColumnScale.DECIMAL_RATIO),

            // --- Campaign hosting target: schema-annotated percentage ---
            new AcosColumn("campaigns", "target_acos", AcosColumnScale.PERCENT),

            // --- Per-record performance metrics: computed/compared on a percentage scale ---
            new AcosColumn("campaigns", "acos", AcosColumnScale.PERCENT),
            new AcosColumn("keywords", "acos", AcosColumnScale.PERCENT),
            new AcosColumn("targets", "acos", AcosColumnScale.PERCENT),
            new AcosColumn("search_terms", "acos", AcosColumnScale.PERCENT),
            new AcosColumn("performance_daily", "acos", AcosColumnScale.PERCENT),
            new AcosColumn("keyword_coverage", "acos", AcosColumnScale.PERCENT),
            new AcosColumn("keyword_ngrams", "acos", AcosColumnScale.PERCENT),
            new AcosColumn("keyword_ngrams", "avg_acos", AcosColumnScale.PERCENT));

    private AcosColumnRegistry() {
        // Static catalogue — not instantiable.
    }

    /**
     * @return the immutable, ordered list of ACoS columns to migrate, each paired with its known
     *         source semantics. Migration processes the columns in this order.
     */
    public static List<AcosColumn> columns() {
        return COLUMNS;
    }
}
