package com.adpilot.modules.advertising.support;

/**
 * An immutable descriptor of one historical ACoS column to migrate: the table it lives on, the
 * column name, and that column's {@link AcosColumnScale known source semantics} (Requirement 17.5).
 *
 * <p>The {@code (table, column)} pair is sourced from a fixed internal {@link AcosColumnRegistry
 * registry}, never from user input, so it is safe to interpolate into migration SQL.</p>
 *
 * @param table  the database table name (for example {@code keywords})
 * @param column the ACoS column name on that table (for example {@code acos})
 * @param scale  the column's known source semantics that drive its migration
 */
public record AcosColumn(String table, String column, AcosColumnScale scale) {

    public AcosColumn {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("column must not be blank");
        }
        if (scale == null) {
            throw new IllegalArgumentException("scale must not be null");
        }
    }
}
