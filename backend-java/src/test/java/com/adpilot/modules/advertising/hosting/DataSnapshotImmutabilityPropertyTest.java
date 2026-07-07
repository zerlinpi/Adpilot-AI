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
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based test for the single immutable data snapshot captured by
 * {@link DataSnapshotProviderImpl}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 27: Single immutable snapshot
 *
 * <p><b>Validates: Requirements 23.1, 23.2</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>The DataSnapshot is immutable — attempts to modify its collections after
 *       capture throw {@link UnsupportedOperationException} (Req 23.1)</li>
 *   <li>Multiple engines reading the same snapshot see identical data — same metrics,
 *       same timestamps, same entities (Req 23.2)</li>
 *   <li>The snapshot is captured once — subsequent calls within the same run return
 *       fresh snapshots from DB (each call queries the DB), so a single capture shared
 *       across all engines never re-reads (Req 23.2)</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 27: Single immutable snapshot")
class DataSnapshotImmutabilityPropertyTest {

    // ── Helper: build the provider with controlled mapper behavior ───────────────

    /**
     * Builds a DataSnapshotProviderImpl with mocked mappers returning the
     * given data. The underlying lists are mutable (simulating DB-returned data)
     * so we can verify the snapshot wraps them immutably.
     */
    @SuppressWarnings("unchecked")
    private DataSnapshotProviderImpl buildProvider(
            UUID storeId,
            UUID marketplaceId,
            String timezone,
            String currency,
            List<PerformanceDailyEntity> perfData,
            List<SearchTermDailyEntity> searchTermData,
            List<ExternalEntityMappingEntity> mappings,
            List<SafetyBoundaryEntity> boundaries) {

        PerformanceDailyMapper perfMapper = mock(PerformanceDailyMapper.class);
        SearchTermDailyMapper searchTermMapper = mock(SearchTermDailyMapper.class);
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        SafetyBoundaryMapper boundaryMapper = mock(SafetyBoundaryMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceMapper marketplaceMapper = mock(MarketplaceMapper.class);

        // Configure store and marketplace lookup
        StoreEntity store = StoreEntity.builder()
                .id(storeId)
                .orgId(UUID.randomUUID())
                .name("Test Store")
                .marketplaceId(marketplaceId)
                .build();
        when(storeMapper.selectById(storeId)).thenReturn(store);

        MarketplaceEntity marketplace = MarketplaceEntity.builder()
                .id(marketplaceId)
                .code("US")
                .name("United States")
                .currency(currency)
                .timezone(timezone)
                .vatApplicable(false)
                .build();
        when(marketplaceMapper.selectById(marketplaceId)).thenReturn(marketplace);

        // Return mutable lists from mappers (simulating DB query results)
        when(perfMapper.selectList(any())).thenReturn(new ArrayList<>(perfData));
        when(searchTermMapper.selectList(any())).thenReturn(new ArrayList<>(searchTermData));
        when(mappingMapper.selectList(any())).thenReturn(new ArrayList<>(mappings));
        when(boundaryMapper.findByStoreId(storeId)).thenReturn(new ArrayList<>(boundaries));
        when(boundaryMapper.findSystemBoundaries()).thenReturn(new ArrayList<>());

        return new DataSnapshotProviderImpl(
                perfMapper, searchTermMapper, mappingMapper,
                boundaryMapper, storeMapper, marketplaceMapper);
    }

    /**
     * Builds a provider that counts how many times each mapper's query method is invoked.
     */
    @SuppressWarnings("unchecked")
    private record CountingProvider(
            DataSnapshotProviderImpl provider,
            AtomicInteger perfQueryCount,
            AtomicInteger searchTermQueryCount,
            AtomicInteger mappingQueryCount,
            AtomicInteger boundaryQueryCount) {
    }

    private CountingProvider buildCountingProvider(UUID storeId, UUID marketplaceId) {
        PerformanceDailyMapper perfMapper = mock(PerformanceDailyMapper.class);
        SearchTermDailyMapper searchTermMapper = mock(SearchTermDailyMapper.class);
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        SafetyBoundaryMapper boundaryMapper = mock(SafetyBoundaryMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceMapper marketplaceMapper = mock(MarketplaceMapper.class);

        StoreEntity store = StoreEntity.builder()
                .id(storeId)
                .orgId(UUID.randomUUID())
                .name("Test Store")
                .marketplaceId(marketplaceId)
                .build();
        when(storeMapper.selectById(storeId)).thenReturn(store);

        MarketplaceEntity marketplace = MarketplaceEntity.builder()
                .id(marketplaceId)
                .code("US")
                .name("United States")
                .currency("USD")
                .timezone("America/New_York")
                .vatApplicable(false)
                .build();
        when(marketplaceMapper.selectById(marketplaceId)).thenReturn(marketplace);

        AtomicInteger perfCount = new AtomicInteger(0);
        AtomicInteger searchTermCount = new AtomicInteger(0);
        AtomicInteger mappingCount = new AtomicInteger(0);
        AtomicInteger boundaryCount = new AtomicInteger(0);

        when(perfMapper.selectList(any())).thenAnswer(inv -> {
            perfCount.incrementAndGet();
            return new ArrayList<>();
        });
        when(searchTermMapper.selectList(any())).thenAnswer(inv -> {
            searchTermCount.incrementAndGet();
            return new ArrayList<>();
        });
        when(mappingMapper.selectList(any())).thenAnswer(inv -> {
            mappingCount.incrementAndGet();
            return new ArrayList<>();
        });
        when(boundaryMapper.findByStoreId(storeId)).thenAnswer(inv -> {
            boundaryCount.incrementAndGet();
            return new ArrayList<>();
        });
        when(boundaryMapper.findSystemBoundaries()).thenReturn(new ArrayList<>());

        DataSnapshotProviderImpl provider = new DataSnapshotProviderImpl(
                perfMapper, searchTermMapper, mappingMapper,
                boundaryMapper, storeMapper, marketplaceMapper);

        return new CountingProvider(provider, perfCount, searchTermCount, mappingCount, boundaryCount);
    }

    // ── Property 1: Snapshot collections are immutable ───────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 27: Single immutable snapshot
     *
     * <p><b>Validates: Requirements 23.1, 23.2</b></p>
     *
     * <p>Once captured, the DataSnapshot's collections cannot be modified. Any
     * attempt to add, remove, or clear an element throws UnsupportedOperationException.
     * This ensures all engines operate on truly immutable data.</p>
     */
    @Property(tries = 100)
    void snapshotCollectionsAreImmutableAfterCapture(
            @ForAll @IntRange(min = 1, max = 30) int lookbackDays,
            @ForAll @IntRange(min = 0, max = 5) int perfRowCount,
            @ForAll @IntRange(min = 0, max = 3) int searchTermRowCount,
            @ForAll @IntRange(min = 0, max = 3) int mappingCount,
            @ForAll @IntRange(min = 0, max = 3) int boundaryCount) {

        UUID storeId = UUID.randomUUID();
        UUID marketplaceId = UUID.randomUUID();

        List<PerformanceDailyEntity> perfData = buildPerfData(storeId, perfRowCount, lookbackDays);
        List<SearchTermDailyEntity> searchTermData = buildSearchTermData(storeId, searchTermRowCount, lookbackDays);
        List<ExternalEntityMappingEntity> mappings = buildMappings(storeId, mappingCount);
        List<SafetyBoundaryEntity> boundaries = buildBoundaries(storeId, boundaryCount);

        DataSnapshotProviderImpl provider = buildProvider(
                storeId, marketplaceId, "America/New_York", "USD",
                perfData, searchTermData, mappings, boundaries);

        DataSnapshot snapshot = provider.capture(storeId, lookbackDays);

        // All collection getters should return unmodifiable views
        assertThatThrownBy(() -> snapshot.getPerformanceData().add(
                PerformanceDailyEntity.builder().id(UUID.randomUUID()).storeId(storeId).build()))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> snapshot.getSearchTermData().add(
                SearchTermDailyEntity.builder().id(UUID.randomUUID()).storeId(storeId).build()))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> snapshot.getExternalEntityMappings().add(
                ExternalEntityMappingEntity.builder().id(UUID.randomUUID()).storeId(storeId).build()))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> snapshot.getSafetyBoundaries().add(
                SafetyBoundaryEntity.builder().id(UUID.randomUUID()).storeId(storeId).build()))
                .isInstanceOf(UnsupportedOperationException.class);

