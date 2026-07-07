package com.adpilot.modules.apisync.oauth;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsAuthUrlVo;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsBindRequest;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsCallbackVo;
import com.adpilot.modules.apisync.support.MockApiServer;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.dto.CreateStoreGroupCommand;
import com.adpilot.modules.rbac.service.StoreGroupService;
import com.adpilot.modules.rbac.vo.StoreGroupVo;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.StoreService;
import com.adpilot.modules.store.vo.StoreVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the Amazon_Connection_Wizard one-step flow
 * (platform-workspace-rbac Req 4.2, 4.3, 4.5) exercising the real
 * {@link AmazonAdsOAuthService} end to end against a mocked OAuth endpoint
 * ({@link MockApiServer}): the full {@code RestClient} stack performs the LWA
 * token exchange and the {@code /v2/profiles} fetch over a real socket, then the
 * selected profile is bound to the store and the store is assigned to its Amazon
 * Store_Group — all in one continuous flow.
 *
 * <p>The flow is driven exactly as the wizard drives it:
 * {@code buildAuthorizeUrl} (mints + persists the CSRF state) →
 * {@code handleCallback} (token exchange + profile list against the mock OAuth
 * server) → {@code bindProfile} (bind + Store_Group assignment).</p>
 */
class AmazonConnectionWizardIntegrationTest {

    private static final String TOKEN_PATH = "/auth/o2/token";
    private static final String PROFILES_PATH = "/v2/profiles";
    private static final String REFRESH_TOKEN = "Atzr|refresh-token-xyz";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID storeId = UUID.randomUUID();

    private MockApiServer server;
    private AmazonAdsProperties props;
    private OAuthStateStore stateStore;
    private CryptoUtil cryptoUtil;
    private PlatformConnectionMapper connectionMapper;
    private StoreService storeService;
    private StoreGroupService storeGroupService;
    private StoreMapper storeMapper;
    private AmazonAdsOAuthService service;

    /** In-memory holder so the connection persisted by the callback is found at bind. */
    private final AtomicReference<PlatformConnectionEntity> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = MockApiServer.http();
        // Mocked OAuth endpoint: token exchange returns an access + refresh token,
        // and the Ads profile list returns one bindable advertising account.
        server.route(TOKEN_PATH, query -> MockApiServer.Response.json(200, """
                {"access_token":"ACCESS-XYZ","refresh_token":"%s",
                 "token_type":"bearer","expires_in":3600}
                """.formatted(REFRESH_TOKEN)));
        server.route(PROFILES_PATH, query -> MockApiServer.Response.json(200, """
                [
                  {"profileId": 12345, "countryCode": "US", "currencyCode": "USD",
                   "accountInfo": {"marketplaceStringId": "ATVPDKIKX0DER",
                                   "name": "My Ads Account", "id": "SELLER-1", "type": "seller"}}
                ]
                """));

        // Point the NA region's token + API hosts at the mock OAuth server.
        props = new AmazonAdsProperties();
        props.setClientId("client-id");
        props.setClientSecret("client-secret");
        props.setRedirectUri("https://app.example/callback");
        AmazonAdsProperties.Region na = new AmazonAdsProperties.Region();
        na.setAuthorizeHost("http://" + server.host());
        na.setTokenUrl("http://" + server.host() + TOKEN_PATH);
        na.setApiHost("http://" + server.host());
        props.getRegions().put("na", na);

        // Real CSRF state store with memory fallback (Redis unavailable in test).
        stateStore = new OAuthStateStore(mock(RedisTemplate.class));
        // Real crypto so the refresh token persisted at callback round-trips at bind.
        cryptoUtil = new CryptoUtil("test-secret");

