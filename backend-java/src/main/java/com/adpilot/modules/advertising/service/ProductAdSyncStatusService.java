package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.vo.ProductAdSyncStatusVo;

import java.util.List;

/**
 * Read-only report-sync observability for product-ad data (Req 2.5, 2.6).
 *
 * <p>Exposes, per report type for a store, the most recent run status, the most
 * recent successful completion time, and a readable failure reason when the most
 * recent run failed. A failed sync is never reported as a success.</p>
 */
public interface ProductAdSyncStatusService {

    /**
     * Return the latest report-sync status for each report type of the given
     * store, derived from {@code report_sync_runs} / {@code report_sync_errors}.
     * Returns an empty list when the store has no sync history.
     */
    List<ProductAdSyncStatusVo> getSyncStatus(String storeId);
}
