package com.adpilot.modules.apisync.model;

import java.util.List;

/**
 * A single page of external records returned by a connector pull, together
 * with the cursor needed to fetch the following page.
 *
 * @param records the records on this page
 * @param next    cursor to pass on the next pull; {@code null} when exhausted
 * @param hasMore whether further pages remain to be pulled
 */
public record ExternalPage(List<ExternalRecord> records,
                           PageCursor next,
                           boolean hasMore) {

    /** Convenience for a final page with no further records to pull. */
    public static ExternalPage last(List<ExternalRecord> records) {
        return new ExternalPage(records, null, false);
    }

    public static ExternalPage empty() {
        return new ExternalPage(List.of(), null, false);
    }
}
