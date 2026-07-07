package com.adpilot.modules.advertising.hosting;

/**
 * Summary of a report ingestion run, capturing counts for rows inserted,
 * updated, quarantined, and version-bumped.
 *
 * @param rowsInserted     number of new rows created
 * @param rowsUpdated      number of existing rows updated (same values, no version bump)
 * @param rowsVersionBumped number of existing rows where values changed (data_version incremented)
 * @param rowsQuarantined  number of rows routed to metric_quarantine (unresolved entity)
 * @param totalProcessed   total rows processed from the report
 */
public record IngestionResult(
        int rowsInserted,
        int rowsUpdated,
        int rowsVersionBumped,
        int rowsQuarantined,
        int totalProcessed) {

    /** An empty result for cases where no rows were processed. */
    public static IngestionResult empty() {
        return new IngestionResult(0, 0, 0, 0, 0);
    }
}
