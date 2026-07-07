package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Service responsible for ingesting downloaded Amazon Ads report data into the
 * local performance stores ({@code performance_daily} and {@code search_term_daily}).
 *
 * <p>Key behaviors (Requirements 2.3, 2.4, 2.5, 31.1, 32.1, 32.4):</p>
 * <ul>
 *   <li>Idempotent upsert keyed by {@code (store_id, entity_type, entity_id, report_date)}
 *       for campaign/keyword reports and
 *       {@code (store_id, campaign_id, ad_group_id, search_term, report_date)} for search-term reports.</li>
 *   <li>Derives {@code data_status} from age vs {@code finalizationLagDays}:
 *       recent rows are {@code preliminary}, rows past the lag are {@code finalized}.</li>
 *   <li>Increments {@code data_version} when a backfill changes a previously ingested row.</li>
 *   <li>Resolves Amazon external IDs to internal UUIDs via {@code external_entity_mappings}.</li>
 *   <li>Routes unresolved rows to {@code metric_quarantine} — never fabricates entities.</li>
 * </ul>
 */
public interface ReportIngestionService {

    /**
     * Ingest a completed report lifecycle result into the appropriate performance store.
     *
     * <p>Campaign and keyword reports are upserted into {@code performance_daily};
     * search-term reports are upserted into {@code search_term_daily}.
     * Rows whose external entity IDs cannot be resolved are quarantined.</p>
     *
     * @param storeId the store that owns the report data
     * @param result  the downloaded and validated report lifecycle result
     * @return an ingestion summary with counts of upserted, quarantined, and versioned rows
     */
    IngestionResult ingest(UUID storeId, ReportLifecycleResult result);

    /**
     * Ingest a completed report lifecycle result with an explicit finalization lag.
     *
     * @param storeId              the store that owns the report data
     * @param result               the downloaded and validated report lifecycle result
     * @param finalizationLagDays  the number of days after report_date before data is considered finalized
     * @return an ingestion summary
     */
    IngestionResult ingest(UUID storeId, ReportLifecycleResult result, int finalizationLagDays);
}
