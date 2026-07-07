package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.dto.IndependentSiteConnectRequest;
import com.adpilot.modules.apisync.dto.IndependentSiteGoogleAdsBindRequest;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link IndependentSiteConnectionServiceImpl} covering the
 * Independent_Site_Connection_Wizard backend (platform-workspace-rbac Req 5.2,
 * 5.3, 5.4, 5.5).
 */
class IndependentSiteConnectionServiceImplTest {

    private PlatformConnectionMapper platformConnectionMapper;
    private StoreMapper storeMapper;
    private MarketplaceMapper marketplaceMapper;
    private PlatformConnector platformConnector;
    private StoreGroupService storeGroupService;
    private CryptoUtil cryptoUtil;

    private IndependentSiteConnectionServiceImpl service;

    private MockedStatic<SecurityUtils> securityUtils;
    private UUID orgId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        platformConnectionMapper = mock(PlatformConnectionMapper.class);
        storeMapper = mock(StoreMapper.class);
        marketplaceMapper = mock(MarketplaceMapper.class);
        platformConnector = mock(PlatformConnector.class);
        storeGroupService = mock(StoreGroupService.class);
        cryptoUtil = mock(CryptoUtil.class);
        when(cryptoUtil.encrypt(any())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        service = new IndependentSiteConnectionServiceImpl(
                platformConnectionMapper, storeMapper, marketplaceMapper,
                platformConnector, storeGroupService, cryptoUtil, new ObjectMapper());

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
    }

    // --- Req 5.2, 5.4: one-flow connect creates store + connection + group ---

    @Test
    void connectStore_success_createsStoreConnectionAndAssignsIndependentSiteGroup() {
        UUID groupId = UUID.randomUUID();
        when(platformConnector.test(eq("shopify"), any()))
                .thenReturn(PlatformConnector.TestResult.ok("Shopify 连接成功"));
        when(marketplaceMapper.selectOne(any()))
                .thenReturn(MarketplaceEntity.builder().id(UUID.randomUUID()).code("SHOPIFY").build());
        when(storeGroupService.defaultGroupFor(PlatformFamily.INDEPENDENT_SITE)).thenReturn(groupId);

        IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
        req.setPlatform("shopify");
        req.setStoreName("My Shop");
        req.setConfig(Map.of("shopDomain", "my.myshopify.com", "accessToken", "shpat_x"));

        PlatformConnectionVo vo = service.connectStore(req, userId.toString());

        // Store created with independent_site platform family.
        ArgumentCaptor<StoreEntity> storeCaptor = ArgumentCaptor.forClass(StoreEntity.class);
        verify(storeMapper, times(1)).insert(storeCaptor.capture());
        StoreEntity store = storeCaptor.getValue();
        assertThat(store.getPlatformFamily()).isEqualTo(PlatformFamily.INDEPENDENT_SITE.getCode());
        assertThat(store.getOrgId()).isEqualTo(orgId);

        // Store assigned to the independent-site default group (Req 5.4).
        verify(storeGroupService, times(1)).assignStore(store.getId(), groupId);

        // Connection created and bound to the new store (Req 5.2).
        ArgumentCaptor<PlatformConnectionEntity> connCaptor =
                ArgumentCaptor.forClass(PlatformConnectionEntity.class);
        verify(platformConnectionMapper, times(1)).insert(connCaptor.capture());
        PlatformConnectionEntity conn = connCaptor.getValue();
        assertThat(conn.getPlatform()).isEqualTo("shopify");
        assertThat(conn.getStoreId()).isEqualTo(store.getId());
        assertThat(conn.getStatus()).isEqualTo("connected");

        assertThat(vo).isNotNull();
        assertThat(vo.getPlatform()).isEqualTo("shopify");
    }

    // --- Req 5.3: credential rejection leaves any existing connection unchanged ---

