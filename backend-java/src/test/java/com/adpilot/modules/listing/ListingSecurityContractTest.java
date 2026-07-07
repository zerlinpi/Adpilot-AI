package com.adpilot.modules.listing;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.keyword.entity.KeywordCoverageEntity;
import com.adpilot.modules.keyword.entity.KeywordInsightEntity;
import com.adpilot.modules.keyword.mapper.KeywordCoverageMapper;
import com.adpilot.modules.keyword.mapper.KeywordInsightMapper;
import com.adpilot.modules.listing.controller.ListingController;
import com.adpilot.modules.listing.entity.ListingContentEntity;
import com.adpilot.modules.listing.mapper.ListingContentMapper;
import com.adpilot.modules.listing.mapper.ListingDraftMapper;
import com.adpilot.modules.listing.service.impl.ListingServiceImpl;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.StoreService;
import com.adpilot.modules.store.vo.StoreVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListingSecurityContractTest {

    @Test
    void rejectsStoreScopeBeforeReadingListingContent() {
        Fixture fixture = new Fixture();
        when(fixture.productMapper.selectById(fixture.productId)).thenReturn(fixture.product());
        when(fixture.storeService.getStoreById(fixture.storeId.toString()))
                .thenThrow(new BusinessException(403, "STORE_FORBIDDEN", "forbidden"));

        assertThatThrownBy(() -> fixture.service().getListingContent(fixture.productId.toString()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("STORE_FORBIDDEN");
        verify(fixture.listingContentMapper, never()).selectOne(any());
    }

    @Test
    void keywordCoverageUsesProductsRealStoreId() {
        Fixture fixture = new Fixture();
        when(fixture.productMapper.selectById(fixture.productId)).thenReturn(fixture.product());
        when(fixture.storeService.getStoreById(fixture.storeId.toString()))
                .thenReturn(StoreVo.builder().id(fixture.storeId.toString()).build());

        ListingContentEntity content = new ListingContentEntity();
        content.setId(UUID.randomUUID());
        content.setProductId(fixture.productId);
        content.setTitle("alpha product");
        content.setBulletPoints("[]");
        when(fixture.listingContentMapper.selectOne(any())).thenReturn(content);
        when(fixture.keywordInsightMapper.selectList(any())).thenReturn(List.of(
                KeywordInsightEntity.builder().productId(fixture.productId).text("alpha").build()));

        CapturingListingService service = fixture.service();
        service.scoreListing(fixture.productId.toString());

        assertThat(service.queriedStoreId).isEqualTo(fixture.storeId);
    }

    @Test
    void controllerEndpointsDeclareReadAndWritePermissions() throws Exception {
        assertPermission("getListingContent", "product:view", String.class);
        assertPermission("getKeywordMapping", "product:view", String.class);
        assertPermission("getVersions", "product:view", String.class);
        assertPermission("generate", "product:update", String.class,
                com.adpilot.modules.listing.dto.ListingGenerateRequest.class);
        assertPermission("score", "product:update", String.class);
        assertPermission("complianceCheck", "product:view", String.class);
        assertPermission("updateDraft", "product:update", String.class,
                com.adpilot.modules.listing.dto.ListingGenerateRequest.class);
        assertPermission("approveDraft", "product:update", String.class);
    }

    private static void assertPermission(String methodName, String expected, Class<?>... parameterTypes)
            throws Exception {
        Method method = ListingController.class.getMethod(methodName, parameterTypes);
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo(expected);
    }

    private static final class Fixture {
        private final UUID productId = UUID.randomUUID();
        private final UUID storeId = UUID.randomUUID();
        private final ListingContentMapper listingContentMapper = mock(ListingContentMapper.class);
        private final ListingDraftMapper listingDraftMapper = mock(ListingDraftMapper.class);
        private final ProductMapper productMapper = mock(ProductMapper.class);
        private final StoreMapper storeMapper = mock(StoreMapper.class);
        private final KeywordInsightMapper keywordInsightMapper = mock(KeywordInsightMapper.class);
        private final KeywordCoverageMapper keywordCoverageMapper = mock(KeywordCoverageMapper.class);
        private final AiAssistService aiAssistService = mock(AiAssistService.class);
        private final StoreService storeService = mock(StoreService.class);

        private ProductEntity product() {
            return ProductEntity.builder().id(productId).storeId(storeId).name("Product").build();
        }

        private CapturingListingService service() {
            return new CapturingListingService(listingContentMapper, listingDraftMapper, productMapper,
                    storeMapper, keywordInsightMapper, keywordCoverageMapper, new ObjectMapper(),
                    aiAssistService, storeService);
        }
    }

    private static final class CapturingListingService extends ListingServiceImpl {
        private UUID queriedStoreId;

        private CapturingListingService(ListingContentMapper listingContentMapper,
                ListingDraftMapper listingDraftMapper, ProductMapper productMapper,
                StoreMapper storeMapper, KeywordInsightMapper keywordInsightMapper,
                KeywordCoverageMapper keywordCoverageMapper, ObjectMapper objectMapper,
                AiAssistService aiAssistService, StoreService storeService) {
            super(listingContentMapper, listingDraftMapper, productMapper, storeMapper,
                    keywordInsightMapper, keywordCoverageMapper, objectMapper, aiAssistService,
                    storeService);
        }

        @Override
        protected List<KeywordCoverageEntity> loadKeywordCoverage(UUID storeId, List<String> keywords) {
            queriedStoreId = storeId;
            return List.of(KeywordCoverageEntity.builder().storeId(storeId).keywordText("alpha")
                    .inListing(true).build());
        }
    }
}