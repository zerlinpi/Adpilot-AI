package com.adpilot.modules.rbac;

/**
 * The platform families that define the top-level Nav_Blocks and the
 * Platform_Access RBAC dimension (platform-workspace-rbac Req 12.1).
 *
 * <p>Each constant carries the lowercase {@code code} persisted in the
 * {@code platform_family} columns of {@code store_groups},
 * {@code account_platform_access}, and {@code stores}. Note that
 * {@code store_groups} only permits {@link #AMAZON} and {@link #INDEPENDENT_SITE}
 * (Req 10.1), whereas Platform_Access spans all families (Req 12.1).</p>
 *
 * <p>{@link #TIKTOK} is the fifth Nav_Block family added by
 * multistore-ai-ads-operations (Req 5.1, 6.1); it is a valid Platform_Access
 * family but not a Store_Group family.</p>
 */
public enum PlatformFamily {

    AMAZON("amazon"),
    INDEPENDENT_SITE("independent_site"),
    LOGISTICS("logistics"),
    FINANCE("finance"),
    TIKTOK("tiktok");

    private final String code;

    PlatformFamily(String code) {
        this.code = code;
    }

    /** The persisted lowercase code (e.g. {@code amazon}, {@code independent_site}). */
    public String getCode() {
        return code;
    }

    /** Whether this family is a valid Store_Group family (Req 10.1). */
    public boolean isStoreGroupFamily() {
        return this == AMAZON || this == INDEPENDENT_SITE;
    }

    /**
     * Map a {@code PlatformConnector.SUPPORTED} platform key to the
     * Platform_Family that owns it (multistore-ai-ads-operations Req 6.2). Used
     * to enforce that a connection created inside a Nav_Block belongs to that
     * block's platform family (no cross-family connections).
     *
     * <ul>
     *   <li>{@code amazon_ads}, {@code amazon_sp_api} &rarr; {@link #AMAZON}</li>
     *   <li>{@code shopify}, {@code woocommerce}, {@code google_ads} &rarr; {@link #INDEPENDENT_SITE}
     *       (Google Ads is an independent-site advertising account)</li>
     *   <li>{@code tiktok_shop} &rarr; {@link #TIKTOK}</li>
     * </ul>
     *
     * @throws IllegalArgumentException when {@code platformKey} is null or is not
     *     a recognised platform key
     */
    public static PlatformFamily ofPlatformKey(String platformKey) {
        if (platformKey != null) {
            switch (platformKey.trim().toLowerCase()) {
                case "amazon_ads":
                case "amazon_sp_api":
                    return AMAZON;
                case "shopify":
                case "woocommerce":
                case "google_ads":
                    return INDEPENDENT_SITE;
                case "tiktok_shop":
                    return TIKTOK;
                default:
                    break;
            }
        }
        throw new IllegalArgumentException("Unknown platform key: " + platformKey);
    }

    /**
     * Parse a persisted {@code platform_family} code into a {@link PlatformFamily}.
     *
     * @throws IllegalArgumentException when {@code code} is null or unrecognised
     */
    public static PlatformFamily fromCode(String code) {
        if (code != null) {
            String normalized = code.trim().toLowerCase();
            for (PlatformFamily family : values()) {
                if (family.code.equals(normalized)) {
                    return family;
                }
            }
        }
        throw new IllegalArgumentException("Unknown platform family: " + code);
    }
}
