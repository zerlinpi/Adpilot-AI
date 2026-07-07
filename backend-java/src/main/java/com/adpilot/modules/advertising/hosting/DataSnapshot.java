package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.SafetyBoundaryEntity;
import com.adpilot.modules.advertising.entity.SearchTermDailyEntity;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable point-in-time data snapshot captured ONCE at the start of an
 * optimization run and shared by all engines (V1/V2/V3).
 *
 * <p>Requirements 23.1, 23.2: every engine reads the same point-in-time data;
 * no engine queries the database directly during processing. This class is the
 * single source of truth for all data consumed during an optimization run.
 *
 * <p>All collections are wrapped in unmodifiable views. There are no setters.
 * Once constructed, the snapshot cannot be mutated.
 *
 * <h3>Captured data</h3>
 * <ol>
 *   <li>Snapshot timestamp (when the snapshot was taken)</li>
 *   <li>Performance data by campaign/keyword from {@code performance_daily}</li>
 *   <li>Search term data from {@code search_term_daily}</li>
 *   <li>External entity mappings relevant to the store</li>
 *   <li>Current safety boundaries resolved for the store</li>
 *   <li>Store metadata (marketplace timezone, currency)</li>
 * </ol>
 */
public final class DataSnapshot {

    private final Instant snapshotTimestamp;
    private final UUID storeId;
    private final int lookbackDays;
    private final String marketplaceTimezone;
    private final String currency;
    private final List<PerformanceDailyEntity> performanceData;
    private final List<SearchTermDailyEntity> searchTermData;
    private final List<ExternalEntityMappingEntity> externalEntityMappings;
    private final List<SafetyBoundaryEntity> safetyBoundaries;

    /**
     * Construct an immutable DataSnapshot. All collections are defensively
     * wrapped in unmodifiable views.
     *
     * @param snapshotTimestamp     when the snapshot was captured
     * @param storeId              the store this snapshot belongs to
     * @param lookbackDays         the number of days of lookback data included
     * @param marketplaceTimezone  the IANA timezone of the store's marketplace
     * @param currency             the currency code (e.g., USD, CNY)
     * @param performanceData      performance rows for the lookback window
     * @param searchTermData       search term rows for the lookback window
     * @param externalEntityMappings external entity mappings for the store
     * @param safetyBoundaries     safety boundary entries applicable to the store
     */
    public DataSnapshot(Instant snapshotTimestamp,
                        UUID storeId,
                        int lookbackDays,
                        String marketplaceTimezone,
                        String currency,
                        List<PerformanceDailyEntity> performanceData,
                        List<SearchTermDailyEntity> searchTermData,
                        List<ExternalEntityMappingEntity> externalEntityMappings,
                        List<SafetyBoundaryEntity> safetyBoundaries) {
        this.snapshotTimestamp = Objects.requireNonNull(snapshotTimestamp, "snapshotTimestamp must not be null");
        this.storeId = Objects.requireNonNull(storeId, "storeId must not be null");
        this.lookbackDays = lookbackDays;
        this.marketplaceTimezone = marketplaceTimezone;
        this.currency = currency;
        this.performanceData = performanceData == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(performanceData);
        this.searchTermData = searchTermData == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(searchTermData);
        this.externalEntityMappings = externalEntityMappings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(externalEntityMappings);
        this.safetyBoundaries = safetyBoundaries == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(safetyBoundaries);
    }

    // --- Getters (no setters — immutable) ---

    /** When the snapshot was captured (wall clock). */
    public Instant getSnapshotTimestamp() {
        return snapshotTimestamp;
    }

    /** The store this snapshot was captured for. */
    public UUID getStoreId() {
        return storeId;
    }

    /** The number of lookback days of data included in the snapshot. */
    public int getLookbackDays() {
        return lookbackDays;
    }

    /** The IANA timezone of the store's marketplace (e.g., "Asia/Shanghai"). */
    public String getMarketplaceTimezone() {
        return marketplaceTimezone;
    }

