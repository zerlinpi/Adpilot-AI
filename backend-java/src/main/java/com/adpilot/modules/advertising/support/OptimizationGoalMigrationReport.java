package com.adpilot.modules.advertising.support;

import java.util.List;

/**
 * The aggregate result of running the Optimization_Goal enum migration across every column in the
 * {@link OptimizationGoalColumnRegistry registry} (Requirement 57.2).
 *
 * @param columns per-column migration summaries, in registry order
 */
public record OptimizationGoalMigrationReport(List<OptimizationGoalColumnMigrationResult> columns) {

    public OptimizationGoalMigrationReport {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** @return total values normalized to a canonical enum across all columns. */
    public int totalNormalized() {
        return columns.stream().mapToInt(OptimizationGoalColumnMigrationResult::normalized).sum();
    }

    /** @return total values already canonical and left unchanged across all columns. */
    public int totalUnchanged() {
        return columns.stream().mapToInt(OptimizationGoalColumnMigrationResult::unchanged).sum();
    }

    /** @return total unmappable values flagged for manual review across all columns. */
    public int totalFlagged() {
        return columns.stream().mapToInt(OptimizationGoalColumnMigrationResult::flagged).sum();
    }
}
