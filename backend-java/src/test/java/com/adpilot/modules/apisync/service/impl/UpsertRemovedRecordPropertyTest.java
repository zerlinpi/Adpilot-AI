package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_ORDER;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link UpsertServiceImpl#upsert}.
 *
 * Feature: core-platform-completion, Property 5: Externally removed records are
 * status-marked, never physically deleted.
 *
 * For any previously-mapped internal record that the platform reports as
 * deleted/cancelled (any of the recognized removed statuses, case-insensitive),
 * processing the record leaves the internal row in place and marks its status
 * (cancelled for orders, inactive for products). No physical delete is ever
 * issued against the channel mappers.
 *
 * Validates: Requirements 1.2.4
 */
class UpsertRemovedRecordPropertyTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String PLATFORM = "woocommerce";

    private static final String ORDER_REMOVED_STATUS = "cancelled";
    private static final String PRODUCT_REMOVED_STATUS = "inactive";

    /** A generated scenario for a previously-mapped record reported as removed. */
    record Scenario(String entityType,
                    String externalId,
                    String removedStatus,
                    String priorActiveStatus) {
    }

    // Feature: core-platform-completion, Property 5: Externally removed records are status-marked, never physically deleted
    @Property(tries = 200)
    void removedRecordsAreStatusMarkedNeverDeleted(@ForAll("scenarios") Scenario scenario) {
        boolean isOrder = ENTITY_ORDER.equals(scenario.entityType());

        // The internal record already exists and is mapped to the external id.
        UUID internalId = UUID.randomUUID();

        // Stateful in-memory backing for the mapping row and the channel row.
        ExternalEntityMappingEntity mapping = ExternalEntityMappingEntity.builder()
                .id(UUID.randomUUID())
                .storeId(STORE_ID)
                .platform(PLATFORM)
                .internalEntityType(scenario.entityType())
                .internalEntityId(internalId)
                .externalEntityType(scenario.entityType())
                .externalEntityId(scenario.externalId())
                .externalData("{}")
                .lastSyncedAt(LocalDateTime.now())
                .build();

        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        when(mappingMapper.selectOne(any())).thenReturn(mapping);
        when(mappingMapper.updateById(any(ExternalEntityMappingEntity.class))).thenReturn(1);

        Map<UUID, ChannelOrderEntity> orders = new HashMap<>();
        Map<UUID, ChannelProductEntity> products = new HashMap<>();

        ChannelOrderMapper orderMapper = mock(ChannelOrderMapper.class);
        ChannelProductMapper productMapper = mock(ChannelProductMapper.class);

        if (isOrder) {
            orders.put(internalId, ChannelOrderEntity.builder()
                    .id(internalId)
                    .connectionId(CONNECTION_ID)
                    .externalOrderId(scenario.externalId())
                    .orderStatus(scenario.priorActiveStatus())
                    .build());
        } else {
            products.put(internalId, ChannelProductEntity.builder()
                    .id(internalId)
                    .connectionId(CONNECTION_ID)
                    .externalProductId(scenario.externalId())
                    .status(scenario.priorActiveStatus())
                    .build());
        }

        when(orderMapper.updateById(any(ChannelOrderEntity.class))).thenAnswer(inv -> {
            ChannelOrderEntity e = inv.getArgument(0);
            if (orders.containsKey(e.getId())) {
                orders.put(e.getId(), e);
                return 1;
            }
            return 0;
        });
        when(orderMapper.insert(any(ChannelOrderEntity.class))).thenAnswer(inv -> {
            ChannelOrderEntity e = inv.getArgument(0);
            orders.put(e.getId(), e);
            return 1;
        });
        when(productMapper.updateById(any(ChannelProductEntity.class))).thenAnswer(inv -> {
            ChannelProductEntity e = inv.getArgument(0);
            if (products.containsKey(e.getId())) {
                products.put(e.getId(), e);
                return 1;
            }
            return 0;
        });
        when(productMapper.insert(any(ChannelProductEntity.class))).thenAnswer(inv -> {
            ChannelProductEntity e = inv.getArgument(0);
            products.put(e.getId(), e);
            return 1;
        });

        UpsertServiceImpl service = new UpsertServiceImpl(
                mappingMapper, orderMapper, productMapper,
                mock(ChannelInventorySyncMapper.class),
                mock(com.adpilot.modules.inventory.mapper.InventorySnapshotMapper.class),
                mock(com.adpilot.modules.product.mapper.ProductMapper.class),
                mock(PerformanceDailyMapper.class),
                mock(com.adpilot.modules.store.mapper.StoreMapper.class),
                mock(com.adpilot.modules.store.service.MarketplaceReferenceService.class),
                new ObjectMapper());

        SyncContext ctx = new SyncContext(
                UUID.randomUUID(), CONNECTION_ID, STORE_ID, PLATFORM, scenario.entityType(), false);

        Map<String, Object> fields = new LinkedHashMap<>();
        // Include an active status in the mapped fields to prove the removed
        // status takes precedence over the field-level status.
        if (isOrder) {
            fields.put(DefaultRecordMapper.F_ORDER_STATUS, scenario.priorActiveStatus());
        } else {
            fields.put(DefaultRecordMapper.F_STATUS, scenario.priorActiveStatus());
        }

        MappedRecord record = new MappedRecord(
                scenario.externalId(),
                scenario.entityType(),
                STORE_ID,
                scenario.removedStatus(),
                fields,
                Instant.now());

        UpsertResult result = service.upsert(ctx, record);

        // The previously-mapped record is updated in place, not recreated.
        assertThat(result.isUpdated()).isTrue();
        assertThat(result.internalId()).isEqualTo(internalId);

        if (isOrder) {
            // The internal row still exists (never physically deleted).
            assertThat(orders).containsKey(internalId);
            // Its status is marked cancelled.
            assertThat(orders.get(internalId).getOrderStatus()).isEqualTo(ORDER_REMOVED_STATUS);
            // No physical delete was ever issued.
            verify(orderMapper, never()).deleteById(any(java.io.Serializable.class));
            verify(orderMapper, never()).deleteById(any(ChannelOrderEntity.class));
            verify(orderMapper, never()).delete(any());
            verify(orderMapper, never()).deleteByMap(any());
            verify(orderMapper, never()).deleteBatchIds(any());
        } else {
            assertThat(products).containsKey(internalId);
            assertThat(products.get(internalId).getStatus()).isEqualTo(PRODUCT_REMOVED_STATUS);
            verify(productMapper, never()).deleteById(any(java.io.Serializable.class));
            verify(productMapper, never()).deleteById(any(ChannelProductEntity.class));
            verify(productMapper, never()).delete(any());
            verify(productMapper, never()).deleteByMap(any());
            verify(productMapper, never()).deleteBatchIds(any());
        }
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<String> entityType = Arbitraries.of(ENTITY_ORDER, ENTITY_PRODUCT);
        Arbitrary<String> externalId = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(24);
        Arbitrary<String> removedStatus = removedStatuses();
        Arbitrary<String> priorActiveStatus = Arbitraries.of(
                "active", "processing", "completed", "open", "published", "pending");
        return Combinators.combine(entityType, externalId, removedStatus, priorActiveStatus)
                .as(Scenario::new);
    }

    /**
     * Recognized removed statuses, generated in mixed case to also exercise the
     * case-insensitive matching guaranteed by Req 1.2.4.
     */
    private Arbitrary<String> removedStatuses() {
        Arbitrary<String> base = Arbitraries.of(
                "deleted", "trash", "trashed", "cancelled", "canceled",
                "refunded", "archived", "removed", "inactive");
        Arbitrary<Integer> casing = Arbitraries.integers().between(0, 2);
        return Combinators.combine(base, casing).as((s, c) -> switch (c) {
            case 0 -> s;
            case 1 -> s.toUpperCase(java.util.Locale.ROOT);
            default -> "  " + s + "  "; // leading/trailing whitespace is trimmed by the service
        });
    }
}
