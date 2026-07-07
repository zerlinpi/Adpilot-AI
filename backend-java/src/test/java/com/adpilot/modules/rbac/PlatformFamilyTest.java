package com.adpilot.modules.rbac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PlatformFamily#ofPlatformKey(String)}, the platform-key
 * &rarr; Platform_Family mapping that backs same-family connection enforcement
 * (multistore-ai-ads-operations Req 6.2).
 */
class PlatformFamilyTest {

    @ParameterizedTest
    @CsvSource({
            "amazon_ads,     amazon",
            "amazon_sp_api,  amazon",
            "shopify,        independent_site",
            "woocommerce,    independent_site",
            "google_ads,     independent_site",
            "tiktok_shop,    tiktok",
    })
    void ofPlatformKey_mapsEachSupportedKeyToItsFamily(String platformKey, String expectedFamilyCode) {
        assertThat(PlatformFamily.ofPlatformKey(platformKey).getCode()).isEqualTo(expectedFamilyCode);
    }

    @Test
    void ofPlatformKey_isCaseAndWhitespaceInsensitive() {
        assertThat(PlatformFamily.ofPlatformKey("  Amazon_Ads ")).isEqualTo(PlatformFamily.AMAZON);
    }

    @Test
    void ofPlatformKey_rejectsUnknownOrNullKey() {
        assertThatThrownBy(() -> PlatformFamily.ofPlatformKey("unknown_platform"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlatformFamily.ofPlatformKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
