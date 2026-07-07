package com.adpilot.modules.advertising.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single, authoritative encoding of the Optimization_Goal migration convention (Requirement 57):
 * the AI optimizer's objective is represented with exactly the canonical machine enum
 * {@code profit_first | sales_growth | rank | clearance}, and historical goal-type / hosting-goal
 * values are normalized on migration using a <strong>fixed, explicit</strong> legacy mapping. A legacy
 * value that does not have an unambiguous correspondence to one of the four enums is
 * <strong>never auto-mapped</strong> to an arbitrary value; it is flagged for manual review instead
 * (Requirement 57.3).
 *
 * <p>This is a <strong>pure</strong>, stateless utility: every method is side-effect free and free of
 * persistence, JSON, or framework concerns, so it can be unit- and property-tested in isolation
 * (migration-never-guessing property, task 14.18). It is the one place that defines:</p>
 * <ul>
 *   <li>the canonical enum ({@link #canonicalValues()}, {@link #isCanonical}),</li>
 *   <li>the fixed legacy-to-canonical mapping ({@link #LEGACY_MAPPING}), and</li>
 *   <li>the migration classifier that normalizes a known value and flags an unmappable one
 *       ({@link #normalize}).</li>
 * </ul>
 *
 * <p><strong>The explicit migration mapping (Requirement 57.1, 57.2).</strong> The existing system
 * stores two legacy vocabularies that flow into the migrated field: the {@code goals.type} goal-type
 * values ({@code launch}, {@code profit}, {@code brand_defense}, {@code competitor}, {@code category},
 * {@code clearance}, {@code rank_boost}) and the {@code campaigns.hosting_goal} hosting-goal value
 * ({@code maximize_sales_at_target}); goal-type values can also reach {@code hosting_goal} through
 * goal→campaign propagation. Only the legacy values whose objective maps unambiguously onto one of the
 * four canonical enums are normalized:</p>
 * <ul>
 *   <li>{@code profit} → {@code profit_first} — an explicit profit objective.</li>
 *   <li>{@code launch} → {@code sales_growth} — a launch drives sales volume/growth.</li>
 *   <li>{@code maximize_sales_at_target} → {@code sales_growth} — maximizing sales at a target ACoS.</li>
 *   <li>{@code rank_boost} → {@code rank} — a ranking objective.</li>
 *   <li>{@code clearance} → {@code clearance} — already the canonical value (kept unchanged).</li>
 * </ul>
 *
 * <p>The remaining legacy goal-types {@code brand_defense}, {@code competitor}, and {@code category}
 * describe a <em>targeting strategy</em> rather than one of the four optimization objectives, so they
 * have no unambiguous canonical correspondence and are deliberately <strong>not</strong> in the
 * mapping: they (and any other unknown value) are flagged for manual review rather than guessed
 * (Requirement 57.3).</p>
 *
 * <p>Validates: Requirements 57.1, 57.2, 57.3.</p>
 */
public final class OptimizationGoalVocabulary {

    /**
     * The fixed legacy-to-canonical mapping (Requirement 57.1, 57.2). Keys are matched
     * case-insensitively after trimming; only these legacy values are recognized. The canonical
     * {@code clearance} is included so a value stored in non-canonical case still normalizes cleanly;
     * a value already in exact canonical form is kept unchanged by {@link #normalize} before the map
     * is consulted.
     */
    public static final Map<String, OptimizationGoal> LEGACY_MAPPING;

    static {
        // LinkedHashMap to keep a stable, documented iteration order in flag messages and logs.
        Map<String, OptimizationGoal> mapping = new LinkedHashMap<>();
        // Legacy goal-type values (goals.type) with an unambiguous objective.
        mapping.put("profit", OptimizationGoal.PROFIT_FIRST);
        mapping.put("launch", OptimizationGoal.SALES_GROWTH);
        mapping.put("rank_boost", OptimizationGoal.RANK);
        mapping.put("clearance", OptimizationGoal.CLEARANCE);
        // Legacy hosting-goal value (campaigns.hosting_goal).
        mapping.put("maximize_sales_at_target", OptimizationGoal.SALES_GROWTH);
        // Already-canonical enum values, so a non-canonically-cased variant still normalizes.
        mapping.put("profit_first", OptimizationGoal.PROFIT_FIRST);
        mapping.put("sales_growth", OptimizationGoal.SALES_GROWTH);
        mapping.put("rank", OptimizationGoal.RANK);
        LEGACY_MAPPING = Map.copyOf(mapping);
    }

    private static final Set<String> CANONICAL_VALUES =
            Set.of(OptimizationGoal.PROFIT_FIRST.machineValue(),
                    OptimizationGoal.SALES_GROWTH.machineValue(),
                    OptimizationGoal.RANK.machineValue(),
                    OptimizationGoal.CLEARANCE.machineValue());

    private OptimizationGoalVocabulary() {
        // Utility class — not instantiable.
    }

    /** @return the immutable set of canonical Optimization_Goal machine values. */
    public static Set<String> canonicalValues() {
        return CANONICAL_VALUES;
    }

    /**
     * Report whether a value is already a canonical Optimization_Goal machine value (Requirement 57.4).
     * The comparison is exact (canonical values are lowercase), so a non-lowercase or padded variant
     * is not considered canonical and would be normalized.
     *
     * @param value the raw value (may be {@code null})
     * @return {@code true} iff {@code value} is exactly one of {@code profit_first}, {@code
     *         sales_growth}, {@code rank}, or {@code clearance}
     */
    public static boolean isCanonical(String value) {
        return value != null && CANONICAL_VALUES.contains(value);
    }

    /**
     * Classify a single stored legacy goal-type / hosting-goal value against the canonical
     * Optimization_Goal enum and the fixed legacy mapping (Requirement 57.2, 57.3), producing the
     * migration {@link OptimizationGoalMigrationOutcome outcome}. This never guesses: a value is
     * normalized only when it is a recognized canonical or explicitly mapped legacy value, and any
     * other value is flagged for manual review rather than mapped to an arbitrary enum
     * (Requirement 57.3).
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>A {@code null} value migrates {@link OptimizationGoalMigrationOutcome.Kind#UNCHANGED
     *       unchanged} (nothing to normalize).</li>
     *   <li>An already-canonical value stored in its exact canonical form is kept
     *       {@link OptimizationGoalMigrationOutcome.Kind#UNCHANGED unchanged}.</li>
     *   <li>A recognized legacy or non-canonically-cased value (matched case-insensitively after
     *       trimming) is {@link OptimizationGoalMigrationOutcome.Kind#NORMALIZED normalized} to its
     *       canonical form via {@link #LEGACY_MAPPING}.</li>
     *   <li>Any other value — including a blank string — is
     *       {@link OptimizationGoalMigrationOutcome.Kind#FLAGGED flagged} and recorded for manual
     *       review; it is never auto-mapped.</li>
     * </ul>
     *
     * @param raw the historical stored value (may be {@code null})
     * @return the migration outcome, never {@code null}
     */
    public static OptimizationGoalMigrationOutcome normalize(String raw) {
        if (raw == null) {
            return OptimizationGoalMigrationOutcome.unchanged(null);
        }
        // An exact canonical value is kept as-is (no rewrite needed).
        if (isCanonical(raw)) {
            return OptimizationGoalMigrationOutcome.unchanged(raw);
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        OptimizationGoal mapped = LEGACY_MAPPING.get(key);
        if (mapped != null) {
            return OptimizationGoalMigrationOutcome.normalized(mapped.machineValue());
        }
        return OptimizationGoalMigrationOutcome.flagged(
                "unmappable Optimization_Goal value '" + raw + "'; not in the canonical enum "
                        + CANONICAL_VALUES + " or the legacy mapping " + LEGACY_MAPPING.keySet()
                        + "; flagged for manual review rather than auto-mapped to an arbitrary value");
    }

    /**
     * Convenience accessor returning the canonical value for a known (canonical or legacy) input, or
     * {@link Optional#empty()} when the value is unmappable and would be flagged. This never guesses
     * and is the read-time counterpart to {@link #normalize}.
     *
     * @param raw the raw value (may be {@code null})
     * @return the canonical machine value, or empty when absent/unmappable
     */
    public static Optional<String> toCanonical(String raw) {
        OptimizationGoalMigrationOutcome outcome = normalize(raw);
        return outcome.isFlagged() ? Optional.empty() : Optional.ofNullable(outcome.value());
    }
}
