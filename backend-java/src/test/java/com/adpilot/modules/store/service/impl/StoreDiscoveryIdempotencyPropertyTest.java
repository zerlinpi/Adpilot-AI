package com.adpilot.modules.store.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.PlatformDataConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.DiscoveredStore;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.StoreDiscoveryResult;
import com.adpilot.modules.store.vo.StoreVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link StoreDiscoveryServiceImpl#discover}.
 *
 * Feature: core-platform-completion, Property 18: Store discovery is idempotent
 * and links the marketplace identifier.
 *
 * <p>For any set of discovered marketplaces, the first discovery creates exactly
 * one internal store per previously-unseen marketplace — linked to the
 * originating seller account (the originating connection's organization) and
 * carrying the platform-reported marketplace identifier — while a second
 * discovery over the same set creates nothing and reuses every existing store,
 * so repeated discovery never produces duplicates.</p>
 *
 * <p>The mappers ({@link PlatformConnectionMapper}, {@link StoreMapper},
 * {@link MarketplaceMapper}), {@link CryptoUtil} and {@link ObjectMapper} are
 * mocked; the store mapper is backed by a stateful in-memory map so create and
 * reuse behaviour is exercised without a database. A stub
 * {@link PlatformDataConnector} returns a controllable list of
 * {@link DiscoveredStore}.</p>
 *
 * Validates: Requirements 6.1.2, 6.1.3, 6.1.4
 */
class StoreDiscoveryIdempotencyPropertyTest {

    private static final String PLATFORM = "amazon_sp_api";
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID ORIGINATING_STORE_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID FALLBACK_MARKETPLACE_REF = UUID.randomUUID();

    /** Mutable list the stub connector returns from discoverStores. */
    private final StubConnector connector = new StubConnector();

    // Feature: core-platform-completion, Property 18: Store discovery is idempotent and links the marketplace identifier
    @Property(tries = 200)
    void discoveryCreatesOnePerUnseenMarketplaceAndReusesOnRepeat(
            @ForAll("marketplaceSets") List<DiscoveredStore> discovered) {

        // Distinct, non-blank marketplace identifiers are the idempotency anchors.
        List<String> distinctMarketplaceIds = discovered.stream()
                .map(DiscoveredStore::marketplaceId)
                .distinct()
                .collect(Collectors.toList());
        int expectedStores = distinctMarketplaceIds.size();

        // Stateful in-memory backing for the stores table.
        Map<UUID, StoreEntity> stores = new HashMap<>();
        StoreDiscoveryServiceImpl service = buildService(stores);
        connector.toReturn = discovered;
        CurrentUser user = CurrentUser.builder().userId(UUID.randomUUID().toString()).build();

        // --- first discovery: every marketplace is previously-unseen (Req 6.1.2, 6.1.4) ---
        StoreDiscoveryResult first = service.discover(CONNECTION_ID, user);

        assertThat(first.created())
                .as("first discovery creates one store per previously-unseen marketplace")
                .hasSize(expectedStores);
        assertThat(first.reused())
                .as("first discovery reuses nothing")
                .isEmpty();

        // One backing store per distinct marketplace identifier (no duplicates).
        assertThat(stores).hasSize(expectedStores);

        // Every created store carries the reported marketplace identifier (Req 6.1.4)
        // and is linked to the originating seller account's organization (Req 6.1.2).
        Map<String, StoreEntity> byMarketplaceId = new HashMap<>();
        for (StoreEntity e : stores.values()) {
            assertThat(e.getSellerId())
                    .as("created store carries the platform marketplace identifier")
                    .isNotBlank();
            assertThat(distinctMarketplaceIds)
                    .as("stored marketplace identifier is one of the discovered ones")
                    .contains(e.getSellerId());
            assertThat(e.getOrgId())
                    .as("created store is linked to the originating seller account (organization)")
                    .isEqualTo(ORG_ID);
            byMarketplaceId.put(e.getSellerId(), e);
        }
        // Exactly the discovered marketplace identifiers are represented, one store each.
        assertThat(byMarketplaceId.keySet())
                .containsExactlyInAnyOrderElementsOf(distinctMarketplaceIds);

        // The created VOs expose the marketplace identifier via sellerId (Req 6.1.4).
        assertThat(first.created().stream().map(StoreVo::getSellerId).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(distinctMarketplaceIds);

        // --- second discovery over the same set: idempotent, reuse-all, no duplicates (Req 6.1.3) ---
        StoreDiscoveryResult second = service.discover(CONNECTION_ID, user);

        assertThat(second.created())
                .as("repeated discovery creates nothing for already-known marketplaces")
                .isEmpty();
        assertThat(second.reused())
                .as("repeated discovery reuses every existing store")
                .hasSize(expectedStores);

        // No new backing rows: repeated discovery never duplicates.
        assertThat(stores).hasSize(expectedStores);

        // Reused stores are exactly the ones created on the first run (same ids, same marketplace ids).
        assertThat(second.reused().stream().map(StoreVo::getSellerId).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(distinctMarketplaceIds);
        assertThat(second.reused().stream().map(StoreVo::getId).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(
                        first.created().stream().map(StoreVo::getId).collect(Collectors.toSet()));
    }

    // --- wiring ---------------------------------------------------------------

    private StoreDiscoveryServiceImpl buildService(Map<UUID, StoreEntity> stores) {
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        PlatformConnectionEntity connection = PlatformConnectionEntity.builder()
                .id(CONNECTION_ID)
                .storeId(ORIGINATING_STORE_ID)
                .platform(PLATFORM)
                .configEncrypted(null)
                .build();
        when(connectionMapper.selectById(CONNECTION_ID)).thenReturn(connection);

        // The originating store anchors the organization and the fallback marketplace ref.
        StoreEntity originatingStore = StoreEntity.builder()
                .id(ORIGINATING_STORE_ID)
                .orgId(ORG_ID)
                .name("Originating Store")
                .marketplaceId(FALLBACK_MARKETPLACE_REF)
                .sellerId(null) // no marketplace identifier -> never indexed as an existing discovered store
                .status("connected")
                .build();

        StoreMapper storeMapper = mock(StoreMapper.class);
        when(storeMapper.selectById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (ORIGINATING_STORE_ID.equals(id)) {
                return originatingStore;
            }
            return stores.get(id);
        });
        // Existing stores for the org are whatever has been inserted so far.
        when(storeMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(stores.values()));
        when(storeMapper.insert(any(StoreEntity.class))).thenAnswer(inv -> {
            StoreEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            stores.put(e.getId(), e);
            return 1;
        });

        // No currency-matched marketplace -> resolution falls back to the originating ref.
        MarketplaceMapper marketplaceMapper = mock(MarketplaceMapper.class);
        when(marketplaceMapper.selectOne(any())).thenReturn(null);
        when(marketplaceMapper.selectById(any())).thenReturn(null);

        CryptoUtil cryptoUtil = mock(CryptoUtil.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        // Real TransactionTemplate over a mock transaction manager so the per-batch
        // persistence callback actually executes (executeWithoutResult runs the
        // action) while no real transaction is opened. Mirrors the EntitySync test.
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);

        return new StoreDiscoveryServiceImpl(connectionMapper, storeMapper, marketplaceMapper,
                cryptoUtil, objectMapper, List.of(connector), transactionTemplate);
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<List<DiscoveredStore>> marketplaceSets() {
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> currencies = Arbitraries.of("USD", "EUR", "GBP", "JPY", "CAD", "AUD");
        Arbitrary<String> regions = Arbitraries.of("NA", "EU", "FE", "JP");

        // Build raw stores, then assign a distinct marketplace identifier by position so a
        // generated "set" of marketplaces has no accidental duplicates and the first run
        // creates exactly one store per element.
        Arbitrary<List<RawStore>> raw = Combinators.combine(names, currencies, regions)
                .as(RawStore::new)
                .list().ofMinSize(1).ofMaxSize(25);

        return raw.map(list -> {
            List<DiscoveredStore> result = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                RawStore r = list.get(i);
                String marketplaceId = "mkt-" + i + "-" + UUID.randomUUID();
                result.add(new DiscoveredStore(marketplaceId, r.name(), r.currency(), r.region()));
            }
            return result;
        });
    }

    record RawStore(String name, String currency, String region) {
    }

    /** Stub connector returning a controllable list of discovered stores. */
    private static final class StubConnector implements PlatformDataConnector {
        private List<DiscoveredStore> toReturn = List.of();

        @Override
        public String platform() {
            return PLATFORM;
        }

        @Override
        public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public List<DiscoveredStore> discoverStores(ConnectionContext ctx) {
            return toReturn;
        }
    }
}
