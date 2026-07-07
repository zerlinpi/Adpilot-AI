package com.adpilot.modules.advertising.support;

/**
 * The summary of migrating one ACoS column (Requirement 17.5): how many values were converted,
 * how many were already canonical and left unchanged, and how many were flagged for manual review
 * because they were ambiguous under the column's known semantics (Requirement 17.6).
 *
 * @param table     the migrated table
 * @param column    the migrated column
 * @param scale     the known source semantics applied
 * @param converted count of values rewritten to the canonical decimal-ratio scale
 * @param unchanged count of values already canonical (or null/zero) and left as-is
 * @param flagged   count of ambiguous/unknown values recorded in the exception list, never guessed
 */
public record AcosColumnMigrationResult(String table,
                                        String column,
                                        AcosColumnScale scale,
                                        int converted,
                                        int unchanged,
                                        int flagged) {

    /** @return total number of non-null values examined in this column. */
    public int examined() {
        return converted + unchanged + flagged;
    }
}
