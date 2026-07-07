package com.adpilot.modules.advertising.support;

/**
 * The summary of migrating one legacy goal-type / hosting-goal column to the canonical
 * Optimization_Goal enum (Requirement 57.2): how many values were normalized from a legacy value, how
 * many were already canonical and left unchanged, and how many were flagged for manual review because
 * they did not map to any of the four enums and were never auto-mapped (Requirement 57.3).
 *
 * @param table      the migrated table
 * @param column     the migrated column
 * @param normalized count of values rewritten to a canonical enum via the fixed legacy mapping
 * @param unchanged  count of values already canonical (or null) and left as-is
 * @param flagged    count of unmappable values recorded in the exception list, never auto-mapped
 */
public record OptimizationGoalColumnMigrationResult(String table,
                                                    String column,
                                                    int normalized,
                                                    int unchanged,
                                                    int flagged) {

    /** @return total number of non-null values examined in this column. */
    public int examined() {
        return normalized + unchanged + flagged;
    }
}