    /** The currency code for the store's marketplace (e.g., "USD", "CNY"). */
    public String getCurrency() {
        return currency;
    }

    /**
     * Performance data from {@code performance_daily} for the lookback window,
     * keyed by campaign and keyword. Returns an unmodifiable list.
     */
    public List<PerformanceDailyEntity> getPerformanceData() {
        return performanceData;
    }

    /**
     * Search term data from {@code search_term_daily} for the lookback window.
     * Returns an unmodifiable list.
     */
    public List<SearchTermDailyEntity> getSearchTermData() {
        return searchTermData;
    }

    /**
     * External entity mappings (internal ↔ Amazon entity IDs) relevant to the store.
     * Returns an unmodifiable list.
     */
    public List<ExternalEntityMappingEntity> getExternalEntityMappings() {
        return externalEntityMappings;
    }

    /**
     * Safety boundaries applicable to this store (across all scope levels).
     * Returns an unmodifiable list.
     */
    public List<SafetyBoundaryEntity> getSafetyBoundaries() {
        return safetyBoundaries;
    }

    // --- Convenience query methods ---

    /**
     * Filter performance data for a specific campaign.
     *
     * @param campaignId the campaign to filter by
     * @return unmodifiable list of performance rows for the campaign
     */
    public List<PerformanceDailyEntity> getPerformanceDataByCampaign(UUID campaignId) {
        if (campaignId == null) {
            return Collections.emptyList();
        }
        return performanceData.stream()
                .filter(p -> campaignId.equals(p.getCampaignId()))
                .toList();
    }

    /**
     * Filter performance data for a specific keyword within a campaign.
     *
     * @param campaignId the campaign
     * @param keywordId  the keyword to filter by
     * @return unmodifiable list of performance rows for the keyword
     */
    public List<PerformanceDailyEntity> getPerformanceDataByKeyword(UUID campaignId, UUID keywordId) {
        if (campaignId == null || keywordId == null) {
            return Collections.emptyList();
        }
        return performanceData.stream()
                .filter(p -> campaignId.equals(p.getCampaignId()) && keywordId.equals(p.getKeywordId()))
                .toList();
    }

    /**
     * Filter search term data for a specific campaign.
     *
     * @param campaignId the campaign to filter by
     * @return unmodifiable list of search term rows for the campaign
     */
    public List<SearchTermDailyEntity> getSearchTermDataByCampaign(UUID campaignId) {
        if (campaignId == null) {
            return Collections.emptyList();
        }
        return searchTermData.stream()
                .filter(s -> campaignId.equals(s.getCampaignId()))
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataSnapshot that = (DataSnapshot) o;
        return lookbackDays == that.lookbackDays
                && Objects.equals(snapshotTimestamp, that.snapshotTimestamp)
                && Objects.equals(storeId, that.storeId)
                && Objects.equals(marketplaceTimezone, that.marketplaceTimezone)
                && Objects.equals(currency, that.currency)
                && Objects.equals(performanceData, that.performanceData)
                && Objects.equals(searchTermData, that.searchTermData)
                && Objects.equals(externalEntityMappings, that.externalEntityMappings)
                && Objects.equals(safetyBoundaries, that.safetyBoundaries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshotTimestamp, storeId, lookbackDays,
                marketplaceTimezone, currency, performanceData, searchTermData,
                externalEntityMappings, safetyBoundaries);
    }

    @Override
    public String toString() {
        return "DataSnapshot{" +
                "snapshotTimestamp=" + snapshotTimestamp +
                ", storeId=" + storeId +
                ", lookbackDays=" + lookbackDays +
                ", timezone=" + marketplaceTimezone +
                ", currency=" + currency +
                ", performanceRows=" + performanceData.size() +
                ", searchTermRows=" + searchTermData.size() +
                ", mappings=" + externalEntityMappings.size() +
                ", boundaries=" + safetyBoundaries.size() +
                '}';
    }
}
