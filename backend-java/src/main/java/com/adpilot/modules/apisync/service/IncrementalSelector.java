package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.model.ExternalRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Pure incremental-selection logic shared by the sync engine. Given a per-
 * (store, entityType) {@link WatermarkStore} watermark and whether a full
 * resync was requested, it resolves the lower-bound {@code since} window and
 * selects exactly the records that belong to an incremental pull.
 *
 * <p>This is the canonical, connector-independent expression of the incremental
 * contract that the WooCommerce/Shopify connectors implement by pushing
 * {@code since} to the platform query (WooCommerce {@code modified_after},
 * Shopify {@code updated_at_min}): with a watermark present and no full resync,
 * only records whose change timestamp is <em>strictly after</em> the watermark
 * are selected; with no watermark or an explicit full resync, every record is
 * selected.
 *
 * <p>Requirements: 1.1.6 (incremental window selects post-watermark records),
 * 1.1.7 (full pull when no watermark exists or a full resync is requested).
 */
public final class IncrementalSelector {

    private IncrementalSelector() {
    }

    /**
     * Resolve the lower-bound {@code since} window for a pull.
     *
     * @param watermark  the current watermark, or {@link Optional#empty()} when
     *                   none exists
     * @param fullResync whether a full retrieval was explicitly requested
     * @return the {@link Instant} after which records are incremental, or
     *         {@code null} to signal a full pull (no lower bound)
     */
    public static Instant resolveSince(Optional<Instant> watermark, boolean fullResync) {
        if (fullResync || watermark == null || watermark.isEmpty()) {
            // Req 1.1.7: full pull when no watermark exists or full resync requested.
            return null;
        }
        return watermark.get();
    }

    /**
     * Select the records that belong to an incremental pull bounded by
     * {@code since}.
     *
     * @param records the candidate records (a {@code null} element or a record
     *                with a {@code null} change timestamp is never selected for
     *                an incremental window)
     * @param since   the lower bound resolved by
     *                {@link #resolveSince(Optional, boolean)}; {@code null}
     *                selects all records (full pull)
     * @return the selected records, preserving input order
     */
    public static List<ExternalRecord> select(Collection<ExternalRecord> records, Instant since) {
        List<ExternalRecord> selected = new ArrayList<>();
        if (records == null) {
            return selected;
        }
        for (ExternalRecord record : records) {
            if (record == null) {
                continue;
            }
            if (since == null) {
                // Req 1.1.7: full pull selects every record.
                selected.add(record);
                continue;
            }
            Instant changedAt = record.changedAt();
            // Req 1.1.6: strictly-after-watermark records only.
            if (changedAt != null && changedAt.isAfter(since)) {
                selected.add(record);
            }
        }
        return selected;
    }

    /**
     * Convenience combining {@link #resolveSince(Optional, boolean)} and
     * {@link #select(Collection, Instant)} for a single (store, entityType)
     * incremental pull.
     */
    public static List<ExternalRecord> selectIncremental(Collection<ExternalRecord> records,
                                                         Optional<Instant> watermark,
                                                         boolean fullResync) {
        return select(records, resolveSince(watermark, fullResync));
    }
}