        connectionMapper = mock(PlatformConnectionMapper.class);
        when(connectionMapper.selectOne(any())).thenAnswer(inv -> stored.get());
        when(connectionMapper.insert(any(PlatformConnectionEntity.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return 1;
        });
        when(connectionMapper.updateById(any(PlatformConnectionEntity.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return 1;
        });

        storeService = mock(StoreService.class);
        when(storeService.getStoreById(any())).thenReturn(StoreVo.builder()
                .id(storeId.toString()).platform("amazon").build());

        storeMapper = mock(StoreMapper.class);
        when(storeMapper.selectById(storeId)).thenReturn(StoreEntity.builder()
                .id(storeId).name("Amazon Store")
                .platformFamily(PlatformFamily.AMAZON.getCode()).build());

        storeGroupService = mock(StoreGroupService.class);

        service = new AmazonAdsOAuthService(props, stateStore, connectionMapper, cryptoUtil,
                objectMapper, storeService, storeGroupService, storeMapper,
                mock(com.adpilot.modules.audit.service.AuditLogService.class),
                new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    /** Drive authorize → callback and return the bindable callback result. */
    private AmazonAdsCallbackVo runOauthThroughCallback() {
        AmazonAdsAuthUrlVo authUrl = service.buildAuthorizeUrl("na", storeId.toString());
        assertThat(authUrl.getState()).isNotBlank();
        return service.handleCallback("auth-code-123", authUrl.getState());
    }

    // ── Req 4.2 / 4.3: one-step OAuth + bind, defaulting to the family group ───

    @Test
    void oneStepOauthBindAssignsStoreToDefaultAmazonGroup() {
        UUID defaultGroup = UUID.randomUUID();
        when(storeGroupService.defaultGroupFor(PlatformFamily.AMAZON)).thenReturn(defaultGroup);

        // Step 1+2: OAuth authorize + callback against the mocked endpoint.
        AmazonAdsCallbackVo callback = runOauthThroughCallback();
        assertThat(callback.getStoreId()).isEqualTo(storeId.toString());
        assertThat(callback.getProfiles()).hasSize(1);
        assertThat(callback.getProfiles().get(0).getProfileId()).isEqualTo("12345");

        // The mocked OAuth endpoints were actually called.
        assertThat(server.requests()).anyMatch(r -> r.startsWith(TOKEN_PATH));
        assertThat(server.requests()).anyMatch(r -> r.startsWith(PROFILES_PATH));

        // Step 3: bind the selected profile (Req 4.3) — no group selected.
        AmazonAdsBindRequest bind = new AmazonAdsBindRequest();
        bind.setStoreId(storeId.toString());
        bind.setProfileId("12345");
        bind.setRegion("na");
        PlatformConnectionEntity conn = service.bindProfile(bind);

        // The connection is created/updated and bound to the store (Req 4.3).
        assertThat(conn.getStoreId()).isEqualTo(storeId);
        assertThat(conn.getProfileId()).isEqualTo("12345");
        assertThat(conn.getStatus()).isEqualTo("connected");
        // Refresh token captured at callback round-trips through encryption.
        assertThat(cryptoUtil.decrypt(conn.getRefreshTokenEncrypted())).isEqualTo(REFRESH_TOKEN);

        // Store assigned to the Amazon family default group in the same flow (Req 4.5).
        verify(storeGroupService, times(1)).assignStore(storeId, defaultGroup);
    }

    // ── Req 4.5: one-step flow assigns to a named group, creating it if absent ─

    @Test
    void oneStepOauthBindCreatesAndAssignsNamedAmazonGroup() {
        UUID createdGroup = UUID.randomUUID();
        when(storeGroupService.listByFamily(PlatformFamily.AMAZON)).thenReturn(List.of());
        when(storeGroupService.create(any(CreateStoreGroupCommand.class))).thenReturn(
                StoreGroupVo.builder().id(createdGroup.toString())
                        .name("亚马逊三组").platformFamily(PlatformFamily.AMAZON.getCode()).build());

        runOauthThroughCallback();

        AmazonAdsBindRequest bind = new AmazonAdsBindRequest();
        bind.setStoreId(storeId.toString());
        bind.setProfileId("12345");
        bind.setRegion("na");
        bind.setStoreGroupName("亚马逊三组");
        service.bindProfile(bind);

        // The named group is created (it did not exist) and the store assigned to it.
        ArgumentCaptor<CreateStoreGroupCommand> cmd = ArgumentCaptor.forClass(CreateStoreGroupCommand.class);
        verify(storeGroupService).create(cmd.capture());
        assertThat(cmd.getValue().getName()).isEqualTo("亚马逊三组");
        assertThat(cmd.getValue().getPlatformFamily()).isEqualTo(PlatformFamily.AMAZON);
        verify(storeGroupService, times(1)).assignStore(storeId, createdGroup);
        verify(storeGroupService, never()).defaultGroupFor(any());
    }

    // ── Req 4.4: OAuth failure leaves any existing connection unchanged ────────

    @Test
    void oauthTokenExchangeFailureLeavesConnectionUnchangedAndAssignsNoGroup() {
        // The mocked token endpoint rejects the authorization code.
        server.route(TOKEN_PATH, query -> MockApiServer.Response.json(400,
                "{\"error\":\"invalid_grant\"}"));

        AmazonAdsAuthUrlVo authUrl = service.buildAuthorizeUrl("na", storeId.toString());
        String state = authUrl.getState();

        assertThatThrownBy(() -> service.handleCallback("bad-code", state))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("TOKEN_EXCHANGE_FAILED");

        // No connection was persisted and no Store_Group was assigned (Req 4.4).
        assertThat(stored.get()).isNull();
        verify(connectionMapper, never()).insert(any());
        verify(storeGroupService, never()).assignStore(any(), any());
    }
}
