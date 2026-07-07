package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.support.OptimizationGoalColumn;
import com.adpilot.modules.advertising.support.OptimizationGoalColumnMigrationResult;
import com.adpilot.modules.advertising.support.OptimizationGoalMigrationReport;

/**
 * Migration of persisted legacy goal-type / hosting-goal values to the canonical Optimization_Goal
 * enum {@code profit_first | sales_growth | rank | clearance} (Requirement 57.2). The migration
 * applies the fixed legacy mapping (for example {@code profit → profit_first}, {@code launch →
 * sales_growth}) and records any value that does not map to one of the four enums in the
 * {@code optimization_goal_migration_exceptions} list for manual review rather than auto-mapping it to
 * an arbitrary value (Requirement 57.3).
 */
public interface OptimizationGoalMigrationService {

    /**
     * Migrate every goal-type / hosting-goal column in the registry.
     *
     * @return the aggregate migration report
     */
    OptimizationGoalMigrationReport migrateAll();

    /**
     * Migrate a single goal-type / hosting-goal column using the canonical enum mapping.
     *
     * @param column the column descriptor (table, column); must not be {@code null}
     * @return the per-column migration summary
     */
    OptimizationGoalColumnMigrationResult migrateColumn(OptimizationGoalColumn column);
}
