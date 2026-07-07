package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.dto.IndependentSiteConnectRequest;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.support.MockApiServer;
import com.adpilot.modules.apisync.vo.PlatformConnectionVo;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.service.StoreGroupService;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the Independent_Site_Connection_Wizard one-step flow
 * (platform-workspace-rbac Req 5.2, 5.4) exercising the real
 * {@link IndependentSiteConnectionServiceImpl} together with the real
 * {@link PlatformConnector} credential check against a mocked external platform
 * endpoint ({@link MockApiServer}). WooCommerce is used because it builds its
 * URL from the configured {@code siteUrl}, so the credential-validation HTTP
 * call can be redirected to the local mock server over plain HTTP.
 *
 * <p>The single flow validated: submit credentials → real connectivity check →
 * create the Store → create the {@code PlatformConnectionEntity} bound to the
 * Store → assign the Store to the independent-site Store_Group.</p>
 */
class IndependentSiteConnectionWizardIntegrationTest {

    private static final String WC_PRODUCTS_PATH = "/wp-json/wc/v3/products";

    private MockApiServer server;
    private PlatformConnectionMapper platformConnectionMapper;
    private StoreMapper storeMapper;
    private MarketplaceMapper marketplaceMapper;
    private PlatformConnector platformConnector;
    private StoreGroupService storeGroupService;
    private IndependentSiteConnectionServiceImpl service;

    private MockedStatic<SecurityUtils> securityUtils;
    private UUID orgId;
    private UUID userId;

    private final AtomicReference<StoreEntity> storedStore = new AtomicReference<>();
    private final AtomicReference<PlatformConnectionEntity> storedConn = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = MockApiServer.http();

        platformConnectionMapper = mock(PlatformConnectionMapper.class);
        when(platformConnectionMapper.insert(any(PlatformConnectionEntity.class))).thenAnswer(inv -> {
            storedConn.set(inv.getArgument(0));
            return 1;
        });
        when(platformConnectionMapper.selectOne(any())).thenAnswer(inv -> storedConn.get());

        storeMapper = mock(StoreMapper.class);
        when(storeMapper.insert(any(StoreEntity.class))).thenAnswer(inv -> {
            storedStore.set(inv.getArgument(0));
            return 1;
        });

        marketplaceMapper = mock(MarketplaceMapper.class);
        when(marketplaceMapper.selectOne(any())).thenReturn(
                MarketplaceEntity.builder().id(UUID.randomUUID()).code("WOO").build());

        // Real credential validation against the mocked external endpoint.
        platformConnector = new PlatformConnector(new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
        storeGroupService = mock(StoreGroupService.class);

        service = new IndependentSiteConnectionServiceImpl(
                platformConnectionMapper, storeMapper, marketplaceMapper,
                platformConnector, storeGroupService, new CryptoUtil("test-secret"), new ObjectMapper());

        orgId = UUID.randomUUID();
        userId = UUID.randomUUID();
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
        securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(orgId.toString());
        securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(userId.toString());
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
        server.close();
    }

    private Map<String, String> wooConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("siteUrl", "http://" + server.host());
        config.put("consumerKey", "ck_test");
        config.put("consumerSecret", "cs_test");
        return config;
    }

    // ── Req 5.2 / 5.4: one-flow credential submission + bind + group assignment ─

    @Test
    void connectStore_validCredentials_createsStoreConnectionAndAssignsGroup() {
        // The mocked WooCommerce endpoint accepts the credentials.
        server.route(WC_PRODUCTS_PATH, query -> MockApiServer.Response.json(200, "[]"));
        UUID defaultGroup = UUID.randomUUID();
        when(storeGroupService.defaultGroupFor(PlatformFamily.INDEPENDENT_SITE)).thenReturn(defaultGroup);

        IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
        req.setPlatform("woocommerce");
        req.setStoreName("My Indie Shop");
        req.setConfig(wooConfig());

        PlatformConnectionVo vo = service.connectStore(req, userId.toString());

        // The real connectivity check hit the mocked external endpoint.
        assertThat(server.requests()).anyMatch(r -> r.startsWith(WC_PRODUCTS_PATH));

        // Store created with the independent-site platform family (Req 5.4).
        ArgumentCaptor<StoreEntity> storeCaptor = ArgumentCaptor.forClass(StoreEntity.class);
        verify(storeMapper, times(1)).insert(storeCaptor.capture());
        StoreEntity store = storeCaptor.getValue();
        assertThat(store.getPlatformFamily()).isEqualTo(PlatformFamily.INDEPENDENT_SITE.getCode());
        assertThat(store.getOrgId()).isEqualTo(orgId);
        assertThat(store.getName()).isEqualTo("My Indie Shop");

        // Connection created and bound to the new store in the same flow (Req 5.2).
        ArgumentCaptor<PlatformConnectionEntity> connCaptor =
                ArgumentCaptor.forClass(PlatformConnectionEntity.class);
        verify(platformConnectionMapper, times(1)).insert(connCaptor.capture());
        PlatformConnectionEntity conn = connCaptor.getValue();
        assertThat(conn.getPlatform()).isEqualTo("woocommerce");
        assertThat(conn.getStoreId()).isEqualTo(store.getId());
        assertThat(conn.getStatus()).isEqualTo("connected");

        // Store assigned to the independent-site default group (Req 5.4).
        verify(storeGroupService, times(1)).assignStore(store.getId(), defaultGroup);

        assertThat(vo).isNotNull();
        assertThat(vo.getPlatform()).isEqualTo("woocommerce");
    }

    // ── Req 5.3: a rejection from the external platform leaves everything unchanged

    @Test
    void connectStore_credentialsRejectedByExternalPlatform_createsNothing() {
        // The mocked WooCommerce endpoint rejects the credentials (HTTP 401).
        server.route(WC_PRODUCTS_PATH, query -> MockApiServer.Response.json(401,
                "{\"code\":\"woocommerce_rest_authentication_error\"}"));

        IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
        req.setPlatform("woocommerce");
        req.setConfig(wooConfig());

        assertThatThrownBy(() -> service.connectStore(req, userId.toString()))
                .isInstanceOf(BusinessException.class);

        // No store, connection, or group assignment is written (Req 5.3).
        verify(storeMapper, never()).insert(any());
        verify(platformConnectionMapper, never()).insert(any());
        verify(storeGroupService, never()).assignStore(any(), any());
    }
}
