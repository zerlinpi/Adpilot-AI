package com.adpilot.modules.listing;

import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.keyword.entity.KeywordInsightEntity;
import com.adpilot.modules.keyword.mapper.KeywordCoverageMapper;
import com.adpilot.modules.keyword.mapper.KeywordInsightMapper;
import com.adpilot.modules.listing.dto.ListingGenerateRequest;
import com.adpilot.modules.listing.entity.ListingContentEntity;
import com.adpilot.modules.listing.mapper.ListingContentMapper;
import com.adpilot.modules.listing.mapper.ListingDraftMapper;
import com.adpilot.modules.listing.service.impl.ListingServiceImpl;
import com.adpilot.modules.listing.vo.ListingContentVo;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.StoreService;
import com.adpilot.modules.store.vo.StoreVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that AI listing generation branches by the product's store platform
 * family: Amazon keeps its marketplace conventions (75-char title clamp,
 * space-separated backend search terms within 250 bytes, plain-text body) while
 * independent sites (Shopify/WooCommerce) get SEO-oriented output (≈60-char SEO
 * title, ≈150-160-char meta description, HTML body, comma-separated SEO keywords
 * with NO Amazon byte clamping). TikTok gets a short title + hashtags.
 *
 * The AI client is left disabled so generation uses the deterministic template
 * fallback path; the product/store lookups are mocked.
 */
class ListingPlatformAwarenessTest {

    private static final int AMAZON_TITLE_MAX = 75;
    private static final int SEO_TITLE_MAX = 60;
    private static final int AMAZON_HIGHLIGHTS_MAX = 125;
    private static final int META_DESCRIPTION_MAX = 160;
    private static final int TIKTOK_TITLE_MAX = 50;

    @Test
    void amazonProductYieldsAmazonShapedOutput() {
        Fixture fixture = new Fixture("amazon");
        ListingContentVo vo = fixture.service().generateDraft(
                fixture.productId.toString(), fixture.request(), "user-1");

        // 75-char title clamp.
        assertThat(vo.getTitle().length()).isLessThanOrEqualTo(AMAZON_TITLE_MAX);
        // Amazon backend search terms are space-separated keywords (no commas)
        // and stay within the 250-byte cap.
        assertThat(vo.getBackendSearchTerms()).doesNotContain(",");
        assertThat(vo.getBackendSearchTerms().getBytes().length).isLessThanOrEqualTo(250);
        // Plain-text body (NOT an HTML SEO body).
        assertThat(vo.getDescription()).doesNotContain("<p>");
        // Entity-SEO Product Highlights stay within the 125-char cap.
        assertThat(vo.getProductHighlights().length()).isLessThanOrEqualTo(AMAZON_HIGHLIGHTS_MAX);
    }

    @Test
    void independentSiteProductYieldsSeoShapedOutput() {
        Fixture fixture = new Fixture("shopify");
        ListingContentVo vo = fixture.service().generateDraft(
                fixture.productId.toString(), fixture.request(), "user-1");

        // SEO title is concise (~60 chars).
        assertThat(vo.getTitle().length()).isLessThanOrEqualTo(SEO_TITLE_MAX);
        // Meta description (stored in product_highlights) targets ~150-160 chars
        // and is NOT clamped to Amazon's 125-char highlights rule.
        assertThat(vo.getProductHighlights()).isNotBlank();
        assertThat(vo.getProductHighlights().length()).isLessThanOrEqualTo(META_DESCRIPTION_MAX);
        assertThat(vo.getProductHighlights().length()).isGreaterThan(AMAZON_HIGHLIGHTS_MAX);
        // HTML-friendly product description body.
        assertThat(vo.getDescription()).contains("<p>");
        // SEO keywords are comma-separated (NOT Amazon space-separated backend terms).
        assertThat(vo.getBackendSearchTerms()).contains(",");
    }

    @Test
    void requestPlatformOverrideForcesSeoShapeOnAmazonStore() {
        // Store resolves to amazon, but the request explicitly overrides to an
        // independent site — the override wins.
        Fixture fixture = new Fixture("amazon");
        ListingGenerateRequest request = fixture.request();
        request.setPlatform("woocommerce");

        ListingContentVo vo = fixture.service().generateDraft(
                fixture.productId.toString(), request, "user-1");

        assertThat(vo.getDescription()).contains("<p>");
        assertThat(vo.getTitle().length()).isLessThanOrEqualTo(SEO_TITLE_MAX);
    }

    @Test
    void tiktokProductYieldsShortTitleAndHashtags() {
        Fixture fixture = new Fixture("tiktok");
        ListingContentVo vo = fixture.service().generateDraft(
                fixture.productId.toString(), fixture.request(), "user-1");

        assertThat(vo.getTitle().length()).isLessThanOrEqualTo(TIKTOK_TITLE_MAX);
        // Hashtag-style keywords.
        assertThat(vo.getBackendSearchTerms()).contains("#");
    }

    private static final class Fixture {
        private final UUID productId = UUID.randomUUID();
        private final UUID storeId = UUID.randomUUID();
        private final UUID marketplaceId = UUID.randomUUID();
        private final String platform;

        private final ListingContentMapper listingContentMapper = mock(ListingContentMapper.class);
        private final ListingDraftMapper listingDraftMapper = mock(ListingDraftMapper.class);
        private final ProductMapper productMapper = mock(ProductMapper.class);
        private final StoreMapper storeMapper = mock(StoreMapper.class);
        private final KeywordInsightMapper keywordInsightMapper = mock(KeywordInsightMapper.class);
        private final KeywordCoverageMapper keywordCoverageMapper = mock(KeywordCoverageMapper.class);
        private final AiAssistService aiAssistService = mock(AiAssistService.class);
        private final StoreService storeService = mock(StoreService.class);

        private Fixture(String platform) {
            this.platform = platform;
        }

        private ListingGenerateRequest request() {
            ListingGenerateRequest request = new ListingGenerateRequest();
            request.setTargetAudience("athletes");
            request.setSellingPoints(List.of("Noise cancelling", "30 hour battery"));
            return request;
        }

        private ListingServiceImpl service() {
            ProductEntity product = ProductEntity.builder()
                    .id(productId)
                    .storeId(storeId)
                    .name("Wireless Earbuds")
                    .brand("Acme")
                    .category("Audio")
                    .asin("B0TEST1234")
                    .build();

            when(productMapper.selectById(productId)).thenReturn(product);
            when(storeService.getStoreById(storeId.toString()))
                    .thenReturn(StoreVo.builder()
                            .id(storeId.toString())
                            .platform(platform)
                            .build());
            when(keywordInsightMapper.selectList(any())).thenReturn(List.of(
                    KeywordInsightEntity.builder().productId(productId).text("bluetooth earbuds").build(),
                    KeywordInsightEntity.builder().productId(productId).text("running headphones").build()));
            when(keywordCoverageMapper.selectList(any())).thenReturn(List.of());
            // Mock mapper assigns an id on insert (normally done by the UUID interceptor).
            when(listingContentMapper.insert(any())).thenAnswer(inv -> {
                ListingContentEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return 1;
            });
            // AI disabled -> deterministic template fallback.
            when(aiAssistService.isEnabled()).thenReturn(false);

            return new ListingServiceImpl(listingContentMapper, listingDraftMapper, productMapper,
                    storeMapper, keywordInsightMapper, keywordCoverageMapper, new ObjectMapper(),
                    aiAssistService, storeService);
        }
    }
}
