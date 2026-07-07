package com.adpilot.modules.advertising.support;

import java.util.List;

/**
 * The aggregate result of running the per-column ACoS historical migration across every column in
 * the {@link AcosColumnRegistry registry} (Requirement 17.5).
 *
 * @param columns per-column migration summaries, in registry order
 */
public record AcosMigrationReport(List<AcosColumnMigrationResult> columns) {

    public AcosMigrationReport {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** @return total values converted to the canonical decimal-ratio scale across all columns. */
    public int totalConverted() {
        return columns.stream().mapToInt(AcosColumnMigrationResult::converted).sum();
    }

    /** @return total values already canonical and left unchanged across all columns. */
    public int totalUnchanged() {
        return columns.stream().mapToInt(AcosColumnMigrationResult::unchanged).sum();
    }

    /** @return total ambiguous/unknown values flagged for manual review across all columns. */
    public int totalFlagged() {
        return columns.stream().mapToInt(AcosColumnMigrationResult::flagged).sum();
    }
}
