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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_ORDER;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_PRODUCT;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_CURRENCY;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_ORDER_STATUS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_PRICE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_SKU;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_STATUS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_TITLE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_TOTAL_AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link UpsertServiceImpl#upsert}.
 *
 * Feature: core-platform-completion, Property 4: Upsert is idempotent and keyed
 * on the external identifier.
 *
 * For any set of external records, processing them once produces the same set
 * of internal records and external-to-internal mappings as processing them two
 * or more times; a record whose external id already maps updates the existing
 * internal record, and a record with an unmapped external id creates exactly one
 * internal record plus its mapping.
 *
 * The mappers are replaced with stateful in-memory mocks (mirroring the
 * {@code WatermarkStorePropertyTest} mocking style) so the upsert keying and
 * idempotency logic is exercised without a database.
 *
 * Validates: Requirements 1.2.1, 1.2.2, 1.2.3, 8.1.4
 */
class UpsertServicePropertyTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final String PLATFORM = "woocommerce";
    /** External ids carry this prefix purely for readability in failure samples. */
    private static final String EXT_PREFIX = "ext-";

    /**
     * The external id the in-memory mapping mapper should resolve on the next
     * {@code selectOne} call. {@link UpsertServiceImpl#upsert} performs exactly
     * one mapping lookup per call, so setting this immediately before each
     * {@code upsert} faithfully emulates the keyed
     * {@code (store, platform, entityType, externalId)} lookup without needing a
     * real database or query-wrapper introspection.
     */
    private String lookupKey;

    /** A generated scenario: a batch of distinct external records and how many extra times to reprocess it. */
    record Scenario(List<MappedRecord> records, int extraRuns) {
    }

    // Feature: core-platform-completion, Property 4: Upsert is idempotent and keyed on the external identifier
    @Property(tries = 200)
    void upsertIsIdempotentAndKeyedOnExternalId(@ForAll("scenarios") Scenario scenario) {
        // Stateful in-memory backing stores for the three mappers.
        Map<String, ExternalEntityMappingEntity> mappings = new HashMap<>();
        Map<UUID, ChannelOrderEntity> orders = new HashMap<>();
        Map<UUID, ChannelProductEntity> products = new HashMap<>();

        UpsertServiceImpl service = buildService(mappings, orders, products);
        SyncContext orderCtx = new SyncContext(JOB_ID, CONNECTION_ID, STORE_ID, PLATFORM, ENTITY_ORDER, false);
        SyncContext productCtx = new SyncContext(JOB_ID, CONNECTION_ID, STORE_ID, PLATFORM, ENTITY_PRODUCT, false);

        long distinctExternalIds = scenario.records().stream().map(MappedRecord::externalId).distinct().count();
        long expectedOrders = scenario.records().stream().filter(r -> ENTITY_ORDER.equals(r.entityType())).count();
        long expectedProducts = scenario.records().stream().filter(r -> ENTITY_PRODUCT.equals(r.entityType())).count();

        // --- first processing run: every distinct external id is unmapped -> CREATED + new mapping (Req 1.2.2)
        for (MappedRecord record : scenario.records()) {
            UpsertResult result = upsert(service, ctxFor(record, orderCtx, productCtx), record);
            assertThat(result.isCreated())
                    .as("first sight of external id %s should create", record.externalId())
                    .isTrue();
        }

        // Exactly one internal record + one mapping per distinct external id (Req 1.2.2).
        assertThat(mappings).hasSize((int) distinctExternalIds);
        assertThat(orders).hasSize((int) expectedOrders);
        assertThat(products).hasSize((int) expectedProducts);

        // Capture the post-first-run state: external id -> internal id, and internal record counts.
        Map<String, UUID> internalIdByExternalId = new HashMap<>();
        mappings.forEach((extId, m) -> internalIdByExternalId.put(extId, m.getInternalEntityId()));
        int mappingCountAfterFirst = mappings.size();
        int orderCountAfterFirst = orders.size();
        int productCountAfterFirst = products.size();

        // --- reprocess the same batch one or more additional times (Req 1.2.1, 1.2.3 idempotence)
        for (int run = 0; run < scenario.extraRuns(); run++) {
            for (MappedRecord record : scenario.records()) {
                UpsertResult result = upsert(service, ctxFor(record, orderCtx, productCtx), record);
                // Already mapped -> updates the existing internal record, never creates (Req 1.2.1).
                assertThat(result.isUpdated())
                        .as("re-processing external id %s should update", record.externalId())
                        .isTrue();
                // Same external id keeps the same internal id (keyed on external identifier, Req 8.1.4).
                assertThat(result.internalId())
                        .as("internal id stable for external id %s", record.externalId())
                        .isEqualTo(internalIdByExternalId.get(record.externalId()));
            }
        }

        // Idempotence: the resulting set of internal records and mappings is unchanged by reprocessing (Req 1.2.3).
        assertThat(mappings).hasSize(mappingCountAfterFirst);
        assertThat(orders).hasSize(orderCountAfterFirst);
        assertThat(products).hasSize(productCountAfterFirst);
        mappings.forEach((extId, m) ->
                assertThat(m.getInternalEntityId()).isEqualTo(internalIdByExternalId.get(extId)));
    }

    /**
     * Set the lookup key the mapping mapper should resolve, then run the single
     * upsert that consumes it. Mirrors the keyed lookup the real service does.
     */
    private UpsertResult upsert(UpsertServiceImpl service, SyncContext ctx, MappedRecord record) {
        this.lookupKey = record.externalId();
        return service.upsert(ctx, record);
    }

    private SyncContext ctxFor(MappedRecord record, SyncContext orderCtx, SyncContext productCtx) {
        return ENTITY_ORDER.equals(record.entityType()) ? orderCtx : productCtx;
    }

    // --- wiring ---------------------------------------------------------------

    private UpsertServiceImpl buildService(Map<String, ExternalEntityMappingEntity> mappings,
                                           Map<UUID, ChannelOrderEntity> orders,
                                           Map<UUID, ChannelProductEntity> products) {
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        when(mappingMapper.selectOne(any())).thenAnswer(inv -> mappings.get(lookupKey));
        when(mappingMapper.insert(any(ExternalEntityMappingEntity.class))).thenAnswer(inv -> {
            ExternalEntityMappingEntity m = inv.getArgument(0);
            mappings.put(m.getExternalEntityId(), m);
            return 1;
        });
        when(mappingMapper.updateById(any(ExternalEntityMappingEntity.class))).thenReturn(1);

        ChannelOrderMapper orderMapper = mock(ChannelOrderMapper.class);
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

        ChannelProductMapper productMapper = mock(ChannelProductMapper.class);
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

        return new UpsertServiceImpl(mappingMapper, orderMapper, productMapper,
                mock(ChannelInventorySyncMapper.class),
                mock(com.adpilot.modules.inventory.mapper.InventorySnapshotMapper.class),
                mock(com.adpilot.modules.product.mapper.ProductMapper.class),
                mock(PerformanceDailyMapper.class),
                mock(com.adpilot.modules.store.mapper.StoreMapper.class),
                mock(com.adpilot.modules.store.service.MarketplaceReferenceService.class),
                new ObjectMapper());
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Integer> extraRuns = Arbitraries.integers().between(1, 3);
        return Combinators.combine(records(), extraRuns).as(Scenario::new);
    }

    /**
     * A non-empty batch of records with distinct external ids. External ids are
     * assigned by position to guarantee distinctness, so the first run creates
     * exactly one internal record per record.
     */
    private Arbitrary<List<MappedRecord>> records() {
        return rawRecords().map(raw -> {
            List<MappedRecord> result = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                RawRecord r = raw.get(i);
                String externalId = EXT_PREFIX + i + "-" + UUID.randomUUID();
                result.add(toMappedRecord(externalId, r));
            }
            return result;
        });
    }

    private Arbitrary<List<RawRecord>> rawRecords() {
        return Combinators.combine(
                        Arbitraries.of(ENTITY_ORDER, ENTITY_PRODUCT),
                        activeStatuses(),
                        amounts(),
                        Arbitraries.of("USD", "EUR", "GBP", "CNY"),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12),
                        Arbitraries.longs().between(0L, 4_000_000_000_000L))
                .as(RawRecord::new)
                .list().ofMinSize(1).ofMaxSize(30);
    }

    private Arbitrary<String> activeStatuses() {
        // Deliberately avoid removed/cancelled statuses; those are covered by Property 5.
        return Arbitraries.of("processing", "completed", "pending", "on-hold", "active", "published");
    }

    private Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs().between(0L, 100_000_00L).map(l -> new BigDecimal(l).movePointLeft(2));
    }

    private MappedRecord toMappedRecord(String externalId, RawRecord r) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(F_CURRENCY, r.currency());
        if (ENTITY_ORDER.equals(r.entityType())) {
            fields.put(F_ORDER_STATUS, r.status());
            fields.put(F_TOTAL_AMOUNT, r.amount());
        } else {
            fields.put(F_STATUS, r.status());
            fields.put(F_SKU, r.text());
            fields.put(F_TITLE, r.text());
            fields.put(F_PRICE, r.amount());
        }
        return new MappedRecord(externalId, r.entityType(), STORE_ID, r.status(), fields,
                Instant.ofEpochMilli(r.changedAtMillis()));
    }

    /** Raw generated values for a single record before an external id is assigned. */
    record RawRecord(String entityType, String status, BigDecimal amount,
                     String currency, String text, long changedAtMillis) {
    }
}
