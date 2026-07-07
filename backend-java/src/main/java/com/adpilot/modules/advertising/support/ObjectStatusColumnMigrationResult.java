package com.adpilot.modules.advertising.support;

/**
 * The summary of migrating one Object_Status column to the canonical vocabulary (Requirement 16.7):
 * how many values were normalized from a legacy value, how many were already canonical and left
 * unchanged, and how many were flagged for manual review because they were unknown/unrecognized and
 * never auto-mapped (Requirement 16.6).
 *
 * @param table      the migrated table
 * @param column     the migrated column
 * @param normalized count of values rewritten to a canonical value via the fixed legacy mapping
 * @param unchanged  count of values already canonical (or null) and left as-is
 * @param flagged    count of unknown values recorded in the exception list, never auto-mapped
 */
public record ObjectStatusColumnMigrationResult(String table,
                                                String column,
                                                int normalized,
                                                int unchanged,
                                                int flagged) {

    /** @return total number of non-null values examined in this column. */
    public int examined() {
        return normalized + unchanged + flagged;
    }
}
