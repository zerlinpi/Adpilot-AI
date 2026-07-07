package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.dto.ConnectStoreRequest;
import com.adpilot.modules.apisync.dto.IndependentSiteConnectRequest;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.service.StoreGroupService;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for in-block connection-creation family enforcement.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 15: 块内连接只能创建同平台族连接.
 *
 * <p>Validates: Requirements 6.2.
 *
 * <p>For any (Nav_Block platform family, platform key) pair, a connection
 * creation initiated inside the block is allowed iff the platform key belongs to
 * that block's platform family; platform keys of other families are always
 * rejected with HTTP 403, so no cross-family connection is created inside a
 * block. This drives the real {@code assertPlatformInBlockFamily} gate
 * (backed by the shared {@link PlatformFamily#ofPlatformKey(String)} mapping)
 * used by both {@link ApiSyncServiceImpl#connectStore} and
 * {@link IndependentSiteConnectionServiceImpl#connectStore} (task 8.4), with
 * collaborators mocked so the request reaches the family check.
 */
@Label("Feature: multistore-ai-ads-operations, Property 15: 块内连接只能创建同平台族连接")
class BlockConnectionFamilyEnforcementPropertyTest {

    private static final int TRIES = 200;
    private static final String USER = UUID.randomUUID().toString();
    private static final String ORG = UUID.randomUUID().toString();

    /** Every PlatformConnector-supported key the ApiSync wizard accepts. */
    private static final List<String> ALL_KEYS = List.of(
            "amazon_ads", "amazon_sp_api", "google_ads", "shopify", "woocommerce", "tiktok_shop");

    /** The keys the Independent_Site wizard accepts (its own allow-list). */
    private static final List<String> INDEPENDENT_SITE_KEYS = List.of("shopify", "woocommerce", "tiktok_shop");

    /** The three Nav_Block platform families a connection can be created under. */
    private static final List<String> FAMILIES = List.of("amazon", "independent_site", "tiktok");

    record Combo(String platformKey, String blockFamily) {}

    /** The family that owns a given platform key (mirrors {@link PlatformFamily#ofPlatformKey}). */
    private static String familyOf(String key) {
        return PlatformFamily.ofPlatformKey(key).getCode();
    }

    // ── ApiSync wizard ──────────────────────────────────────────────────────────────────────────

    // Feature: multistore-ai-ads-operations, Property 15: cross-family keys are rejected (403) with
    // no connection created inside the block (ApiSync connect-store path).
    @Property(tries = TRIES)
    @Label("ApiSync: cross-family connection rejected with HTTP 403 and no side effects")
    void apiSyncCrossFamilyRejected(@ForAll("apiSyncCrossFamily") Combo combo) {
        PlatformConnectionMapper connMapper = mock(PlatformConnectionMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceMapper marketplaceMapper = mock(MarketplaceMapper.class);
        ApiSyncServiceImpl service = newApiSyncService(connMapper, storeMapper, marketplaceMapper);

        BusinessException ex = catchThrowableOfType(
                () -> service.connectStore(request(combo), USER), BusinessException.class);

        // Rejected as a cross-family connection.
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(403);
        assertThat(ex.getCode()).isEqualTo("CROSS_FAMILY_CONNECTION");
        // Nothing persisted: no store and no connection created inside the block.
        verify(storeMapper, never()).insert(any());
        verify(connMapper, never()).insert(any());
        verify(marketplaceMapper, never()).insert(any());
    }

    // Feature: multistore-ai-ads-operations, Property 15: a same-family key passes the family gate
    // (it is never rejected as cross-family) on the ApiSync connect-store path.
    @Property(tries = TRIES)
    @Label("ApiSync: same-family connection passes the family gate")
    void apiSyncSameFamilyAllowed(@ForAll("apiSyncSameFamily") Combo combo) {
        PlatformConnectionMapper connMapper = mock(PlatformConnectionMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceMapper marketplaceMapper = mock(MarketplaceMapper.class);
        when(marketplaceMapper.selectOne(any())).thenReturn(
                MarketplaceEntity.builder().id(UUID.randomUUID()).build());
        ApiSyncServiceImpl service = newApiSyncService(connMapper, storeMapper, marketplaceMapper);

        try (MockedStatic<SecurityUtils> security = Mockito.mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentOrgId).thenReturn(ORG);
            try {
                service.connectStore(request(combo), USER);
                // Reached the end of the orchestration: the block created the connection.
                verify(connMapper, times(1)).insert(any());
            } catch (BusinessException e) {
                // Allowed by the family gate; any failure here is for a non-family reason
                // (e.g. google_ads must be bound to an existing store), never cross-family.
                assertThat(e.getCode()).isNotEqualTo("CROSS_FAMILY_CONNECTION");
            }
        }
    }

    // ── Independent_Site wizard ───────────────────────────────────────────────────────────────────

    // Feature: multistore-ai-ads-operations, Property 15: cross-family keys are rejected (403) with no
    // credential test and no connection created (Independent_Site connect-store path).
    @Property(tries = TRIES)
    @Label("IndependentSite: cross-family connection rejected with HTTP 403 and no side effects")
    void independentSiteCrossFamilyRejected(@ForAll("independentSiteCrossFamily") Combo combo) {
        PlatformConnectionMapper connMapper = mock(PlatformConnectionMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceMapper marketplaceMapper = mock(MarketplaceMapper.class);
        PlatformConnector connector = mock(PlatformConnector.class);
        StoreGroupService storeGroupService = mock(StoreGroupService.class);
        IndependentSiteConnectionServiceImpl service =
                newIndependentSiteService(connMapper, storeMapper, marketplaceMapper, connector, storeGroupService);

        BusinessException ex = catchThrowableOfType(
                () -> service.connectStore(independentRequest(combo), USER), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(403);
        assertThat(ex.getCode()).isEqualTo("CROSS_FAMILY_CONNECTION");
        // The gate runs before the credential test and before any persistence.
        verify(connector, never()).test(anyString(), any());
        verify(storeMapper, never()).insert(any());
        verify(connMapper, never()).insert(any());
        verify(storeGroupService, never()).assignStore(any(), any());
    }

    // Feature: multistore-ai-ads-operations, Property 15: a same-family key passes the family gate and
    // creates the connection inside the block (Independent_Site connect-store path).
    @Property(tries = TRIES)
    @Label("IndependentSite: same-family connection passes the family gate and is created")
    void independentSiteSameFamilyAllowed(@ForAll("independentSiteSameFamily") Combo combo) {
        PlatformConnectionMapper connMapper = mock(PlatformConnectionMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceMapper marketplaceMapper = mock(MarketplaceMapper.class);
        PlatformConnector connector = mock(PlatformConnector.class);
        StoreGroupService storeGroupService = mock(StoreGroupService.class);
        when(connector.test(anyString(), any())).thenReturn(PlatformConnector.TestResult.ok("ok"));
        when(marketplaceMapper.selectOne(any())).thenReturn(
                MarketplaceEntity.builder().id(UUID.randomUUID()).build());
        when(storeGroupService.defaultGroupFor(PlatformFamily.INDEPENDENT_SITE))
                .thenReturn(UUID.randomUUID());
        IndependentSiteConnectionServiceImpl service =
                newIndependentSiteService(connMapper, storeMapper, marketplaceMapper, connector, storeGroupService);

        try (MockedStatic<SecurityUtils> security = Mockito.mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAuthenticated).thenReturn(true);
            security.when(SecurityUtils::getCurrentOrgId).thenReturn(ORG);

            service.connectStore(independentRequest(combo), USER);

            // The family gate passed (credential test reached) and the connection was created.
            verify(connector, atLeastOnce()).test(anyString(), any());
            verify(connMapper, times(1)).insert(any());
        }
    }

    // ── builders ─────────────────────────────────────────────────────────────────────────────────

    private static ConnectStoreRequest request(Combo combo) {
        ConnectStoreRequest req = new ConnectStoreRequest();
        req.setPlatform(combo.platformKey());
        req.setBlockFamily(combo.blockFamily());
        req.setStoreName("Test Store");
        req.setConfig(new LinkedHashMap<>());
        return req;
    }

    private static IndependentSiteConnectRequest independentRequest(Combo combo) {
        IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
        req.setPlatform(combo.platformKey());
        req.setBlockFamily(combo.blockFamily());
        req.setStoreName("Test Store");
        req.setConfig(new LinkedHashMap<>());
        return req;
    }

    private static ApiSyncServiceImpl newApiSyncService(
            PlatformConnectionMapper connMapper, StoreMapper storeMapper, MarketplaceMapper marketplaceMapper) {
        return new ApiSyncServiceImpl(
                connMapper,
                mock(ApiSyncJobMapper.class),
                mock(ApiSyncLogMapper.class),
                mock(CryptoUtil.class),
                mock(PlatformConnector.class),
                new ObjectMapper(),
                mock(SyncJobRunner.class),
                storeMapper,
                marketplaceMapper,
                mock(UserStoreMapper.class),
                mock(com.adpilot.modules.audit.service.AuditLogService.class));
    }

    private static IndependentSiteConnectionServiceImpl newIndependentSiteService(
            PlatformConnectionMapper connMapper, StoreMapper storeMapper, MarketplaceMapper marketplaceMapper,
            PlatformConnector connector, StoreGroupService storeGroupService) {
        return new IndependentSiteConnectionServiceImpl(
                connMapper,
                storeMapper,
                marketplaceMapper,
                connector,
                storeGroupService,
                mock(CryptoUtil.class),
                new ObjectMapper());
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<Combo> apiSyncCrossFamily() {
        return combos(ALL_KEYS).filter(c -> !familyOf(c.platformKey()).equals(c.blockFamily()));
    }

    @Provide
    Arbitrary<Combo> apiSyncSameFamily() {
        return Arbitraries.of(ALL_KEYS).map(key -> new Combo(key, familyOf(key)));
    }

    @Provide
    Arbitrary<Combo> independentSiteCrossFamily() {
        return combos(INDEPENDENT_SITE_KEYS).filter(c -> !familyOf(c.platformKey()).equals(c.blockFamily()));
    }

    @Provide
    Arbitrary<Combo> independentSiteSameFamily() {
        return Arbitraries.of(INDEPENDENT_SITE_KEYS).map(key -> new Combo(key, familyOf(key)));
    }

    private static Arbitrary<Combo> combos(List<String> keys) {
        Arbitrary<String> keyArb = Arbitraries.of(keys);
        Arbitrary<String> familyArb = Arbitraries.of(FAMILIES);
        return Combinators.combine(keyArb, familyArb).as(Combo::new);
    }
}
