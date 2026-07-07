package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.apisync.entity.ChannelInventorySyncEntity;
import com.adpilot.modules.apisync.entity.ChannelOrderEntity;
import com.adpilot.modules.apisync.entity.ChannelProductEntity;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ChannelInventorySyncMapper;
import com.adpilot.modules.apisync.mapper.ChannelOrderMapper;
import com.adpilot.modules.apisync.mapper.ChannelProductMapper;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.SyncContext;
import com.adpilot.modules.apisync.model.UpsertResult;
import com.adpilot.modules.apisync.service.UpsertService;
import com.adpilot.modules.inventory.entity.InventorySnapshotEntity;
import com.adpilot.modules.inventory.mapper.InventorySnapshotMapper;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_AD_REPORT;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_INVENTORY;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_ORDER;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_PRODUCT;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_ACOS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_AVG_CPC;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_CLICKS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_CTR;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_CURRENCY;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_CVR;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_DATE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_ENTITY_TYPE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_IMPRESSIONS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_ORDER_DATE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_ORDER_STATUS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_ORDERS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_PRICE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_QUANTITY;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_RAW_DATA;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_ROAS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_SALES;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_SKU;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_SPEND;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_STATUS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_TITLE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_TOTAL_AMOUNT;

/**
 * Default {@link UpsertService} keyed on the {@code external_entity_mappings}
 * unique key {@code (store_id, platform, internal_entity_type, external_entity_id)}
 * (Req 1.2).
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>When a mapping already exists for the external id, the linked internal
 *       record is updated and {@link UpsertResult.Outcome#UPDATED} is returned
 *       (Req 1.2.1).</li>
 *   <li>When the external id is unmapped, exactly one internal record is created
 *       together with its mapping and {@link UpsertResult.Outcome#CREATED} is
 *       returned (Req 1.2.2).</li>
 *   <li>Looking up the mapping before writing makes repeated processing of the
 *       same external records idempotent (Req 1.2.3).</li>
 *   <li>When the platform reports the record as removed (deleted/cancelled/etc.),
 *       the existing internal record is status-marked cancelled/inactive rather
 *       than physically deleted (Req 1.2.4).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpsertServiceImpl implements UpsertService {

    /**
     * Platform-reported statuses that mean the external record has been removed
     * and the internal row must be status-marked rather than left active.
     * Compared case-insensitively (Req 1.2.4).
     */
    private static final Set<String> REMOVED_STATUSES = Set.of(
            "deleted", "trash", "trashed", "cancelled", "canceled",
            "refunded", "archived", "removed", "inactive");

    /** Canonical internal status for a removed order. */
    private static final String ORDER_REMOVED_STATUS = "cancelled";
    /** Canonical internal status for a removed product. */
    private static final String PRODUCT_REMOVED_STATUS = "inactive";

    private final ExternalEntityMappingMapper mappingMapper;
    private final ChannelOrderMapper channelOrderMapper;
    private final ChannelProductMapper channelProductMapper;
    private final ChannelInventorySyncMapper channelInventorySyncMapper;
    private final InventorySnapshotMapper inventorySnapshotMapper;
    private final ProductMapper productMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final StoreMapper storeMapper;
    private final MarketplaceReferenceService marketplaceReferenceService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public UpsertResult upsert(SyncContext ctx, MappedRecord record) {
        if (ctx == null) {
            throw new IllegalArgumentException("SyncContext is required");
        }
        if (record == null) {
            throw new IllegalArgumentException("MappedRecord is required");
        }
        String entityType = record.entityType();
        if (entityType == null) {
            throw new IllegalArgumentException("MappedRecord.entityType is required");
        }
        if (record.externalId() == null || record.externalId().isBlank()) {
            throw new IllegalArgumentException("MappedRecord.externalId is required for idempotent upsert");
        }
        if (!isSupported(entityType)) {
            throw new IllegalArgumentException("Unsupported entity type for upsert: '" + entityType + "'");
        }

        boolean removed = isRemoved(record.status());
        ExternalEntityMappingEntity mapping = findMapping(ctx, record);

        if (mapping != null) {
            // Req 1.2.1 / 1.2.3: update the already-mapped internal record in place.
            UUID internalId = mapping.getInternalEntityId();
            writeEntity(entityType, internalId, ctx, record, removed);
            touchMapping(mapping, record);
            return UpsertResult.updated(internalId);
        }

        // Req 1.2.2: unmapped external id -> create one internal record + mapping.
        UUID internalId = UUID.randomUUID();
        writeEntity(entityType, internalId, ctx, record, removed);
        createMapping(ctx, record, internalId);
        return UpsertResult.created(internalId);
    }

    private boolean isSupported(String entityType) {
        return ENTITY_ORDER.equals(entityType)
                || ENTITY_PRODUCT.equals(entityType)
                || ENTITY_INVENTORY.equals(entityType)
                || ENTITY_AD_REPORT.equals(entityType);
    }

    /** Dispatch the write to the per-entity-type internal table. */
    private void writeEntity(String entityType, UUID internalId, SyncContext ctx,
                             MappedRecord record, boolean removed) {
        switch (entityType) {
            case ENTITY_ORDER -> writeOrder(internalId, ctx, record, removed);
            case ENTITY_PRODUCT -> writeProduct(internalId, ctx, record, removed);
            case ENTITY_INVENTORY -> writeInventory(internalId, ctx, record);
            case ENTITY_AD_REPORT -> writeAdReport(internalId, record);
            default -> throw new IllegalArgumentException(
                    "Unsupported entity type for upsert: '" + entityType + "'");
        }
    }

    // --- mapping lookup / persistence ----------------------------------------

    private ExternalEntityMappingEntity findMapping(SyncContext ctx, MappedRecord record) {
        LambdaQueryWrapper<ExternalEntityMappingEntity> wrapper =
                new LambdaQueryWrapper<ExternalEntityMappingEntity>()
                        .eq(ExternalEntityMappingEntity::getStoreId, record.storeId())
                        .eq(ExternalEntityMappingEntity::getPlatform, ctx.platform())
                        .eq(ExternalEntityMappingEntity::getInternalEntityType, record.entityType())
                        .eq(ExternalEntityMappingEntity::getExternalEntityId, record.externalId())
                        .last("limit 1");
        return mappingMapper.selectOne(wrapper);
    }

    private void createMapping(SyncContext ctx, MappedRecord record, UUID internalId) {
        ExternalEntityMappingEntity mapping = ExternalEntityMappingEntity.builder()
                .id(UUID.randomUUID())
                .storeId(record.storeId())
                .platform(ctx.platform())
                .internalEntityType(record.entityType())
                .internalEntityId(internalId)
                .externalEntityType(record.entityType())
                .externalEntityId(record.externalId())
                .externalData("{}")
                .lastSyncedAt(LocalDateTime.now())
                .build();
        mappingMapper.insert(mapping);
    }

    private void touchMapping(ExternalEntityMappingEntity mapping, MappedRecord record) {
        ExternalEntityMappingEntity update = new ExternalEntityMappingEntity();
        update.setId(mapping.getId());
        update.setLastSyncedAt(LocalDateTime.now());
        mappingMapper.updateById(update);
    }

    // --- channel_orders -------------------------------------------------------

    private void writeOrder(UUID internalId, SyncContext ctx, MappedRecord record, boolean removed) {
        Map<String, Object> fields = safeFields(record);
        String status = removed
                ? ORDER_REMOVED_STATUS
                : firstNonBlank(getString(fields, F_ORDER_STATUS), record.status());

        ChannelOrderEntity entity = ChannelOrderEntity.builder()
                .id(internalId)
                .connectionId(ctx.connectionId())
                .externalOrderId(record.externalId())
                .orderStatus(status)
                .totalAmount(getDecimal(fields, F_TOTAL_AMOUNT))
                .currency(getString(fields, F_CURRENCY))
                .orderDate(getDateTime(fields, F_ORDER_DATE))
                .rawData(toJson(fields.get(F_RAW_DATA)))
                .lastSyncedAt(LocalDateTime.now())
                .build();

        // updateById is a no-op when the row does not yet exist; insert in that case.
        if (channelOrderMapper.updateById(entity) == 0) {
            channelOrderMapper.insert(entity);
        }
    }

    // --- channel_products -----------------------------------------------------

    private void writeProduct(UUID internalId, SyncContext ctx, MappedRecord record, boolean removed) {
        Map<String, Object> fields = safeFields(record);
        String status = removed
                ? PRODUCT_REMOVED_STATUS
                : firstNonBlank(getString(fields, F_STATUS), record.status());

        ChannelProductEntity entity = ChannelProductEntity.builder()
                .id(internalId)
                .connectionId(ctx.connectionId())
                .externalProductId(record.externalId())
                .sku(getString(fields, F_SKU))
                .title(getString(fields, F_TITLE))
                .price(getDecimal(fields, F_PRICE))
                .currency(getString(fields, F_CURRENCY))
                .status(status)
                .rawData(toJson(fields.get(F_RAW_DATA)))
                .lastSyncedAt(LocalDateTime.now())
                .build();

        if (channelProductMapper.updateById(entity) == 0) {
            channelProductMapper.insert(entity);
        }
    }

    // --- channel_inventory_sync (Amazon SP-API inventory, Req 8.1.1/8.1.4) ----

    private void writeInventory(UUID internalId, SyncContext ctx, MappedRecord record) {
        Map<String, Object> fields = safeFields(record);
        ChannelInventorySyncEntity entity = ChannelInventorySyncEntity.builder()
                .id(internalId)
                .connectionId(ctx.connectionId())
                .sku(getString(fields, F_SKU))
                .externalProductId(record.externalId())
                .quantity(getIntegerOrZero(fields, F_QUANTITY))
                .syncDirection("inbound")
                .lastSyncedAt(LocalDateTime.now())
                .build();

        if (channelInventorySyncMapper.updateById(entity) == 0) {
            channelInventorySyncMapper.insert(entity);
        }
        projectInventorySnapshot(record.storeId(), entity.getSku(), entity.getQuantity());
    }

    private void projectInventorySnapshot(UUID storeId, String sku, int quantity) {
        if (storeId == null || sku == null || sku.isBlank()) {
            return;
        }
        LambdaQueryWrapper<ProductEntity> productQuery = new LambdaQueryWrapper<>();
        productQuery.eq(ProductEntity::getStoreId, storeId)
                .eq(ProductEntity::getSku, sku)
                .last("limit 1");
        ProductEntity product = productMapper.selectOne(productQuery);
        if (product == null) {
            log.warn("Inventory sync could not project SKU {} for store {} because no catalog product matched", sku, storeId);
            return;
        }

        int safeQuantity = Math.max(0, quantity);
        ProductEntity productUpdate = new ProductEntity();
        productUpdate.setId(product.getId());
        productUpdate.setInventory(safeQuantity);
        productMapper.updateById(productUpdate);

        // The snapshot day must be computed in the store's marketplace timezone,
        // not the server JVM zone: orders/metrics are stored in UTC and a store can
        // be in any marketplace, so "today" only makes sense relative to that
        // marketplace's civil day.
        LocalDate snapshotDate = LocalDate.now(zoneForStore(storeId));
        LambdaQueryWrapper<InventorySnapshotEntity> snapshotQuery = new LambdaQueryWrapper<>();
        snapshotQuery.eq(InventorySnapshotEntity::getStoreId, storeId)
                .eq(InventorySnapshotEntity::getProductId, product.getId())
                .eq(InventorySnapshotEntity::getSnapshotDate, snapshotDate)
                .last("limit 1");
        InventorySnapshotEntity existingSnapshot = inventorySnapshotMapper.selectOne(snapshotQuery);
        boolean create = existingSnapshot == null;
        InventorySnapshotEntity snapshot = existingSnapshot != null
                ? existingSnapshot : new InventorySnapshotEntity();
        if (create) {
            snapshot.setId(UUID.randomUUID());
            snapshot.setStoreId(storeId);
            snapshot.setProductId(product.getId());
            snapshot.setSnapshotDate(snapshotDate);
            snapshot.setCreatedAt(LocalDateTime.now());
        }
        snapshot.setAvailableInventory(safeQuantity);
        snapshot.setReservedInventory(0);
        snapshot.setInboundInventory(0);
        snapshot.setTransferInventory(0);
        snapshot.setUnsellableInventory(0);
        snapshot.setTotalInventory(safeQuantity);
        BigDecimal unitCost = product.getCost() != null ? product.getCost() : BigDecimal.ZERO;
        snapshot.setInventoryValue(unitCost.multiply(BigDecimal.valueOf(safeQuantity)));
        if (create) {
            inventorySnapshotMapper.insert(snapshot);
        } else {
            inventorySnapshotMapper.updateById(snapshot);
        }
    }

    /**
     * Resolve the civil-day timezone for a store via its marketplace. Falls back to
     * UTC (never the JVM default) when the store is unknown or has no marketplace.
     */
    private ZoneId zoneForStore(UUID storeId) {
        UUID marketplaceId = null;
        if (storeId != null) {
            StoreEntity store = storeMapper.selectById(storeId);
            if (store != null) {
                marketplaceId = store.getMarketplaceId();
            }
        }
        return marketplaceReferenceService.timezoneForMarketplace(marketplaceId);
    }

    // --- performance_daily (Amazon Ads reports, Req 8.1.3/8.1.4) --------------

    private void writeAdReport(UUID internalId, MappedRecord record) {
        Map<String, Object> fields = safeFields(record);
        PerformanceDailyEntity entity = PerformanceDailyEntity.builder()
                .id(internalId)
                .storeId(record.storeId())
                .entityType(firstNonBlank(getString(fields, F_ENTITY_TYPE), "campaign"))
                // entity_id is NOT NULL; anchor it to the internal record id so the
                // mapping (keyed on the Amazon external id) stays idempotent.
                .entityId(internalId)
                .date(getDate(fields, F_DATE))
                .impressions(getLongOrZero(fields, F_IMPRESSIONS))
                .clicks(getIntegerOrZero(fields, F_CLICKS))
                .spend(getDecimalOrZero(fields, F_SPEND))
                .sales(getDecimalOrZero(fields, F_SALES))
                .orders(getIntegerOrZero(fields, F_ORDERS))
                .acos(getDecimalOrZero(fields, F_ACOS))
                .roas(getDecimalOrZero(fields, F_ROAS))
                .ctr(getDecimalOrZero(fields, F_CTR))
                .cvr(getDecimalOrZero(fields, F_CVR))
                .avgCpc(getDecimalOrZero(fields, F_AVG_CPC))
                .build();

        if (performanceDailyMapper.updateById(entity) == 0) {
            performanceDailyMapper.insert(entity);
        }
    }

    // --- helpers --------------------------------------------------------------

    private boolean isRemoved(String status) {
        if (status == null) {
            return false;
        }
        return REMOVED_STATUSES.contains(status.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> safeFields(MappedRecord record) {
        return record.fields() == null ? Map.of() : record.fields();
    }

    private static String getString(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal getDecimal(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return DefaultRecordMapper.toDecimal(value);
    }

    private static LocalDateTime getDateTime(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        Instant instant = value instanceof Instant i ? i : DefaultRecordMapper.toInstant(value);
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static LocalDate getDate(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value instanceof LocalDate ld) {
            return ld;
        }
        Instant instant = value instanceof Instant i ? i : DefaultRecordMapper.toInstant(value);
        return instant == null ? null : LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

    private static BigDecimal getDecimalOrZero(Map<String, Object> fields, String key) {
        BigDecimal value = getDecimal(fields, key);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Integer getIntegerOrZero(Map<String, Object> fields, String key) {
        BigDecimal value = getDecimal(fields, key);
        return value == null ? Integer.valueOf(0) : Integer.valueOf(value.intValue());
    }

    private static Long getLongOrZero(Map<String, Object> fields, String key) {
        BigDecimal value = getDecimal(fields, key);
        return value == null ? Long.valueOf(0L) : Long.valueOf(value.longValue());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }

    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof String s) {
            return s.isBlank() ? "{}" : s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize raw_data to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
