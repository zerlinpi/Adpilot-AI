package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.support.AcosColumn;
import com.adpilot.modules.advertising.support.AcosColumnMigrationResult;
import com.adpilot.modules.advertising.support.AcosMigrationReport;

/**
 * Per-column historical migration of ACoS values to the canonical decimal-ratio scale
 * (Requirement 17.5). The migration applies each column's known source semantics (it never uses a
 * naive "divide by 100 if greater than 1" rule) and records any value it cannot safely convert in
 * the {@code acos_migration_exceptions} list for manual review rather than guessing its scale
 * (Requirement 17.6).
 */
public interface AcosMigrationService {

    /**
     * Migrate every ACoS column in the registry.
     *
     * @return the aggregate migration report
     */
    AcosMigrationReport migrateAll();

    /**
     * Migrate a single ACoS column using its declared source semantics.
     *
     * @param column the column descriptor (table, column, known scale); must not be {@code null}
     * @return the per-column migration summary
     */
    AcosColumnMigrationResult migrateColumn(AcosColumn column);
}
