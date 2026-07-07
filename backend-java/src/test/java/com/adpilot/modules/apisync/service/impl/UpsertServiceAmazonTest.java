package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.apisync.entity.ChannelInventorySyncEntity;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ChannelInventorySyncMapper;
import com.adpilot.modules.apisync.mapper.ChannelOrderMapper;
import com.adpilot.modules.apisync.mapper.ChannelProductMapper;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.SyncContext;
import com.adpilot.modules.apisync.model.UpsertResult;
import com.adpilot.modules.inventory.entity.InventorySnapshotEntity;
import com.adpilot.modules.inventory.mapper.InventorySnapshotMapper;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_AD_REPORT;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_INVENTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Amazon entity-type wiring of {@link UpsertServiceImpl}:
 * inventory persists to {@code channel_inventory_sync} and ad reports to
 * {@code performance_daily}, both idempotently keyed on the Amazon external
 * identifier via {@code external_entity_mappings} (Req 8.1.4).
 *
 * <p>The mappers are replaced with stateful in-memory mocks so the keying and
 * idempotency logic is exercised without a database.</p>
 */
class UpsertServiceAmazonTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final String PLATFORM_SP = "amazon_sp_api";
    private static final String PLATFORM_ADS = "amazon_ads";

    private final Map<String, ExternalEntityMappingEntity> mappings = new HashMap<>();
    private final Map<UUID, ChannelInventorySyncEntity> inventory = new HashMap<>();
    private final Map<UUID, InventorySnapshotEntity> snapshots = new HashMap<>();
    private final Map<UUID, PerformanceDailyEntity> performance = new HashMap<>();
    private ProductEntity catalogProduct;

    private String lookupKey;
    private UpsertServiceImpl service;

    @BeforeEach
    void setUp() {
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        when(mappingMapper.selectOne(any())).thenAnswer(inv -> mappings.get(lookupKey));
        when(mappingMapper.insert(any(ExternalEntityMappingEntity.class))).thenAnswer(inv -> {
            ExternalEntityMappingEntity m = inv.getArgument(0);
            mappings.put(m.getExternalEntityId(), m);
            return 1;
        });
        when(mappingMapper.updateById(any(ExternalEntityMappingEntity.class))).thenReturn(1);

        ChannelInventorySyncMapper inventoryMapper = mock(ChannelInventorySyncMapper.class);
        when(inventoryMapper.updateById(any(ChannelInventorySyncEntity.class))).thenAnswer(inv -> {
            ChannelInventorySyncEntity e = inv.getArgument(0);
            if (inventory.containsKey(e.getId())) {
                inventory.put(e.getId(), e);
                return 1;
            }
            return 0;
        });
        when(inventoryMapper.insert(any(ChannelInventorySyncEntity.class))).thenAnswer(inv -> {
            ChannelInventorySyncEntity e = inv.getArgument(0);
            inventory.put(e.getId(), e);
            return 1;
        });

        catalogProduct = ProductEntity.builder()
                .id(UUID.randomUUID())
                .storeId(STORE_ID)
                .sku("SKU-AMZ-1")
                .name("Synced product")
                .cost(new BigDecimal("3.50"))
                .inventory(0)
                .build();
        ProductMapper productMapper = mock(ProductMapper.class);
        when(productMapper.selectOne(any())).thenReturn(catalogProduct);
        when(productMapper.updateById(any(ProductEntity.class))).thenAnswer(inv -> {
            ProductEntity update = inv.getArgument(0);
            catalogProduct.setInventory(update.getInventory());
            return 1;
        });

        InventorySnapshotMapper snapshotMapper = mock(InventorySnapshotMapper.class);
        when(snapshotMapper.selectOne(any())).thenAnswer(inv -> snapshots.values().stream().findFirst().orElse(null));
        when(snapshotMapper.insert(any(InventorySnapshotEntity.class))).thenAnswer(inv -> {
            InventorySnapshotEntity snapshot = inv.getArgument(0);
            snapshots.put(snapshot.getId(), snapshot);
            return 1;
        });
        when(snapshotMapper.updateById(any(InventorySnapshotEntity.class))).thenAnswer(inv -> {
            InventorySnapshotEntity snapshot = inv.getArgument(0);
            snapshots.put(snapshot.getId(), snapshot);
            return 1;
        });

        PerformanceDailyMapper performanceMapper = mock(PerformanceDailyMapper.class);
        when(performanceMapper.updateById(any(PerformanceDailyEntity.class))).thenAnswer(inv -> {
            PerformanceDailyEntity e = inv.getArgument(0);
            if (performance.containsKey(e.getId())) {
                performance.put(e.getId(), e);
                return 1;
            }
            return 0;
        });
        when(performanceMapper.insert(any(PerformanceDailyEntity.class))).thenAnswer(inv -> {
            PerformanceDailyEntity e = inv.getArgument(0);
            performance.put(e.getId(), e);
            return 1;
        });

        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceReferenceService marketplaceReferenceService = mock(MarketplaceReferenceService.class);
        when(marketplaceReferenceService.timezoneForMarketplace(any())).thenReturn(ZoneId.of("UTC"));

        service = new UpsertServiceImpl(mappingMapper,
                mock(ChannelOrderMapper.class), mock(ChannelProductMapper.class),
                inventoryMapper, snapshotMapper, productMapper,
                performanceMapper, storeMapper, marketplaceReferenceService, new ObjectMapper());
    }

    private UpsertResult upsert(SyncContext ctx, MappedRecord record) {
        this.lookupKey = record.externalId();
        return service.upsert(ctx, record);
    }

    @Test
    void inventoryUpsertIsIdempotentKeyedOnExternalId() {
        SyncContext ctx = new SyncContext(JOB_ID, CONNECTION_ID, STORE_ID, PLATFORM_SP, ENTITY_INVENTORY, false);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DefaultRecordMapper.F_EXTERNAL_PRODUCT_ID, "SKU-AMZ-1");
        fields.put(DefaultRecordMapper.F_SKU, "SKU-AMZ-1");
        fields.put(DefaultRecordMapper.F_QUANTITY, new BigDecimal("42"));
        MappedRecord record = new MappedRecord("SKU-AMZ-1", ENTITY_INVENTORY, STORE_ID,
                "InStock", fields, Instant.parse("2024-04-01T00:00:00Z"));

        UpsertResult first = upsert(ctx, record);
        assertThat(first.isCreated()).isTrue();
        assertThat(inventory).hasSize(1);
        assertThat(mappings).hasSize(1);
        ChannelInventorySyncEntity stored = inventory.get(first.internalId());
        assertThat(stored.getExternalProductId()).isEqualTo("SKU-AMZ-1");
        assertThat(stored.getQuantity()).isEqualTo(42);
        assertThat(catalogProduct.getInventory()).isEqualTo(42);
        assertThat(snapshots).hasSize(1);
        InventorySnapshotEntity snapshot = snapshots.values().iterator().next();
        assertThat(snapshot.getStoreId()).isEqualTo(STORE_ID);
        assertThat(snapshot.getProductId()).isEqualTo(catalogProduct.getId());
        assertThat(snapshot.getSnapshotDate()).isEqualTo(LocalDate.now());
        assertThat(snapshot.getTotalInventory()).isEqualTo(42);
        assertThat(snapshot.getInventoryValue()).isEqualByComparingTo(new BigDecimal("147.00"));

        // Reprocessing the same external id updates the same internal record (idempotent).
        UpsertResult second = upsert(ctx, record);
        assertThat(second.isUpdated()).isTrue();
        assertThat(second.internalId()).isEqualTo(first.internalId());
        assertThat(inventory).hasSize(1);
        assertThat(mappings).hasSize(1);
        assertThat(snapshots).hasSize(1);
    }

    @Test
    void adReportUpsertIsIdempotentKeyedOnExternalId() {
        SyncContext ctx = new SyncContext(JOB_ID, CONNECTION_ID, STORE_ID, PLATFORM_ADS, ENTITY_AD_REPORT, false);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DefaultRecordMapper.F_EXTERNAL_CAMPAIGN_ID, "AMZ-CAMP-1");
        fields.put(DefaultRecordMapper.F_ENTITY_TYPE, "campaign");
        fields.put(DefaultRecordMapper.F_DATE, Instant.parse("2024-03-01T00:00:00Z"));
        fields.put(DefaultRecordMapper.F_IMPRESSIONS, new BigDecimal("1000"));
        fields.put(DefaultRecordMapper.F_CLICKS, new BigDecimal("25"));
        fields.put(DefaultRecordMapper.F_SPEND, new BigDecimal("12.50"));
        fields.put(DefaultRecordMapper.F_SALES, new BigDecimal("99.00"));
        MappedRecord record = new MappedRecord("AMZ-CAMP-1#2024-03-01", ENTITY_AD_REPORT, STORE_ID,
                null, fields, Instant.parse("2024-03-01T00:00:00Z"));

        UpsertResult first = upsert(ctx, record);
        assertThat(first.isCreated()).isTrue();
        assertThat(performance).hasSize(1);
        assertThat(mappings).hasSize(1);
        PerformanceDailyEntity stored = performance.get(first.internalId());
        assertThat(stored.getStoreId()).isEqualTo(STORE_ID);
        assertThat(stored.getEntityType()).isEqualTo("campaign");
        assertThat(stored.getEntityId()).isEqualTo(first.internalId());
        assertThat(stored.getImpressions()).isEqualTo(1000L);
        assertThat(stored.getClicks()).isEqualTo(25);
        assertThat(stored.getSpend()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(stored.getSales()).isEqualByComparingTo(new BigDecimal("99.00"));

        // Same external id (campaign#date) updates the same row (idempotent).
        UpsertResult second = upsert(ctx, record);
        assertThat(second.isUpdated()).isTrue();
        assertThat(second.internalId()).isEqualTo(first.internalId());
        assertThat(performance).hasSize(1);
        assertThat(mappings).hasSize(1);
    }
}
