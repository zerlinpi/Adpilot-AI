package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.support.ObjectStatusColumn;
import com.adpilot.modules.advertising.support.ObjectStatusColumnMigrationResult;
import com.adpilot.modules.advertising.support.ObjectStatusMigrationReport;

/**
 * Migration of persisted Object_Status values to the canonical vocabulary {@code enabled | paused |
 * archived} (Requirement 16.7). The migration applies the fixed legacy mapping ({@code active →
 * enabled}, {@code paused → paused}, {@code archived → archived}) and records any unknown value in
 * the {@code object_status_migration_exceptions} list for manual review rather than auto-mapping it
 * to a canonical value (Requirement 16.6).
 */
public interface ObjectStatusMigrationService {

    /**
     * Migrate every Object_Status column in the registry.
     *
     * @return the aggregate migration report
     */
    ObjectStatusMigrationReport migrateAll();

    /**
     * Migrate a single Object_Status column using the canonical vocabulary mapping.
     *
     * @param column the column descriptor (table, column); must not be {@code null}
     * @return the per-column migration summary
     */
    ObjectStatusColumnMigrationResult migrateColumn(ObjectStatusColumn column);
}
