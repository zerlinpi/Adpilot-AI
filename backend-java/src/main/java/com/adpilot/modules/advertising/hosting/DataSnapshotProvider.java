package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Service that captures a single immutable {@link DataSnapshot} at the start
 * of an optimization run (Requirements 23.1, 23.2).
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>The snapshot is captured <b>once</b> per optimization run.</li>
 *   <li>All engines (V1/V2/V3) share the same snapshot instance — they never
 *       query the database directly during processing.</li>
 *   <li>The returned {@link DataSnapshot} is immutable: all collections are
 *       unmodifiable views.</li>
 * </ul>
 *
 * <p>The implementation reads from the database at snapshot time and creates
 * an unmodifiable view. Subsequent calls to {@link #capture} produce a fresh
 * snapshot (they do not return a cached one), because each optimization run
 * should get its own point-in-time view.</p>
 */
public interface DataSnapshotProvider {

    /**
     * Capture an immutable point-in-time data snapshot for the given store,
     * using the specified lookback window.
     *
     * <p>This method queries the database for:</p>
     * <ol>
     *   <li>Performance data (from {@code performance_daily}) within the lookback window</li>
     *   <li>Search term data (from {@code search_term_daily}) within the lookback window</li>
     *   <li>External entity mappings for the store</li>
     *   <li>Safety boundaries applicable to the store (all hierarchy levels)</li>
     *   <li>Store metadata (marketplace timezone, currency)</li>
     * </ol>
     *
     * <p>The resulting snapshot is immutable and safe to share across all engines
     * for the duration of the optimization run.</p>
     *
     * @param storeId      the store to snapshot data for
     * @param lookbackDays the number of days of historical data to include
     * @return an immutable {@link DataSnapshot}
     * @throws IllegalArgumentException if storeId is null or lookbackDays is negative
     * @throws IllegalStateException    if the store or its marketplace cannot be resolved
     */
    DataSnapshot capture(UUID storeId, int lookbackDays);
}
