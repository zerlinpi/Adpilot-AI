package com.adpilot.modules.upload.service.impl;

import com.adpilot.modules.listing.mapper.ListingContentMapper;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.upload.dto.UploadJobCreateRequest;
import com.adpilot.modules.upload.entity.ProductUploadJobEntity;
import com.adpilot.modules.upload.mapper.ProductUploadJobMapper;
import com.adpilot.modules.upload.publish.IndependentSiteProductPublishService;
import com.adpilot.modules.upload.publish.ProductPublishOutcome;
import com.adpilot.modules.upload.vo.UploadJobVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the direct-publish wiring in {@link ProductUploadServiceImpl}:
 * {@code shopify_api}/{@code woocommerce_api}/{@code tiktok_shop_api} jobs no
 * longer silently downgrade to {@code manual_export}, and
 * {@link ProductUploadServiceImpl#publishJob}
 * marks the job {@code published} on success, {@code failed} on a refusal /
 * rejection (never exported, never faked).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductUploadServiceImpl direct publish")
class ProductUploadServiceDirectPublishTest {

    @Mock
    private ProductUploadJobMapper uploadJobMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private ListingContentMapper listingContentMapper;
    @Mock
    private IndependentSiteProductPublishService publishService;

    private ProductUploadServiceImpl service;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID MARKETPLACE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProductUploadServiceImpl(
                uploadJobMapper, productMapper, listingContentMapper, new ObjectMapper(), publishService);
    }

    /** Mirror the MyBatis id-insert interceptor: assign an id on insert so toVo works. */
    private void stubInsertAssignsId() {
        when(uploadJobMapper.insert(any(ProductUploadJobEntity.class))).thenAnswer(inv -> {
            ProductUploadJobEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return 1;
        });
    }

    private UploadJobCreateRequest createRequest(String method) {
        UploadJobCreateRequest req = new UploadJobCreateRequest();
        req.setStoreId(STORE_ID.toString());
        req.setProductId(PRODUCT_ID.toString());
        req.setMarketplaceId(MARKETPLACE_ID.toString());
        req.setUploadMethod(method);
        return req;
    }

    private ProductUploadJobEntity approvedJob(String method) {
        ProductUploadJobEntity job = new ProductUploadJobEntity();
        job.setId(UUID.randomUUID());
        job.setStoreId(STORE_ID);
        job.setProductId(PRODUCT_ID);
        job.setUploadMethod(method);
        job.setStatus("approved");
        return job;
    }

    // =========================================================================
    // No silent downgrade for *_api
    // =========================================================================
    @Test
    @DisplayName("shopify_api job keeps its method (no downgrade to manual_export)")
    void shopifyApiNotDowngraded() {
        stubInsertAssignsId();
        ArgumentCaptor<ProductUploadJobEntity> captor = ArgumentCaptor.forClass(ProductUploadJobEntity.class);

        UploadJobVo vo = service.createJob(createRequest("shopify_api"), null);

        verify(uploadJobMapper).insert(captor.capture());
        assertThat(captor.getValue().getUploadMethod()).isEqualTo("shopify_api");
        assertThat(vo.getUploadMethod()).isEqualTo("shopify_api");
    }

    @Test
    @DisplayName("woocommerce_api job keeps its method (no downgrade to manual_export)")
    void wooApiNotDowngraded() {
        stubInsertAssignsId();
        ArgumentCaptor<ProductUploadJobEntity> captor = ArgumentCaptor.forClass(ProductUploadJobEntity.class);

        service.createJob(createRequest("woocommerce_api"), null);

        verify(uploadJobMapper).insert(captor.capture());
        assertThat(captor.getValue().getUploadMethod()).isEqualTo("woocommerce_api");
    }

    @Test
    @DisplayName("tiktok_shop_api job keeps its method (direct-publish; no downgrade to manual_export)")
    void tiktokApiNotDowngraded() {
        stubInsertAssignsId();
        ArgumentCaptor<ProductUploadJobEntity> captor = ArgumentCaptor.forClass(ProductUploadJobEntity.class);

        service.createJob(createRequest("tiktok_shop_api"), null);

        verify(uploadJobMapper).insert(captor.capture());
        assertThat(captor.getValue().getUploadMethod()).isEqualTo("tiktok_shop_api");
    }

    // =========================================================================
    // publishJob lifecycle
    // =========================================================================
    @Test
    @DisplayName("publishJob marks job published with publish_mode=direct on success")
    void publishJobSuccess() {
        ProductUploadJobEntity job = approvedJob("shopify_api");
        when(uploadJobMapper.selectById(job.getId())).thenReturn(job);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(new ProductEntity());
        when(publishService.publish(any(), any(), any()))
                .thenReturn(ProductPublishOutcome.published("PLAT-99", "direct", "Published to Shopify"));

        UploadJobVo vo = service.publishJob(job.getId().toString(), null);

        assertThat(vo.getStatus()).isEqualTo("published");
        assertThat(vo.getErrorMessage()).isNull();
        verify(uploadJobMapper).updateById(job);
        assertThat(job.getResponse()).contains("PLAT-99").contains("direct");
    }

    @Test
    @DisplayName("publishJob marks job failed (not exported) on a refusal — no connected connection")
    void publishJobRefused() {
        ProductUploadJobEntity job = approvedJob("woocommerce_api");
        when(uploadJobMapper.selectById(job.getId())).thenReturn(job);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(new ProductEntity());
        when(publishService.publish(any(), any(), any()))
                .thenReturn(ProductPublishOutcome.refused("NO_CONNECTED_CONNECTION",
                        "店铺没有已连接的 woocommerce 连接，无法直发商品。"));

        UploadJobVo vo = service.publishJob(job.getId().toString(), null);

        assertThat(vo.getStatus()).isEqualTo("failed");
        assertThat(vo.getErrorMessage()).contains("无法直发商品");
    }

    @Test
    @DisplayName("publishJob marks job failed on a transport/credential exception (never faked)")
    void publishJobTransportError() {
        ProductUploadJobEntity job = approvedJob("shopify_api");
        when(uploadJobMapper.selectById(job.getId())).thenReturn(job);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(new ProductEntity());
        when(publishService.publish(any(), any(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        UploadJobVo vo = service.publishJob(job.getId().toString(), null);

        assertThat(vo.getStatus()).isEqualTo("failed");
        assertThat(vo.getErrorMessage()).contains("Connection refused");
    }

    @Test
    @DisplayName("publishJob rejects a non-direct-publish method (manual_export) without publishing")
    void publishJobRejectsExportMethod() {
        ProductUploadJobEntity job = approvedJob("manual_export");
        when(uploadJobMapper.selectById(job.getId())).thenReturn(job);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.adpilot.common.exception.BusinessException.class,
                () -> service.publishJob(job.getId().toString(), null));

        verify(publishService, never()).publish(any(), any(), any());
    }
}
