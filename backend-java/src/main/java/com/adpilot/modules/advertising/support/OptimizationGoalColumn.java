package com.adpilot.modules.advertising.support;

/**
 * An immutable descriptor of one legacy goal-type / hosting-goal column to migrate to the canonical
 * Optimization_Goal enum: the table it lives on and the column name (Requirement 57.2).
 *
 * <p>The {@code (table, column)} pair is sourced from a fixed internal
 * {@link OptimizationGoalColumnRegistry registry}, never from user input, so it is safe to interpolate
 * into migration SQL.</p>
 *
 * @param table  the database table name (for example {@code goals})
 * @param column the goal-type / hosting-goal column name on that table (for example {@code type})
 */
public record OptimizationGoalColumn(String table, String column) {

    public OptimizationGoalColumn {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("column must not be blank");
        }
    }
}
