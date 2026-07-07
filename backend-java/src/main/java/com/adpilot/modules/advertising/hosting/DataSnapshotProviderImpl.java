package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.SafetyBoundaryEntity;
import com.adpilot.modules.advertising.entity.SearchTermDailyEntity;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.mapper.SearchTermDailyMapper;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of {@link DataSnapshotProvider} (Requirements 23.1, 23.2).
 *
 * <p>Captures a single immutable point-in-time {@link DataSnapshot} at the start
 * of an optimization run. All engines (V1/V2/V3) share this snapshot — they never
 * query the database directly during processing.</p>
 *
 * <p>Each call to {@link #capture} produces a fresh snapshot from the database.
 * The resulting {@link DataSnapshot} wraps all collections as unmodifiable views,
 * so concurrent engine reads are safe without synchronization.</p>
 */
@Service
public class DataSnapshotProviderImpl implements DataSnapshotProvider {

    private static final Logger log = LoggerFactory.getLogger(DataSnapshotProviderImpl.class);

    private final PerformanceDailyMapper performanceDailyMapper;
    private final SearchTermDailyMapper searchTermDailyMapper;
    private final ExternalEntityMappingMapper externalEntityMappingMapper;
    private final SafetyBoundaryMapper safetyBoundaryMapper;
    private final StoreMapper storeMapper;
    private final MarketplaceMapper marketplaceMapper;

    public DataSnapshotProviderImpl(PerformanceDailyMapper performanceDailyMapper,
                                    SearchTermDailyMapper searchTermDailyMapper,
                                    ExternalEntityMappingMapper externalEntityMappingMapper,
                                    SafetyBoundaryMapper safetyBoundaryMapper,
                                    StoreMapper storeMapper,
                                    MarketplaceMapper marketplaceMapper) {
        this.performanceDailyMapper = performanceDailyMapper;
        this.searchTermDailyMapper = searchTermDailyMapper;
        this.externalEntityMappingMapper = externalEntityMappingMapper;
        this.safetyBoundaryMapper = safetyBoundaryMapper;
        this.storeMapper = storeMapper;
        this.marketplaceMapper = marketplaceMapper;
    }

    @Override
    public DataSnapshot capture(UUID storeId, int lookbackDays) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId must not be null");
        }
        if (lookbackDays < 0) {
            throw new IllegalArgumentException("lookbackDays must not be negative");
        }

        Instant snapshotTimestamp = Instant.now();
        log.info("Capturing data snapshot for store={} lookback={}d at {}",
                storeId, lookbackDays, snapshotTimestamp);

        // 1. Resolve store and marketplace metadata
        StoreEntity store = storeMapper.selectById(storeId);
        if (store == null) {
            throw new IllegalStateException("Store not found: " + storeId);
        }

        MarketplaceEntity marketplace = marketplaceMapper.selectById(store.getMarketplaceId());
        if (marketplace == null) {
            throw new IllegalStateException(
                    "Marketplace not found for store " + storeId + " (marketplaceId=" + store.getMarketplaceId() + ")");
        }

        String marketplaceTimezone = marketplace.getTimezone() != null
                ? marketplace.getTimezone() : "UTC";
        String currency = marketplace.getCurrency();

        // 2. Determine the lookback date range
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(lookbackDays);

        // 3. Query performance data for the lookback window
        List<PerformanceDailyEntity> performanceData = queryPerformanceData(storeId, startDate, endDate);
        log.debug("Snapshot captured {} performance rows for store={}", performanceData.size(), storeId);

        // 4. Query search term data for the lookback window
        List<SearchTermDailyEntity> searchTermData = querySearchTermData(storeId, startDate, endDate);
        log.debug("Snapshot captured {} search term rows for store={}", searchTermData.size(), storeId);

        // 5. Query external entity mappings for the store
        List<ExternalEntityMappingEntity> entityMappings = queryEntityMappings(storeId);
        log.debug("Snapshot captured {} entity mappings for store={}", entityMappings.size(), storeId);

        // 6. Query safety boundaries (store-level + system-level)
        List<SafetyBoundaryEntity> safetyBoundaries = querySafetyBoundaries(storeId);
        log.debug("Snapshot captured {} safety boundaries for store={}", safetyBoundaries.size(), storeId);

        // 7. Build the immutable snapshot
        DataSnapshot snapshot = new DataSnapshot(
                snapshotTimestamp,
                storeId,
                lookbackDays,
                marketplaceTimezone,
                currency,
                performanceData,
                searchTermData,
                entityMappings,
                safetyBoundaries
        );

        log.info("Data snapshot captured: {}", snapshot);
        return snapshot;
    }

    /**
     * Query performance_daily rows for the store within the lookback window.
     */
    private List<PerformanceDailyEntity> queryPerformanceData(UUID storeId, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<PerformanceDailyEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceDailyEntity::getStoreId, storeId)
               .ge(PerformanceDailyEntity::getDate, startDate)
               .le(PerformanceDailyEntity::getDate, endDate);
        return performanceDailyMapper.selectList(wrapper);
    }

    /**
     * Query search_term_daily rows for the store within the lookback window.
     */
    private List<SearchTermDailyEntity> querySearchTermData(UUID storeId, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<SearchTermDailyEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SearchTermDailyEntity::getStoreId, storeId)
               .ge(SearchTermDailyEntity::getReportDate, startDate)
               .le(SearchTermDailyEntity::getReportDate, endDate);
        return searchTermDailyMapper.selectList(wrapper);
    }

    /**
     * Query external_entity_mappings for the store.
     */
    private List<ExternalEntityMappingEntity> queryEntityMappings(UUID storeId) {
        LambdaQueryWrapper<ExternalEntityMappingEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExternalEntityMappingEntity::getStoreId, storeId);
        return externalEntityMappingMapper.selectList(wrapper);
    }

    /**
     * Query safety boundaries applicable to the store. Loads both the store-specific
     * boundaries and system-level boundaries so the full hierarchy is available for
     * the engines to resolve.
     */
    private List<SafetyBoundaryEntity> querySafetyBoundaries(UUID storeId) {
        // Store-specific boundaries (includes campaign/goal/store scopes for this store)
        List<SafetyBoundaryEntity> storeBoundaries = safetyBoundaryMapper.findByStoreId(storeId);

        // System-level boundaries (global defaults)
        List<SafetyBoundaryEntity> systemBoundaries = safetyBoundaryMapper.findSystemBoundaries();

        // Combine into a single list
        List<SafetyBoundaryEntity> combined = new ArrayList<>(storeBoundaries.size() + systemBoundaries.size());
        combined.addAll(storeBoundaries);
        combined.addAll(systemBoundaries);
        return combined;
    }
}
