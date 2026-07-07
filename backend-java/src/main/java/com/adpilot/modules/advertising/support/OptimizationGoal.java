package com.adpilot.modules.advertising.support;

import java.util.Optional;

/**
 * Optimization_Goal — the objective the AI optimizer aims for, surfaced as 优化目标 (formerly 托管目标),
 * expressed with the single canonical machine enum {@code profit_first | sales_growth | rank |
 * clearance} (Requirement 57.1, 57.4).
 *
 * <p>Per Requirement 48/57 the Backend contract carries only these stable lowercase machine values
 * and never a Chinese display string; the Frontend owns the display translation (Requirement 48.4,
 * 57.4).</p>
 *
 * <p>{@link #parse(String)} is intentionally LENIENT-by-rejection: it only recognizes the four
 * canonical values (case-insensitively, after trimming) and returns {@link Optional#empty()} for a
 * {@code null}, blank, or non-canonical value, so callers never have to guess. The legacy
 * goal-type / hosting-goal normalization mapping (for example {@code profit → profit_first}) lives in
 * {@link OptimizationGoalVocabulary}, which is the single authority for migrating historical values.</p>
 *
 * <p>Validates: Requirements 57.1, 57.4.</p>
 */
public enum OptimizationGoal {

    PROFIT_FIRST("profit_first"),
    SALES_GROWTH("sales_growth"),
    RANK("rank"),
    CLEARANCE("clearance");

    private final String machineValue;

    OptimizationGoal(String machineValue) {
        this.machineValue = machineValue;
    }

    /** The canonical lowercase machine value persisted and exchanged on the API contract (Req 57.1, 57.4). */
    public String machineValue() {
        return machineValue;
    }

    /**
     * Parse a stored/raw value into a canonical {@link OptimizationGoal}.
     *
     * @param value a raw value (may be {@code null}, blank, or non-canonical)
     * @return the matching goal, or {@link Optional#empty()} when the value is absent or is not one of
     *     the four canonical machine values
     */
    public static Optional<OptimizationGoal> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (OptimizationGoal goal : values()) {
            if (goal.machineValue.equals(normalized)) {
                return Optional.of(goal);
            }
        }
        return Optional.empty();
    }
}