    @Test
    void connectStore_credentialsRejected_createsNothingAndSurfacesReason() {
        when(platformConnector.test(eq("woocommerce"), any()))
                .thenReturn(PlatformConnector.TestResult.fail("WooCommerce 凭证校验失败"));

        IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
        req.setPlatform("woocommerce");
        req.setConfig(Map.of("siteUrl", "https://x.com", "consumerKey", "ck", "consumerSecret", "cs"));

        assertThatThrownBy(() -> service.connectStore(req, userId.toString()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WooCommerce 凭证校验失败");

        // No store, connection, or group assignment is written (Req 5.3).
        verify(storeMapper, never()).insert(any());
        verify(platformConnectionMapper, never()).insert(any());
        verify(storeGroupService, never()).assignStore(any(), any());
    }

    @Test
    void connectStore_unsupportedPlatform_rejected() {
        IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
        req.setPlatform("amazon_ads");

        assertThatThrownBy(() -> service.connectStore(req, userId.toString()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("独立站连接仅支持");

        verify(storeMapper, never()).insert(any());
    }

    // --- Req 6.2: block-family scope enforcement (no cross-family connections) ---

    @Test
    void connectStore_crossFamilyBlockScope_rejectedWith403() {
        // shopify is an independent-site platform, but the connection entry
        // declared an amazon block scope: cross-family must be rejected (403).
        IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
        req.setPlatform("shopify");
        req.setBlockFamily(PlatformFamily.AMAZON.getCode());
        req.setConfig(Map.of("shopDomain", "my.myshopify.com", "accessToken", "shpat_x"));

        assertThatThrownBy(() -> service.connectStore(req, userId.toString()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("跨平台族")
                .extracting("status").isEqualTo(403);

        // Nothing is written and credentials are not even validated.
        verify(platformConnector, never()).test(any(), any());
        verify(storeMapper, never()).insert(any());
        verify(platformConnectionMapper, never()).insert(any());
    }

    @Test
    void connectStore_tiktokKeyUnderIndependentSiteScope_rejected() {
        // tiktok_shop now belongs to the tiktok family, so it cannot be created
        // under an independent_site block scope (Req 6.2).
        IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
        req.setPlatform("tiktok_shop");
        req.setBlockFamily(PlatformFamily.INDEPENDENT_SITE.getCode());
        req.setConfig(Map.of("appKey", "k", "appSecret", "s", "accessToken", "t"));

        assertThatThrownBy(() -> service.connectStore(req, userId.toString()))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(403);

        verify(storeMapper, never()).insert(any());
        verify(platformConnectionMapper, never()).insert(any());
    }

    @Test
    void connectStore_matchingBlockScope_succeeds() {
        UUID groupId = UUID.randomUUID();
        when(platformConnector.test(eq("shopify"), any()))
                .thenReturn(PlatformConnector.TestResult.ok("Shopify 连接成功"));
        when(marketplaceMapper.selectOne(any()))
                .thenReturn(MarketplaceEntity.builder().id(UUID.randomUUID()).code("SHOPIFY").build());
        when(storeGroupService.defaultGroupFor(PlatformFamily.INDEPENDENT_SITE)).thenReturn(groupId);

        IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
        req.setPlatform("shopify");
        req.setBlockFamily(PlatformFamily.INDEPENDENT_SITE.getCode());
        req.setConfig(Map.of("shopDomain", "my.myshopify.com", "accessToken", "shpat_x"));

        PlatformConnectionVo vo = service.connectStore(req, userId.toString());

        verify(storeMapper, times(1)).insert(any());
        verify(platformConnectionMapper, times(1)).insert(any());
        assertThat(vo.getPlatform()).isEqualTo("shopify");
    }

    // --- Req 5.5: Google Ads binds to an existing independent-site store ----

    @Test
    void bindGoogleAds_independentSiteStore_createsConnectionBoundToStore() {
        UUID storeId = UUID.randomUUID();
        StoreEntity store = StoreEntity.builder()
                .id(storeId)
                .orgId(orgId)
                .name("Indie Shop")
                .platformFamily(PlatformFamily.INDEPENDENT_SITE.getCode())
                .build();
        when(storeMapper.selectById(storeId)).thenReturn(store);
        when(platformConnector.test(eq("google_ads"), any()))
                .thenReturn(PlatformConnector.TestResult.ok("Google Ads 凭证有效"));
        when(platformConnectionMapper.selectOne(any())).thenReturn(null);

        IndependentSiteGoogleAdsBindRequest req = new IndependentSiteGoogleAdsBindRequest();
        req.setStoreId(storeId.toString());
        req.setConfig(Map.of(
                "clientId", "x", "clientSecret", "s", "refreshToken", "r", "developerToken", "d"));

        PlatformConnectionVo vo = service.bindGoogleAds(req, userId.toString());

        ArgumentCaptor<PlatformConnectionEntity> captor =
                ArgumentCaptor.forClass(PlatformConnectionEntity.class);
        verify(platformConnectionMapper, times(1)).insert(captor.capture());
        PlatformConnectionEntity conn = captor.getValue();
        assertThat(conn.getPlatform()).isEqualTo("google_ads");
        assertThat(conn.getStoreId()).isEqualTo(storeId);
        assertThat(vo.getPlatform()).isEqualTo("google_ads");
    }

    @Test
    void bindGoogleAds_nonIndependentSiteStore_rejected() {
        UUID storeId = UUID.randomUUID();
        StoreEntity store = StoreEntity.builder()
                .id(storeId)
                .orgId(orgId)
                .name("Amazon Store")
                .platformFamily(PlatformFamily.AMAZON.getCode())
                .build();
        when(storeMapper.selectById(storeId)).thenReturn(store);

        IndependentSiteGoogleAdsBindRequest req = new IndependentSiteGoogleAdsBindRequest();
        req.setStoreId(storeId.toString());
        req.setConfig(Map.of("clientId", "x"));

        assertThatThrownBy(() -> service.bindGoogleAds(req, userId.toString()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("独立站");

        verify(platformConnectionMapper, never()).insert(any());
    }
}
