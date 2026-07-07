package com.adpilot.modules.advertising.support;

import java.util.List;

/**
 * The aggregate result of running the Object_Status vocabulary migration across every column in the
 * {@link ObjectStatusColumnRegistry registry} (Requirement 16.7).
 *
 * @param columns per-column migration summaries, in registry order
 */
public record ObjectStatusMigrationReport(List<ObjectStatusColumnMigrationResult> columns) {

    public ObjectStatusMigrationReport {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** @return total values normalized to a canonical value across all columns. */
    public int totalNormalized() {
        return columns.stream().mapToInt(ObjectStatusColumnMigrationResult::normalized).sum();
    }

    /** @return total values already canonical and left unchanged across all columns. */
    public int totalUnchanged() {
        return columns.stream().mapToInt(ObjectStatusColumnMigrationResult::unchanged).sum();
    }

    /** @return total unknown values flagged for manual review across all columns. */
    public int totalFlagged() {
        return columns.stream().mapToInt(ObjectStatusColumnMigrationResult::flagged).sum();
    }
}
