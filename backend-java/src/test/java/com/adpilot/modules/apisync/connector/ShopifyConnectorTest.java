package com.adpilot.modules.apisync.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Shopify cursor-paging logic that does not require a live
 * HTTP call: parsing the {@code page_info} continuation token out of the
 * {@code Link} response header (Req 1.1.6).
 */
class ShopifyConnectorTest {

    @Test
    void nextPageInfoExtractsTokenFromRelNextLink() {
        String link = "<https://x.myshopify.com/admin/api/2024-01/orders.json?limit=250&page_info=abc123>; rel=\"next\"";

        assertThat(ShopifyConnector.nextPageInfo(link)).isEqualTo("abc123");
    }

    @Test
    void nextPageInfoPrefersNextOverPrevious() {
        String link = "<https://x.myshopify.com/admin/api/2024-01/orders.json?limit=250&page_info=PREV>; rel=\"previous\", "
                + "<https://x.myshopify.com/admin/api/2024-01/orders.json?limit=250&page_info=NEXT>; rel=\"next\"";

        assertThat(ShopifyConnector.nextPageInfo(link)).isEqualTo("NEXT");
    }

    @Test
    void nextPageInfoReturnsNullWhenOnlyPreviousLinkPresent() {
        String link = "<https://x.myshopify.com/admin/api/2024-01/orders.json?limit=250&page_info=PREV>; rel=\"previous\"";

        assertThat(ShopifyConnector.nextPageInfo(link)).isNull();
    }

    @Test
    void nextPageInfoReturnsNullForNullOrBlankHeader() {
        assertThat(ShopifyConnector.nextPageInfo(null)).isNull();
        assertThat(ShopifyConnector.nextPageInfo("   ")).isNull();
    }
}