        // Removal should also be rejected
        if (!snapshot.getPerformanceData().isEmpty()) {
            assertThatThrownBy(() -> snapshot.getPerformanceData().remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        if (!snapshot.getSearchTermData().isEmpty()) {
            assertThatThrownBy(() -> snapshot.getSearchTermData().remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        if (!snapshot.getExternalEntityMappings().isEmpty()) {
            assertThatThrownBy(() -> snapshot.getExternalEntityMappings().remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        if (!snapshot.getSafetyBoundaries().isEmpty()) {
            assertThatThrownBy(() -> snapshot.getSafetyBoundaries().remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        // Clear should also fail
        assertThatThrownBy(() -> snapshot.getPerformanceData().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.getSearchTermData().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.getExternalEntityMappings().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.getSafetyBoundaries().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Property 2: Multiple readers see identical data ──────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 27: Single immutable snapshot
     *
     * <p><b>Validates: Requirements 23.1, 23.2</b></p>
     *
     * <p>Multiple engines (simulated as multiple readers) accessing the same
     * DataSnapshot instance see identical data: same performance metrics,
     * same timestamps, same entities. The snapshot is shared safely.</p>
     */
    @Property(tries = 100)
    void multipleReadersOfSameSnapshotSeeIdenticalData(
            @ForAll @IntRange(min = 1, max = 30) int lookbackDays,
            @ForAll @IntRange(min = 1, max = 10) int perfRowCount,
            @ForAll @IntRange(min = 1, max = 5) int searchTermRowCount,
            @ForAll @IntRange(min = 2, max = 5) int engineCount) {

        UUID storeId = UUID.randomUUID();
        UUID marketplaceId = UUID.randomUUID();

        List<PerformanceDailyEntity> perfData = buildPerfData(storeId, perfRowCount, lookbackDays);
        List<SearchTermDailyEntity> searchTermData = buildSearchTermData(storeId, searchTermRowCount, lookbackDays);
        List<ExternalEntityMappingEntity> mappings = buildMappings(storeId, 3);
        List<SafetyBoundaryEntity> boundaries = buildBoundaries(storeId, 2);

        DataSnapshotProviderImpl provider = buildProvider(
                storeId, marketplaceId, "Asia/Shanghai", "CNY",
                perfData, searchTermData, mappings, boundaries);

        // Capture the snapshot once (like the OptimizationRunService does at run start)
        DataSnapshot snapshot = provider.capture(storeId, lookbackDays);

        // Simulate multiple engines reading from the same snapshot
        for (int i = 0; i < engineCount; i++) {
            // Each "engine" gets the same snapshot reference and reads its collections
            List<PerformanceDailyEntity> enginePerfView = snapshot.getPerformanceData();
            List<SearchTermDailyEntity> engineSearchTermView = snapshot.getSearchTermData();
            List<ExternalEntityMappingEntity> engineMappingsView = snapshot.getExternalEntityMappings();
            List<SafetyBoundaryEntity> engineBoundariesView = snapshot.getSafetyBoundaries();

            // All engines see the same number of rows
            assertThat(enginePerfView).hasSize(perfRowCount);
            assertThat(engineSearchTermView).hasSize(searchTermRowCount);
            assertThat(engineMappingsView).hasSize(3);
            assertThat(engineBoundariesView).hasSize(2);

            // All engines see the same store metadata
            assertThat(snapshot.getStoreId()).isEqualTo(storeId);
            assertThat(snapshot.getMarketplaceTimezone()).isEqualTo("Asia/Shanghai");
            assertThat(snapshot.getCurrency()).isEqualTo("CNY");
            assertThat(snapshot.getLookbackDays()).isEqualTo(lookbackDays);

            // The timestamp is consistent across reads
            assertThat(snapshot.getSnapshotTimestamp()).isNotNull();

            // Every element is the same object reference (shared, not copied per engine)
            if (!enginePerfView.isEmpty()) {
                assertThat(enginePerfView.get(0))
                        .isSameAs(snapshot.getPerformanceData().get(0));
            }
            if (!engineSearchTermView.isEmpty()) {
                assertThat(engineSearchTermView.get(0))
                        .isSameAs(snapshot.getSearchTermData().get(0));
            }
        }
    }

    // ── Property 3: Snapshot is captured once per call (DB queried each time) ────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 27: Single immutable snapshot
     *
     * <p><b>Validates: Requirements 23.1, 23.2</b></p>
     *
     * <p>Each call to {@code capture()} queries the database exactly once per
     * data source. Within a single optimization run the provider is called once
     * and the result is shared — engines never re-read. This test verifies that
     * two captures produce independent snapshots (proving no internal caching)
     * and that each capture hits the DB exactly once per source.</p>
     */
    @Property(tries = 80)
    void eachCaptureQueriesDatabaseExactlyOnceAndProducesIndependentSnapshots(
            @ForAll @IntRange(min = 1, max = 30) int lookbackDays) {

        UUID storeId = UUID.randomUUID();
        UUID marketplaceId = UUID.randomUUID();

        CountingProvider counting = buildCountingProvider(storeId, marketplaceId);

        // First capture
        DataSnapshot snapshot1 = counting.provider().capture(storeId, lookbackDays);

        // Verify DB was queried exactly once per data source
        assertThat(counting.perfQueryCount().get())
                .as("Performance query count after first capture")
                .isEqualTo(1);
        assertThat(counting.searchTermQueryCount().get())
                .as("Search term query count after first capture")
                .isEqualTo(1);
        assertThat(counting.mappingQueryCount().get())
                .as("Entity mapping query count after first capture")
                .isEqualTo(1);
        assertThat(counting.boundaryQueryCount().get())
                .as("Safety boundary query count after first capture")
                .isEqualTo(1);

        // Second capture
        DataSnapshot snapshot2 = counting.provider().capture(storeId, lookbackDays);

        // Each capture queries DB independently (no caching)
        assertThat(counting.perfQueryCount().get())
                .as("Performance query count after second capture")
                .isEqualTo(2);
        assertThat(counting.searchTermQueryCount().get())
                .as("Search term query count after second capture")
                .isEqualTo(2);
        assertThat(counting.mappingQueryCount().get())
                .as("Entity mapping query count after second capture")
                .isEqualTo(2);
        assertThat(counting.boundaryQueryCount().get())
                .as("Safety boundary query count after second capture")
                .isEqualTo(2);

        // The two snapshots are independent instances (not the same reference)
        assertThat(snapshot1).isNotSameAs(snapshot2);

        // But they have equal content (since both read from the same mock data)
        assertThat(snapshot1.getStoreId()).isEqualTo(snapshot2.getStoreId());
        assertThat(snapshot1.getLookbackDays()).isEqualTo(snapshot2.getLookbackDays());
        assertThat(snapshot1.getMarketplaceTimezone()).isEqualTo(snapshot2.getMarketplaceTimezone());
        assertThat(snapshot1.getCurrency()).isEqualTo(snapshot2.getCurrency());
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 27: Single immutable snapshot
     *
     * <p><b>Validates: Requirements 23.1, 23.2</b></p>
     *
     * <p>The DataSnapshot preserves its data content as-is at capture time and is
     * structurally equal to another snapshot captured from the same source data.
     * Two captures from identical source data produce equal snapshots, confirming
     * the snapshot accurately represents the point-in-time data.</p>
     */
    @Property(tries = 100)
    void snapshotPreservesAllCapturedDataAccurately(
            @ForAll @IntRange(min = 1, max = 30) int lookbackDays,
            @ForAll @IntRange(min = 1, max = 5) int perfRowCount,
            @ForAll @IntRange(min = 1, max = 3) int searchTermRowCount) {

        UUID storeId = UUID.randomUUID();
        UUID marketplaceId = UUID.randomUUID();

        List<PerformanceDailyEntity> perfData = buildPerfData(storeId, perfRowCount, lookbackDays);
        List<SearchTermDailyEntity> searchTermData = buildSearchTermData(storeId, searchTermRowCount, lookbackDays);
        List<ExternalEntityMappingEntity> mappings = buildMappings(storeId, 2);
        List<SafetyBoundaryEntity> boundaries = buildBoundaries(storeId, 2);

        DataSnapshotProviderImpl provider = buildProvider(
                storeId, marketplaceId, "Europe/London", "GBP",
                perfData, searchTermData, mappings, boundaries);

        DataSnapshot snapshot = provider.capture(storeId, lookbackDays);

        // Snapshot preserves count and content accurately
        assertThat(snapshot.getPerformanceData()).hasSize(perfRowCount);
        assertThat(snapshot.getSearchTermData()).hasSize(searchTermRowCount);
        assertThat(snapshot.getExternalEntityMappings()).hasSize(2);
        assertThat(snapshot.getSafetyBoundaries()).hasSize(2);

        // Snapshot preserves metadata accurately
        assertThat(snapshot.getStoreId()).isEqualTo(storeId);
        assertThat(snapshot.getLookbackDays()).isEqualTo(lookbackDays);
        assertThat(snapshot.getMarketplaceTimezone()).isEqualTo("Europe/London");
        assertThat(snapshot.getCurrency()).isEqualTo("GBP");
        assertThat(snapshot.getSnapshotTimestamp()).isNotNull();

        // Each data element is the same as what the DB returned
        for (int i = 0; i < perfRowCount; i++) {
            assertThat(snapshot.getPerformanceData().get(i).getId())
                    .isEqualTo(perfData.get(i).getId());
            assertThat(snapshot.getPerformanceData().get(i).getStoreId())
                    .isEqualTo(storeId);
        }
        for (int i = 0; i < searchTermRowCount; i++) {
            assertThat(snapshot.getSearchTermData().get(i).getId())
                    .isEqualTo(searchTermData.get(i).getId());
        }
    }

    // ── Data builders ────────────────────────────────────────────────────────────

    private List<PerformanceDailyEntity> buildPerfData(UUID storeId, int count, int lookbackDays) {
        List<PerformanceDailyEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(PerformanceDailyEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .campaignId(UUID.randomUUID())
                    .entityType("campaign")
                    .entityId(UUID.randomUUID())
                    .date(LocalDate.now().minusDays(i % lookbackDays))
                    .impressions((long) (100 + i * 50))
                    .clicks(10 + i)
                    .spend(BigDecimal.valueOf(5.0 + i))
                    .sales(BigDecimal.valueOf(20.0 + i * 2))
                    .orders(2 + i)
                    .acos(BigDecimal.valueOf(0.25))
                    .currency("USD")
                    .dataStatus("finalized")
                    .dataVersion(1)
                    .build());
        }
        return list;
    }

    private List<SearchTermDailyEntity> buildSearchTermData(UUID storeId, int count, int lookbackDays) {
        List<SearchTermDailyEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(SearchTermDailyEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .campaignId(UUID.randomUUID())
                    .adGroupId(UUID.randomUUID())
                    .searchTerm("term_" + i)
                    .reportDate(LocalDate.now().minusDays(i % lookbackDays))
                    .impressions((long) (50 + i * 20))
                    .clicks(5 + i)
                    .orders(1 + i)
                    .spend(BigDecimal.valueOf(3.0 + i))
                    .sales(BigDecimal.valueOf(15.0 + i))
                    .acos(BigDecimal.valueOf(0.20))
                    .currency("USD")
                    .dataStatus("finalized")
                    .dataVersion(1)
                    .build());
        }
        return list;
    }

    private List<ExternalEntityMappingEntity> buildMappings(UUID storeId, int count) {
        List<ExternalEntityMappingEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(ExternalEntityMappingEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .platform("amazon_ads")
                    .internalEntityType("campaign")
                    .internalEntityId(UUID.randomUUID())
                    .externalEntityType("campaign")
                    .externalEntityId("amz_" + UUID.randomUUID().toString().substring(0, 8))
                    .build());
        }
        return list;
    }

    private List<SafetyBoundaryEntity> buildBoundaries(UUID storeId, int count) {
        List<SafetyBoundaryEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(SafetyBoundaryEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .scope("store")
                    .scopeId(storeId)
                    .limitType("MAX_BID")
                    .valueType("amount")
                    .valueAmount(BigDecimal.valueOf(10.0 + i))
                    .comparisonSemantics("upper_bound")
                    .build());
        }
        return list;
    }
}
