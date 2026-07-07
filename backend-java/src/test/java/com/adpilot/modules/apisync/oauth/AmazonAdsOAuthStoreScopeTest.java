package com.adpilot.modules.apisync.oauth;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsBindRequest;
import com.adpilot.modules.store.service.StoreService;
import com.adpilot.modules.store.vo.StoreVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmazonAdsOAuthStoreScopeTest {

    @Test
    void authorizeRequiresAnExplicitStoreId() {
        AmazonAdsOAuthService service = configuredService("amazon", mock(PlatformConnectionMapper.class));

        assertThatThrownBy(() -> service.buildAuthorizeUrl("na", null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    void authorizeRejectsNonAmazonStore() {
        AmazonAdsOAuthService service = configuredService("shopify", mock(PlatformConnectionMapper.class));

        assertThatThrownBy(() -> service.buildAuthorizeUrl(
                "na", "00000000-0000-0000-0000-000000000401"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_STORE_PLATFORM");
    }

    @Test
    void bindDoesNotReuseAnotherStoresRefreshToken() {
        PlatformConnectionMapper mapper = mock(PlatformConnectionMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        AmazonAdsOAuthService service = configuredService("amazon", mapper);
        AmazonAdsBindRequest request = new AmazonAdsBindRequest();
        request.setStoreId("00000000-0000-0000-0000-000000000301");
        request.setProfileId("profile-1");
        request.setRegion("na");

        assertThatThrownBy(() -> service.bindProfile(request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("NO_REFRESH_TOKEN");
        verify(mapper, never()).selectList(any());
    }

    private static AmazonAdsOAuthService configuredService(String platform,
                                                            PlatformConnectionMapper mapper) {
        AmazonAdsProperties props = new AmazonAdsProperties();
        props.setClientId("client-id");
        props.setClientSecret("client-secret");
        props.setRedirectUri("https://app.example/callback");
        StoreService storeService = mock(StoreService.class);
        when(storeService.getStoreById(any())).thenReturn(StoreVo.builder()
                .id("00000000-0000-0000-0000-000000000301")
                .platform(platform)
                .build());
        return new AmazonAdsOAuthService(props, mock(OAuthStateStore.class), mapper,
                null, new ObjectMapper(), storeService,
                mock(com.adpilot.modules.rbac.service.StoreGroupService.class),
                mock(com.adpilot.modules.store.mapper.StoreMapper.class),
                mock(com.adpilot.modules.audit.service.AuditLogService.class),
                new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
    }
}