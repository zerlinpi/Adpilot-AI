package com.adpilot.modules.advertising.support;

import java.util.Objects;

/**
 * The pure result of classifying a single stored legacy goal-type / hosting-goal value against the
 * canonical Optimization_Goal enum and the fixed legacy mapping (Requirement 57.2, 57.3).
 *
 * <p>An outcome is exactly one of three {@link Kind kinds}:</p>
 * <ul>
 *   <li>{@link Kind#UNCHANGED} — the value is already a canonical Optimization_Goal and is kept as-is;
 *       {@link #value()} is the canonical value to keep (possibly {@code null} when the input was
 *       {@code null}).</li>
 *   <li>{@link Kind#NORMALIZED} — the value is a known legacy goal-type / hosting-goal value and is
 *       mapped to its canonical form via the fixed mapping (for example {@code profit → profit_first});
 *       {@link #value()} is the canonical value to store.</li>
 *   <li>{@link Kind#FLAGGED} — the value does not map to any of the four Optimization_Goal machine
 *       enums and MUST be written to the migration exception list for manual review rather than
 *       auto-mapped to an arbitrary value (Requirement 57.3); {@link #reason()} explains why and
 *       {@link #value()} is {@code null}.</li>
 * </ul>
 *
 * <p>This is an immutable value object with no persistence or framework concerns, so the
 * normalization classifier ({@link OptimizationGoalVocabulary#normalize}) can be property-tested in
 * isolation (migration-never-guessing property, task 14.18).</p>
 */
public final class OptimizationGoalMigrationOutcome {

    /** The three mutually exclusive classifications of a migrated Optimization_Goal value. */
    public enum Kind {
        /** Value is already a canonical Optimization_Goal (or null) and is kept unchanged. */
        UNCHANGED,
        /** Value is a known legacy goal-type / hosting-goal value and has been normalized to its canonical form. */
        NORMALIZED,
        /** Value does not map to any canonical enum and is flagged for manual review, never auto-mapped. */
        FLAGGED
    }

    private final Kind kind;
    private final String value;
    private final String reason;

    private OptimizationGoalMigrationOutcome(Kind kind, String value, String reason) {
        this.kind = kind;
        this.value = value;
        this.reason = reason;
    }

    /** @return an {@link Kind#UNCHANGED} outcome carrying the (possibly {@code null}) canonical value to keep. */
    public static OptimizationGoalMigrationOutcome unchanged(String value) {
        return new OptimizationGoalMigrationOutcome(Kind.UNCHANGED, value, null);
    }

    /** @return a {@link Kind#NORMALIZED} outcome carrying the canonical value mapped from a legacy value. */
    public static OptimizationGoalMigrationOutcome normalized(String value) {
        return new OptimizationGoalMigrationOutcome(Kind.NORMALIZED, Objects.requireNonNull(value, "value"), null);
    }

    /** @return a {@link Kind#FLAGGED} outcome carrying the human-readable reason for manual review. */
    public static OptimizationGoalMigrationOutcome flagged(String reason) {
        return new OptimizationGoalMigrationOutcome(Kind.FLAGGED, null, Objects.requireNonNull(reason, "reason"));
    }

    public Kind kind() {
        return kind;
    }

    /**
     * @return the resulting canonical value for {@link Kind#UNCHANGED} (possibly {@code null}) and
     *         {@link Kind#NORMALIZED} outcomes; always {@code null} for {@link Kind#FLAGGED}.
     */
    public String value() {
        return value;
    }

    /** @return the manual-review reason for a {@link Kind#FLAGGED} outcome; {@code null} otherwise. */
    public String reason() {
        return reason;
    }

    /** @return {@code true} when this value is unmappable and must be recorded in the exception list. */
    public boolean isFlagged() {
        return kind == Kind.FLAGGED;
    }

    /** @return {@code true} when this outcome changes the stored value (a {@link Kind#NORMALIZED} result). */
    public boolean isWrite() {
        return kind == Kind.NORMALIZED;
    }

    @Override
    public String toString() {
        return "OptimizationGoalMigrationOutcome{kind=" + kind + ", value=" + value + ", reason=" + reason + '}';
    }
}
