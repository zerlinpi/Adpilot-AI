package com.adpilot.modules.advertising.support;

import java.util.List;

/**
 * The fixed, internal catalogue of advertising-module Object_Status columns to normalize to the
 * canonical vocabulary {@code enabled | paused | archived} (Requirement 16.7).
 *
 * <p>Per Requirement 16.1 Object_Status applies to Campaign, Keyword, and Target lifecycle state, so
 * the registry covers the {@code status} column on the {@code campaigns}, {@code keywords}, and
 * {@code targets} tables. Each of these columns historically stored the legacy {@code active} default
 * that the migration mapping rewrites to {@code enabled} (Requirement 16.5), while any unknown value
 * is flagged rather than guessed (Requirement 16.6).</p>
 *
 * <p>All entries here are compile-time constants and contain no user input, so the
 * {@code (table, column)} pairs are safe to interpolate into migration SQL.</p>
 */
public final class ObjectStatusColumnRegistry {

    private static final List<ObjectStatusColumn> COLUMNS = List.of(
            new ObjectStatusColumn("campaigns", "status"),
            new ObjectStatusColumn("keywords", "status"),
            new ObjectStatusColumn("targets", "status"));

    private ObjectStatusColumnRegistry() {
        // Static catalogue — not instantiable.
    }

    /**
     * @return the immutable, ordered list of Object_Status columns to migrate. Migration processes the
     *         columns in this order.
     */
    public static List<ObjectStatusColumn> columns() {
        return COLUMNS;
    }
}
