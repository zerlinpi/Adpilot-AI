package com.adpilot.modules.advertising.support;

/**
 * An immutable descriptor of one Object_Status column to migrate to the canonical vocabulary: the
 * table it lives on and the column name (Requirement 16.7).
 *
 * <p>The {@code (table, column)} pair is sourced from a fixed internal {@link ObjectStatusColumnRegistry
 * registry}, never from user input, so it is safe to interpolate into migration SQL.</p>
 *
 * @param table  the database table name (for example {@code campaigns})
 * @param column the Object_Status column name on that table (for example {@code status})
 */
public record ObjectStatusColumn(String table, String column) {

    public ObjectStatusColumn {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("column must not be blank");
        }
    }
}
